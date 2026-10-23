"""Past-only parameter selection followed by held-out evaluation."""

from __future__ import annotations

from datetime import date
from typing import Mapping

from ..core.interface import Algorithm
from ..core.registry import get_algorithm
from ..evaluation.backtest import evaluate_spec
from ..evaluation.split import split_cutoffs


def select_parameters(days: Mapping[date, Mapping[str, int]], app_id: str,
                      horizon_days: int, exclude_last_days: int = 0,
                      algorithm: Algorithm | None = None) -> dict:
    """Select parameters using past cutoffs, without evaluating the held-out test set."""
    if algorithm is None:
        algorithm = get_algorithm()
    valid, selection, test, latest_allowed = split_cutoffs(
        days, horizon_days, exclude_last_days
    )
    selection_results = {
        name: evaluate_spec(days, app_id, horizon_days, selection, spec, algorithm)
        for name, spec in algorithm.candidates.items()
    }
    selected = algorithm.select_candidate(selection_results)
    return {
        "appId": app_id,
        "modelVersion": algorithm.version,
        "horizonDays": horizon_days,
        "metricScope": "observed-future-proxy; completeness not established",
        "observedDateCount": len(days),
        "eligibleCutoffCount": len(valid),
        "skippedCutoffCount": sum(1 for day in days if day < latest_allowed) - len(valid),
        "selectionEndDate": selection[-1].isoformat(),
        "testStartDate": test[0].isoformat(),
        "selection": selection_results,
        "selectionRule": algorithm.selection_rule,
        "selectedMethod": selected,
        "selectedSpec": algorithm.describe_spec(algorithm.candidates[selected]),
    }


def rolling_backtest(days: Mapping[date, Mapping[str, int]], app_id: str,
                     horizon_days: int, exclude_last_days: int = 0,
                     configured_spec: object | None = None,
                     algorithm: Algorithm | None = None) -> dict:
    """Choose on past cutoffs; evaluate only that choice on later cutoffs.

    All metrics use the observed future list. Unobserved files get zero *proxy
    relevance in the metric*, which is not a claim of measured zero traffic.
    """
    if algorithm is None:
        algorithm = get_algorithm()
    result = select_parameters(days, app_id, horizon_days, exclude_last_days, algorithm)
    _, _, test, _ = split_cutoffs(days, horizon_days, exclude_last_days)
    selected = result["selectedMethod"]
    selected_spec = algorithm.candidates[selected]
    selected_test = evaluate_spec(days, app_id, horizon_days, test, selected_spec, algorithm)
    result["test"] = selected_test
    if configured_spec is not None:
        configured_name = next((name for name, spec in algorithm.candidates.items()
                                if spec == configured_spec), "configured")
        result["configuredMethod"] = configured_name
        result["configuredSpec"] = algorithm.describe_spec(configured_spec)
        result["configuredTest"] = (selected_test if configured_spec == selected_spec
                                    else evaluate_spec(days, app_id, horizon_days, test,
                                                       configured_spec, algorithm))
    return result
