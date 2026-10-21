"""Single-instance durable execution queue and immutable, fully published runs."""

import asyncio
import csv
import hashlib
import logging
import math
import os
import re
import threading
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from .csv_import import ImportProblem
from .import_store import json_bytes, now, read_json, replace_json, sync_directory, write_bytes
from .prediction import PredictionRequest, mock_items
from .algorithm_adapter import MODEL_VERSION, LOADED_ALGORITHM_FINGERPRINT, algorithm_fingerprint, predict_real


logger = logging.getLogger(__name__)
RESULT_MODES = {"MOCK": "MOCK", "MODEL": "REAL"}


class PredictionStore:
    def __init__(self, root, imports, *, mode="MOCK", model_version="contract-demo-v1", delay_seconds=0, timeout_seconds=180):
        if mode not in {"MOCK", "MODEL"}:
            raise ValueError("PREDICTION_MODE 仅支持 MOCK 或 MODEL，不允许静默回退。")
        if not isinstance(model_version, str) or not model_version.strip() or len(model_version) > 64 or model_version != model_version.strip():
            raise ValueError("PREDICTION_MODEL_VERSION 必须为 1 至 64 字符的非空版本。")
        if not math.isfinite(delay_seconds) or delay_seconds < 0 or not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("Mock 延迟必须非负，任务总超时必须大于零，且均为有限秒数。")
        self.root, self.imports = Path(root), imports
        self.tasks, self.runs = self.root / "tasks", self.root / "runs"
        self.execution = {"mode": mode, "modelVersion": model_version, "delaySeconds": delay_seconds, "timeoutSeconds": timeout_seconds}
        self.lock = threading.RLock()

    def initialize(self):
        self.tasks.mkdir(parents=True, exist_ok=True)
        self.runs.mkdir(parents=True, exist_ok=True)
        self.recover()

    def _task_path(self, task_id):
        return self.tasks / hashlib.sha256(task_id.encode("utf-8")).hexdigest()

    @staticmethod
    def _run_id(task_id):
        # A stable private destination permits repair after result publication but before task success.
        return "run_" + hashlib.sha256(task_id.encode("utf-8")).hexdigest()

    def accept(self, body):
        request = PredictionRequest.from_json(body)
        fingerprint = hashlib.sha256(json_bytes(request.as_dict())).hexdigest()
        path = self._task_path(request.taskId)
        with self.lock:
            if (path / "task.json").is_file():
                task = read_json(path / "task.json")
                if task["requestHash"] != fingerprint:
                    raise ImportProblem("IDEMPOTENCY_CONFLICT", "同一 taskId 已用于不同预测参数。", 409)
                return self._view(task)
            created = datetime.now(timezone.utc)
            task = {
                **request.as_dict(), "status": "PENDING", "stage": "WAITING", "runId": None, "error": None,
                "datasetSource": None, "predictionSource": None, "requestHash": fingerprint,
                "execution": dict(self.execution), "createdAt": created.isoformat().replace("+00:00", "Z"),
                "updatedAt": now(), "deadlineAt": (created + timedelta(seconds=self.execution["timeoutSeconds"])).isoformat().replace("+00:00", "Z"),
            }
            if self.execution["mode"] == "MODEL":
                task["execution"]["algorithmFingerprint"] = LOADED_ALGORITHM_FINGERPRINT
            staging = self.tasks / f".task-{uuid.uuid4().hex}"
            staging.mkdir()
            write_bytes(staging / "task.json", json_bytes(task))
            sync_directory(staging)
            os.rename(staging, path)
            sync_directory(self.tasks)
            return self._view(task)

    def get_task(self, task_id):
        path = self._task_path(task_id) / "task.json"
        if not path.is_file():
            raise ImportProblem("TASK_NOT_FOUND", "计算任务不存在。", 404)
        return self._view(read_json(path))

    @staticmethod
    def _view(task):
        fields = ("taskId", "status", "stage", "datasetId", "appId", "cutoffDate", "horizonDays", "modelVersion",
                  "datasetSource", "predictionSource", "runId", "error", "createdAt", "updatedAt", "deadlineAt")
        return {**{key: task[key] for key in fields}, "resultMode": RESULT_MODES.get(task["predictionSource"])}

    def get_items(self, run_id, page, page_size):
        if not re.fullmatch(r"run_[0-9a-f]{64}", run_id) or not (self.runs / run_id / "result.json").is_file():
            raise ImportProblem("RUN_NOT_FOUND", "完整预测运行不存在。", 404)
        result = self._read_run(run_id)
        start = (page - 1) * page_size
        return {**result, "page": page, "pageSize": page_size, "items": result["items"][start:start + page_size]}

    def _read_run(self, run_id):
        try:
            result = read_json(self.runs / run_id / "result.json")
            if not isinstance(result, dict):
                raise ValueError
            request = PredictionRequest.from_json({key: result.get(key) for key in PredictionRequest.__dataclass_fields__})
            cutoff = date.fromisoformat(request.cutoffDate)
            if result.get("runId") != run_id or self._run_id(request.taskId) != run_id \
                    or result.get("datasetSource") not in {"REAL", "FAKE"} or result.get("predictionSource") not in {"MOCK", "MODEL"} \
                    or result.get("predictionStartDate") != (cutoff + timedelta(days=1)).isoformat() \
                    or result.get("predictionEndDate") != (cutoff + timedelta(days=request.horizonDays)).isoformat() \
                    or not isinstance(result.get("items"), list) or type(result.get("total")) is not int or result["total"] != len(result["items"]):
                raise ValueError
            identities = set()
            for rank, item in enumerate(result["items"], 1):
                if not isinstance(item, dict) or type(item.get("rank")) is not int or item["rank"] != rank \
                        or not isinstance(item.get("fileKey"), str) or not item["fileKey"].strip() or item["fileKey"] in identities \
                        or not isinstance(item.get("predictedBytes"), str) or len(item["predictedBytes"]) > 256 \
                        or not re.fullmatch(r"(?:0|[1-9]\d*)(?:\.\d+)?", item["predictedBytes"]):
                    raise ValueError
                identities.add(item["fileKey"])
            expected_mode = RESULT_MODES[result["predictionSource"]]
            if "resultMode" in result and result["resultMode"] != expected_mode:
                raise ValueError
            if result["predictionSource"] == "MODEL":
                info = result.get("modelInfo")
                if not isinstance(info, dict) or not isinstance(info.get("algorithmFingerprint"), str) \
                        or not re.fullmatch(r"[0-9a-f]{64}", info["algorithmFingerprint"]):
                    raise ValueError
            result.setdefault("resultMode", expected_mode)
            return result
        except (ValueError, TypeError, KeyError, ImportProblem):
            raise ImportProblem("INVALID_RESULT", "已发布结果损坏或不符合计算结果契约，不能作为成功结果读取。") from None

    async def process_next(self, stop):
        # Only the single execution worker calls this, never concurrently with another execution.
        # Also settles RUNNING records left by a transient storage error on the previous iteration.
        await asyncio.to_thread(self.recover)
        task = await asyncio.to_thread(self._claim)
        if task is None:
            return False
        try:
            await asyncio.to_thread(self._validate_input, task)
            self._check_deadline(task)
            delay = task["execution"]["delaySeconds"] if task["execution"]["mode"] == "MOCK" else 0
            if delay:
                try:
                    await asyncio.wait_for(stop.wait(), timeout=min(delay, self._remaining(task)))
                except TimeoutError:
                    pass
            if stop.is_set():
                # Leave a durable RUNNING record. Startup will repair it or explicitly fail it.
                return True
            await asyncio.to_thread(self._compute_publish, task)
        except OSError:
            # A published run wins over a lost success write. Unpublished executions fail explicitly.
            await asyncio.to_thread(self._settle, task, {"code": "EXECUTION_STORAGE_ERROR", "message": "计算持久存储异常，未确认完整结果发布。"})
        except Exception as error:
            problem = error if isinstance(error, ImportProblem) else ImportProblem("EXECUTION_FAILED", "预测计算失败，未发布结果。")
            logger.warning("prediction failed taskId=%s code=%s", task["taskId"], problem.code)
            await asyncio.to_thread(self._settle, task, problem.as_dict())
        return True

    def _claim(self):
        with self.lock:
            pending = [read_json(path) for path in self.tasks.glob("*/task.json") if not path.parent.name.startswith(".")]
            pending = [task for task in pending if task["status"] == "PENDING"]
            if not pending:
                return None
            task = min(pending, key=lambda value: (value["createdAt"], value["taskId"]))
            self._update(task, status="RUNNING", stage="VALIDATING")
            return task

    def _validate_input(self, task):
        self._check_deadline(task)
        metadata = self.imports.get_dataset(task["datasetId"])
        if metadata["status"] != "READY":
            raise ImportProblem("DATASET_NOT_READY", "数据集未就绪。", 409)
        if metadata["datasetType"] != "FILE_DAILY":
            raise ImportProblem("DATASET_TYPE_UNSUPPORTED", "仅支持 FILE_DAILY 文件日流量。")
        if metadata["sourceUnit"] != "B":
            raise ImportProblem("UNIT_UNCONFIRMED", "数据集流量单位未确认为 B。")
        if task["appId"] not in metadata["appIds"]:
            raise ImportProblem("APP_MISMATCH", "数据集不包含请求的 App ID。")
        interval = metadata["appDateRanges"][task["appId"]]
        if not interval["start"] <= task["cutoffDate"] <= interval["end"]:
            raise ImportProblem("INVALID_CUTOFF_DATE", "截止日超出该 App 的已观测日期范围。")
        policy = task["execution"]
        if task["modelVersion"] != policy["modelVersion"]:
            raise ImportProblem("MODEL_VERSION_UNSUPPORTED", "请求模型版本未配置，不能替换为其他模型或 Mock。")
        if policy["mode"] == "MODEL":
            if task["modelVersion"] != MODEL_VERSION:
                raise ImportProblem("MODEL_VERSION_UNSUPPORTED", "当前真实模式仅批准 hotspot-simple-v1。")
            self._check_algorithm(task)
        self._update(task, stage="COMPUTING", datasetSource=metadata["datasetSource"], predictionSource=policy["mode"])

    @staticmethod
    def _check_algorithm(task):
        if task["execution"].get("algorithmFingerprint") != LOADED_ALGORITHM_FINGERPRINT \
                or algorithm_fingerprint() != LOADED_ALGORITHM_FINGERPRINT:
            raise ImportProblem("MODEL_ARTIFACT_CHANGED", "算法或参数文件已变化；不改变已接收任务的执行语义，请重启服务后提交新任务。")

    def _compute_publish(self, task):
        self._check_deadline(task)
        request = PredictionRequest.from_json({key: task[key] for key in PredictionRequest.__dataclass_fields__})
        # The service reads its own immutable normalized input, never the original CSV again.
        model_info = None
        with (self.imports.datasets / request.datasetId / "normalized.csv").open(encoding="utf-8", newline="") as stream:
            if task["execution"]["mode"] == "MOCK":
                items = mock_items(csv.DictReader(stream), request)
            else:
                self._check_algorithm(task)
                computed = predict_real(csv.DictReader(stream), request)
                items, model_info = computed["items"], computed["modelInfo"]
                self._check_algorithm(task)
                if model_info["algorithmFingerprint"] != task["execution"]["algorithmFingerprint"]:
                    raise ImportProblem("MODEL_ARTIFACT_CHANGED", "计算结果的算法摘要与已接收任务不一致。")
        self._check_deadline(task)
        run_id = self._run_id(task["taskId"])
        cutoff = date.fromisoformat(task["cutoffDate"])
        result = {
            **request.as_dict(), "runId": run_id, "datasetSource": task["datasetSource"],
            "predictionSource": task["execution"]["mode"], "resultMode": RESULT_MODES[task["execution"]["mode"]],
            "predictionStartDate": (cutoff + timedelta(days=1)).isoformat(),
            "predictionEndDate": (cutoff + timedelta(days=request.horizonDays)).isoformat(),
            "generatedAt": now(), "total": len(items), "items": items,
        }
        if model_info is not None:
            result["modelInfo"] = model_info
        staging = self.runs / f".run-{uuid.uuid4().hex}"
        staging.mkdir()
        write_bytes(staging / "result.json", json_bytes(result))
        sync_directory(staging)
        self._check_deadline(task)
        os.rename(staging, self.runs / run_id)
        sync_directory(self.runs)
        # There is intentionally no public runId before the entire run has been published.
        self._update(task, status="SUCCEEDED", stage="COMPLETED", runId=run_id, error=None)
        logger.info("prediction succeeded taskId=%s datasetId=%s runId=%s", task["taskId"], task["datasetId"], run_id)

    def _published(self, task):
        path = self.runs / self._run_id(task["taskId"]) / "result.json"
        if not path.is_file():
            return None
        result = self._read_run(self._run_id(task["taskId"]))
        expected = {key: task[key] for key in PredictionRequest.__dataclass_fields__}
        if any(result.get(key) != value for key, value in expected.items()) \
                or result["predictionSource"] != task["execution"]["mode"] \
                or (result["predictionSource"] == "MODEL" and
                    result.get("modelInfo", {}).get("algorithmFingerprint") != task["execution"].get("algorithmFingerprint")) \
                or (task["datasetSource"] is not None and result["datasetSource"] != task["datasetSource"]):
            raise ImportProblem("INVALID_RESULT", "已发布结果与任务不匹配，不能标记成功。")
        return result

    def _settle(self, task, error):
        try:
            result = self._published(task)
        except ImportProblem as problem:
            result, error = None, problem.as_dict()
        if result:
            self._update(task, status="SUCCEEDED", stage="COMPLETED", runId=result["runId"], error=None,
                         datasetSource=result["datasetSource"], predictionSource=result["predictionSource"])
        else:
            self._update(task, status="FAILED", stage="FAILED", runId=None, error=error)

    def recover(self):
        with self.lock:
            for path in self.tasks.glob("*/task.json"):
                if path.parent.name.startswith("."):
                    continue
                task = read_json(path)
                if task["status"] == "RUNNING":
                    self._settle(task, {"code": "EXECUTION_INTERRUPTED", "message": "服务中断前结果未完整发布；本任务明确失败，不自动重算。"})
                # PENDING remains durable; the single worker picks it up after startup.

    def _update(self, task, **changes):
        with self.lock:
            updated = {**task, **changes, "updatedAt": now()}
            replace_json(self._task_path(task["taskId"]) / "task.json", updated)
            task.update(updated)

    @staticmethod
    def _remaining(task):
        return (datetime.fromisoformat(task["deadlineAt"]) - datetime.now(timezone.utc)).total_seconds()

    def _check_deadline(self, task):
        if self._remaining(task) <= 0:
            raise ImportProblem("TASK_TIMEOUT", "超过 Python 计算任务总时限，未发布结果。")
