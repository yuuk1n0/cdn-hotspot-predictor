import time

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from test_import_api import terminal, upload


def prediction_body(client, task_id="task_java"):
    imported = terminal(client, upload(client).json()["importTaskId"])
    assert imported["status"] == "SUCCEEDED"
    return {"taskId": task_id, "datasetId": imported["datasetId"], "appId": "10350", "cutoffDate": "2026-01-05", "horizonDays": 7, "modelVersion": "contract-demo-v1"}


def prediction_terminal(client, task_id, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = client.get(f"/api/v1/tasks/{task_id}")
        assert response.status_code == 200, response.text
        value = response.json()
        if value["status"] in {"SUCCEEDED", "FAILED"}:
            return value
        assert value["runId"] is None and value["error"] is None
        time.sleep(0.02)
    pytest.fail("prediction did not reach a terminal state")


def test_java_compatible_202_task_and_complete_result_routes(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        body = prediction_body(client)
        accepted = client.post("/api/v1/predictions", json=body)
        assert accepted.status_code == 202
        assert accepted.json()["taskId"] == "task_java"
        assert accepted.json()["status"] == "PENDING"
        task = prediction_terminal(client, body["taskId"])
        assert task["status"] == "SUCCEEDED"
        response = client.get(f"/api/v1/predictions/{task['runId']}/items?page=1&pageSize=50")
        assert response.status_code == 200
        result = response.json()
        assert result["total"] == 1
        assert result["items"][0]["predictedBytes"] == "7000"
        assert result["datasetId"] == body["datasetId"]
        assert result["datasetSource"] == "REAL" and result["predictionSource"] == "MOCK"
        assert client.post("/api/v1/predictions", json=body).json()["runId"] == task["runId"]
        conflict = client.post("/api/v1/predictions", json={**body, "horizonDays": 1})
        assert conflict.status_code == 409
        assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"
    with TestClient(create_app(tmp_path)) as restarted:
        assert restarted.get(f"/api/v1/tasks/{body['taskId']}").json() == task
        assert restarted.get(f"/api/v1/predictions/{task['runId']}/items").json() == {**result, "pageSize": 50}


@pytest.mark.parametrize("change", [{"taskId": 1}, {"horizonDays": "7"}, {"cutoffDate": "2026-02-30"}, {"appId": 10350}, {"mock": True}])
def test_bad_request_returns_422_before_accepting_task(tmp_path, change):
    with TestClient(create_app(tmp_path)) as client:
        body = prediction_body(client)
        response = client.post("/api/v1/predictions", json={**body, **change})
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "INVALID_REQUEST"
        assert list((tmp_path / "tasks").glob("*/task.json")) == []


def test_delayed_execution_does_not_hold_http_or_block_import_queries(tmp_path):
    with TestClient(create_app(tmp_path, mock_delay_seconds=100)) as client:
        body = prediction_body(client)
        started = time.monotonic()
        assert client.post("/api/v1/predictions", json=body).status_code == 202
        assert time.monotonic() - started < 2
        for _ in range(100):
            task = client.get("/api/v1/tasks/task_java").json()
            if task["stage"] == "COMPUTING":
                break
            time.sleep(0.02)
        assert task["status"] == "RUNNING"
        assert task["runId"] is None
        assert client.get("/api/v1/datasets").json()["total"] == 1
        queued = client.post("/api/v1/predictions", json={**body, "taskId": "task_queued"})
        assert queued.status_code == 202
        assert queued.json()["status"] == "PENDING"
        assert client.get("/api/v1/health").json()["status"] == "UP"
    with TestClient(create_app(tmp_path)) as restarted:
        assert restarted.get("/api/v1/tasks/task_java").json()["error"]["code"] == "EXECUTION_INTERRUPTED"


def test_unavailable_storage_is_503_not_fake_acceptance(tmp_path, monkeypatch):
    with TestClient(create_app(tmp_path)) as client:
        body = prediction_body(client)
        def fail_write(*args, **kwargs):
            raise OSError("simulated disk failure")
        monkeypatch.setattr("app.prediction_store.write_bytes", fail_write)
        response = client.post("/api/v1/predictions", json=body)
        assert response.status_code == 503
        assert "taskId" not in response.json()
        assert client.get("/api/v1/tasks/task_java").status_code == 404


def test_missing_ids_and_invalid_page_sizes_return_explicit_errors(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        assert client.get("/api/v1/tasks/not-found").json()["error"]["code"] == "TASK_NOT_FOUND"
        assert client.get("/api/v1/predictions/not-found/items").json()["error"]["code"] == "RUN_NOT_FOUND"
        for query in ("page=0", "pageSize=0", "pageSize=201", "page=abc"):
            assert client.get(f"/api/v1/predictions/not-found/items?{query}").status_code == 422


def test_real_mode_never_silently_executes_a_mock(tmp_path):
    with TestClient(create_app(tmp_path, prediction_mode="MODEL")) as client:
        body = prediction_body(client)
        assert client.post("/api/v1/predictions", json=body).status_code == 202
        task = prediction_terminal(client, body["taskId"])
        assert task["status"] == "FAILED"
        assert task["error"]["code"] == "MODEL_NOT_IMPLEMENTED"
        assert task["runId"] is None
