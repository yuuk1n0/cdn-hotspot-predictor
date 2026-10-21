import asyncio
import json
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.csv_import import ImportConfig, ImportProblem
from app.import_store import ImportStore
from app.prediction_store import PredictionStore
from test_csv_import import MAPPING, csv_bytes


def prepared(tmp_path, rows=None, source="REAL", **settings):
    imports = ImportStore(tmp_path)
    imports.initialize()
    rows = rows if rows is not None else [["a", "2026/1/5", "10000000000000001", "10350"], ["future", "2026/1/8", "100", "10350"]]
    receipt = imports.accept(csv_bytes(rows), ImportConfig(MAPPING, "FILE_DAILY", source, "B"), "fixture", "any.csv")
    imports.process_next()
    dataset = imports.get_import(receipt["importTaskId"])["datasetId"]
    assert dataset
    store = PredictionStore(tmp_path, imports, **settings)
    store.initialize()
    body = {"taskId": "task_java", "datasetId": dataset, "appId": "10350", "cutoffDate": "2026-01-07", "horizonDays": 7, "modelVersion": "contract-demo-v1"}
    return store, body


def finish(store):
    assert asyncio.run(store.process_next(asyncio.Event())) is True


def test_receipt_is_durable_idempotent_and_hashes_paths_without_changing_task_id(tmp_path):
    store, body = prepared(tmp_path)
    body["taskId"] = "task/java?opaque"
    accepted = store.accept(body)
    assert accepted["taskId"] == body["taskId"]
    assert accepted["status"] == "PENDING"
    assert accepted["runId"] is None
    assert len(list((tmp_path / "tasks").glob("*/task.json"))) == 1
    with ThreadPoolExecutor(max_workers=4) as pool:
        assert {r["taskId"] for r in pool.map(lambda _: store.accept(body), range(4))} == {body["taskId"]}
    with pytest.raises(ImportProblem) as error:
        store.accept({**body, "horizonDays": 1})
    assert error.value.status == 409
    assert error.value.code == "IDEMPOTENCY_CONFLICT"
    finish(store)
    task = store.get_task(body["taskId"])
    assert task["status"] == "SUCCEEDED"
    assert store.accept(body)["runId"] == task["runId"]
    assert len(list((tmp_path / "runs").glob("run_*"))) == 1
    result = store.get_items(task["runId"], 1, 50)
    assert result["items"][0]["predictedBytes"] == "70000000000000007"
    assert result["datasetId"] == body["datasetId"]
    assert result["datasetSource"] == "REAL"
    assert result["predictionSource"] == "MOCK"
    assert result["predictionStartDate"] == "2026-01-08"
    assert result["predictionEndDate"] == "2026-01-14"
    assert result["taskId"] == body["taskId"]


def test_all_candidates_are_persisted_and_paged_with_global_stable_ranks(tmp_path):
    rows = [[f"file-{i:03}", "2026/1/5", str(i), "10350"] for i in range(61)]
    store, body = prepared(tmp_path, rows)
    body["cutoffDate"] = "2026-01-05"
    store.accept(body)
    finish(store)
    run_id = store.get_task(body["taskId"])["runId"]
    first, second = store.get_items(run_id, 1, 50), store.get_items(run_id, 2, 50)
    assert first["total"] == 61
    assert len(first["items"]) == 50
    assert len(second["items"]) == 11
    assert first["items"][0]["fileKey"] == "file-060"
    assert second["items"][0]["rank"] == 51
    assert second["items"][-1]["rank"] == 61
    assert store.get_items(run_id, 3, 50)["items"] == []
    restarted = PredictionStore(tmp_path, store.imports)
    restarted.initialize()
    assert restarted.get_task(body["taskId"]) == store.get_task(body["taskId"])
    assert restarted.get_items(run_id, 2, 50) == second


@pytest.mark.parametrize("changes,settings,code", [
    ({"datasetId": "ds_" + "0" * 64}, {}, "DATASET_NOT_FOUND"),
    ({"appId": "10066"}, {}, "APP_MISMATCH"),
    ({"cutoffDate": "2026-01-04"}, {}, "INVALID_CUTOFF_DATE"),
    ({"modelVersion": "mock-unknown"}, {}, "MODEL_VERSION_UNSUPPORTED"),
    ({}, {"mode": "MODEL"}, "MODEL_VERSION_UNSUPPORTED"),
])
def test_semantic_errors_are_durable_failures_without_results(tmp_path, changes, settings, code):
    store, body = prepared(tmp_path, **settings)
    body.update(changes)
    assert store.accept(body)["status"] == "PENDING"
    finish(store)
    task = store.get_task(body["taskId"])
    assert task["status"] == "FAILED"
    assert task["runId"] is None
    assert task["error"]["code"] == code
    assert list((tmp_path / "runs").glob("run_*")) == []


def test_model_version_configuration_is_exact_not_a_mock_prefix_switch(tmp_path):
    store, body = prepared(tmp_path, model_version="any-name-v2")
    body["modelVersion"] = "any-name-v2"
    store.accept(body)
    finish(store)
    result = store.get_items(store.get_task(body["taskId"])["runId"], 1, 50)
    assert result["predictionSource"] == "MOCK"
    assert result["modelVersion"] == "any-name-v2"


