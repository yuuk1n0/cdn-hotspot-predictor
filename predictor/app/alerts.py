"""Best-effort import failure notification into the Java alert ingress."""

import json
import logging
import os
import urllib.error
import urllib.request


logger = logging.getLogger(__name__)


class JavaAlertSink:
    def __init__(self, url: str | None, token: str = "", destination_id: str = ""):
        self.url = (url or "").strip()
        self.token = (token or "").strip()
        self.destination_id = (destination_id or "").strip()

    @classmethod
    def from_env(cls):
        return cls(os.getenv("ALERT_EVENTS_URL"), os.getenv("ALERT_INGEST_TOKEN", ""),
                   os.getenv("ALERT_DESTINATION_ID", ""))

    @property
    def enabled(self):
        return bool(self.url)

    def import_failed(self, import_task_id: str, app_id: str | None, code: str, message: str):
        if not self.enabled:
            return
        body = {
            "eventType": "IMPORT_FAILED",
            "appId": app_id or "SYSTEM",
            "taskId": import_task_id,
            "summary": f"CSV 导入失败：{code}",
            "details": {"errorCode": code, "message": message},
            "destinationId": self.destination_id,
        }
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self.url,
            data=payload,
            method="POST",
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Idempotency-Key": f"import:{import_task_id}",
                **({"X-Alert-Token": self.token} if self.token else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                if response.status not in {200, 202}:
                    logger.warning("alert ingress returned status=%s", response.status)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            logger.warning("alert ingress unavailable: %s", error.__class__.__name__)
