"""Eligible dates and leakage-safe time windows."""

from __future__ import annotations

from datetime import date, timedelta
from typing import Iterator, Mapping


def possible_cutoffs(days: Mapping[date, Mapping[str, int]], horizon_days: int) -> Iterator[date]:
    """Yield cutoffs with a complete *observed calendar* future window."""
    observed = set(days)
    for cutoff in sorted(observed):
        if all(cutoff + timedelta(days=offset) in observed
               for offset in range(1, horizon_days + 1)):
            yield cutoff


def split_cutoffs(days: Mapping[date, Mapping[str, int]], horizon_days: int,
                  exclude_last_days: int) -> tuple[list[date], list[date], list[date], date]:
    """Separate selection and test cutoffs so their future labels never overlap."""
    if horizon_days not in (1, 7):
        raise ValueError("horizonDays must be 1 or 7")
    if exclude_last_days < 0:
        raise ValueError("exclude_last_days cannot be negative")
    latest_allowed = max(days) - timedelta(days=exclude_last_days)
    valid = [cutoff for cutoff in possible_cutoffs(days, horizon_days)
             if cutoff + timedelta(days=horizon_days) <= latest_allowed]
    if len(valid) < 10:
        raise ValueError("Fewer than 10 complete observed evaluation windows")
    split = max(1, int(len(valid) * 0.7))
    test = valid[split:]
    if not test:
        raise ValueError("No independent test cutoffs")
    selection = [cutoff for cutoff in valid[:split]
                 if cutoff + timedelta(days=horizon_days) < test[0]]
    if not selection:
        raise ValueError("No selection cutoffs after label embargo")
    return valid, selection, test, latest_allowed
