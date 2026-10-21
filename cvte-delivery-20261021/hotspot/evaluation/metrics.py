"""Metrics against observed future file traffic."""

from __future__ import annotations

from datetime import date, timedelta
from math import log1p, log2
from typing import Mapping

def future_totals(days: Mapping[date, Mapping[str, int]], cutoff: date,
                  horizon_days: int) -> dict[str, int]:
    totals: dict[str, int] = {}
    for offset in range(1, horizon_days + 1):
        for key, amount in days[cutoff + timedelta(days=offset)].items():
            totals[key] = totals.get(key, 0) + amount
    return totals


def prediction_metrics(items: list[dict], actual: Mapping[str, int]) -> dict[str, float]:
    reference = sorted(actual, key=lambda key: (-actual[key], key))[:50]
    reference_set = set(reference)
    predicted = [item["fileKey"] for item in items[:50]]
    predicted_set = set(predicted)
    candidate_set = {item["fileKey"] for item in items}
    denominator = len(reference) or 1
    gains = [log1p(actual.get(key, 0)) for key in predicted]
    dcg = sum(gain / log2(rank + 2) for rank, gain in enumerate(gains))
    ideal = sum(log1p(actual[key]) / log2(rank + 2)
                for rank, key in enumerate(reference))
    all_traffic = sum(actual.values())
    return {
        "recallAt50": len(reference_set & predicted_set) / denominator,
        "ndcgAt50": dcg / ideal if ideal else 0.0,
        "observedTrafficCoverageAt50": (
            sum(actual.get(key, 0) for key in predicted) / all_traffic
            if all_traffic else 0.0
        ),
        "candidateCoverageAt50": len(reference_set & candidate_set) / denominator,
    }
