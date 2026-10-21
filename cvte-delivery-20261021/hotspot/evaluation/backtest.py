"""Evaluate one fixed specification over historical cutoffs."""

from __future__ import annotations

from datetime import date
from statistics import mean
from typing import Mapping

from ..core.interface import Algorithm
from .metrics import future_totals, prediction_metrics

def evaluate_spec(days: Mapping[date, Mapping[str, int]], app_id: str,
                  horizon_days: int, cutoffs: list[date], spec: object,
                  algorithm: Algorithm) -> dict:
    rows = []
    for cutoff in cutoffs:
        items = algorithm.predict_items(days, app_id, cutoff, horizon_days, spec)
        row = {"cutoffDate": cutoff.isoformat(), **prediction_metrics(
            items, future_totals(days, cutoff, horizon_days)
        )}
        rows.append(row)
    keys = ("recallAt50", "ndcgAt50", "observedTrafficCoverageAt50",
            "candidateCoverageAt50")
    return {
        "cutoffCount": len(rows),
        "mean": {key: mean(row[key] for row in rows) for key in keys},
        "worstCutoffs": sorted(rows, key=lambda row: (row["recallAt50"], row["cutoffDate"]))[:5],
        "rows": rows,
    }
