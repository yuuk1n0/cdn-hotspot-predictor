from datetime import date, timedelta

from app.model_review import review_model
from hotspot.core.v1.spec import ModelSpec


def days_with(count=40, files=80):
    start = date(2026, 9, 1)
    result = {}
    for offset in range(count):
        day = start + timedelta(days=offset)
        result[day] = {f"app/file-{index}": 1000 + index * 10 for index in range(files)}
    return result


def test_review_uses_real_rolling_window_and_same_app_horizon():
    days = days_with()
    result = review_model(days, "10350", 1, ModelSpec("last_day", 1))
    assert result["appId"] == "10350"
    assert result["horizonDays"] == 1
    assert result["modelVersion"] == "hotspot-simple-v1"
    assert result["metricBasis"] == "REAL_OBSERVED_FUTURE_ROLLING_WINDOW"
    assert result["cutoffCount"] == 5
    assert result["dataStartDate"] == min(days).isoformat()
    assert result["dataEndDate"] == max(days).isoformat()
    assert result["currentSpec"] == {"method": "last_day", "window": 1, "halfLife": None}
    assert len(result["candidates"]) >= 2
    assert "canSwitch" in result and isinstance(result["canSwitch"], bool)


def test_identical_candidates_never_invent_a_switch():
    result = review_model(days_with(), "10350", 7, ModelSpec("last_day", 1))
    assert result["canSwitch"] is False
    assert "CURRENT_CONFIG_NONINFERIOR" in result["reasonCodes"]
    assert result["targetSpec"] == result["currentSpec"]


def test_review_does_not_fill_missing_days():
    days = days_with()
    missing = min(days) + timedelta(days=10)
    del days[missing]
    result = review_model(days, "10350", 1, ModelSpec("last_day", 1))
    assert result["dataStartDate"] == min(days).isoformat()
    assert "缺失日期不补零" in " ".join(result["assumptions"])