def test_pending_restarts_and_execution_policy_is_immutable_per_receipt(tmp_path):
    store, body = prepared(tmp_path, source="FAKE")
    store.accept(body)
    restarted = PredictionStore(tmp_path, store.imports, mode="MODEL", model_version="changed")
    restarted.initialize()
    finish(restarted)
    task = restarted.get_task(body["taskId"])
    assert task["status"] == "SUCCEEDED"
    assert restarted.get_items(task["runId"], 1, 50)["datasetSource"] == "FAKE"


def test_unpublished_running_task_fails_on_restart_but_complete_run_repairs_success(tmp_path):
    store, body = prepared(tmp_path)
    store.accept(body)
    finish(store)
    completed = store.get_task(body["taskId"])
    path = next((tmp_path / "tasks").glob("*/task.json"))
    record = json.loads(path.read_text())
    record.update(status="RUNNING", runId=None)
    path.write_text(json.dumps(record))
    restarted = PredictionStore(tmp_path, store.imports)
    restarted.initialize()
    assert restarted.get_task(body["taskId"])["runId"] == completed["runId"]
    store.accept({**body, "taskId": "interrupted"})
    other = next(p for p in (tmp_path / "tasks").glob("*/task.json") if json.loads(p.read_text())["taskId"] == "interrupted")
    record = json.loads(other.read_text())
    record.update(status="RUNNING")
    other.write_text(json.dumps(record))
    # A staging file is not a complete, published run.
    staging = tmp_path / "runs" / ".unfinished"
    staging.mkdir()
    (staging / "result.json").write_text('{"items":[')
    restarted.initialize()
    failed = restarted.get_task("interrupted")
    assert failed["status"] == "FAILED"
    assert failed["error"]["code"] == "EXECUTION_INTERRUPTED"
    assert failed["runId"] is None


def test_result_write_failure_does_not_publish_partial_results_or_erase_previous_run(tmp_path, monkeypatch):
    store, body = prepared(tmp_path)
    store.accept(body)
    finish(store)
    first_id = store.get_task(body["taskId"])["runId"]
    first = store.get_items(first_id, 1, 50)
    store.accept({**body, "taskId": "failed-next"})
    def fail_write(*args, **kwargs):
        raise OSError("simulated unavailable result storage")
    monkeypatch.setattr("app.prediction_store.write_bytes", fail_write)
    finish(store)
    assert store.get_task("failed-next")["status"] == "FAILED"
    assert store.get_task("failed-next")["runId"] is None
    assert store.get_items(first_id, 1, 50) == first


def test_delay_is_asynchronous_shutdown_is_prompt_and_timeout_is_terminal(tmp_path):
    store, body = prepared(tmp_path, delay_seconds=100)
    store.accept(body)
    async def interrupt():
        stop = asyncio.Event()
        working = asyncio.create_task(store.process_next(stop))
        for _ in range(200):
            if store.get_task(body["taskId"])["stage"] == "COMPUTING":
                break
            await asyncio.sleep(0.005)
        assert store.get_task(body["taskId"])["status"] == "RUNNING"
        assert store.get_task(body["taskId"])["runId"] is None
        stop.set()
        await asyncio.wait_for(working, timeout=1)
    asyncio.run(interrupt())
    store.initialize()
    assert store.get_task(body["taskId"])["error"]["code"] == "EXECUTION_INTERRUPTED"
    timed, timed_body = prepared(tmp_path / "timed", delay_seconds=100, timeout_seconds=0.03)
    timed.accept(timed_body)
    finish(timed)
    assert timed.get_task(timed_body["taskId"])["error"]["code"] == "TASK_TIMEOUT"


def test_unknown_and_traversal_identifiers_return_not_found(tmp_path):
    store, _ = prepared(tmp_path)
    for identifier in ("no-such-task", "../datasets", "run_../outside"):
        with pytest.raises(ImportProblem) as error:
            store.get_task(identifier)
        assert error.value.status == 404
        with pytest.raises(ImportProblem) as error:
            store.get_items(identifier, 1, 50)
        assert error.value.status == 404


@pytest.mark.parametrize("damage", ["invalid-json", "non-object", "missing-source", "wrong-source", "wrong-count", "bad-item"])
def test_corrupt_published_result_fails_only_its_task_and_recovery_continues(tmp_path, damage):
    store, body = prepared(tmp_path)
    store.accept(body)
    finish(store)
    run_id = store.get_task(body["taskId"])["runId"]
    result_path = tmp_path / "runs" / run_id / "result.json"
    result = json.loads(result_path.read_text())
    if damage == "missing-source":
        result.pop("datasetSource")
    if damage == "wrong-source":
        result["predictionSource"] = "REAL_ALGORITHM_MAGIC"
    if damage == "wrong-count":
        result["total"] = 99
    if damage == "bad-item":
        result["items"][0]["predictedBytes"] = -1
    result_path.write_text("{" if damage == "invalid-json" else "[]" if damage == "non-object" else json.dumps(result))
    record_path = next((tmp_path / "tasks").glob("*/task.json"))
    record = json.loads(record_path.read_text())
    record.update(status="RUNNING", runId=None)
    record_path.write_text(json.dumps(record))
    store.accept({**body, "taskId": "healthy-next"})
    store.initialize()
    failed = store.get_task(body["taskId"])
    assert failed["status"] == "FAILED"
    assert failed["error"]["code"] == "INVALID_RESULT"
    assert failed["runId"] is None
    finish(store)
    assert store.get_task("healthy-next")["status"] == "SUCCEEDED"
