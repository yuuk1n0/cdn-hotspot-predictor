import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.csv_import import ImportConfig
from test_csv_import import MAPPING, csv_bytes


@pytest.fixture
def history_client(tmp_path):
    app = create_app(tmp_path)
    store = app.state.imports
    store.initialize()
    task = store.accept(csv_bytes([
        ['same', '2026/1/1', '10000000000000001', 'A'],
        ['other', '2026/1/1', '9', 'A'],
        ['same', '2026/1/3', '0', 'A'],
        ['other', '2026/1/3', '11', 'A'],
        ['same', '2026/1/1', '999', 'B'],
    ]), ImportConfig(MAPPING, 'FILE_DAILY', 'FAKE', 'B'), 'history-fixture', 'test.csv')
    store.process_next()
    dataset = store.get_import(task['importTaskId'])['datasetId']
    with TestClient(app) as client:
        yield client, dataset


def observations(client, dataset, **params):
    return client.get(f'/api/v1/datasets/{dataset}/observations', params={'appId': 'A', 'startDate': '2026-01-01', 'endDate': '2026-01-04', **params})


def test_exact_aggregation_app_isolation_missing_days_and_zero(history_client):
    client, dataset = history_client
    response = observations(client, dataset)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['observedTrafficBytes'] == '10000000000000021'
    assert body['scope'] == 'OBSERVED_FILES'
    assert body['days'] == [
        {'date': '2026-01-01', 'observedTrafficBytes': '10000000000000010', 'fileCount': 2, 'status': 'OBSERVED'},
        {'date': '2026-01-02', 'observedTrafficBytes': None, 'fileCount': 0, 'status': 'NO_OBSERVATION'},
        {'date': '2026-01-03', 'observedTrafficBytes': '11', 'fileCount': 2, 'status': 'OBSERVED'},
        {'date': '2026-01-04', 'observedTrafficBytes': None, 'fileCount': 0, 'status': 'NO_OBSERVATION'},
    ]
    assert body['items'][0] == {'rank': 1, 'fileKey': 'same', 'observedTrafficBytes': '10000000000000001', 'observedDays': 2}
    assert body['missingDates'] == ['2026-01-02', '2026-01-04']
    assert body['hotspotTrafficRatio']['value'] is None
    assert body['datasetSource'] == 'FAKE'


def test_precise_file_filter_retains_app_missing_date_semantics(history_client):
    client, dataset = history_client
    body = observations(client, dataset, fileKey='same').json()
    assert body['days'][2]['observedTrafficBytes'] == '0'
    assert body['days'][2]['status'] == 'OBSERVED'
    empty = observations(client, dataset, fileKey='unknown').json()
    assert empty['observedTrafficBytes'] is None
    assert empty['items'] == []
    assert empty['missingDates'] == ['2026-01-02', '2026-01-04']
    assert all(day['observedTrafficBytes'] is None for day in empty['days'])


def test_weekly_is_seven_inclusive_days_and_ranks_cumulative_file_totals(history_client):
    client, dataset = history_client
    body = observations(client, dataset, endDate='2026-01-07', granularity='WEEKLY').json()
    assert len(body['days']) == 7
    assert body['items'][1]['observedTrafficBytes'] == '20'
    assert observations(client, dataset, granularity='WEEKLY').status_code == 422


@pytest.mark.parametrize('params', [
    {'appId': 'wrong'}, {'startDate': '2026-02-30'}, {'endDate': '2025-12-31'},
    {'endDate': '2027-01-02'}, {'granularity': 'MONTHLY'}, {'topK': 51},
])
def test_invalid_history_filters_are_rejected(history_client, params):
    client, dataset = history_client
    assert observations(client, dataset, **params).status_code == 422


def test_missing_dataset_is_not_empty_success(history_client):
    client, _ = history_client
    assert observations(client, 'ds_unknown').status_code == 404
