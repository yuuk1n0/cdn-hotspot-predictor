"""Real Uvicorn processes and durable storage, including an actual 100-second Mock."""

import csv
import io
import json
import socket
import time
from datetime import datetime
from pathlib import Path

from test_import_api import terminal, upload
from test_import_http import serve
from test_prediction_api import prediction_terminal


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def actual_body(client, task_id):
    raw = (Path(__file__).resolve().parents[2] / "datasets" / "10350.csv").read_bytes()
    receipt = upload(client, raw, key="step4-real-10350")
    assert receipt.status_code == 202
    imported = terminal(client, receipt.json()["importTaskId"])
    metadata = client.get(f"/api/v1/datasets/{imported['datasetId']}").json()
    assert metadata["appIds"] == ["10350"]
    body = {"taskId": task_id, "datasetId": imported["datasetId"], "appId": metadata["appIds"][0], "cutoffDate": "2026-10-06", "horizonDays": 7, "modelVersion": "contract-demo-v1"}
    # Independent CSV inspection checks candidate membership, not a second call to Mock helpers.
    rows = list(csv.DictReader(io.StringIO(raw.decode("gb18030"))))
    expected_keys = {row["文件标识"] for row in rows if row["AppID"] == body["appId"] and datetime.strptime(row["日期"], "%Y/%m/%d").date().isoformat() <= body["cutoffDate"]}
    return body, expected_keys


def full_result(client, run_id):
    first = client.get(f"/api/v1/predictions/{run_id}/items?pageSize=200").json()
    items = list(first["items"])
    for page in range(2, (first["total"] + 199) // 200 + 1):
        items.extend(client.get(f"/api/v1/predictions/{run_id}/items?page={page}&pageSize=200").json()["items"])
    return first, items


def test_real_http_short_mock_complete_candidates_and_restart(tmp_path, monkeypatch):
    monkeypatch.setenv("PREDICTION_MODE", "MOCK")
    monkeypatch.setenv("MOCK_DELAY_SECONDS", "0")
    port, root = free_port(), tmp_path / "durable"
    with serve(root, port, tmp_path / "short.log") as client:
        body, expected = actual_body(client, "task_real_short")
        assert client.post("/api/v1/predictions", json=body).status_code == 202
        task = prediction_terminal(client, body["taskId"])
        assert task["status"] == "SUCCEEDED"
        result, items = full_result(client, task["runId"])
        assert result["total"] == len(items) == len(expected)
        assert {item["fileKey"] for item in items} == expected
        assert [item["rank"] for item in items] == list(range(1, len(items) + 1))
        assert result["datasetId"] == body["datasetId"]
        assert result["datasetSource"] == "REAL" and result["predictionSource"] == "MOCK"
        assert result["predictionStartDate"] == "2026-10-07" and result["predictionEndDate"] == "2026-10-13"
        assert client.post("/api/v1/predictions", json=body).json()["runId"] == task["runId"]
        print(json.dumps({"case": "short", "datasetId": body["datasetId"], "taskId": task["taskId"], "runId": task["runId"], "candidates": len(items)}))
    with serve(root, port, tmp_path / "restart.log") as restarted:
        assert restarted.get(f"/api/v1/tasks/{body['taskId']}").json() == task
        assert full_result(restarted, task["runId"]) == (result, items)
        assert restarted.post("/api/v1/predictions", json=body).json()["runId"] == task["runId"]


def test_real_http_100_second_mock_uses_same_contract_without_blocking_submission(tmp_path, monkeypatch):
    monkeypatch.setenv("PREDICTION_MODE", "MOCK")
    monkeypatch.setenv("MOCK_DELAY_SECONDS", "100")
    monkeypatch.setenv("PREDICTION_TASK_TIMEOUT_SECONDS", "180")
    port, root = free_port(), tmp_path / "durable"
    with serve(root, port, tmp_path / "long.log") as client:
        body, expected = actual_body(client, "task_real_100s")
        started = time.monotonic()
        response = client.post("/api/v1/predictions", json=body)
        submit_elapsed = time.monotonic() - started
        assert response.status_code == 202 and submit_elapsed < 3
        for _ in range(200):
            running = client.get(f"/api/v1/tasks/{body['taskId']}").json()
            if running["stage"] == "COMPUTING":
                break
            time.sleep(0.01)
        assert running["status"] == "RUNNING" and running["runId"] is None
        assert client.get("/api/v1/health").json()["status"] == "UP"
        assert client.get(f"/api/v1/datasets/{body['datasetId']}").status_code == 200
        assert client.post("/api/v1/predictions", json=body).json()["taskId"] == body["taskId"]
        deadline = started + 130
        while time.monotonic() < deadline:
            task = client.get(f"/api/v1/tasks/{body['taskId']}").json()
            if task["status"] in {"SUCCEEDED", "FAILED"}:
                break
            time.sleep(1)
        elapsed = time.monotonic() - started
        assert task["status"] == "SUCCEEDED", task
        assert 99.5 <= elapsed < 130
        result, items = full_result(client, task["runId"])
        assert {item["fileKey"] for item in items} == expected
        assert result["total"] == len(items)
        assert result["predictionSource"] == "MOCK" and result["horizonDays"] == 7
        print(json.dumps({"case": "real-100s", "submitSeconds": round(submit_elapsed, 3), "elapsedSeconds": round(elapsed, 3), "datasetId": body["datasetId"], "taskId": task["taskId"], "runId": task["runId"], "candidates": len(items)}))
    with serve(root, port, tmp_path / "long-restart.log") as restarted:
        assert restarted.get(f"/api/v1/tasks/{body['taskId']}").json() == task
        assert full_result(restarted, task["runId"]) == (result, items)
