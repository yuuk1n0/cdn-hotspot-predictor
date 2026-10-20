"""Single-instance durable import receipts and immutable dataset snapshots."""

import csv
import hashlib
import io
import json
import logging
import os
import re
import threading
import uuid
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from .csv_import import IDENTITY_VERSION, PARSER_VERSION, ImportConfig, ImportProblem, parse_csv


logger = logging.getLogger(__name__)


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_bytes(path, content):
    with path.open("xb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())


def replace_json(path, value):
    temporary = path.parent / f".{path.name}.{uuid.uuid4().hex}.tmp"
    write_bytes(temporary, json_bytes(value))
    os.replace(temporary, path)
    sync_directory(path.parent)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


class ImportStore:
    def __init__(self, root: Path, max_rows: int = 20000):
        self.root = Path(root)
        self.imports = self.root / "imports"
        self.datasets = self.root / "datasets"
        self.max_rows = max_rows
        self.lock = threading.RLock()

    def initialize(self):
        self.imports.mkdir(parents=True, exist_ok=True)
        self.datasets.mkdir(parents=True, exist_ok=True)
        self.recover()

    def accept(self, raw: bytes, config: ImportConfig, key: str, filename: str):
        task_id = "imp_" + hashlib.sha256(key.encode()).hexdigest()
        file_hash = hashlib.sha256(raw).hexdigest()
        fingerprint = hashlib.sha256(json_bytes({
            "sha256": file_hash, "config": asdict(config),
            "parserVersion": PARSER_VERSION, "identityMappingVersion": IDENTITY_VERSION,
        })).hexdigest()
        path = self.imports / task_id
        with self.lock:
            if path.exists():
                task = read_json(path / "task.json")
                if task["requestHash"] != fingerprint:
                    raise ImportProblem("IDEMPOTENCY_CONFLICT", "同一幂等键已用于不同文件内容或导入配置。", 409)
                return {"importTaskId": task_id, "status": task["status"]}
            task = {
                "importTaskId": task_id, "status": "PENDING", "datasetId": None,
                "error": None, "quality": None, "requestHash": fingerprint,
                "snapshotId": "ds_" + fingerprint, "sha256": file_hash,
                "config": asdict(config), "originalFilename": filename,
                "createdAt": now(), "updatedAt": now(),
            }
            staging = self.imports / f".upload-{uuid.uuid4().hex}"
            staging.mkdir()
            write_bytes(staging / "original.csv", raw)
            write_bytes(staging / "task.json", json_bytes(task))
            sync_directory(staging)
            os.rename(staging, path)
            sync_directory(self.imports)
        # This receipt is returned only after both original bytes and the task are durable.
        return {"importTaskId": task_id, "status": "PENDING"}

    def get_import(self, task_id):
        path = self._path(self.imports, task_id, "imp_")
        if path is None or not (path / "task.json").is_file():
            raise ImportProblem("IMPORT_NOT_FOUND", "导入任务不存在。", 404)
        task = read_json(path / "task.json")
        return {key: task[key] for key in ("importTaskId", "status", "datasetId", "error", "quality", "createdAt", "updatedAt")}

    def get_dataset(self, dataset_id):
        path = self._path(self.datasets, dataset_id, "ds_")
        if path is None or not self._complete(path):
            raise ImportProblem("DATASET_NOT_FOUND", "READY 数据集不存在。", 404)
        return read_json(path / "metadata.json")

    def get_quality(self, dataset_id):
        self.get_dataset(dataset_id)
        return read_json(self.datasets / dataset_id / "quality.json")

    def list_datasets(self, page, page_size):
        items = [read_json(path / "metadata.json") for path in self.datasets.glob("ds_*") if self._complete(path)]
        items.sort(key=lambda item: (item["createdAt"], item["datasetId"]), reverse=True)
        start = (page - 1) * page_size
        return {"page": page, "pageSize": page_size, "total": len(items), "items": items[start:start + page_size]}

    def process_next(self):
        with self.lock:
            pending = []
            for path in self.imports.glob("imp_*"):
                task = read_json(path / "task.json")
                if task["status"] == "PENDING":
                    pending.append((task["createdAt"], task["importTaskId"]))
            if not pending:
                return False
            task_id = min(pending)[1]
            path = self.imports / task_id
            task = read_json(path / "task.json")
            self._update(task, status="RUNNING")
        try:
            config = ImportConfig(**task["config"])
            raw = (path / "original.csv").read_bytes()
            if hashlib.sha256(raw).hexdigest() != task["sha256"]:
                raise ImportProblem("INVALID_CSV", "已保存原文件的摘要不匹配，不能发布数据集。")
            parsed = parse_csv(raw, config, self.max_rows)
            if parsed.error:
                self._update(task, status="FAILED", error=parsed.error, quality=parsed.quality)
                return True
            dataset_id = task["snapshotId"]
            dataset_path = self.datasets / dataset_id
            if not self._complete(dataset_path):
                staging = self.datasets / f".dataset-{uuid.uuid4().hex}"
                staging.mkdir()
                normalized = io.StringIO(newline="")
                writer = csv.DictWriter(normalized, fieldnames=["fileKey", "date", "trafficBytes", "appId"])
                writer.writeheader()
                writer.writerows(parsed.rows)
                metadata = {
                    "datasetId": dataset_id, "status": "READY", **asdict(config),
                    "appIds": sorted({row["appId"] for row in parsed.rows}),
                    "dateRange": parsed.quality["dateRange"], "appDateRanges": parsed.quality["appDateRanges"],
                    "rowCount": len(parsed.rows), "sha256": task["sha256"],
                    "parserVersion": PARSER_VERSION, "identityMappingVersion": IDENTITY_VERSION,
                    "unitConfirmationSource": "UPLOAD_DECLARATION", "completeness": "UNKNOWN",
                    "importTaskId": task_id, "createdAt": now(),
                }
                write_bytes(staging / "original.csv", raw)
                write_bytes(staging / "normalized.csv", normalized.getvalue().encode("utf-8"))
                write_bytes(staging / "quality.json", json_bytes(parsed.quality))
                write_bytes(staging / "metadata.json", json_bytes(metadata))
                sync_directory(staging)
                os.rename(staging, dataset_path)
                sync_directory(self.datasets)
            # A complete immutable directory is published before the success task record.
            self._update(task, status="SUCCEEDED", datasetId=dataset_id, error=None)
            logger.info("import succeeded importTaskId=%s datasetId=%s", task_id, dataset_id)
        except Exception as error:
            logger.exception("import failed importTaskId=%s", task_id)
            problem = error if isinstance(error, ImportProblem) else ImportProblem("IMPORT_FAILED", "导入处理失败，未发布可用数据集。")
            self._update(task, status="FAILED", datasetId=None, error=problem.as_dict())
        return True

    def recover(self):
        for path in self.imports.glob("imp_*"):
            task = read_json(path / "task.json")
            if task["status"] not in {"PENDING", "RUNNING"}:
                continue
            if self._complete(self.datasets / task["snapshotId"]):
                self._update(task, status="SUCCEEDED", datasetId=task["snapshotId"], error=None)
            elif task["status"] == "RUNNING":
                self._update(task, status="FAILED", datasetId=None, error={
                    "code": "IMPORT_INTERRUPTED", "message": "服务中断前导入未完整发布，请使用新幂等键重新提交。",
                })
            # PENDING receipts remain on disk for the worker to pick up.

    def _update(self, task, **changes):
        with self.lock:
            updated = {**task, **changes, "updatedAt": now()}
            replace_json(self.imports / task["importTaskId"] / "task.json", updated)
            task.update(updated)

    @staticmethod
    def _path(parent, identifier, prefix):
        return parent / identifier if re.fullmatch(prefix + r"[0-9a-f]{64}", identifier) else None

    @staticmethod
    def _complete(path):
        return all((path / name).is_file() for name in ("original.csv", "normalized.csv", "metadata.json", "quality.json"))
