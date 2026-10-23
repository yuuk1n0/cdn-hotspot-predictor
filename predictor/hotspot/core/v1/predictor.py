"""Pure prediction of observed file traffic proxies."""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP, localcontext
from typing import Mapping

from .spec import ModelSpec

EXPANSION_WINDOWS = (3, 7, 14, 30)

def candidate_window(days: Mapping[date, Mapping[str, int]], cutoff: date, initial: int,
                     minimum_candidates: int = 50) -> tuple[int, set[str]]:
    windows = [initial] + [w for w in EXPANSION_WINDOWS if w > initial]
    for window in windows:
        start = cutoff - timedelta(days=window - 1)
        candidates = {
            key for day, items in days.items()
            if start <= day <= cutoff for key in items
        }
        if len(candidates) >= minimum_candidates or window == windows[-1]:
            return window, candidates
    raise AssertionError("unreachable")


def _weights(spec: ModelSpec, window: int) -> tuple[list[Decimal], str]:
    method = spec.method
    half_life = spec.half_life
    if method == "last_day" and window > 1:
        method, half_life = "decay", "0.5"
    with localcontext() as context:
        context.prec = 180
        if method == "mean":
            weights = [Decimal(1)] * window
        elif method == "last_day":
            weights = [Decimal(1)]
        else:
            half = Decimal(half_life)
            weights = [Decimal(2) ** (-Decimal(age) / half) for age in range(window)]
    return weights, method


def predict_items(days: Mapping[date, Mapping[str, int]], app_id: str, cutoff: date,
                  horizon_days: int, spec: ModelSpec) -> list[dict]:
    """Return all candidates, ordered by rounded predicted bytes then fileKey."""
    if horizon_days not in (1, 7):
        raise ValueError("horizonDays must be 1 or 7")
    if not days or cutoff < min(days):
        raise ValueError("cutoffDate must be on or after the first App observation")
    window, candidates = candidate_window(days, cutoff, spec.window)
    if not candidates:
        return []
    weights, used_method = _weights(spec, window)
    with localcontext() as context:
        context.prec = 180
        # A missing whole calendar day is unknown, not a zero-traffic day.
        denominator = sum((weight for age, weight in enumerate(weights)
                           if cutoff - timedelta(days=age) in days), Decimal(0))
        totals: dict[str, Decimal] = {key: Decimal(0) for key in candidates}
        seen_days: dict[str, int] = {key: 0 for key in candidates}
        last_seen: dict[str, date] = {}
        for age, weight in enumerate(weights):
            day = cutoff - timedelta(days=age)
            for key, amount in days.get(day, {}).items():
                if key in candidates:
                    totals[key] += weight * amount
                    seen_days[key] += 1
                    last_seen.setdefault(key, day)
        no_cutoff_observation = cutoff not in days
        result = []
        for key in candidates:
            predicted = (Decimal(horizon_days) * totals[key] / denominator).to_integral_value(
                rounding=ROUND_HALF_UP
            )
            reason = (f"{window}天{used_method}观测强度外推；观测{seen_days[key]}天；"
                      f"最近观测{last_seen[key].isoformat()}；未校准完整流量")
            if no_cutoff_observation:
                reason += "；截止日无观测，使用历史回退"
            result.append({
                "rank": 0,
                "fileKey": key,
                "predictedBytes": format(predicted, "f"),
                "score": None,
                "reason": reason,
                "fileType": None,
                "fileSizeBytes": None,
                "recentActualBytes": None,
                "compressionStatus": None,
            })
    result.sort(key=lambda item: (-int(item["predictedBytes"]), item["fileKey"]))
    for rank, item in enumerate(result, 1):
        item["rank"] = rank
    return result
