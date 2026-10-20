"""Manual step-5 acceptance on the explicitly approved 127.0.0.1:13306/cdn_hotspot.

Requires migration 004 already applied; never migrates, deletes or seeds data.
Starts the existing Java JAR and Python service on private test ports. Retains two
successful snapshots, two failed tasks and isolated Python data for later audit.
"""

import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import uuid
from decimal import Decimal, localcontext
from pathlib import Path

import httpx


ROOT = Path(__file__).resolve().parents[2]
MAPPING = {"fileKey": "文件标识", "date": "日期", "trafficBytes": "当日流量（B）", "appId": "AppID"}
ENV = {**os.environ, "MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": "13306", "MYSQL_DATABASE": "cdn_hotspot",
       "MYSQL_USER": "cdn", "MYSQL_PASSWORD": os.getenv("MYSQL_PASSWORD", "cdn_dev")}


def sql(statement):
    result = subprocess.run([
        "mysql", "--protocol=TCP", "--host=127.0.0.1", "--port=13306", "--user=cdn", "--database=cdn_hotspot",
        "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names", "--execute", statement,
    ], env={**ENV, "MYSQL_PWD": ENV["MYSQL_PASSWORD"]}, capture_output=True, check=True)
    return result.stdout


def quoted_id(value):
    assert isinstance(value, str) and re.fullmatch(r"(?:task_[0-9a-f-]{36}|run_[0-9a-f]{64})", value), "unexpected identifier"
    return "'" + value + "'"


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_for(callback, predicate, seconds=20, interval=0.2):
    deadline, last = time.monotonic() + seconds, None
    while time.monotonic() < deadline:
        try:
            last = callback()
        except httpx.TransportError:
            time.sleep(interval)
            continue
        if predicate(last):
            return last
        time.sleep(interval)
    raise AssertionError(f"condition timed out; last={last}")


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def start(command, cwd, env, log_path, client):
    with log_path.open("ab") as log:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=log)
    try:
        def health():
            assert process.poll() is None, f"service exited; inspect {log_path}"
            return client.get("/api/v1/health").status_code
        wait_for(health, lambda status: status == 200, seconds=25)
        return process
    except BaseException:
        stop(process)
        raise


def get(client, path):
    response = client.get(path)
    assert response.status_code == 200, response.text
    return response.json()


def submit(java, body, key):
    response = java.post("/api/v1/predictions", json=body, headers={"Idempotency-Key": key})
    assert response.status_code == 202, response.text
    return response.json()


def terminal(client, path, seconds=25):
    return wait_for(lambda: get(client, path), lambda task: task["status"] in {"SUCCEEDED", "FAILED"}, seconds, 0.5)


def snapshot(java, python, task, body):
    assert task["status"] == "SUCCEEDED" and task["error"] is None, task
    result = get(java, f"/api/v1/predictions/{task['runId']}")
    page = get(python, f"/api/v1/predictions/{task['runId']}/items?page=1&pageSize=50")
    assert all(result[key] == value for key, value in body.items())
    assert result["taskId"] == task["taskId"] and result["runId"] == task["runId"]
    assert result["datasetSource"] == "REAL" and result["predictionSource"] == "MOCK"
    assert result["candidateCount"] == 930 and result["top50Count"] == len(result["items"]) == 50
    assert result["items"] == page["items"]
    with localcontext() as context:
        context.prec = 512
        assert Decimal(result["top50PredictedBytes"]) == sum(Decimal(item["predictedBytes"]) for item in page["items"])
    assert result["top50PredictedBytes"] == "32741011525971"
    assert result["predictionStartDate"] == "2026-10-07" and result["predictionEndDate"] == "2026-10-13"
    assert result["hotspotTrafficRatio"]["value"] is None and result["hotspotTrafficRatio"]["denominatorBytes"] is None
    assert all(item[key] is None for item in result["items"] for key in ("fileType", "fileSizeBytes", "recentActualBytes", "compressionStatus"))
    saved = json.loads(sql(f"SELECT snapshot_json FROM prediction_run WHERE run_id={quoted_id(task['runId'])}"))
    assert saved == result
    return result


