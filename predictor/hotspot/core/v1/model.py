"""Adapter that exposes the v1 implementation through the shared algorithm contract."""

from __future__ import annotations

from datetime import date
from typing import Mapping

from ..interface import Observations
from .candidates import COMPARISONS
from .config import config_for
from .predictor import predict_items
from .selector import SELECTION_RULE, select_method
from .spec import MODEL_VERSION, ModelSpec


class V1Algorithm:
    version = MODEL_VERSION
    candidates: Mapping[str, object] = COMPARISONS
    selection_rule = SELECTION_RULE

    def configured_spec(self, app_id: str, horizon_days: int) -> ModelSpec:
        return config_for(app_id, horizon_days)

    def predict_items(self, days: Observations, app_id: str, cutoff: date,
                      horizon_days: int, spec: object) -> list[dict]:
        if not isinstance(spec, ModelSpec):
            raise TypeError("v1 requires a v1 ModelSpec")
        return predict_items(days, app_id, cutoff, horizon_days, spec)

    def select_candidate(self, selection_results: Mapping[str, dict]) -> str:
        return select_method(selection_results)

    def describe_spec(self, spec: object) -> dict:
        if not isinstance(spec, ModelSpec):
            raise TypeError("v1 requires a v1 ModelSpec")
        return {"method": spec.method, "window": spec.window,
                "halfLife": spec.half_life}
