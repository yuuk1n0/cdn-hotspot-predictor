"""Local integration against running services; deliberately labelled FAKE input."""
import csv
import io
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ROWS = [
    ['a&b?#c', '2026-01-01', '10000000000000001', 'A'],
    ['a&b?#c', '2026-01-02', '0', 'A'],
    ['b.mp4', '2026-01-01', '20', 'A'],
    ['b.mp4', '2026-01-04', '40', 'A'],
    ['a&b?#c', '2026-01-01', '900', 'B'],
    ['z.png', '2026-01-07', '7', 'B'],
]
stream = io.StringIO(newline='')
writer = csv.writer(stream)
writer.writerow(['fileKey', 'date', 'trafficBytes', 'appId'])
writer.writerows(ROWS)
DATA = stream.getvalue().encode('utf-8')
(ROOT / '20261021_双App历史验收FAKE_v01.csv').write_bytes(DATA)


def request(url, body=None, headers=None):
    req = urllib.request.Request(url, data=body, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def upload():
    boundary = 'cdn-history-acceptance-v01'
    fields = {'mapping': json.dumps({'fileKey': 'fileKey', 'date': 'date', 'trafficBytes': 'trafficBytes', 'appId': 'appId'}),
              'datasetType': 'FILE_DAILY', 'datasetSource': 'FAKE', 'sourceUnit': 'B'}
    body = bytearray()
    for key, value in fields.items():
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="history-acceptance-FAKE.csv"\r\nContent-Type: text/csv\r\n\r\n'.encode())
    body.extend(DATA)
    body.extend(f'\r\n--{boundary}--\r\n'.encode())
    status, receipt = request('http://localhost:5173/data-api/imports', bytes(body), {'Content-Type': f'multipart/form-data; boundary={boundary}', 'Idempotency-Key': 'history-acceptance-20261021-v01'})
    assert status == 202, (status, receipt)
    for _ in range(50):
        status, task = request('http://localhost:5173/data-api/imports/' + receipt['importTaskId'])
        assert status == 200, task
        if task['status'] in ['SUCCEEDED', 'FAILED']:
            assert task['status'] == 'SUCCEEDED', task
            return receipt, task
        time.sleep(.2)
    raise AssertionError('Import did not finish')


receipt, task = upload()
dataset = task['datasetId']
status, metadata = request('http://localhost:5173/data-api/datasets/' + dataset)
assert status == 200 and metadata['datasetSource'] == 'FAKE' and metadata['appIds'] == ['A', 'B'], metadata
params = dict(appId='A', startDate='2026-01-01', endDate='2026-01-07', granularity='WEEKLY', topK=50)
checks = []


def compare(name, p):
    query = urllib.parse.urlencode(p)
    python_status, python = request(f'http://127.0.0.1:8000/api/v1/datasets/{dataset}/observations?{query}')
    java_status, java = request('http://127.0.0.1:8080/api/v1/history?' + urllib.parse.urlencode({'datasetId': dataset, **p}))
    vite_status, vite = request('http://localhost:5173/api/history?' + urllib.parse.urlencode({'datasetId': dataset, **p}), headers={'Origin': 'http://localhost:5173'})
    assert python_status == java_status == vite_status == 200, (python, java, vite)
    assert python == java == vite, name
    checks.append({'name': name, 'status': 'PASSED', 'response': vite})
    return vite


a = compare('weekly_A_exact_large_bytes_and_missing_days', params)
assert a['observedTrafficBytes'] == '10000000000000061'
assert a['days'][0]['observedTrafficBytes'] == '10000000000000021'
assert a['days'][1] == {'date': '2026-01-02', 'observedTrafficBytes': '0', 'fileCount': 1, 'status': 'OBSERVED'}
assert a['days'][2]['observedTrafficBytes'] is None and a['days'][2]['status'] == 'NO_OBSERVATION'
assert a['missingDates'] == ['2026-01-03', '2026-01-05', '2026-01-06', '2026-01-07']
assert [(x['fileKey'], x['observedTrafficBytes']) for x in a['items']] == [('a&b?#c', '10000000000000001'), ('b.mp4', '60')]
assert a['hotspotTrafficRatio']['value'] is None
b = compare('weekly_B_same_key_isolation', {**params, 'appId': 'B'})
assert b['observedTrafficBytes'] == '907' and b['items'][0]['observedTrafficBytes'] == '900'
single = compare('exact_special_fileKey_and_file_vs_App_gaps', {**params, 'fileKey': 'a&b?#c'})
assert single['observedTrafficBytes'] == '10000000000000001' and len(single['items']) == 1
assert single['days'][3]['observedTrafficBytes'] is None and '2026-01-04' not in single['missingDates']
day = compare('single_day_not_week_average', {**params, 'granularity': 'DAILY', 'endDate': '2026-01-01'})
assert day['observedTrafficBytes'] == '10000000000000021'
assert len(compare('topK_reaches_backend', {**params, 'topK': 1})['items']) == 1
empty = compare('outside_observed_range_is_null', {**params, 'granularity': 'DAILY', 'startDate': '2026-01-08', 'endDate': '2026-01-08'})
assert empty['observedTrafficBytes'] is None and empty['items'] == []
for name, patch, code in [('wrong_App', {'appId': 'C'}, 'APP_MISMATCH'), ('invalid_week', {'endDate': '2026-01-06'}, 'INVALID_DATE_RANGE')]:
    status, error = request('http://localhost:5173/api/history?' + urllib.parse.urlencode({'datasetId': dataset, **params, **patch}))
    assert status == 422 and error.get('code', error.get('error', {}).get('code')) == code, error
    checks.append({'name': name, 'status': 'PASSED', 'httpStatus': status, 'response': error})

status, listing = request('http://localhost:5173/data-api/datasets?pageSize=20')
real = next(item for item in listing['items'] if item['datasetSource'] == 'REAL' and '10350' in item['appIds'])
real_params = {'appId': '10350', 'startDate': '2026-10-07', 'endDate': '2026-10-13', 'granularity': 'WEEKLY', 'topK': 50}
query = urllib.parse.urlencode(real_params)
sp, rp = request(f'http://127.0.0.1:8000/api/v1/datasets/{real["datasetId"]}/observations?{query}')
sj, rj = request('http://localhost:5173/api/history?' + urllib.parse.urlencode({'datasetId': real['datasetId'], **real_params}))
assert sp == sj == 200 and rp == rj and rj['datasetSource'] == 'REAL'
checks.append({'name': 'existing_REAL_10350_read_only', 'status': 'PASSED', 'response': rj})
report = {'status': 'PASSED', 'importReceipt': receipt, 'importTask': task, 'metadata': metadata, 'checks': checks,
          'note': '实际本地HTTP链路；FAKE输入仅用于手算验证。REAL快照只读，未发预测任务或数据库写入。'}
(ROOT / '20261021_历史接口结果_v01.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'status': 'PASSED', 'datasetId': dataset, 'checks': len(checks)}, ensure_ascii=False))