def main():
    assert sql("SELECT @@port,DATABASE()").strip() == b"13306\tcdn_hotspot"
    assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='prediction_run'").strip() == b"1"
    before_fake = hashlib.sha256(sql("SELECT * FROM top50_prediction ORDER BY id")).hexdigest()
    assert sql("SELECT COUNT(*) FROM top50_prediction").strip() == b"32"
    old_ids = sql("SELECT task_id FROM prediction_task ORDER BY task_id").decode().splitlines()
    old_filter = "task_id IN (" + ",".join(quoted_id(identifier) for identifier in old_ids) + ")" if old_ids else "1=0"
    old_rows = sql(f"SELECT * FROM prediction_task WHERE {old_filter} ORDER BY task_id")
    before_tasks = int(sql("SELECT COUNT(*) FROM prediction_task"))
    before_runs = int(sql("SELECT COUNT(*) FROM prediction_run"))
    batch = "step5mysql_20261020_" + uuid.uuid4().hex[:8]
    logs = ROOT / "runtime" / batch
    logs.mkdir(parents=True, exist_ok=False)
    python_port, java_port, unavailable_port = free_port(), free_port(), free_port()
    python_url = f"http://127.0.0.1:{python_port}"
    py_env = {**os.environ, "PREDICTOR_DATA_DIR": str(logs / "python-data"), "PREDICTION_MODE": "MOCK",
              "PREDICTION_MODEL_VERSION": "contract-demo-v1", "PREDICTION_TASK_TIMEOUT_SECONDS": "180", "MOCK_DELAY_SECONDS": "0"}
    py_command = [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(python_port)]

    def java_command(url=python_url):
        return ["java", "-jar", str(ROOT / "backend/target/cdn-hotspot-backend-0.1.0-SNAPSHOT.jar"),
                f"--server.port={java_port}", f"--app.prediction.python-base-url={url}"]

    python_process = java_process = None
    with httpx.Client(base_url=python_url, trust_env=False, timeout=5) as python, \
            httpx.Client(base_url=f"http://127.0.0.1:{java_port}", trust_env=False, timeout=5) as java:
        try:
            python_process = start(py_command, ROOT / "predictor", py_env, logs / "python-short.log", python)
            response = python.post("/api/v1/imports", headers={"Idempotency-Key": batch + "_upload"},
                                   files={"file": ("10350.csv", (ROOT / "datasets/10350.csv").read_bytes(), "text/csv")},
                                   data={"mapping": json.dumps(MAPPING), "datasetType": "FILE_DAILY", "datasetSource": "REAL", "sourceUnit": "B"})
            assert response.status_code == 202, response.text
            import_id = response.json()["importTaskId"]
            imported = terminal(python, f"/api/v1/imports/{import_id}")
            assert imported["status"] == "SUCCEEDED", imported
            dataset_id = imported["datasetId"]
            assert get(python, f"/api/v1/datasets/{dataset_id}/quality")["validRows"] == 4478
            java_process = start(java_command(), ROOT / "backend", ENV, logs / "java-short.log", java)
            fake_before = get(java, "/api/v1/top50?page=1&pageSize=50")
            assert fake_before["dataSource"] == "FAKE" and fake_before["total"] == 32
            body = {"datasetId": dataset_id, "appId": "10350", "cutoffDate": "2026-10-06", "horizonDays": 7, "modelVersion": "contract-demo-v1"}
            started = time.monotonic()
            short_id = submit(java, body, batch + "_short")["taskId"]
            short_submit = time.monotonic() - started
            assert short_submit < 2
            short_task = terminal(java, f"/api/v1/tasks/{short_id}")
            short_result = snapshot(java, python, short_task, body)
            assert submit(java, body, batch + "_short")["taskId"] == short_id
            conflict = java.post("/api/v1/predictions", json={**body, "horizonDays": 1}, headers={"Idempotency-Key": batch + "_short"})
            assert conflict.status_code == 409
            failures = {}
            for label, change, code in [("wrong_app", {"appId": "10066"}, "APP_MISMATCH"),
                                        ("missing_dataset", {"datasetId": "ds_missing"}, "DATASET_NOT_FOUND")]:
                failed_id = submit(java, {**body, **change}, batch + "_" + label)["taskId"]
                failed = terminal(java, f"/api/v1/tasks/{failed_id}")
                assert failed["status"] == "FAILED" and failed["runId"] is None and failed["error"]["code"] == code, failed
                failures[label] = {"taskId": failed_id, "code": code}
            assert java.post("/api/v1/predictions", json={**body, "horizonDays": 2}, headers={"Idempotency-Key": batch + "_invalid"}).status_code == 422
            print("short Mock, MySQL snapshot, idempotency and semantic failures: PASS", flush=True)

            stop(python_process); python_process = None
            py_env["MOCK_DELAY_SECONDS"] = "100"
            python_process = start(py_command, ROOT / "predictor", py_env, logs / "python-100s.log", python)
            assert get(python, f"/api/v1/datasets/{dataset_id}")["status"] == "READY"
            started = time.monotonic()
            long_id = submit(java, body, batch + "_long")["taskId"]
            long_submit = time.monotonic() - started
            assert long_submit < 2
            task_path = f"/api/v1/tasks/{long_id}"
            running = wait_for(lambda: get(java, task_path), lambda task: task["stage"] == "COMPUTING")
            assert running["status"] == "RUNNING" and running["runId"] is None and running["error"] is None
            assert get(python, f"/api/v1/tasks/{long_id}")["status"] == "RUNNING"
            deadline = running["deadlineAt"]
            stop(java_process); java_process = None
            # Real connection refusal without interrupting the Python computation; process-local configuration only.
            java_process = start(java_command(f"http://127.0.0.1:{unavailable_port}"), ROOT / "backend", ENV, logs / "java-disconnected.log", java)
            waiting = wait_for(lambda: get(java, task_path), lambda task: (task.get("lastDependencyError") or {}).get("code") == "PYTHON_UNAVAILABLE")
            assert waiting["status"] == "RUNNING" and waiting["error"] is None and waiting["runId"] is None
            assert waiting["deadlineAt"] == deadline
            stop(java_process); java_process = None
            java_process = start(java_command(), ROOT / "backend", ENV, logs / "java-resumed.log", java)
            assert get(java, task_path)["deadlineAt"] == deadline
            assert submit(java, body, batch + "_long")["taskId"] == long_id
            long_task = terminal(java, task_path, seconds=130)
            long_elapsed = time.monotonic() - started
            assert 99.5 <= long_elapsed < 160
            long_result = snapshot(java, python, long_task, body)
            assert long_result["items"] == short_result["items"]
            assert long_task["deadlineAt"] == deadline
            print("actual 100-second Mock, transient connection refusal and Java restart recovery: PASS", flush=True)

            stop(python_process); python_process = None
            stop(java_process); java_process = None
            java_process = start(java_command(), ROOT / "backend", ENV, logs / "java-offline-snapshot.log", java)
            assert get(java, task_path) == long_task
            assert get(java, f"/api/v1/predictions/{long_task['runId']}") == long_result
            assert get(java, f"/api/v1/predictions/{short_task['runId']}") == short_result
            assert submit(java, body, batch + "_long")["taskId"] == long_id
            assert get(java, "/api/v1/top50?page=1&pageSize=50") == fake_before
            after_fake = hashlib.sha256(sql("SELECT * FROM top50_prediction ORDER BY id")).hexdigest()
            assert after_fake == before_fake
            assert sql(f"SELECT * FROM prediction_task WHERE {old_filter} ORDER BY task_id") == old_rows
            assert int(sql("SELECT COUNT(*) FROM prediction_task")) == before_tasks + 4
            assert int(sql("SELECT COUNT(*) FROM prediction_run")) == before_runs + 2
            for task in (short_task, long_task):
                assert sql(f"SELECT COUNT(*) FROM prediction_run WHERE task_id={quoted_id(task['taskId'])}").strip() == b"1"
                assert sql(f"SELECT status FROM prediction_task WHERE task_id={quoted_id(task['taskId'])}").strip() == b"SUCCEEDED"
            evidence = {"batch": batch, "database": "127.0.0.1:13306/cdn_hotspot", "importTaskId": import_id,
                        "datasetId": dataset_id, "datasetSource": "REAL", "predictionSource": "MOCK", "validRows": 4478,
                        "candidateCount": 930, "top50Count": 50, "top50PredictedBytes": long_result["top50PredictedBytes"],
                        "shortTaskId": short_id, "shortRunId": short_task["runId"], "longTaskId": long_id, "longRunId": long_task["runId"],
                        "shortSubmitSeconds": round(short_submit, 6), "longSubmitSeconds": round(long_submit, 6), "longElapsedSeconds": round(long_elapsed, 3),
                        "conflictHttpStatus": conflict.status_code, "semanticFailures": failures, "newTasks": 4, "newSnapshots": 2,
                        "pythonUnavailability": "RUNNING_WITH_RETRY_NOT_ALGORITHM_FAILURE", "javaRestart": "TASK_AND_DEADLINE_PRESERVED",
                        "offlineSnapshot": "PERSISTENT_AFTER_JAVA_RESTART_WITH_PYTHON_OFFLINE", "fakeRows": 32,
                        "fakeRowsSha256Before": before_fake, "fakeRowsSha256After": after_fake, "oldTasksUnchanged": True,
                        "taskTimeoutDefault": "180s", "pythonDataDir": py_env["PREDICTOR_DATA_DIR"], "logs": str(logs)}
            (logs / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(evidence, ensure_ascii=False, indent=2), flush=True)
        finally:
            stop(java_process)
            stop(python_process)


if __name__ == "__main__":
    main()
