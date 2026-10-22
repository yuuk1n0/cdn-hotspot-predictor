"""Explicit MySQL persistence for import tasks and immutable dataset versions."""

import csv
import hashlib
import json
import os
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

from .csv_import import ImportProblem


BATCH_ROWS = 500


@dataclass(frozen=True)
class MySqlSettings:
    host: str
    port: int
    database: str
    user: str
    password: str

    @classmethod
    def from_env(cls):
        try:
            port = int(os.getenv("MYSQL_PORT", "3306"))
        except ValueError:
            raise ValueError("MYSQL_PORT 必须为整数。") from None
        values = {
            "host": os.getenv("MYSQL_HOST", "127.0.0.1"),
            "port": port,
            "database": os.getenv("MYSQL_DATABASE", "cdn_hotspot"),
            "user": os.getenv("MYSQL_USER", "cdn"),
            "password": os.getenv("MYSQL_PASSWORD", "cdn_dev"),
        }
        if not all(str(value).strip() for key, value in values.items() if key != "port"):
            raise ValueError("MySQL 主机、库名、用户和密码不能为空。")
        return cls(**values)


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _chunks(iterator, size):
    batch = []
    for item in iterator:
        batch.append(item)
        if len(batch) == size:
            yield batch
            batch = []
    if batch:
        yield batch


def _epoch_ms(value=None) -> int:
    if value is None:
        return int(time.time() * 1000)
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def _iso_time(value: int) -> str:
    return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat().replace("+00:00", "Z")


def _file_rows(dataset_id: str, normalized_path: Path):
    with Path(normalized_path).open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            yield (
                dataset_id, row["appId"], row["date"], row["fileKey"], row["trafficBytes"],
                row["parseStatus"], row["sourceIdentityHash"], int(row["sourceRowCount"]),
                int(row["sourceIdentityCount"]), row["aggregationRule"],
            )


def _total_rows(dataset_id: str, source_unit: str, normalized_path: Path):
    with Path(normalized_path).open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            yield (
                dataset_id, row["appId"], row["date"], row["rawTrafficValue"],
                row["totalTrafficBytes"], source_unit,
            )


def _fits_decimal(value: str, precision: int, scale: int) -> bool:
    try:
        number = Decimal(value)
    except InvalidOperation:
        return False
    if not number.is_finite() or number < 0:
        return False
    # DECIMAL accepts insignificant trailing zeroes beyond its scale; count the
    # canonical value rather than the source string's preserved zero padding.
    _, digits, exponent = number.normalize().as_tuple()
    fractional = max(-exponent, 0)
    integer = max(len(digits) + exponent, 0)
    return fractional <= scale and integer <= precision - scale


