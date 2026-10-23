import csv
import io
import json
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from app.main import DEFAULT_IMPORT_MAX_BYTES, create_app, resolve_import_max_bytes
from app.csv_import import ImportConfig
from app.import_store import ImportStore
from test_csv_import import MAPPING, csv_bytes


class RecordingDataStore:
    def __init__(self):
        self.tasks = {}
        self.statuses = []
        self.imported = []

    def check_schema(self):
        return None

    def ensure_import_task(self, task):
        self.tasks.setdefault(task["importTaskId"], dict(task))
        self.statuses.append(task["status"])

    def update_import_task(self, task):
        self.tasks[task["importTaskId"]] = dict(task)
        self.statuses.append(task["status"])

    def get_import_task(self, task_id):
        task = self.tasks[task_id]
        return {key: task[key] for key in (
            "importTaskId", "status", "datasetId", "error", "quality", "createdAt", "updatedAt"
        )}

    def import_dataset(self, metadata, normalized_path):
        self.imported.append((metadata["datasetId"], normalized_path.name))


class FailingDataStore(RecordingDataStore):
    def import_dataset(self, metadata, normalized_path):
        raise RuntimeError("database write failed")


def test_mysql_mode_records_authoritative_import_lifecycle(tmp_path):
    data_store = RecordingDataStore()
    store = ImportStore(tmp_path, data_store=data_store)
    store.initialize()
    receipt = store.accept(
        csv_bytes([["a", "2026/1/5", "100", "10350"]]),
        ImportConfig(MAPPING, "FILE_DAILY", "REAL", "B"),
        "mysql-ledger",
        "history.csv",
    )
    assert store.process_next() is True
    task = store.get_import(receipt["importTaskId"])

    assert task["status"] == "SUCCEEDED"
    assert data_store.statuses[0] == "PENDING"
    assert "RUNNING" in data_store.statuses
    assert data_store.statuses[-1] == "SUCCEEDED"
    assert data_store.imported == [(task["datasetId"], "normalized.csv")]
    assert task["quality"]["totalRows"] == 1
    assert task["quality"]["validRows"] == 1


def test_database_write_failure_cannot_publish_success(tmp_path):
    data_store = FailingDataStore()
    store = ImportStore(tmp_path, data_store=data_store)
    store.initialize()
    receipt = store.accept(
        csv_bytes([["a", "2026/1/5", "100", "10350"]]),
        ImportConfig(MAPPING, "FILE_DAILY", "REAL", "B"),
        "mysql-failure",
        "history.csv",
    )

    assert store.process_next() is True
    task = store.get_import(receipt["importTaskId"])
    assert task["status"] == "FAILED"
    assert task["datasetId"] is None
    assert data_store.statuses[-1] == "FAILED"


def upload(client, raw=None, key="upload-one", **fields):
    data = {"mapping": json.dumps(MAPPING), "datasetType": "FILE_DAILY", "datasetSource": "REAL", "sourceUnit": "B", **fields}
    return client.post("/api/v1/imports", data=data, files={"file": ("arbitrary.csv", raw if raw is not None else csv_bytes([["a", "2026/1/5", "1E3", "10350"]]), "text/csv")}, headers={"Idempotency-Key": key})


def terminal(client, task_id):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        response = client.get(f"/api/v1/imports/{task_id}")
        assert response.status_code == 200, response.text
        task = response.json()
        if task["status"] in {"SUCCEEDED", "FAILED"}:
            return task
        assert task["datasetId"] is None
        time.sleep(0.01)
    pytest.fail("import did not reach a terminal state")


def test_upload_limit_defaults_to_200_mib_and_preserves_overrides(monkeypatch):
    monkeypatch.delenv("IMPORT_MAX_BYTES", raising=False)
    assert DEFAULT_IMPORT_MAX_BYTES == 209715200
    assert resolve_import_max_bytes(None) == 209715200

    monkeypatch.setenv("IMPORT_MAX_BYTES", "1048576")
    assert resolve_import_max_bytes(None) == 1048576
    assert resolve_import_max_bytes(512) == 512


