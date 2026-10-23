"""Read-only deployed evidence, using isolated historical report fixtures."""

import asyncio
import json
from datetime import date, datetime, timedelta, timezone

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


REPORT = {
    "artifactVersion": 1, "appId": "10350", "horizonDays": 1,
    "modelVersion": "hotspot-simple-v1", "selectedMethod": "last_day",
    "selectedSpec": {"method": "last_day", "window": 1, "halfLife": None},
    "testStartDate": "2026-08-03", "testEndDate": "2026-10-12", "testCutoffCount": 71,
    "testMean": {"recallAt50": 0.71, "ndcgAt50": 0.76,
                 "observedTrafficCoverageAt50": 0.90, "candidateCoverageAt50": 0.75},
    "metricScope": "observed-future-proxy; completeness not established",
}


class ASGIClient:
    """Read-only requests need no import/prediction workers or cross-thread portal."""

    def __init__(self, app):
        self.app = app

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def get(self, path, **kwargs):
        async def request():
            async with AsyncClient(transport=ASGITransport(app=self.app), base_url="http://test") as client:
                return await client.get(path, **kwargs)
        return asyncio.run(request())


def client_for(tmp_path, mode="MODEL", version="hotspot-simple-v1"):
    return ASGIClient(create_app(tmp_path / "runtime", prediction_mode=mode,
                                 prediction_model_version=version))


def query(client, app_id="10350", horizon=1):
    return client.get(f"/api/v1/models/{app_id}/metrics", params={"horizonDays": horizon})


def test_old_report_is_proxy_evidence_with_original_dates_not_daily_accuracy(tmp_path):
    # Omitting the route (or treating testMean as formal/current metrics) breaks this contract.
    with client_for(tmp_path) as client:
        response = query(client)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["appId"] == "10350" and body["horizonDays"] == 1
    assert body["deployedMode"] == "MODEL"
    assert body["availableModels"] == [{"modelVersion": "hotspot-simple-v1", "predictionSource": "MODEL"}]
    assert body["metrics"] is None
    assert body["proxyMetrics"]["ndcgAt50"] == 0.764299016325421
    assert body["metricBasis"] == "OBSERVED_FUTURE_PROXY_LOG1P"
    assert body["metricSource"] == "DEPLOYED_CONFIG_TEST_REPORT"
    assert body["labelBasis"] == "UNCONFIRMED"
    assert body["formalSelectionEligible"] is False
    assert {"SINGLE_DEPLOYED_MODEL", "UNCONFIRMED_LABELS", "NO_DAILY_BACKTEST"} <= set(body["reasonCodes"])
    assert body["evidenceStartDate"] == "2026-08-03"
    assert body["evidenceEndDate"] == "2026-10-12"
    report = body["report"]
    assert report["source"] == "DEPLOYED_CONFIG_TEST_REPORT"
    assert report["freshness"] == "HISTORICAL_REPORT"
    assert report["generatedByThisRequest"] is False
    assert report["observationStartDate"] == "2026-08-04"
    assert report["observationEndDate"] == "2026-10-13"
    queried = datetime.fromisoformat(body["queriedAt"].replace("Z", "+00:00"))
    assert abs((datetime.now(timezone.utc) - queried).total_seconds()) < 30
    assert report["ageDays"] == max(0, (queried.date() - date(2026, 10, 13)).days)
    assert "log1p" in body["metricDefinitions"]["ndcgAt50"]


@pytest.mark.parametrize("app_id,horizon,method,start,end", [
    ("10066", 1, "last_day", "2026-03-14", "2026-04-14"),
    ("10066", 7, "decay_3_half_0_5", "2026-03-10", "2026-04-08"),
    ("10350", 7, "last_day", "2026-08-12", "2026-10-06"),
])
def test_reports_stay_in_requested_app_and_horizon(tmp_path, app_id, horizon, method, start, end):
    with client_for(tmp_path) as client:
        response = query(client, app_id, horizon)
    assert response.status_code == 200, response.text
    body = response.json()
    assert (body["appId"], body["horizonDays"]) == (app_id, horizon)
    assert body["selectedMethod"] == method
    assert (body["evidenceStartDate"], body["evidenceEndDate"]) == (start, end)
    assert body["report"]["observationEndDate"] == (date.fromisoformat(end) + timedelta(days=horizon)).isoformat()


def test_mock_uses_live_store_execution_and_never_exposes_real_report(tmp_path):
    app = create_app(tmp_path, prediction_mode="MODEL", prediction_model_version="hotspot-simple-v1")
    with ASGIClient(app) as client:
        app.state.predictions.execution.update(mode="MOCK", modelVersion="local-demo-v2")
        body = query(client).json()
    assert body["deployedMode"] == "MOCK"
    assert body["availableModels"] == [{"modelVersion": "local-demo-v2", "predictionSource": "MOCK"}]
    assert body["metrics"] is None and body["proxyMetrics"] is None and body["report"] is None
    assert body["metricSource"] == "NONE"
    assert "MOCK_EVIDENCE_ONLY" in body["reasonCodes"]