class MySqlDataStore:
    """DB-API repository; schema creation remains a separately approved migration."""

    def __init__(self, settings: MySqlSettings):
        self.settings = settings

    def _driver(self):
        try:
            import pymysql
        except ImportError:
            raise RuntimeError("PREDICTION_DATA_SOURCE=MYSQL 需要安装 PyMySQL。") from None
        return pymysql

    @contextmanager
    def _connect(self):
        driver = self._driver()
        connection = driver.connect(
            host=self.settings.host,
            port=self.settings.port,
            user=self.settings.user,
            password=self.settings.password,
            database=self.settings.database,
            charset="utf8mb4",
            autocommit=False,
            connect_timeout=5,
            cursorclass=driver.cursors.SSCursor,
        )
        try:
            yield connection
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def check_schema(self):
        try:
            with self._connect() as connection, connection.cursor() as cursor:
                for table in ("data_import_task", "imported_dataset", "file_daily_observation", "app_daily_total"):
                    cursor.execute(f"SELECT 1 FROM {table} LIMIT 1")
                    cursor.fetchone()
        except Exception as error:
            if error.__class__.__name__ == "ProgrammingError":
                raise ImportProblem(
                    "SCHEMA_MIGRATION_REQUIRED",
                    "MySQL 缺少已批准的 006 导入表；不得自动建表或降级到文件数据源。",
                    503,
                ) from None
            raise

    def ensure_import_task(self, task: dict):
        config = task["config"]
        values = (
            task["importTaskId"], task["importTaskId"].removeprefix("imp_"), task["requestHash"],
            task["snapshotId"], config.get("appId"), config["datasetType"], config["datasetSource"],
            task["sha256"], task["status"], task.get("stage", "WAITING"),
            _epoch_ms(task["createdAt"]), _epoch_ms(task["updatedAt"]),
        )
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO data_import_task
                (import_task_id,idempotency_key_hash,request_hash,target_dataset_id,app_id,source_type,
                 dataset_source,source_sha256,status,stage,created_at_ms,updated_at_ms)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON DUPLICATE KEY UPDATE import_task_id=import_task_id
                """,
                values,
            )
            cursor.execute(
                "SELECT request_hash,target_dataset_id FROM data_import_task WHERE import_task_id=%s",
                (task["importTaskId"],),
            )
            existing = cursor.fetchone()
            if not existing or tuple(existing) != (task["requestHash"], task["snapshotId"]):
                raise ImportProblem("IDEMPOTENCY_CONFLICT", "MySQL 中同一导入任务已绑定不同请求。", 409)
            connection.commit()

    def update_import_task(self, task: dict):
        quality = task.get("quality") or {}
        error = task.get("error") or {}
        completed = _epoch_ms(task["updatedAt"]) if task["status"] in {"SUCCEEDED", "FAILED"} else None
        app_id = task.get("config", {}).get("appId")
        quality_values = (
            quality.get("validRows"), quality.get("totalRows"), quality.get("validRows"),
            quality.get("invalidRows"), quality.get("duplicateRows"), quality.get("aggregatedRows"),
        ) if quality else (None,) * 6
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE data_import_task SET app_id=COALESCE(%s,app_id),status=%s,stage=%s,
                processed_rows=COALESCE(%s,processed_rows),total_rows=COALESCE(%s,total_rows),
                valid_rows=COALESCE(%s,valid_rows),invalid_rows=COALESCE(%s,invalid_rows),
                duplicate_rows=COALESCE(%s,duplicate_rows),aggregated_rows=COALESCE(%s,aggregated_rows),
                quality_report_json=COALESCE(%s,quality_report_json),error_code=%s,error_message=%s,
                updated_at_ms=%s,completed_at_ms=%s WHERE import_task_id=%s
                """,
                (
                    app_id, task["status"], task.get("stage", task["status"]), *quality_values,
                    json.dumps(quality, ensure_ascii=False, sort_keys=True, separators=(",", ":")) if quality else None,
                    error.get("code"), error.get("message"), _epoch_ms(task["updatedAt"]), completed,
                    task["importTaskId"],
                ),
            )
            if cursor.rowcount != 1:
                raise ImportProblem("IMPORT_NOT_FOUND", "MySQL 导入任务不存在。", 404)
            connection.commit()

    def get_import_task(self, task_id: str):
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT import_task_id,status,target_dataset_id,quality_report_json,error_code,error_message,"
                "created_at_ms,updated_at_ms FROM data_import_task WHERE import_task_id=%s",
                (task_id,),
            )
            row = cursor.fetchone()
        if row is None:
            raise ImportProblem("IMPORT_NOT_FOUND", "导入任务不存在。", 404)
        quality = json.loads(row[3]) if row[3] else None
        error = {"code": row[4], "message": row[5]} if row[4] else None
        return {
            "importTaskId": row[0], "status": row[1],
            "datasetId": row[2] if row[1] == "SUCCEEDED" else None,
            "error": error, "quality": quality,
            "createdAt": _iso_time(row[6]), "updatedAt": _iso_time(row[7]),
        }

    def import_dataset(self, metadata: dict, normalized_path: Path):
        self.check_schema()
        quality = json.loads((Path(normalized_path).parent / "quality.json").read_text(encoding="utf-8"))
        app_ids = metadata.get("appIds") or []
        if len(app_ids) != 1:
            raise ImportProblem("MULTI_APP_DATASET_UNSUPPORTED", "MySQL 数据集必须且只能包含一个 AppID。")
        try:
            if self._begin_dataset(metadata, quality, app_ids[0]) == "READY":
                return
            processed = 0
            rows = (_file_rows(metadata["datasetId"], normalized_path)
                    if metadata["datasetType"] == "FILE_DAILY"
                    else _total_rows(metadata["datasetId"], metadata["sourceUnit"], normalized_path))
            writer = self._upsert_file_rows if metadata["datasetType"] == "FILE_DAILY" else self._upsert_total_rows
            if metadata["datasetType"] not in {"FILE_DAILY", "APP_DAILY_TOTAL"}:
                raise ImportProblem("DATASET_TYPE_UNSUPPORTED", "数据库不支持该数据集类型。")
            for batch in _chunks(rows, BATCH_ROWS):
                with self._connect() as connection:
                    writer(connection, batch)
                    processed += len(batch)
                    with connection.cursor() as cursor:
                        cursor.execute(
                            "UPDATE data_import_task SET processed_rows=%s,updated_at_ms=%s "
                            "WHERE import_task_id=%s AND status='RUNNING'",
                            (processed, _epoch_ms(), metadata["importTaskId"]),
                        )
                    connection.commit()
            if processed != metadata["rowCount"]:
                raise ImportProblem("DB_WRITE_UNCONFIRMED", "MySQL 写入行数与规范化数据集不一致。")
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE imported_dataset SET status='READY',ready_at_ms=%s WHERE dataset_id=%s AND status='IMPORTING'",
                    (_epoch_ms(), metadata["datasetId"]),
                )
                if cursor.rowcount != 1:
                    raise ImportProblem("DB_WRITE_UNCONFIRMED", "数据集未能原子切换为 READY。")
                connection.commit()
        except Exception:
            self._mark_dataset_failed(metadata["datasetId"])
            raise

    def _begin_dataset(self, metadata, quality, app_id):
        fingerprint = metadata["datasetId"].removeprefix("ds_")
        now_ms = _epoch_ms()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO imported_dataset
                (dataset_id,fingerprint_hash,app_id,dataset_type,dataset_source,status,source_unit,sha256,
                 parser_version,identity_mapping_version,total_rows,valid_rows,invalid_rows,duplicate_rows,
                 aggregated_rows,metadata_json,quality_report_json,created_at_ms,ready_at_ms)
                VALUES (%s,%s,%s,%s,%s,'IMPORTING',%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NULL)
                ON DUPLICATE KEY UPDATE dataset_id=dataset_id
                """,
                (
                    metadata["datasetId"], fingerprint, app_id, metadata["datasetType"], metadata["datasetSource"],
                    metadata["sourceUnit"], metadata["sha256"], metadata["parserVersion"],
                    metadata.get("identityMappingVersion"), quality["totalRows"], quality["validRows"],
                    quality["invalidRows"], quality["duplicateRows"], quality.get("aggregatedRows", 0),
                    json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                    json.dumps(quality, ensure_ascii=False, sort_keys=True, separators=(",", ":")), now_ms,
                ),
            )
            cursor.execute(
                "SELECT app_id,dataset_type,dataset_source,sha256,status,valid_rows FROM imported_dataset WHERE dataset_id=%s",
                (metadata["datasetId"],),
            )
            existing = cursor.fetchone()
            expected = (app_id, metadata["datasetType"], metadata["datasetSource"], metadata["sha256"])
            if not existing or tuple(existing[:4]) != expected or int(existing[5]) != int(quality["validRows"]):
                raise ImportProblem("DATASET_CONFLICT", "同一 datasetId 已绑定不同数据集内容或质量统计。", 409)
            status = existing[4]
            if status != "READY":
                cursor.execute(
                    "UPDATE imported_dataset SET status='IMPORTING',ready_at_ms=NULL WHERE dataset_id=%s",
                    (metadata["datasetId"],),
                )
                status = "IMPORTING"
            cursor.execute(
                "UPDATE data_import_task SET app_id=%s,total_rows=%s,valid_rows=%s,invalid_rows=%s,"
                "duplicate_rows=%s,aggregated_rows=%s,quality_report_json=%s,updated_at_ms=%s "
                "WHERE import_task_id=%s AND status='RUNNING'",
                (app_id, quality["totalRows"], quality["validRows"], quality["invalidRows"],
                 quality["duplicateRows"], quality.get("aggregatedRows", 0),
                 json.dumps(quality, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                 now_ms, metadata["importTaskId"]),
            )
            connection.commit()
            return status

    def _mark_dataset_failed(self, dataset_id):
        try:
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE imported_dataset SET status='FAILED' WHERE dataset_id=%s AND status<>'READY'",
                    (dataset_id,),
                )
                connection.commit()
        except Exception:
            pass

    def _upsert_file_rows(self, connection, batch):
        for row in batch:
            if not _fits_decimal(row[4], 65, 0):
                raise ImportProblem("DB_PRECISION_UNSUPPORTED", "字节值超过 DECIMAL(65,0) 精确范围。")
        values = [
            (row[0], row[1], row[2], row[3], _hash(row[3]), *row[4:], _epoch_ms())
            for row in batch
        ]
        with connection.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO file_daily_observation
                (dataset_id,app_id,observed_date,file_key,file_key_hash,traffic_bytes,parse_status,
                 source_identity_hash,source_row_count,source_identity_count,aggregation_rule,created_at_ms)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON DUPLICATE KEY UPDATE dataset_id=dataset_id
                """,
                values,
            )
            identities = " OR ".join(["(dataset_id=%s AND app_id=%s AND observed_date=%s AND file_key_hash=%s)"] * len(batch))
            params = [item for row in batch for item in (row[0], row[1], row[2], _hash(row[3]))]
            cursor.execute(
                "SELECT dataset_id,app_id,observed_date,file_key_hash,traffic_bytes,source_identity_hash,"
                f"source_row_count,source_identity_count,aggregation_rule FROM file_daily_observation WHERE {identities}",
                params,
            )
            existing = {
                (row[0], row[1], str(row[2]), row[3]):
                    (Decimal(str(row[4])), row[5], int(row[6]), int(row[7]), row[8])
                for row in cursor
            }
        for row in batch:
            current = existing.get((row[0], row[1], row[2], _hash(row[3])))
            expected = (Decimal(row[4]), row[6], row[7], row[8], row[9])
            if current != expected:
                raise ImportProblem("DB_ROW_CONFLICT", "同一数据集业务键已有不同文件观测，当前批次回滚。", 409)

    def _upsert_total_rows(self, connection, batch):
        for row in batch:
            if not _fits_decimal(row[3], 38, 18) or not _fits_decimal(row[4], 65, 9):
                raise ImportProblem("DB_PRECISION_UNSUPPORTED", "总流量超过已批准的定点数精度。")
        values = [(*row, _epoch_ms()) for row in batch]
        with connection.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO app_daily_total
                (dataset_id,app_id,observed_date,raw_traffic_value,total_traffic_bytes,source_unit,created_at_ms)
                VALUES (%s,%s,%s,%s,%s,%s,%s)
                ON DUPLICATE KEY UPDATE dataset_id=dataset_id
                """,
                values,
            )
            identities = " OR ".join(["(dataset_id=%s AND app_id=%s AND observed_date=%s)"] * len(batch))
            params = [item for row in batch for item in row[:3]]
            cursor.execute(
                "SELECT dataset_id,app_id,observed_date,raw_traffic_value,total_traffic_bytes,source_unit "
                f"FROM app_daily_total WHERE {identities}", params,
            )
            existing = {
                (row[0], row[1], str(row[2])): (Decimal(str(row[3])), Decimal(str(row[4])), row[5])
                for row in cursor
            }
        for row in batch:
            if existing.get(row[:3]) != (Decimal(row[3]), Decimal(row[4]), row[5]):
                raise ImportProblem("DB_ROW_CONFLICT", "同一数据集日期已有不同总流量，当前批次回滚。", 409)

    def has_observations(self, dataset_id: str, app_id: str, cutoff_date: str) -> bool:
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT 1 FROM file_daily_observation o JOIN imported_dataset d ON d.dataset_id=o.dataset_id "
                "WHERE d.dataset_id=%s AND d.app_id=%s AND d.dataset_type='FILE_DAILY' "
                "AND d.status='READY' AND d.dataset_source='REAL' AND o.observed_date<=%s LIMIT 1",
                (dataset_id, app_id, cutoff_date),
            )
            return cursor.fetchone() is not None

    def observations(self, dataset_id: str, app_id: str, cutoff_date: str):
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT o.app_id,o.file_key,o.observed_date,o.traffic_bytes "
                "FROM file_daily_observation o JOIN imported_dataset d ON d.dataset_id=o.dataset_id "
                "WHERE d.dataset_id=%s AND d.app_id=%s AND d.dataset_type='FILE_DAILY' "
                "AND d.status='READY' AND d.dataset_source='REAL' AND o.observed_date<=%s "
                "ORDER BY o.observed_date,o.file_key",
                (dataset_id, app_id, cutoff_date),
            )
            for row in cursor:
                yield {
                    "appId": row[0], "fileKey": row[1],
                    "date": row[2].isoformat() if hasattr(row[2], "isoformat") else str(row[2]),
                    "trafficBytes": str(row[3]),
                }
