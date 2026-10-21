"""Contract shared by versioned prediction algorithms and the surrounding workflows."""

from __future__ import annotations

from datetime import date
from typing import Mapping, Protocol


Observations = Mapping[date, Mapping[str, int]]


class Algorithm(Protocol):
    """Supply version-specific behavior to the shared CLI, training, and reports.

    ``predict_items`` returns every candidate in rank order. Each item needs
    ``rank``, ``fileKey``, and decimal-string ``predictedBytes`` plus the public
    prediction fields used by the service response. ``describe_spec`` returns
    a JSON-serializable dictionary; the specification itself is opaque here.
    """

    version: str
    candidates: Mapping[str, object]
    selection_rule: str

    def configured_spec(self, app_id: str, horizon_days: int) -> object: ...

    def predict_items(self, days: Observations, app_id: str, cutoff: date,
                      horizon_days: int, spec: object) -> list[dict]: ...

    def select_candidate(self, selection_results: Mapping[str, dict]) -> str: ...

    def describe_spec(self, spec: object) -> dict: ...
