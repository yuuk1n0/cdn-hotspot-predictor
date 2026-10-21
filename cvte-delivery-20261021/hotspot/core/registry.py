"""Single entry point for selecting a prediction algorithm by version."""

from __future__ import annotations

from types import MappingProxyType
from typing import Mapping

from .interface import Algorithm
from .v1.model import V1Algorithm
from .v2.model import V2Algorithm
from .v3.model import V3Algorithm

_v1 = V1Algorithm()
_v2 = V2Algorithm()
_v3 = V3Algorithm()
DEFAULT_VERSION = _v1.version
_ALGORITHMS: Mapping[str, Algorithm] = MappingProxyType({
    _v1.version: _v1, _v2.version: _v2, _v3.version: _v3,
})


def available_versions() -> tuple[str, ...]:
    return tuple(_ALGORITHMS)


def get_algorithm(model_version: str = DEFAULT_VERSION) -> Algorithm:
    try:
        return _ALGORITHMS[model_version]
    except KeyError as error:
        raise ValueError(f"Unsupported modelVersion: {model_version}") from error