def test_durable_upload_ready_dataset_and_quality(tmp_path):
    raw = csv_bytes([["https://host/a", "2026/1/5", "1.0000000000000001E16", "10350"], ["host/a", "2026/1/7", "2E3", "10350"]])
    with TestClient(create_app(tmp_path)) as client:
        response = upload(client, raw)
        assert response.status_code == 202
        assert response.json()["status"] == "PENDING"
        task_id = response.json()["importTaskId"]
        assert isinstance(task_id, str)
        assert "datasetId" not in response.json()
        assert (tmp_path / "imports" / task_id / "original.csv").read_bytes() == raw
        task = terminal(client, task_id)
        assert task["status"] == "SUCCEEDED"
        dataset_id = task["datasetId"]
        metadata = client.get(f"/api/v1/datasets/{dataset_id}").json()
        assert metadata["status"] == "READY"
        assert metadata["appIds"] == ["10350"]
        assert metadata["datasetSource"] == "REAL"
        assert metadata["sourceUnit"] == "B"
        assert metadata["dateRange"] == {"start": "2026-01-05", "end": "2026-01-07"}
        quality = client.get(f"/api/v1/datasets/{dataset_id}/quality").json()
        assert quality["totalRows"] == quality["validRows"] == 2
        assert quality["missingDateCount"] == 1
        path = tmp_path / "datasets" / dataset_id
        assert (path / "source.csv").read_bytes() == raw
        assert (path / "original.csv").read_bytes() == raw
        assert (path / "source.csv").stat().st_ino == (path / "original.csv").stat().st_ino
        normalized = list(csv.DictReader(io.StringIO((path / "normalized.csv").read_text())))
        assert [row["fileKey"] for row in normalized] == ["host/a", "https://host/a"]
        assert normalized[1]["trafficBytes"] == "10000000000000001"
        totals = list(csv.DictReader(io.StringIO((path / "daily_totals.csv").read_text())))
        assert totals == [
            {"appId": "10350", "date": "2026-01-05", "totalTrafficBytes": "10000000000000001", "fileCount": "1"},
            {"appId": "10350", "date": "2026-01-07", "totalTrafficBytes": "2000", "fileCount": "1"},
        ]
        assert not list(path.glob("*.sqlite*"))
        assert client.get("/api/v1/datasets").json()["total"] == 1
        assert client.get("/api/v1/datasets?page=2&pageSize=1").json()["items"] == []


