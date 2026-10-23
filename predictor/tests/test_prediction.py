from decimal import Decimal

import pytest

from app.csv_import import ImportProblem
from app.prediction import PredictionRequest, mock_items


def request(**changes):
    return PredictionRequest.from_json({
        "taskId": "task_from_java", "datasetId": "ds_" + "a" * 64, "appId": "10350",
        "cutoffDate": "2026-01-07", "horizonDays": 7, "modelVersion": "contract-demo-v1", **changes,
    })


@pytest.mark.parametrize("changes", [
    {"taskId": 1}, {"taskId": " ../task "}, {"taskId": "a\n"}, {"taskId": ""},
    {"datasetId": 1}, {"appId": 10350}, {"modelVersion": None}, {"modelVersion": "x" * 65},
    {"cutoffDate": "2026-02-30"}, {"cutoffDate": "2026-1-07"}, {"cutoffDate": "9999-12-31"},
    {"horizonDays": True}, {"horizonDays": "7"}, {"horizonDays": 7.0}, {"horizonDays": 2},
    {"mock": True},
])
def test_invalid_request_is_not_coerced_into_a_valid_prediction(changes):
    with pytest.raises(ImportProblem) as error:
        request(**changes)
    assert error.value.code == "INVALID_REQUEST"


def test_mock_uses_only_known_files_and_latest_observation_without_float_or_zero_filling():
    rows = [
        {"fileKey": "https://host/a", "date": "2026-01-05", "trafficBytes": "10", "appId": "10350"},
        {"fileKey": "https://host/a", "date": "2026-01-07", "trafficBytes": "10000000000000001", "appId": "10350"},
        {"fileKey": "host/a", "date": "2026-01-05", "trafficBytes": "2", "appId": "10350"},
        {"fileKey": "tie-b", "date": "2026-01-07", "trafficBytes": "2", "appId": "10350"},
        {"fileKey": "tie-a", "date": "2026-01-06", "trafficBytes": "2", "appId": "10350"},
        {"fileKey": "future-only", "date": "2026-01-08", "trafficBytes": "999999999999999999", "appId": "10350"},
        {"fileKey": "other-app", "date": "2026-01-07", "trafficBytes": "999", "appId": "10066"},
        {"fileKey": "zero", "date": "2026-01-07", "trafficBytes": "0", "appId": "10350"},
    ]
    items = mock_items(rows, request())
    assert [item["fileKey"] for item in items] == ["https://host/a", "host/a", "tie-a", "tie-b", "zero"]
    assert [item["predictedBytes"] for item in items] == ["70000000000000007", "14", "14", "14", "0"]
    assert [item["rank"] for item in items] == [1, 2, 3, 4, 5]
    assert mock_items(list(reversed(rows)), request()) == items
    assert all(item["score"] is None and "模拟" in item["reason"] for item in items)
    assert all(item[key] is None for item in items for key in ("fileSizeBytes", "recentActualBytes", "compressionStatus", "fileType"))
    daily = mock_items(rows, request(horizonDays=1))
    assert daily[0]["predictedBytes"] == "10000000000000001"


def test_mock_preserves_128_digit_inputs_and_does_not_truncate_candidates():
    rows = [{"fileKey": f"file-{i:03}", "date": "2026-01-07", "trafficBytes": "9" * 128, "appId": "10350"} for i in range(61)]
    items = mock_items(rows, request())
    assert len(items) == 61
    assert items[-1]["rank"] == 61
    assert items[0]["predictedBytes"] == str(int("9" * 128) * 7)
    assert "E" not in items[0]["predictedBytes"]
    assert Decimal(items[0]["predictedBytes"]) > 0


def test_mock_empty_history_does_not_invent_files():
    assert mock_items([], request()) == []


def test_sorting_close_large_decimals_does_not_round_to_a_false_tie():
    rows = [
        {"fileKey": "a", "date": "2026-01-07", "trafficBytes": "10000000000000000000000000001", "appId": "10350"},
        {"fileKey": "z", "date": "2026-01-07", "trafficBytes": "10000000000000000000000000002", "appId": "10350"},
    ]
    assert [item["fileKey"] for item in mock_items(rows, request(horizonDays=1))] == ["z", "a"]
