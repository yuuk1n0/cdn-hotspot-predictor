"""Real HTTP/process check; uses only Python, an isolated directory and a free port."""

import os
import socket
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

import httpx

from test_import_api import terminal, upload


@contextmanager
def serve(data_dir, port, log_path):
    env = {**os.environ, "PREDICTOR_DATA_DIR": str(data_dir)}
    with log_path.open("ab") as log:
        process = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port)],
            cwd=Path(__file__).resolve().parents[1], env=env, stdout=log, stderr=log,
        )
        try:
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", trust_env=False, timeout=3) as client:
                deadline = time.monotonic() + 10
                while True:
                    if process.poll() is not None:
                        raise AssertionError(log_path.read_text())
                    try:
                        if client.get("/api/v1/health").status_code == 200:
                            break
                    except httpx.TransportError:
                        pass
                    if time.monotonic() > deadline:
                        raise AssertionError("uvicorn startup timed out")
                    time.sleep(0.05)
                yield client
        finally:
            process.terminate()
            process.wait(timeout=10)


def test_real_http_original_bytes_and_query_after_python_process_restart(tmp_path):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    raw = (Path(__file__).resolve().parents[2] / "datasets" / "10350.csv").read_bytes()
    data_dir = tmp_path / "durable"
    with serve(data_dir, port, tmp_path / "first.log") as client:
        response = upload(client, raw, key="real-http-10350")
        assert response.status_code == 202
        task_id = response.json()["importTaskId"]
        task = terminal(client, task_id)
        assert task["status"] == "SUCCEEDED"
        dataset_id = task["datasetId"]
        quality = client.get(f"/api/v1/datasets/{dataset_id}/quality").json()
        assert quality["validRows"] == 4478
        assert (data_dir / "datasets" / dataset_id / "original.csv").read_bytes() == raw
    with serve(data_dir, port, tmp_path / "restarted.log") as client:
        assert client.get(f"/api/v1/imports/{task_id}").json() == task
        assert client.get(f"/api/v1/datasets/{dataset_id}").json()["status"] == "READY"
        assert client.get(f"/api/v1/datasets/{dataset_id}/quality").json() == quality
        assert upload(client, raw, key="real-http-10350").json()["importTaskId"] == task_id
        assert client.get("/api/v1/datasets").json()["total"] == 1
