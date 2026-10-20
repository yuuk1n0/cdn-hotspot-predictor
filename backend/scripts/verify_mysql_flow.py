"""Manual step-3 verification against the explicitly approved local MySQL database.

Does not apply migrations or clean data. Starts existing services on free ports,
retains the real imported dataset and three clearly identified verification tasks.
Uses the existing cdn-predictor environment's httpx; installs nothing.
"""

import hashlib
import json
import os
import socket
import subprocess
import time
import uuid
from pathlib import Path

import httpx


ROOT = Path(__file__).resolve().parents[2]
RUN = "step3mysql_20261020_" + uuid.uuid4().hex[:8]
LOGS = ROOT / "runtime" / RUN
LOGS.mkdir(parents=True, exist_ok=False)
ENV = {**os.environ, "MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": "13306", "MYSQL_DATABASE": "cdn_hotspot",
       "MYSQL_USER": "cdn", "MYSQL_PASSWORD": os.getenv("MYSQL_PASSWORD", "cdn_dev")}
MAPPING = {"fileKey": "文件标识", "date": "日期", "trafficBytes": "当日流量（B）", "appId": "AppID"}


def sql(statement):
    result = subprocess.run([
        "mysql", "--protocol=TCP", "--host=127.0.0.1", "--port=13306", "--user=cdn", "--database=cdn_hotspot",
        "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names", "--execute", statement,
    ], env={**ENV, "MYSQL_PWD": ENV["MYSQL_PASSWORD"]}, capture_output=True, check=True)
    return result.stdout


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_for(callback, predicate, seconds=15):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = callback()
        except httpx.TransportError:
            time.sleep(0.1)
            continue
        if predicate(last):
            return last
        time.sleep(0.1)
    raise AssertionError(f"condition timed out; last={last}")


