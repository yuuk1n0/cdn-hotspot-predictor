"""Candidate parameter sets for offline selection."""

from types import MappingProxyType
from typing import Mapping

from .spec import ModelSpec

COMPARISONS: Mapping[str, ModelSpec] = MappingProxyType({
    "last_day": ModelSpec("last_day", 1),
    "mean_7": ModelSpec("mean", 7),
    "decay_3_half_0_5": ModelSpec("decay", 3, "0.5"),
    "decay_14_half_3": ModelSpec("decay", 14, "3"),
})
