"""Use real calculations through the durable asynchronous queue, never a fake predictor."""

import json

import pytest

from app.prediction_store import PredictionStore
from test_prediction_store import prepared, finish


@pytest.mark.parametrize("horizon,total", [(1, "1775"), (7, "12425")])
def test_real_queue_publishes_all_candidates_modes_and_survives_restart(tmp_path, horizon, total):
    rows = [[f"f-{i:03}", "2026/1/5", str(i), "10350"] for i in range(1, 61)]
    rows.append(["future", "2026/1/8", "999999999", "10350"])
    store, body = prepared(tmp_path, rows, mode="MODEL", model_version="hotspot-simple-v1")
    body.update(modelVersion="hotspot-simple-v1", horizonDays=horizon)
    assert store.accept(body)["status"] == "PENDING"
    finish(store)
    task = store.get_task(body["taskId"])
    assert task["status"] == "SUCCEEDED", task
    assert task["predictionSource"] == "MODEL" and task["resultMode"] == "REAL"
    page = store.get_items(task["runId"], 1, 50)
    assert page["total"] == 60 and len(page["items"]) == 50
    assert str(sum(int(x["predictedBytes"]) for x in page["items"])) == total
    assert page["resultMode"] == "REAL" and page["datasetSource"] == "REAL"
    assert page["modelInfo"]["selectedMethod"] == "last_day"
    assert len(store.get_items(task["runId"], 2, 50)["items"]) == 10
    assert len(task["runId"]) == 68
    assert all(x["score"] is None and "模拟" not in x["reason"] for x in page["items"])
    restart = PredictionStore(tmp_path, store.imports)
    restart.initialize()
    assert restart.get_task(body["taskId"]) == task
    assert restart.get_items(task["runId"], 1, 50) == page
    assert restart.accept(body)["runId"] == task["runId"]


def test_queued_real_task_does_not_change_mode_after_restart(tmp_path):
    store, body = prepared(tmp_path, mode="MODEL", model_version="hotspot-simple-v1")
    body["modelVersion"] = "hotspot-simple-v1"
    store.accept(body)
    restarted = PredictionStore(tmp_path, store.imports, mode="MOCK")
    restarted.initialize()
    finish(restarted)
    task = restarted.get_task(body["taskId"])
    assert task["status"] == "SUCCEEDED", task
    assert task["resultMode"] == "REAL"


def test_changed_model_artifact_fails_without_publishing_or_mock_fallback(tmp_path, monkeypatch):
    store, body = prepared(tmp_path, mode="MODEL", model_version="hotspot-simple-v1")
    body["modelVersion"] = "hotspot-simple-v1"
    store.accept(body)
    monkeypatch.setattr("app.prediction_store.algorithm_fingerprint", lambda: "0" * 64)
    finish(store)
    task = store.get_task(body["taskId"])
    assert task["status"] == "FAILED" and task["runId"] is None
    assert task["error"]["code"] == "MODEL_ARTIFACT_CHANGED"
    assert not list(store.runs.glob("run_*"))


def test_new_task_after_disk_update_cannot_claim_new_hash_for_old_loaded_code(tmp_path, monkeypatch):
    store, body = prepared(tmp_path, mode="MODEL", model_version="hotspot-simple-v1")
    body["modelVersion"] = "hotspot-simple-v1"
    # Simulate a live file replacement before acceptance: both disk readers see new bytes,
    # but Python still holds the original imported predictor in memory.
    monkeypatch.setattr("app.prediction_store.algorithm_fingerprint", lambda: "0" * 64)
    monkeypatch.setattr("app.algorithm_adapter.algorithm_fingerprint", lambda: "0" * 64)
    store.accept(body)
    finish(store)
    task = store.get_task(body["taskId"])
    assert task["status"] == "FAILED" and task["runId"] is None
    assert task["error"]["code"] == "MODEL_ARTIFACT_CHANGED"


def test_real_execution_exception_never_publishes_mock(tmp_path, monkeypatch):
    store, body = prepared(tmp_path, mode="MODEL", model_version="hotspot-simple-v1")
    body["modelVersion"] = "hotspot-simple-v1"
    store.accept(body)
    def broken(*args):
        raise RuntimeError("unexpected real algorithm failure")
    monkeypatch.setattr("app.prediction_store.predict_real", broken)
    finish(store)
    task = store.get_task(body["taskId"])
    assert task["status"] == "FAILED" and task["error"]["code"] == "EXECUTION_FAILED"
    assert not list(store.runs.glob("run_*"))


def test_legacy_mock_result_is_readable_but_conflicting_mode_is_rejected(tmp_path):
    from app.csv_import import ImportProblem
    store, body = prepared(tmp_path)
    store.accept(body)
    finish(store)
    run_id = store.get_task(body["taskId"])["runId"]
    path = store.runs / run_id / "result.json"
    result = json.loads(path.read_text())
    result.pop("resultMode", None)
    path.write_text(json.dumps(result))
    assert store.get_items(run_id, 1, 50)["resultMode"] == "MOCK"
    result["resultMode"] = "REAL"
    path.write_text(json.dumps(result))
    with pytest.raises(ImportProblem) as error:
        store.get_items(run_id, 1, 50)
    assert error.value.code == "INVALID_RESULT"


@pytest.mark.parametrize("metadata", [None, "invalid", {}, {"algorithmFingerprint": "invalid"}])
def test_corrupt_real_provenance_is_invalid_result_not_a_recovery_crash(tmp_path, metadata):
    from app.csv_import import ImportProblem
    store, body = prepared(tmp_path, mode="MODEL", model_version="hotspot-simple-v1")
    body["modelVersion"] = "hotspot-simple-v1"
    store.accept(body)
    finish(store)
    run_id = store.get_task(body["taskId"])["runId"]
    path = store.runs / run_id / "result.json"
    result = json.loads(path.read_text())
    result["modelInfo"] = metadata
    path.write_text(json.dumps(result))
    with pytest.raises(ImportProblem) as error:
        store.get_items(run_id, 1, 50)
    assert error.value.code == "INVALID_RESULT"
    task_path = store._task_path(body["taskId"]) / "task.json"
    task = json.loads(task_path.read_text())
    task.update(status="RUNNING", runId=None)
    task_path.write_text(json.dumps(task))
    store.initialize()
    assert store.get_task(body["taskId"])["error"]["code"] == "INVALID_RESULT"