def start(command, cwd, env, log_name, client):
    with (LOGS / log_name).open("wb") as log:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=log)
    try:
        def health():
            if process.poll() is not None:
                raise AssertionError(f"service exited; inspect {LOGS / log_name}")
            return client.get("/api/v1/health").status_code
        wait_for(health, lambda value: value == 200, 20)
        return process
    except BaseException:
        stop(process)
        raise


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def main():
    before_fake = hashlib.sha256(sql("SELECT * FROM top50_prediction ORDER BY id")).hexdigest()
    assert sql("SELECT COUNT(*) FROM top50_prediction").strip() == b"32"
    python_port, java_port = free_port(), free_port()
    py = httpx.Client(base_url=f"http://127.0.0.1:{python_port}", trust_env=False, timeout=5)
    java = httpx.Client(base_url=f"http://127.0.0.1:{java_port}", trust_env=False, timeout=5)
    py_env = {**os.environ, "PREDICTOR_DATA_DIR": str(ROOT / "runtime" / "predictor")}
    python_command = [str(Path(os.sys.executable)), "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(python_port)]
    java_command = ["java", "-jar", str(ROOT / "backend" / "target" / "cdn-hotspot-backend-0.1.0-SNAPSHOT.jar"),
                    f"--server.port={java_port}", f"--app.prediction.python-base-url=http://127.0.0.1:{python_port}",
                    "--app.prediction.task-timeout=20s", "--app.prediction.poll-interval=500ms",
                    "--app.prediction.max-retry-delay=1s", "--app.prediction.worker-tick-ms=100"]
    python_process = java_process = None
    try:
        python_process = start(python_command, ROOT / "predictor", py_env, "python.log", py)
        raw = (ROOT / "datasets" / "10350.csv").read_bytes()
        accepted_import = py.post("/api/v1/imports", headers={"Idempotency-Key": "step3-mysql-real-10350-v1"},
                                  files={"file": ("10350.csv", raw, "text/csv")}, data={
                                      "mapping": json.dumps(MAPPING), "datasetType": "FILE_DAILY", "datasetSource": "REAL", "sourceUnit": "B"})
        assert accepted_import.status_code == 202, accepted_import.text
        import_id = accepted_import.json()["importTaskId"]
        imported = wait_for(lambda: py.get(f"/api/v1/imports/{import_id}").json(), lambda t: t["status"] in {"SUCCEEDED", "FAILED"})
        assert imported["status"] == "SUCCEEDED", imported
        dataset_id = imported["datasetId"]
        assert py.get(f"/api/v1/datasets/{dataset_id}/quality").json()["validRows"] == 4478
        java_process = start(java_command, ROOT / "backend", ENV, "java-first.log", java)
        request = {"datasetId": dataset_id, "appId": "10350", "cutoffDate": "2026-10-06", "horizonDays": 7, "modelVersion": "contract-demo-v1"}
        key = RUN + "_main"
        started = time.monotonic()
        accepted = java.post("/api/v1/predictions", json=request, headers={"Idempotency-Key": key})
        submit_seconds = time.monotonic() - started
        assert accepted.status_code == 202, accepted.text
        assert submit_seconds < 2, submit_seconds
        task_id = accepted.json()["taskId"]

        def task():
            result = java.get(f"/api/v1/tasks/{task_id}")
            assert result.status_code == 200, result.text
            return result.json()

        def dependency(code):
            return lambda t: (t.get("lastDependencyError") or {}).get("code") == code

        waiting = wait_for(task, dependency("PYTHON_API_UNAVAILABLE"))
        assert waiting["status"] == "RUNNING" and waiting["error"] is None and waiting["runId"] is None
        assert waiting["datasetSource"] == "REAL"
        assert java.post("/api/v1/predictions", json=request, headers={"Idempotency-Key": key}).json()["taskId"] == task_id
        conflict = java.post("/api/v1/predictions", json={**request, "horizonDays": 1}, headers={"Idempotency-Key": key})
        assert conflict.status_code == 409
        failures = {}
        for label, changes, expected in [
            ("wrong_app", {"appId": "10066"}, "APP_MISMATCH"),
            ("missing_dataset", {"datasetId": "ds_missing_" + RUN}, "DATASET_NOT_FOUND"),
        ]:
            response = java.post("/api/v1/predictions", json={**request, **changes}, headers={"Idempotency-Key": RUN + "_" + label})
            assert response.status_code == 202, response.text
            failed_id = response.json()["taskId"]
            failed = wait_for(lambda: java.get(f"/api/v1/tasks/{failed_id}").json(), lambda t: t["status"] == "FAILED")
            assert failed["error"]["code"] == expected, failed
            failures[label] = {"taskId": failed_id, "code": expected}
        assert java.post("/api/v1/predictions", json={**request, "horizonDays": 2}, headers={"Idempotency-Key": RUN + "_invalid"}).status_code == 422

        stop(python_process)
        python_process = None
        disconnected = wait_for(task, dependency("PYTHON_UNAVAILABLE"), 5)
        assert disconnected["status"] == "RUNNING" and disconnected["error"] is None
        python_process = start(python_command, ROOT / "predictor", py_env, "python-restarted.log", py)
        assert py.get(f"/api/v1/datasets/{dataset_id}").json()["status"] == "READY"
        recovered = wait_for(task, dependency("PYTHON_API_UNAVAILABLE"), 5)
        deadline = recovered["deadlineAt"]
        attempts = recovered["attemptCount"]
        stop(java_process)
        java_process = None
        java_process = start(java_command, ROOT / "backend", ENV, "java-restarted.log", java)
        resumed = wait_for(task, lambda t: t["attemptCount"] > attempts and t["status"] == "RUNNING", 7)
        assert resumed["deadlineAt"] == deadline
        assert java.post("/api/v1/predictions", json=request, headers={"Idempotency-Key": key}).json()["taskId"] == task_id
        finished = wait_for(task, lambda t: t["status"] == "FAILED", 22)
        assert finished["error"]["code"] == "TASK_TIMEOUT", finished
        assert finished["runId"] is None
        stop(java_process)
        java_process = None
        java_process = start(java_command, ROOT / "backend", ENV, "java-terminal-restarted.log", java)
        assert task() == finished
        assert java.post("/api/v1/predictions", json=request, headers={"Idempotency-Key": key}).json()["taskId"] == task_id
        fake = java.get("/api/v1/top50", params={"page": 1, "pageSize": 50})
        assert fake.status_code == 200 and fake.json()["dataSource"] == "FAKE" and fake.json()["total"] == 32
        after_fake = hashlib.sha256(sql("SELECT * FROM top50_prediction ORDER BY id")).hexdigest()
        assert after_fake == before_fake
        database_row = sql(f"SELECT task_id,status,error_code,deadline_at_ms,attempt_count FROM prediction_task WHERE task_id='{task_id}'").decode().strip()
        print(json.dumps({
            "run": RUN, "database": "127.0.0.1:13306/cdn_hotspot", "importTaskId": import_id,
            "datasetId": dataset_id, "datasetSource": "REAL", "validRows": 4478,
            "taskId": task_id, "finalStatus": finished["status"], "finalError": finished["error"]["code"],
            "pythonMissingPredictionApi": "PYTHON_API_UNAVAILABLE", "submitSeconds": round(submit_seconds, 4),
            "idempotency": "PASS", "conflictHttpStatus": conflict.status_code, "semanticFailures": failures,
            "pythonUnavailability": "RETRY_WITHOUT_FALSE_ALGORITHM_FAILURE", "pythonRestart": "READY_DATASET_PRESERVED",
            "javaRestart": "TASK_AND_DEADLINE_PRESERVED", "javaTerminalRestart": "TERMINAL_TASK_PRESERVED",
            "testTaskTimeout": "20s", "defaultTaskTimeoutChanged": False, "fakeRows": 32,
            "fakeRowsSha256Before": before_fake, "fakeRowsSha256After": after_fake,
            "databaseRow": database_row, "logs": str(LOGS), "runId": None,
        }, ensure_ascii=False, indent=2), flush=True)
    finally:
        stop(java_process)
        stop(python_process)
        java.close()
        py.close()


if __name__ == "__main__":
    main()
