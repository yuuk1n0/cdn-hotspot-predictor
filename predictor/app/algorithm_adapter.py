"""Adapt immutable FILE_DAILY observations to the approved real v1 algorithm."""

import hashlib
import json
from datetime import date, timedelta
from pathlib import Path

from hotspot.core.registry import get_algorithm
from hotspot.core.v1.config import has_reviewed_config
from hotspot.startup import annotate_file_history, assess_app_history, suspect_partial_cutoff
from hotspot.training.auto import FALLBACK, auto_select_v1
from hotspot.training.selection import load_selected_spec

from .csv_import import ImportProblem


MODEL_VERSION = "hotspot-simple-v1"
SERVICE_ROOT = Path(__file__).resolve().parents[1]
CONFIG_ROOT = SERVICE_ROOT / "model_configs" / MODEL_VERSION


def algorithm_fingerprint():
    """Identify executable source and parameter artifacts, independent of install path."""
    paths = [Path(__file__), *sorted((SERVICE_ROOT / "hotspot").rglob("*.py")),
             *sorted(CONFIG_ROOT.glob("*.json"))]
    digest = hashlib.sha256()
    for path in paths:
        digest.update(path.relative_to(SERVICE_ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


LOADED_ALGORITHM_FINGERPRINT = algorithm_fingerprint()


def predict_real(rows, request):
    """Return complete real candidates and provenance; never substitute a Mock."""
    if request.modelVersion != MODEL_VERSION:
        raise ImportProblem("MODEL_VERSION_UNSUPPORTED", "当前真实模式仅批准 hotspot-simple-v1。")
    if algorithm_fingerprint() != LOADED_ALGORITHM_FINGERPRINT:
        raise ImportProblem("MODEL_ARTIFACT_CHANGED", "算法或参数文件已变化；请重启服务后提交新任务。")
    cutoff = date.fromisoformat(request.cutoffDate)
    days = {}
    for row in rows:
        if row["appId"] == request.appId and row["date"] <= request.cutoffDate:
            day = date.fromisoformat(row["date"])
            days.setdefault(day, {})[row["fileKey"]] = int(row["trafficBytes"])
    if not days:
        raise ImportProblem("INSUFFICIENT_HISTORY", "该 App 在截止日前没有可用文件观测。")
    partial = suspect_partial_cutoff(days, cutoff)
    if partial:
        days.pop(cutoff)
    history = assess_app_history(days, cutoff, request.horizonDays)
    algorithm = get_algorithm(MODEL_VERSION)
    evidence = None
    configuration_end = None
    spec = None
    if history.state == "COLD":
        method, spec, source = "last_day", FALLBACK, "APP_COLD_FALLBACK"
    elif has_reviewed_config(request.appId, request.horizonDays):
        # Only reviewed identifiers enter this path; user IDs cannot select filesystem paths.
        path = CONFIG_ROOT / f"{request.appId}-h{request.horizonDays}.json"
        artifact = json.loads(path.read_text(encoding="utf-8"))
        matured = date.fromisoformat(artifact["testEndDate"]) + timedelta(days=request.horizonDays)
        if matured <= cutoff:
            method, spec = load_selected_spec(path, algorithm, request.appId, request.horizonDays, cutoff)
            configuration_end, source = matured.isoformat(), "REVIEWED_CONFIG"
    if spec is None:
        method, spec, evidence = auto_select_v1(days, request.appId, cutoff, request.horizonDays)
        source = "AUTO_SELECTION"
    items = algorithm.predict_items(days, request.appId, cutoff, request.horizonDays, spec)
    if not items:
        raise ImportProblem("INSUFFICIENT_HISTORY", "最近30天没有可用文件候选，未生成替代结果。")
    annotate_file_history(items, days, cutoff)
    return {"items": items, "modelInfo": {
        "algorithmFingerprint": LOADED_ALGORITHM_FINGERPRINT,
        "selectedMethod": method, "selectedSpec": algorithm.describe_spec(spec),
        "parameterSource": source, "configurationEvidenceEndDate": configuration_end,
        "appHistory": history.as_dict(), "selectionEvidence": evidence,
        "dataQuality": {"excludedSuspectCutoffDate": request.cutoffDate if partial else None},
        "predictionBasis": "observed historical intensity; not calibrated complete CDN traffic",
        "candidatePolicy": "past observations only; adaptive window up to 30 days",
    }}
