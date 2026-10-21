"""Read-only aggregates over one immutable normalized dataset, never zero-fill gaps."""

import csv
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal, localcontext

from .csv_import import ImportProblem


def observations(store, dataset_id, app_id, start_date, end_date, granularity='DAILY', file_key=None, top_k=50):
    metadata = store.get_dataset(dataset_id)
    if metadata.get('datasetType') != 'FILE_DAILY':
        raise ImportProblem('DATASET_TYPE_UNSUPPORTED', '历史查询只支持文件日观测。')
    if app_id not in metadata.get('appIds', []):
        raise ImportProblem('APP_MISMATCH', '所选数据集不包含该App。')
    try:
        start, end = date.fromisoformat(start_date), date.fromisoformat(end_date)
        valid = start.isoformat() == start_date and end.isoformat() == end_date and 0 <= (end-start).days <= 365
        valid = valid and granularity in {'DAILY', 'WEEKLY'} and (granularity != 'WEEKLY' or (end-start).days == 6)
        if not valid:
            raise ValueError()
    except (ValueError, TypeError):
        raise ImportProblem('INVALID_DATE_RANGE', '日查询最多366天；周查询必须为连续七天，日期含首尾。') from None
    if not isinstance(top_k, int) or isinstance(top_k, bool) or not 1 <= top_k <= 50:
        raise ImportProblem('INVALID_REQUEST', 'topK必须为1至50。')

    app_days = set()
    daily_bytes = defaultdict(Decimal)
    daily_files = defaultdict(set)
    file_bytes = defaultdict(Decimal)
    file_days = defaultdict(set)
    with localcontext() as context:
        # Import caps each integer at 128 digits; another 10 digits covers row summation.
        context.prec = 512
        with (store.datasets / dataset_id / 'normalized.csv').open(encoding='utf-8', newline='') as stream:
            for row in csv.DictReader(stream):
                if row['appId'] != app_id or not start_date <= row['date'] <= end_date:
                    continue
                day, key = row['date'], row['fileKey']
                app_days.add(day)
                if file_key is not None and key != file_key:
                    continue
                value = Decimal(row['trafficBytes'])
                daily_bytes[day] += value
                daily_files[day].add(key)
                file_bytes[key] += value
                file_days[key].add(day)
        days, missing = [], []
        for offset in range((end-start).days+1):
            day = (start+timedelta(days=offset)).isoformat()
            observed = day in daily_bytes
            days.append({'date': day, 'observedTrafficBytes': format(daily_bytes[day], 'f') if observed else None,
                         'fileCount': len(daily_files[day]), 'status': 'OBSERVED' if observed else 'NO_OBSERVATION'})
            if day not in app_days:
                missing.append(day)
        ordered = sorted(file_bytes, key=lambda key: (-file_bytes[key], key))[:top_k]
        return {
            'datasetId': dataset_id, 'appId': app_id, 'datasetSource': metadata['datasetSource'],
            'startDate': start_date, 'endDate': end_date, 'granularity': granularity, 'scope': 'OBSERVED_FILES',
            'completeness': metadata.get('completeness', 'UNKNOWN'),
            'observedTrafficBytes': format(sum(file_bytes.values(), Decimal(0)), 'f') if file_bytes else None,
            'days': days, 'missingDates': missing,
            'items': [{'rank': rank, 'fileKey': key, 'observedTrafficBytes': format(file_bytes[key], 'f'), 'observedDays': len(file_days[key])}
                      for rank, key in enumerate(ordered, 1)],
            'hotspotTrafficRatio': {'value': None, 'reasonCode': 'NO_CONFIRMED_APP_TOTAL'},
        }
