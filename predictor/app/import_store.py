"""Single-instance durable import receipts and immutable dataset snapshots."""

import hashlib
import json
import logging
import os
import re
import shutil
import threading
import uuid
from contextlib import suppress
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from .csv_import import IDENTITY_VERSION, PARSER_VERSION, TOTAL_PARSER_VERSION, ImportConfig, ImportProblem
from .app_total_csv import normalize_total_staged_csv
from .staged_csv import normalize_staged_csv
from .upload_stream import StagedUpload


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


def copy_file(source, destination, chunk_bytes=1024 * 1024):
    with Path(source).open("rb") as reader, Path(destination).open("xb") as writer:
        while chunk := reader.read(chunk_bytes):
            writer.write(chunk)
        writer.flush()
        os.fsync(writer.fileno())


def replace_json(path, value):
    temporary = path.parent / f".{path.name}.{uuid.uuid4().hex}.tmp"
    write_bytes(temporary, json_bytes(value))
    os.replace(temporary, path)
    sync_directory(path.parent)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


class ImportStore:
    def __init__(self, root: Path, max_rows: int = 1200000, data_store=None, alert_sink=None):
        self.root = Path(root)
        self.incoming = self.root / "incoming"
        self.imports = self.root / "imports"
        self.datasets = self.root / "datasets"
        self.max_rows = max_rows
        self.data_store = data_store
        self.alert_sink = alert_sink
        self.lock = threading.RLock()

    def initialize(self):
        self.incoming.mkdir(parents=True, exist_ok=True)
        self.imports.mkdir(parents=True, exist_ok=True)
        self.datasets.mkdir(parents=True, exist_ok=True)
        for pattern in (".upload-*.part", ".compat-*.part"):
            for path in self.incoming.glob(pattern):
                path.unlink()
        self.recover()

    def accept(self, raw: bytes, config: ImportConfig, key: str, filename: str):
        path = self.incoming / f".compat-{uuid.uuid4().hex}.part"
        try:
            write_bytes(path, raw)
            sync_directory(self.incoming)
            return self.accept_staged(
                StagedUpload(path=path, size=len(raw), sha256=hashlib.sha256(raw).hexdigest()),
                config,
                key,
                filename,
            )
        finally:
            with suppress(FileNotFoundError):
                path.unlink()

    def accept_staged(self, staged: StagedUpload, config: ImportConfig, key: str, filename: str):
        task_id = "imp_" + hashlib.sha256(key.encode()).hexdigest()
        file_hash = staged.sha256
        fingerprint = hashlib.sha256(json_bytes({
            "sha256": file_hash, "config": asdict(config),
            "parserVersion": PARSER_VERSION, "identityMappingVersion": IDENTITY_VERSION,
        })).hexdigest()
        path = self.imports / task_id
        staging = None
        try:
            if staged.path.parent.resolve() != self.incoming.resolve():
                raise ImportProblem("INVALID_CSV", "暂存上传文件不属于当前导入目录。")
            if not staged.path.is_file() or staged.path.stat().st_size != staged.size:
                raise ImportProblem("INVALID_CSV", "暂存上传文件不存在或大小不匹配。")
            with self.lock:
                if path.exists():
                    task = read_json(path / "task.json")
                    if task["requestHash"] != fingerprint:
                        raise ImportProblem("IDEMPOTENCY_CONFLICT", "同一幂等键已用于不同文件内容或导入配置。", 409)
                    if self.data_store is not None:
                        self.data_store.ensure_import_task(task)
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
                os.rename(staged.path, staging / "original.csv")
                sync_directory(self.incoming)
                write_bytes(staging / "task.json", json_bytes(task))
                sync_directory(staging)
                os.rename(staging, path)
                sync_directory(self.imports)
                if self.data_store is not None:
                    self.data_store.ensure_import_task(task)
            # This receipt is returned only after both original bytes and the task are durable.
            return {"importTaskId": task_id, "status": "PENDING"}
        finally:
            with suppress(FileNotFoundError):
                staged.path.unlink()
            if staging is not None and staging.exists():
                shutil.rmtree(staging, ignore_errors=True)

    def get_import(self, task_id):
        if self.data_store is not None:
            return self.data_store.get_import_task(task_id)
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
        staging = None
        try:
            config = ImportConfig(**task["config"])
            source = path / "original.csv"
            digest = hashlib.sha256()
            with source.open("rb") as stream:
                while chunk := stream.read(1024 * 1024):
                    digest.update(chunk)
            if digest.hexdigest() != task["sha256"]:
                raise ImportProblem("INVALID_CSV", "已保存原文件的摘要不匹配，不能发布数据集。")
            dataset_id = task["snapshotId"]
            dataset_path = self.datasets / dataset_id
            if not self._complete(dataset_path):
                staging = self.datasets / f".dataset-{uuid.uuid4().hex}"
                normalize = normalize_staged_csv if config.datasetType == "FILE_DAILY" else normalize_total_staged_csv
                normalized = normalize(source, config, staging, self.max_rows)
                quality = read_json(normalized.quality_path)
                if normalized.error:
                    shutil.rmtree(staging, ignore_errors=True)
                    staging = None
                    self._update(task, status="FAILED", error=normalized.error, quality=quality)
                    return True
                metadata = {
                    "datasetId": dataset_id, "status": "READY", **asdict(config),
                    "appIds": list(normalized.app_ids),
                    "dateRange": quality["dateRange"], "appDateRanges": normalized.app_date_ranges,
                    "rowCount": normalized.row_count, "sha256": task["sha256"],
                    "parserVersion": PARSER_VERSION if config.datasetType == "FILE_DAILY" else TOTAL_PARSER_VERSION,
                    "identityMappingVersion": IDENTITY_VERSION if config.datasetType == "FILE_DAILY" else None,
                    "unitConfirmationSource": "UPLOAD_DECLARATION", "completeness": "UNKNOWN",
                    "importTaskId": task_id, "createdAt": now(),
                }
                copy_file(source, staging / "source.csv")
                os.link(staging / "source.csv", staging / "original.csv")
                write_bytes(staging / "metadata.json", json_bytes(metadata))
                sync_directory(staging)
                os.rename(staging, dataset_path)
                staging = None
                sync_directory(self.datasets)
            metadata = read_json(dataset_path / "metadata.json")
            quality = read_json(dataset_path / "quality.json")
            if self.data_store is not None:
                self.data_store.import_dataset(metadata, dataset_path / "normalized.csv")
            # A complete immutable directory is published before the success task record.
            self._update(task, status="SUCCEEDED", datasetId=dataset_id, error=None, quality=quality)
            logger.info("import succeeded importTaskId=%s datasetId=%s", task_id, dataset_id)
        except Exception as error:
            logger.exception("import failed importTaskId=%s", task_id)
            if staging is not None and staging.exists():
                shutil.rmtree(staging, ignore_errors=True)
            problem = error if isinstance(error, ImportProblem) else ImportProblem("IMPORT_FAILED", "导入处理失败，未发布可用数据集。")
            self._update(task, status="FAILED", datasetId=None, error=problem.as_dict())
            if self.alert_sink is not None:
                try:
                    self.alert_sink.import_failed(task_id, task.get("config", {}).get("appId"),
                                                  problem.code, problem.message)
                except Exception:
                    logger.exception("import alert delivery failed importTaskId=%s", task_id)
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
            if self.data_store is not None:
                self.data_store.update_import_task(updated)
            replace_json(self.imports / task["importTaskId"] / "task.json", updated)
            task.update(updated)

    @staticmethod
    def _path(parent, identifier, prefix):
        return parent / identifier if re.fullmatch(prefix + r"[0-9a-f]{64}", identifier) else None

    @staticmethod
    def _complete(path):
        return all((path / name).is_file() for name in ("original.csv", "normalized.csv", "metadata.json", "quality.json"))