def test_same_key_is_idempotent_different_content_or_source_conflicts(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        first = upload(client)
        again = upload(client)
        assert again.status_code == 202
        assert again.json()["importTaskId"] == first.json()["importTaskId"]
        assert upload(client, csv_bytes([["b", "2026/1/5", "1", "10350"]])).status_code == 409
        assert upload(client, datasetSource="FAKE").status_code == 409
        result = terminal(client, first.json()["importTaskId"])
        other_key = upload(client, key="another-key")
        assert terminal(client, other_key.json()["importTaskId"])["datasetId"] == result["datasetId"]
        assert client.get("/api/v1/datasets").json()["total"] == 1


def test_concurrent_same_key_has_one_durable_receipt(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        with ThreadPoolExecutor(max_workers=4) as pool:
            responses = list(pool.map(lambda _: upload(client), range(4)))
        assert {r.status_code for r in responses} == {202}
        assert len({r.json()["importTaskId"] for r in responses}) == 1
        assert len(list((tmp_path / "imports").glob("imp_*"))) == 1


@pytest.mark.parametrize("fields,code", [
    ({"mapping": "not-json"}, "INVALID_MAPPING"),
    ({"mapping": "[]"}, "INVALID_MAPPING"),
    ({"mapping": json.dumps({**MAPPING, "date": "文件标识"})}, "INVALID_MAPPING"),
    ({"sourceUnit": "UNKNOWN"}, "UNIT_UNCONFIRMED"),
    ({"datasetType": "APP_DAILY_TOTAL"}, "DATASET_TYPE_UNSUPPORTED"),
    ({"datasetSource": "UNKNOWN"}, "INVALID_REQUEST"),
])
def test_invalid_config_is_rejected_before_accepting(tmp_path, fields, code):
    with TestClient(create_app(tmp_path)) as client:
        response = upload(client, **fields)
        assert response.status_code == 422
        assert response.json()["error"]["code"] == code
        assert list((tmp_path / "imports").glob("imp_*")) == []


@pytest.mark.parametrize("rows,fields,code", [
    ([["a", "bad-date", "100", "10350"]], {}, "INVALID_CSV"),
    ([["a", "2026/1/5", "100", "10350"]], {"appId": "10066"}, "APP_MISMATCH"),
    ([["a", "2026/1/5", "100", "10350"], ["a", "2026/1/5", "200", "10350"]], {}, "DUPLICATE_CONFLICT"),
])
def test_bad_data_has_queryable_failure_and_report_but_no_dataset(tmp_path, rows, fields, code):
    with TestClient(create_app(tmp_path)) as client:
        response = upload(client, csv_bytes(rows), **fields)
        assert response.status_code == 202
        task = terminal(client, response.json()["importTaskId"])
        assert task["status"] == "FAILED"
        assert task["datasetId"] is None
        assert task["error"]["code"] == code
        assert task["quality"]["errorSamples"]
        assert client.get("/api/v1/datasets").json()["items"] == []


def test_oversized_upload_and_row_limit_are_not_silent_truncations(tmp_path):
    with TestClient(create_app(tmp_path, max_file_bytes=128, max_rows=1)) as client:
        assert upload(client, b"a" * 129).status_code == 413
        assert list((tmp_path / "incoming").glob("*.part")) == []
        assert upload(client, b"").status_code == 422
        response = upload(client, csv_bytes([["a", "2026/1/5", "1", "10350"], ["b", "2026/1/5", "2", "10350"]]))
        task = terminal(client, response.json()["importTaskId"])
        assert task["status"] == "FAILED"
        assert task["error"]["code"] == "ROW_LIMIT_EXCEEDED"


def test_default_upload_limit_accepts_more_than_legacy_five_mib(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        response = upload(client, b"x" * (5 * 1024 * 1024 + 1), key="over-legacy-limit")

        assert response.status_code == 202
        assert response.json()["status"] == "PENDING"


def test_default_row_limit_accepts_more_than_legacy_twenty_thousand(tmp_path):
    rows = [[f"file-{index}", "2026/1/5", "1", "10350"] for index in range(20001)]
    with TestClient(create_app(tmp_path)) as client:
        response = upload(client, csv_bytes(rows), key="over-legacy-row-limit")
        task = terminal(client, response.json()["importTaskId"])

        assert task["status"] == "SUCCEEDED"
        metadata = client.get(f"/api/v1/datasets/{task['datasetId']}").json()
        assert metadata["rowCount"] == 20001


def test_restart_preserves_success_failures_and_idempotency(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        task_id = upload(client).json()["importTaskId"]
        task = terminal(client, task_id)
        dataset_id = task["datasetId"]
        failed_id = upload(client, b"bad", key="bad").json()["importTaskId"]
        terminal(client, failed_id)
    with TestClient(create_app(tmp_path)) as client:
        assert client.get(f"/api/v1/imports/{task_id}").json() == task
        assert client.get(f"/api/v1/imports/{failed_id}").json()["status"] == "FAILED"
        assert client.get(f"/api/v1/datasets/{dataset_id}").json()["status"] == "READY"
        assert client.get(f"/api/v1/datasets/{dataset_id}/quality").json()["validRows"] == 1
        assert upload(client).json()["importTaskId"] == task_id


def test_restart_recovers_pending_and_explicitly_fails_interrupted_running(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        task_id = upload(client).json()["importTaskId"]
        task = terminal(client, task_id)
    task_path = tmp_path / "imports" / task_id / "task.json"
    record = json.loads(task_path.read_text())
    record.update(status="PENDING", datasetId=None)
    task_path.write_text(json.dumps(record))
    with TestClient(create_app(tmp_path)) as client:
        assert terminal(client, task_id)["datasetId"] == task["datasetId"]
    # A RUNNING task with no published dataset is an interrupted import, not an eternal RUNNING.
    record.update(status="RUNNING", datasetId=None, snapshotId="ds_" + "0" * 64)
    task_path.write_text(json.dumps(record))
    with TestClient(create_app(tmp_path)) as client:
        interrupted = client.get(f"/api/v1/imports/{task_id}").json()
        assert interrupted["status"] == "FAILED"
        assert interrupted["error"]["code"] == "IMPORT_INTERRUPTED"


def test_restart_repairs_result_published_before_task_success(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        task_id = upload(client).json()["importTaskId"]
        task = terminal(client, task_id)
    path = tmp_path / "imports" / task_id / "task.json"
    record = json.loads(path.read_text())
    record.update(status="RUNNING", datasetId=None)
    path.write_text(json.dumps(record))
    with TestClient(create_app(tmp_path)) as client:
        repaired = client.get(f"/api/v1/imports/{task_id}").json()
        assert repaired["status"] == "SUCCEEDED"
        assert repaired["datasetId"] == task["datasetId"]


def test_pending_receipt_is_processed_after_restart_without_a_published_dataset(tmp_path):
    store = ImportStore(tmp_path)
    store.initialize()
    raw = csv_bytes([["a", "2026/1/5", "100", "10350"]])
    receipt = store.accept(raw, ImportConfig(MAPPING, "FILE_DAILY", "REAL", "B"), "pending", "any-name.csv")
    assert receipt["status"] == "PENDING"
    assert list((tmp_path / "datasets").glob("ds_*")) == []
    with TestClient(create_app(tmp_path)) as client:
        task = terminal(client, receipt["importTaskId"])
        assert task["status"] == "SUCCEEDED"
        assert client.get(f"/api/v1/datasets/{task['datasetId']}").status_code == 200


def test_failed_later_upload_does_not_modify_a_previous_ready_snapshot(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        first = terminal(client, upload(client).json()["importTaskId"])
        dataset_id = first["datasetId"]
        before = client.get(f"/api/v1/datasets/{dataset_id}").json()
        failed = terminal(client, upload(client, b"broken", key="bad-new-batch").json()["importTaskId"])
        assert failed["status"] == "FAILED"
        assert client.get(f"/api/v1/datasets/{dataset_id}").json() == before
        assert client.get("/api/v1/datasets").json()["total"] == 1


def test_storage_failure_returns_503_not_fake_acceptance(tmp_path, monkeypatch):
    with TestClient(create_app(tmp_path)) as client:
        def fail_write(*_args, **_kwargs):
            raise OSError("simulated disk failure")
        monkeypatch.setattr("app.import_store.write_bytes", fail_write)
        response = upload(client)
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "STORAGE_UNAVAILABLE"
        assert "importTaskId" not in response.json()
        assert list((tmp_path / "incoming").glob("*.part")) == []


def test_errors_and_browser_cors_match_frontend_contract(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        response = client.options("/api/v1/imports", headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "Idempotency-Key"})
        assert response.status_code == 200
        assert "POST" in response.headers["access-control-allow-methods"]
        assert client.post("/api/v1/imports").json()["error"]["code"] == "INVALID_REQUEST"
        assert client.get("/api/v1/imports/nonexistent").status_code == 404
        assert client.get("/api/v1/datasets/nonexistent").json()["error"]["code"] == "DATASET_NOT_FOUND"
        assert client.get("/api/v1/datasets?page=0").status_code == 422


