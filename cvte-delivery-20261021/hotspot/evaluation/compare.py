"""Compare v2 against v1 on identical chronological holdout cutoffs."""

from __future__ import annotations

from datetime import date
from importlib.metadata import PackageNotFoundError, version
from typing import Mapping

from ..core.v1.model import V1Algorithm
from ..core.v1.spec import ModelSpec
from ..core.v2.model import V2Algorithm
from ..training.pipeline import rolling_backtest
from .backtest import evaluate_spec
from .split import split_cutoffs


def compare_v1_v2(days: Mapping[date, Mapping[str, int]], app_id: str,
                  horizon_days: int, exclude_last_days: int = 0) -> dict:
    """Report the selected v1, matched-pool v1, and walk-forward v2 test scores."""
    v1 = V1Algorithm()
    v2 = V2Algorithm()
    v1_report = rolling_backtest(days, app_id, horizon_days, exclude_last_days,
                                 algorithm=v1)
    v2_report = rolling_backtest(days, app_id, horizon_days, exclude_last_days,
                                 algorithm=v2)
    _, _, test_cutoffs, _ = split_cutoffs(days, horizon_days, exclude_last_days)
    matched_v1 = evaluate_spec(days, app_id, horizon_days, test_cutoffs,
                               ModelSpec("last_day", 1), v1)
    v2_mean = v2_report["test"]["mean"]
    matched_mean = matched_v1["mean"]
    selected_mean = v1_report["test"]["mean"]
    try:
        lightgbm_version = version("lightgbm")
    except PackageNotFoundError:
        lightgbm_version = None
    return {
        "appId": app_id,
        "horizonDays": horizon_days,
        "metricScope": "observed-future-proxy; completeness not established",
        "testStartDate": test_cutoffs[0].isoformat(),
        "testEndDate": test_cutoffs[-1].isoformat(),
        "testCutoffCount": len(test_cutoffs),
        "lightgbmVersion": lightgbm_version,
        "trainingPolicy": "walk-forward; only observed future labels completed by each cutoff",
        "labelLimitation": "unobserved future files are unknown, not zero labels",
        "v1SelectedMethod": v1_report["selectedMethod"],
        "v1SelectedSpec": v1_report["selectedSpec"],
        "v2Spec": v2_report["selectedSpec"],
        "v1SelectedTest": v1_report["test"],
        "v1SameCandidatePoolTest": matched_v1,
        "v2Test": v2_report["test"],
        "deltaVsSamePool": {
            key: v2_mean[key] - matched_mean[key]
            for key in v2_mean
        },
        "deltaVsSelectedV1": {
            key: v2_mean[key] - selected_mean[key]
            for key in v2_mean
        },
        "rankingHeadroomAt50": max(
            0.0, v2_mean["candidateCoverageAt50"] - v2_mean["recallAt50"]
        ),
        "candidatePool": "v1 last_day with the same 1-day initial, 30-day maximum expansion",
    }
