import csv
from contextlib import contextmanager
from pathlib import Path

from app.mysql_store import MySqlDataStore, MySqlSettings, _file_rows, _fits_decimal, _total_rows


class FakeCursor:
    def __init__(self, rows=(), one=None):
        self.rows = list(rows)
        self.one = one
        self.calls = []
        self.fetchone_calls = 0
        self.rowcount = 1

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def execute(self, sql, params=None):
        self.calls.append((" ".join(sql.split()), params))

    def fetchone(self):
        self.fetchone_calls += 1
        return self.one

    def fetchall(self):
        raise AssertionError("observation reads must stream instead of fetchall")

    def __iter__(self):
        return iter(self.rows)


class FakeConnection:
    def __init__(self, cursor):
        self.value = cursor
        self.commits = 0

    def cursor(self, *_args, **_kwargs):
        return self.value

    def commit(self):
        self.commits += 1

    def rollback(self):
        pass


class Store(MySqlDataStore):
    def __init__(self, connection):
        super().__init__(MySqlSettings("localhost", 3306, "db", "user", "password"))
        self.connection = connection

    @contextmanager
    def _connect(self):
        yield self.connection


def write_csv(path: Path, fieldnames, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def test_normalized_file_rows_match_versioned_schema(tmp_path):
    path = tmp_path / "normalized.csv"
    write_csv(path, [
        "fileKey", "date", "trafficBytes", "appId", "sourceIdentityHash", "parseStatus",
        "sourceRowCount", "sourceIdentityCount", "aggregationRule",
    ], [{
        "fileKey": "path/a", "date": "2026-01-01", "trafficBytes": "300", "appId": "10066",
        "sourceIdentityHash": "a" * 64, "parseStatus": "URL_PATH", "sourceRowCount": "2",
        "sourceIdentityCount": "2", "aggregationRule": "SUM_NORMALIZED_SLICES",
    }])

    assert list(_file_rows("ds_one", path)) == [(
        "ds_one", "10066", "2026-01-01", "path/a", "300", "URL_PATH", "a" * 64,
        2, 2, "SUM_NORMALIZED_SLICES",
    )]


def test_normalized_total_rows_keep_raw_gb_and_exact_byte_value(tmp_path):
    path = tmp_path / "normalized.csv"
    write_csv(path, ["appId", "date", "rawTrafficValue", "totalTrafficBytes"], [{
        "appId": "10350", "date": "2026-01-05", "rawTrafficValue": "0.33978334069252014",
        "totalTrafficBytes": "339783340.69252014000000000",
    }])

    assert list(_total_rows("ds_total", "GB_DECIMAL", path)) == [(
        "ds_total", "10350", "2026-01-05", "0.33978334069252014",
        "339783340.69252014000000000", "GB_DECIMAL",
    )]


def test_decimal_precision_ignores_fractional_trailing_zeroes_only():
    assert _fits_decimal("25755514164425.433000000000", 65, 9)
    assert not _fits_decimal("25755514164425.433000000001", 65, 9)


def test_observations_stream_one_ready_real_dataset_only():
    cursor = FakeCursor(rows=[("10066", "a", "2026-01-01", "123")])
    store = Store(FakeConnection(cursor))

    rows = list(store.observations("ds_one", "10066", "2026-01-31"))

    assert rows == [{"appId": "10066", "fileKey": "a", "date": "2026-01-01", "trafficBytes": "123"}]
    sql, params = cursor.calls[0]
    assert "d.dataset_id=%s" in sql
    assert "d.status='READY'" in sql
    assert "d.dataset_source='REAL'" in sql
    assert params == ("ds_one", "10066", "2026-01-31")


def test_schema_check_consumes_each_unbuffered_result():
    cursor = FakeCursor()
    store = Store(FakeConnection(cursor))

    store.check_schema()

    assert len(cursor.calls) == 4
    assert cursor.fetchone_calls == 4


def test_import_task_receipt_is_idempotent_only_for_same_request():
    cursor = FakeCursor(one=("request-hash", "ds_target"))
    connection = FakeConnection(cursor)
    store = Store(connection)
    task = {
        "importTaskId": "imp_one", "requestHash": "request-hash", "snapshotId": "ds_target",
        "sha256": "f" * 64, "status": "PENDING", "config": {
            "appId": "10066", "datasetType": "FILE_DAILY", "datasetSource": "REAL"
        }, "createdAt": "2026-10-22T00:00:00Z", "updatedAt": "2026-10-22T00:00:00Z",
    }

    store.ensure_import_task(task)

    assert connection.commits == 1
    assert "INSERT INTO data_import_task" in cursor.calls[0][0]
    assert "ON DUPLICATE KEY UPDATE import_task_id=import_task_id" in cursor.calls[0][0]


def test_failed_dataset_retry_is_returned_to_importing_before_batches():
    cursor = FakeCursor(one=("10066", "FILE_DAILY", "REAL", "f" * 64, "FAILED", 1))
    store = Store(FakeConnection(cursor))
    metadata = {
        "datasetId": "ds_" + "a" * 64, "datasetType": "FILE_DAILY", "datasetSource": "REAL",
        "sourceUnit": "B", "sha256": "f" * 64, "parserVersion": "file-daily-v3",
        "identityMappingVersion": "filekey-v1", "importTaskId": "imp_one",
    }
    quality = {
        "totalRows": 1, "validRows": 1, "invalidRows": 0, "duplicateRows": 0, "aggregatedRows": 0,
    }

    assert store._begin_dataset(metadata, quality, "10066") == "IMPORTING"
    assert any("SET status='IMPORTING'" in sql for sql, _ in cursor.calls)


def test_terminal_task_update_without_quality_preserves_database_progress():
    cursor = FakeCursor()
    connection = FakeConnection(cursor)
    store = Store(connection)
    task = {
        "importTaskId": "imp_one", "status": "SUCCEEDED", "stage": "SUCCEEDED",
        "quality": None, "error": None, "config": {"appId": "10066"},
        "updatedAt": "2026-10-22T00:00:00Z",
    }

    store.update_import_task(task)

    sql, params = cursor.calls[0]
    assert "processed_rows=COALESCE(%s,processed_rows)" in sql
    assert "quality_report_json=COALESCE(%s,quality_report_json)" in sql
    assert params[3:9] == (None, None, None, None, None, None)
