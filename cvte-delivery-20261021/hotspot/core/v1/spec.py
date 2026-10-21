"""Algorithm specification and version."""

from dataclasses import dataclass
from decimal import Decimal

MODEL_VERSION = "hotspot-simple-v1"

@dataclass(frozen=True)
class ModelSpec:
    method: str
    window: int
    half_life: str | None = None

    def __post_init__(self) -> None:
        if self.method not in {"last_day", "mean", "decay"}:
            raise ValueError(f"Unknown method: {self.method}")
        if not 1 <= self.window <= 30:
            raise ValueError("window must be between 1 and 30 days")
        if self.method == "decay":
            if self.half_life is None or Decimal(self.half_life) <= 0:
                raise ValueError("decay requires a positive half_life")
        elif self.half_life is not None:
            raise ValueError("half_life is only valid for decay")
