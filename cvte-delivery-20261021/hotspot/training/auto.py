"""Conservative App-specific v1 selection from completed historical windows."""

from __future__ import annotations

from datetime import date, timedelta
from typing import Mapping

from ..core.v1.model import V1Algorithm
from ..core.v1.spec import ModelSpec
from .pipeline import rolling_backtest

AUTO_SELECTION_DAYS = 60
PROMOTION_MARGIN = 0.01
FALLBACK = ModelSpec("last_day", 1)


def auto_select_v1(days: Mapping[date, Mapping[str, int]], app_id: str,
                   cutoff: date, horizon_days: int) -> tuple[str, ModelSpec, dict]:
    """Evaluate recent past only and retain the fallback if the holdout loses."""
    recent = {day: values for day, values in days.items()
              if cutoff - timedelta(days=AUTO_SELECTION_DAYS - 1) <= day <= cutoff}
    algorithm = V1Algorithm()
    try:
        report = rolling_backtest(recent, app_id, horizon_days,
                                  configured_spec=FALLBACK, algorithm=algorithm)
    except ValueError as error:
        return "last_day", FALLBACK, {"decision": "FALLBACK", "reason": str(error)}
    selected = report["selectedMethod"]
    selected_mean = report["test"]["mean"]
    fallback_mean = report["configuredTest"]["mean"]
    metrics = ("recallAt50", "ndcgAt50", "observedTrafficCoverageAt50")
    no_material_loss = all(
        selected_mean[key] >= fallback_mean[key] - PROMOTION_MARGIN
        for key in metrics
    )
    meaningful_gain = any(
        selected_mean[key] > fallback_mean[key] + PROMOTION_MARGIN
        for key in metrics
    )
    promoted = selected == "last_day" or (no_material_loss and meaningful_gain)
    method = selected if promoted else "last_day"
    return method, algorithm.candidates[method], {
        "decision": "AUTO_SELECTED" if promoted else "FALLBACK",
        "trainingSelectedMethod": selected,
        "selectionEndDate": report["selectionEndDate"],
        "testStartDate": report["testStartDate"],
        "testCutoffCount": report["test"]["cutoffCount"],
        "selectedTestMean": selected_mean,
        "fallbackTestMean": fallback_mean,
        "metricScope": report["metricScope"],
    }
