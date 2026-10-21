"""Assess App and file history available at a prediction cutoff."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from statistics import median
from typing import Mapping

from .evaluation.split import possible_cutoffs

MIN_APP_OBSERVED_DAYS = 28
MIN_RECENT_OBSERVED_DAYS = 4
RECENT_DAYS = 7
FILE_HISTORY_DAYS = 30
PARTIAL_DAY_RATIO = 0.05


@dataclass(frozen=True)
class AppHistory:
    state: str
    reason: str
    observed_days: int
    recent_observed_days: int
    completed_label_windows: int

    def as_dict(self) -> dict:
        return {
            "state": self.state,
            "reason": self.reason,
            "observedDays": self.observed_days,
            "recentObservedDays": self.recent_observed_days,
            "completedLabelWindows": self.completed_label_windows,
        }


def assess_app_history(days: Mapping[date, Mapping[str, int]], cutoff: date,
                       horizon_days: int) -> AppHistory:
    """Use only recorded dates at or before cutoff; an absent day is unknown."""
    if horizon_days not in (1, 7):
        raise ValueError("horizonDays must be 1 or 7")
    past = {day: items for day, items in days.items() if day <= cutoff}
    count = len(past)
    recent = sum(cutoff - timedelta(days=RECENT_DAYS - 1) <= day <= cutoff
                 for day in past)
    completed = sum(1 for _ in possible_cutoffs(past, horizon_days))
    minimum_windows = max(10, 2 * horizon_days + 7)
    if count == 0:
        state = "COLD"
        reason = "该AppID在截止日前没有文件记录，无法生成文件候选"
    elif count < MIN_APP_OBSERVED_DAYS:
        state = "COLD"
        reason = f"仅有{count}个观测日，少于{MIN_APP_OBSERVED_DAYS}天"
    elif recent < MIN_RECENT_OBSERVED_DAYS:
        state = "COLD"
        reason = f"最近{RECENT_DAYS}天仅有{recent}个观测日"
    elif completed < minimum_windows:
        state = "COLD"
        reason = f"仅有{completed}个完整历史标签窗口，少于{minimum_windows}个"
    else:
        state = "HOT"
        reason = "历史足够，可按该AppID独立选型"
    return AppHistory(state, reason, count, recent, completed)


def annotate_file_history(items: list[dict], days: Mapping[date, Mapping[str, int]],
                          cutoff: date) -> None:
    """Mark files with only one recent observation as low-evidence cold starts."""
    counts = {item["fileKey"]: 0 for item in items}
    for age in range(FILE_HISTORY_DAYS):
        for key in days.get(cutoff - timedelta(days=age), {}):
            if key in counts:
                counts[key] += 1
    for item in items:
        count = counts[item["fileKey"]]
        item["observedDaysLast30"] = count
        item["fileStartState"] = "COLD" if count <= 1 else "WARM"
        if count <= 1:
            item["reason"] += "；近30天仅观测1天，文件冷启动，预测不确定"


def suspect_partial_cutoff(days: Mapping[date, Mapping[str, int]], cutoff: date) -> bool:
    """Detect an extreme file-count collapse at the last recorded date."""
    if cutoff not in days or cutoff != max(days):
        return False
    previous = [len(days[cutoff - timedelta(days=age)])
                for age in range(1, 8) if cutoff - timedelta(days=age) in days]
    if len(previous) < 4:
        return False
    normal = median(previous)
    return normal >= 50 and len(days[cutoff]) < normal * PARTIAL_DAY_RATIO
