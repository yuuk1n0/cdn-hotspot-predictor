"""Read-only evidence for the active execution mode; no evaluation runs here."""

import json
import math
import re
from datetime import date, datetime, timedelta, timezone

from hotspot.core.v1.config import has_reviewed_config

from .algorithm_adapter import (
    CONFIG_ROOT, LOADED_ALGORITHM_FINGERPRINT, MODEL_VERSION, algorithm_fingerprint,
)
from .csv_import import ImportProblem


METRIC_DEFINITIONS = {
    "recallAt50": "Overlap with the top 50 observed future files, divided by their count (up to 50).",
    "ndcgAt50": "NDCG with log1p(observed future bytes) gain; not linear-gain formal NDCG.",
    "observedTrafficCoverageAt50": "Observed future bytes of predicted top 50 / all observed future bytes; completeness unconfirmed.",
    "candidateCoverageAt50": "Observed future top-50 files appearing anywhere in the candidate set / observed top-50 count.",
}


def _unchanged():
    if algorithm_fingerprint() != LOADED_ALGORITHM_FINGERPRINT:
        raise ImportProblem("MODEL_ARTIFACT_CHANGED", "算法或参数已变化；请重启服务后重新查询部署证据。", 503)


def _invalid_config():
    return ImportProblem("MODEL_CONFIG_INVALID", "已批准模型配置的身份、日期或指标无效，未返回评估成绩。", 503)


def model_evidence(execution, app_id, horizon_days):
    """Never derive model availability from source/config presence alone."""
    if not isinstance(app_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", app_id) \
            or horizon_days not in (1, 7):
        raise ImportProblem("INVALID_REQUEST", "App 标识不合法；horizonDays 仅支持 1 或 7。", 422)
    mode, version = execution["mode"], execution["modelVersion"]
    queried = datetime.now(timezone.utc)
    body = {
        "appId": app_id, "horizonDays": horizon_days,
        "queriedAt": queried.isoformat().replace("+00:00", "Z"),
        "deployedMode": mode, "deployedModelVersion": version, "availableModels": [],
        "metricSource": "NONE", "metrics": None, "proxyMetrics": None,
        "metricBasis": None, "metricDefinitions": METRIC_DEFINITIONS,
        "labelBasis": "UNCONFIRMED", "formalSelectionEligible": False,
        "reasonCodes": ["UNCONFIRMED_LABELS", "NO_DAILY_BACKTEST"],
        "selectedMethod": None, "selectedSpec": None,
        "evidenceStartDate": None, "evidenceEndDate": None, "report": None,
    }
    reasons = body["reasonCodes"]
    if mode == "MOCK":
        body["availableModels"] = [{"modelVersion": version, "predictionSource": "MOCK"}]
        reasons.append("MOCK_EVIDENCE_ONLY")
        return body
    if mode != "MODEL" or version != MODEL_VERSION:
        reasons.append("MODEL_VERSION_UNSUPPORTED")
        return body
    _unchanged()
    body["availableModels"] = [{"modelVersion": version, "predictionSource": "MODEL"}]
    reasons.append("SINGLE_DEPLOYED_MODEL")
    if not has_reviewed_config(app_id, horizon_days):
        reasons.append("NO_REVIEWED_CONFIG")
        return body
    # Only the immutable reviewed identifier set reaches this path construction.
    path = CONFIG_ROOT / f"{app_id}-h{horizon_days}.json"
    if path.resolve().parent != CONFIG_ROOT.resolve():
        raise _invalid_config()
    try:
        artifact = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        reasons.append("MODEL_CONFIG_MISSING")
        return body
    except (ValueError, UnicodeError):
        raise _invalid_config() from None
    # Reject a replacement between the fingerprint check and the report read.
    _unchanged()
    if not isinstance(artifact, dict) or artifact.get("appId") != app_id \
            or type(artifact.get("horizonDays")) is not int or artifact["horizonDays"] != horizon_days \
            or artifact.get("modelVersion") != version:
        raise _invalid_config()
    method, spec = artifact.get("selectedMethod"), artifact.get("selectedSpec")
    if not isinstance(method, str) or not method.strip() or not isinstance(spec, dict):
        raise _invalid_config()
    body.update(selectedMethod=method, selectedSpec=spec)
    metrics = artifact.get("testMean")
    if metrics is not None and (not isinstance(metrics, dict) or set(metrics) != set(METRIC_DEFINITIONS)
            or any(type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1
                   for value in metrics.values())):
        raise _invalid_config()
    start, end = artifact.get("testStartDate"), artifact.get("testEndDate")
    if start is None and end is None and metrics is None:
        reasons.append("NO_EVALUATION_REPORT")
        return body
    try:
        start_date, end_date = date.fromisoformat(start), date.fromisoformat(end)
        count = artifact["testCutoffCount"]
        observation_start, observation_end = start_date + timedelta(days=1), end_date + timedelta(days=horizon_days)
        if start_date > end_date or type(count) is not int or count <= 0 \
                or count > (end_date - start_date).days + 1:
            raise ValueError
    except (KeyError, ValueError, TypeError, OverflowError):
        raise _invalid_config() from None
    body.update(
        evidenceStartDate=start_date.isoformat(), evidenceEndDate=end_date.isoformat(),
        report={
            "source": "DEPLOYED_CONFIG_TEST_REPORT", "freshness": "HISTORICAL_REPORT",
            "generatedByThisRequest": False, "testCutoffCount": count,
            "cutoffStartDate": start_date.isoformat(), "cutoffEndDate": end_date.isoformat(),
            "observationStartDate": observation_start.isoformat(), "observationEndDate": observation_end.isoformat(),
            "ageDays": max(0, (queried.date() - observation_end).days),
        },
    )
    if metrics is None:
        reasons.append("NO_EVALUATION_REPORT")
    else:
        body.update(metricSource="DEPLOYED_CONFIG_TEST_REPORT", proxyMetrics=metrics,
                    metricBasis="OBSERVED_FUTURE_PROXY_LOG1P")
        reasons.append("HISTORICAL_PROXY_REPORT_ONLY")
    return body
