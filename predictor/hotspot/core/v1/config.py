"""Reviewed parameters used by the prediction path."""

from types import MappingProxyType
from typing import Mapping

from .spec import ModelSpec

# App-specific settings promoted after past-only selection and held-out review.
# See docs/appid-optimization.md for results and data limitations.
MODEL_CONFIG: Mapping[tuple[str, int], ModelSpec] = MappingProxyType({
    ("10066", 1): ModelSpec("last_day", 1),
    ("10066", 7): ModelSpec("decay", 3, "0.5"),
    ("10350", 1): ModelSpec("last_day", 1),
    ("10350", 7): ModelSpec("last_day", 1),
})
DEFAULT_CONFIG: Mapping[int, ModelSpec] = MappingProxyType({
    1: ModelSpec("mean", 7),
    7: ModelSpec("decay", 14, "3"),
})


def config_for(app_id: str, horizon_days: int) -> ModelSpec:
    if horizon_days not in (1, 7):
        raise ValueError("horizonDays must be 1 or 7")
    return MODEL_CONFIG.get((app_id, horizon_days), DEFAULT_CONFIG[horizon_days])


def has_reviewed_config(app_id: str, horizon_days: int) -> bool:
    return (app_id, horizon_days) in MODEL_CONFIG