def test_unknown_app_has_no_report_without_selecting_a_filesystem_path(tmp_path):
    with client_for(tmp_path) as client:
        body = query(client, "new-app").json()
    assert body["metrics"] is None and body["proxyMetrics"] is None
    assert "NO_REVIEWED_CONFIG" in body["reasonCodes"]
    assert body["evidenceEndDate"] is None


def test_unsupported_model_is_not_declared_available_from_source_presence(tmp_path):
    with client_for(tmp_path, version="not-deployed-v2") as client:
        body = query(client).json()
    assert body["availableModels"] == []
    assert body["deployedModelVersion"] == "not-deployed-v2"
    assert body["proxyMetrics"] is None
    assert "MODEL_VERSION_UNSUPPORTED" in body["reasonCodes"]


@pytest.mark.parametrize("app_id", ["..%2F..%2Fetc", "%2Ftmp%2Freport", "%252Fetc", "a%5Cb", "a%00b", "a%20b"])
def test_path_shaped_app_id_cannot_select_any_report(tmp_path, app_id):
    with client_for(tmp_path) as client:
        response = query(client, app_id)
    assert response.status_code in (404, 422)


@pytest.mark.parametrize("horizon", [0, 2, 8, "../1", "1.0"])
def test_only_exact_daily_or_weekly_horizon_is_accepted(tmp_path, horizon):
    with client_for(tmp_path) as client:
        assert query(client, horizon=horizon).status_code == 422


@pytest.fixture
def reports(tmp_path, monkeypatch):
    from app import model_evidence
    config = tmp_path / "configs"
    config.mkdir()
    monkeypatch.setattr(model_evidence, "CONFIG_ROOT", config)
    return config


def test_missing_reviewed_report_is_unknown_not_zero(tmp_path, reports):
    with client_for(tmp_path) as client:
        body = query(client).json()
    assert body["metrics"] is None and body["proxyMetrics"] is None
    assert "MODEL_CONFIG_MISSING" in body["reasonCodes"]


def test_missing_test_metrics_retains_period_but_does_not_invent_scores(tmp_path, reports):
    artifact = {**REPORT, "testMean": None}
    (reports / "10350-h1.json").write_text(json.dumps(artifact))
    with client_for(tmp_path) as client:
        body = query(client).json()
    assert body["metrics"] is None and body["proxyMetrics"] is None
    assert "NO_EVALUATION_REPORT" in body["reasonCodes"]
    assert body["evidenceEndDate"] == "2026-10-12"


def test_config_without_any_evaluation_metadata_is_unknown(tmp_path, reports):
    artifact = {key: value for key, value in REPORT.items()
                if key not in {"testMean", "testStartDate", "testEndDate", "testCutoffCount"}}
    (reports / "10350-h1.json").write_text(json.dumps(artifact))
    with client_for(tmp_path) as client:
        body = query(client).json()
    assert body["metrics"] is None and body["proxyMetrics"] is None and body["report"] is None
    assert "NO_EVALUATION_REPORT" in body["reasonCodes"]


def test_reading_model_evidence_never_creates_runtime_or_prediction_files(tmp_path):
    with client_for(tmp_path) as client:
        assert query(client).status_code == 200
    assert not (tmp_path / "runtime").exists()


def test_artifact_replaced_during_report_read_is_not_returned(tmp_path, monkeypatch):
    from app import model_evidence
    fingerprints = iter([model_evidence.LOADED_ALGORITHM_FINGERPRINT, "replaced-during-read"])
    monkeypatch.setattr(model_evidence, "algorithm_fingerprint", lambda: next(fingerprints))
    with client_for(tmp_path) as client:
        response = query(client)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_ARTIFACT_CHANGED"


@pytest.mark.parametrize("patch", [
    {"appId": "10066"}, {"horizonDays": 7}, {"modelVersion": "other"},
    {"testEndDate": "bad-date"}, {"testStartDate": "2026-10-14"},
    {"testMean": {"ndcgAt50": float("nan")}},
])
def test_mismatched_or_invalid_artifacts_fail_closed(tmp_path, reports, patch):
    (reports / "10350-h1.json").write_text(json.dumps({**REPORT, **patch}))
    with client_for(tmp_path) as client:
        response = query(client)
    assert response.status_code == 503, response.text
    assert response.json()["error"]["code"] == "MODEL_CONFIG_INVALID"


def test_artifact_change_after_startup_is_detected_on_next_request(tmp_path, monkeypatch):
    from app import model_evidence
    with client_for(tmp_path) as client:
        assert query(client).status_code == 200
        monkeypatch.setattr(model_evidence, "algorithm_fingerprint", lambda: "changed")
        response = query(client)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_ARTIFACT_CHANGED"
