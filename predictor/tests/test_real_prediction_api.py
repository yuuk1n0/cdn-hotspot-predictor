"""Real CSV import -> async v1 task -> durable paged result through FastAPI."""

from pathlib import Path
import time

from fastapi.testclient import TestClient

from app.main import create_app
from test_import_api import terminal, upload
from test_prediction_api import prediction_terminal


def test_real_csv_daily_weekly_predictions_and_api_failures(tmp_path):
    app = create_app(tmp_path, prediction_mode="MODEL", prediction_model_version="hotspot-simple-v1",
                     mock_delay_seconds=100)
    with TestClient(app) as client:
        raw = (Path(__file__).resolve().parents[2] / "datasets/10350.csv").read_bytes()
        imported = terminal(client, upload(client, raw, key="real-v1").json()["importTaskId"])
        assert imported["status"] == "SUCCEEDED"
        body = {"taskId": "daily", "datasetId": imported["datasetId"], "appId": "10350",
                "cutoffDate": "2026-10-06", "horizonDays": 1, "modelVersion": "hotspot-simple-v1"}
        for horizon, total in [(1, 141981266508), (7, 993868865539)]:
            body.update(taskId=f"real-{horizon}", horizonDays=horizon)
            started = time.monotonic()
            receipt = client.post("/api/v1/predictions", json=body)
            assert receipt.status_code == 202 and time.monotonic() - started < 3
            task = prediction_terminal(client, body["taskId"], timeout=10)
            assert task["status"] == "SUCCEEDED", task
            assert task["resultMode"] == "REAL" and task["predictionSource"] == "MODEL"
            page = client.get(f"/api/v1/predictions/{task['runId']}/items").json()
            assert page["total"] == 190 and len(page["items"]) == 50
            assert sum(int(x["predictedBytes"]) for x in page["items"]) == total
            assert page["resultMode"] == "REAL" and page["datasetSource"] == "REAL"
            assert page["predictionEndDate"] == ("2026-10-07" if horizon == 1 else "2026-10-13")
            assert client.post("/api/v1/predictions", json=body).json()["runId"] == task["runId"]
            assert client.post("/api/v1/predictions", json={**body, "horizonDays": 7 if horizon == 1 else 1}).status_code == 409
        for field, value, code in [("modelVersion", "hotspot-lightgbm-v3", "MODEL_VERSION_UNSUPPORTED"),
                                   ("appId", "not-in-dataset", "APP_MISMATCH")]:
            bad = {**body, "taskId": f"bad-{field}", field: value}
            assert client.post("/api/v1/predictions", json=bad).status_code == 202
            failed = prediction_terminal(client, bad["taskId"])
            assert failed["status"] == "FAILED" and failed["runId"] is None
            assert failed["error"]["code"] == code
        saved_task, saved_page = task, page
    # Default Mock settings on restart must not reinterpret completed MODEL runs.
    with TestClient(create_app(tmp_path)) as restarted:
        assert restarted.get(f"/api/v1/tasks/{body['taskId']}").json() == saved_task
        assert restarted.get(f"/api/v1/predictions/{saved_task['runId']}/items").json() == saved_page
