"""Load a past-only, App-specific parameter selection for prediction."""

from __future__ import annotations

import json
from datetime import date, timedelta
from pathlib import Path

from ..core.interface import Algorithm


def load_selected_spec(path: Path, algorithm: Algorithm, app_id: str,
                       horizon_days: int, cutoff: date) -> tuple[str, object]:
    report = json.loads(path.read_text(encoding="utf-8"))
    expected = {"appId": app_id, "horizonDays": horizon_days,
                "modelVersion": algorithm.version}
    for field, value in expected.items():
        if report.get(field) != value:
            raise ValueError(f"Selection file {field} does not match prediction request")
    selected = report.get("selectedMethod")
    if selected not in algorithm.candidates:
        raise ValueError("Selection file has an unknown selectedMethod")
    spec = algorithm.candidates[selected]
    if report.get("selectedSpec") != algorithm.describe_spec(spec):
        raise ValueError("Selection file selectedSpec does not match the algorithm")
    label_end = date.fromisoformat(report["selectionEndDate"]) + timedelta(
        days=horizon_days
    )
    if label_end > cutoff:
        raise ValueError("Selection file uses labels after the prediction cutoff")
    if "deploymentDecision" in report:
        promotion_end = date.fromisoformat(report["testEndDate"]) + timedelta(days=horizon_days)
        if promotion_end > cutoff:
            raise ValueError("Selection file promotion evidence uses labels after the prediction cutoff")
    return selected, spec
