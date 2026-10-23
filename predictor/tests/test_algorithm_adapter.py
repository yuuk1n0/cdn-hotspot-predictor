"""Real v1 calculations: time boundaries, isolation, provenance and exact bytes."""

import importlib
import json
import sys
from datetime import date, timedelta
from unittest.mock import patch

import pytest

from app.csv_import import ImportProblem
from app.prediction import PredictionRequest


def _adapter():
    assert importlib.util.find_spec("app.algorithm_adapter") is not None, "real algorithm adapter is missing"
    return importlib.import_module("app.algorithm_adapter")


def request(app="new-app", cutoff="2026-01-02", horizon=1, version="hotspot-simple-v1"):
    return PredictionRequest("task_real", "ds_example", app, cutoff, horizon, version)


def row(key, day, amount, app="new-app"):
    return {"fileKey": key, "date": day, "trafficBytes": str(amount), "appId": app}


def test_daily_weekly_exact_bytes_ties_and_app_isolation():
    rows = [row("b", "2026-01-02", 10**40), row("a", "2026-01-02", 10**40),
            row("foreign", "2026-01-02", 10**80, "other"),
            row("future", "2026-01-03", 10**80)]
    daily = _adapter().predict_real(rows, request())
    weekly = _adapter().predict_real(reversed(rows), request(horizon=7))
    assert [(x["rank"], x["fileKey"], x["predictedBytes"]) for x in daily["items"]] == [
        (1, "a", str(10**40)), (2, "b", str(10**40))]
    assert [x["predictedBytes"] for x in weekly["items"]] == [str(7 * 10**40)] * 2
    assert all(x["score"] is None and "未校准完整流量" in x["reason"] for x in weekly["items"])
    assert daily["modelInfo"]["parameterSource"] == "APP_COLD_FALLBACK"
    assert len(daily["modelInfo"]["algorithmFingerprint"]) == 64


def test_adding_future_date_cannot_change_partial_cutoff_decision():
    start = date(2026, 1, 1)
    rows = [row(f"f-{n:03}", (start + timedelta(days=i)).isoformat(), 100 - n)
            for i in range(7) for n in range(100)]
    rows += [row("partial", "2026-01-08", 10**10)]
    body = request(cutoff="2026-01-08")
    original = _adapter().predict_real(rows, body)
    changed = _adapter().predict_real(rows + [row("future", "2026-01-09", 10**50)], body)
    assert original == changed
    assert original["modelInfo"]["dataQuality"]["excludedSuspectCutoffDate"] == "2026-01-08"
    assert "partial" not in {x["fileKey"] for x in original["items"]}


def test_reviewed_config_cannot_use_future_promotion_evidence():
    start = date(2026, 7, 1)
    rows = [row("steady", (start + timedelta(days=i)).isoformat(), 10 + i, "10350")
            for i in range(90)]
    before = _adapter().predict_real(rows, request("10350", "2026-08-02"))
    assert before["modelInfo"]["parameterSource"] == "AUTO_SELECTION"
    later = rows + [row("steady", "2026-10-13", 100, "10350")]
    # Enough recent observed days to remain HOT at the deployment evidence boundary.
    later += [row("steady", f"2026-10-{i:02}", 100, "10350") for i in range(7, 13)]
    after = _adapter().predict_real(later, request("10350", "2026-10-13"))
    assert after["modelInfo"]["parameterSource"] == "REVIEWED_CONFIG"
    assert after["modelInfo"]["configurationEvidenceEndDate"] == "2026-10-13"


def test_new_hot_app_selection_is_repeatable_and_uses_only_past():
    start = date(2026, 1, 1)
    rows = [row("steady", (start + timedelta(days=i)).isoformat(), i + 1) for i in range(45)]
    body = request(cutoff="2026-02-09", horizon=7)
    first = _adapter().predict_real(rows, body)
    assert first == _adapter().predict_real(rows + [row("future", "2026-02-15", 10**50)], body)
    assert first["modelInfo"]["parameterSource"] == "AUTO_SELECTION"
    assert first["modelInfo"]["selectionEvidence"]["testCutoffCount"] > 0


def test_missing_calendar_day_does_not_dilute_single_file_observations():
    result = _adapter().predict_real([row("a", "2026-01-01", 10)], request())
    assert result["items"][0]["predictedBytes"] == "10"
    assert "截止日无观测" in result["items"][0]["reason"]


@pytest.mark.parametrize("rows", [[], [row("future", "2026-01-03", 100)],
                                  [row("other", "2026-01-01", 100, "other")]])
def test_no_known_history_is_failure_not_a_fabricated_result(rows):
    with pytest.raises(ImportProblem) as error:
        _adapter().predict_real(rows, request())
    assert error.value.code == "INSUFFICIENT_HISTORY"


@pytest.mark.parametrize("version", ["contract-demo-v1", "hotspot-lightgbm-v2", "hotspot-lightgbm-v3"])
def test_unapproved_versions_do_not_fall_back_to_v1_or_mock(version):
    with pytest.raises(ImportProblem) as error:
        _adapter().predict_real([row("a", "2026-01-02", 10)], request(version=version))
    assert error.value.code == "MODEL_VERSION_UNSUPPORTED"


def test_offline_cli_also_ignores_future_date_presence(tmp_path):
    _adapter()
    from hotspot.cli import main
    start = date(2026, 1, 1)
    days = {start + timedelta(days=i): {f"f-{n}": 100 - n for n in range(100)}
            for i in range(7)}
    days[date(2026, 1, 8)] = {"partial": 10**10}
    days[date(2026, 1, 9)] = {"future": 100}
    output = tmp_path / "result.json"
    args = ["hotspot.cli", "predict", "--input", "unused", "--app-id", "new-app",
            "--source-unit", "B", "--horizon-days", "1", "--cutoff-date", "2026-01-08",
            "--task-id", "test", "--dataset-id", "test", "--dataset-source", "FAKE",
            "--output", str(output)]
    with patch("hotspot.cli.read_observations", return_value=days), patch.object(sys, "argv", args):
        main()
    result = json.loads(output.read_text())
    assert result["dataQuality"]["excludedSuspectCutoffDate"] == "2026-01-08"
    assert "partial" not in {x["fileKey"] for x in result["items"]}


def test_promoted_selection_loader_rejects_not_yet_mature_test_evidence():
    adapter = _adapter()
    from hotspot.core.registry import get_algorithm
    from hotspot.training.selection import load_selected_spec
    with pytest.raises(ValueError, match="promotion.*cutoff"):
        load_selected_spec(adapter.CONFIG_ROOT / "10350-h1.json", get_algorithm(),
                           "10350", 1, date(2026, 8, 2))


def test_cli_reviewed_default_also_requires_mature_promotion_evidence(tmp_path):
    _adapter()
    from hotspot.cli import main
    start = date(2026, 7, 1)
    days = {start + timedelta(days=i): {"steady": 10 + i} for i in range(90)}
    output = tmp_path / "result.json"
    args = ["hotspot.cli", "predict", "--input", "unused", "--app-id", "10350",
            "--source-unit", "B", "--horizon-days", "1", "--cutoff-date", "2026-08-02",
            "--task-id", "test", "--dataset-id", "test", "--dataset-source", "FAKE",
            "--output", str(output)]
    with patch("hotspot.cli.read_observations", return_value=days), patch.object(sys, "argv", args):
        main()
    assert json.loads(output.read_text())["parameterSource"] == "AUTO_SELECTION"
