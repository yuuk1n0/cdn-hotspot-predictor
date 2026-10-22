"""Read-only aggregates over one immutable dataset, never zero-fill gaps."""

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
        for row in store.observations_window(dataset_id, app_id, start_date, end_date):
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
        ratio = _hotspot_ratio(store, app_id, start_date, end_date, file_key, ordered, file_bytes, app_days)
        return {
            'datasetId': dataset_id, 'appId': app_id, 'datasetSource': metadata['datasetSource'],
            'startDate': start_date, 'endDate': end_date, 'granularity': granularity, 'scope': 'OBSERVED_FILES',
            'completeness': metadata.get('completeness', 'UNKNOWN'),
            'observedTrafficBytes': format(sum(file_bytes.values(), Decimal(0)), 'f') if file_bytes else None,
            'days': days, 'missingDates': missing,
            'items': [{'rank': rank, 'fileKey': key, 'observedTrafficBytes': format(file_bytes[key], 'f'), 'observedDays': len(file_days[key])}
                      for rank, key in enumerate(ordered, 1)],
            'hotspotTrafficRatio': ratio,
        }


def _hotspot_ratio(store, app_id, start_date, end_date, file_key, ordered, file_bytes, app_days):
    if file_key is not None:
        return {'value': None, 'reasonCode': 'FILE_FILTER_NOT_TOP_K', 'basis': 'HISTORICAL_ACTUAL'}
    if not app_days:
        return {'value': None, 'reasonCode': 'NO_OBSERVATIONS', 'basis': 'HISTORICAL_ACTUAL'}
    total_metadata = store.latest_dataset(app_id, 'APP_DAILY_TOTAL')
    if total_metadata is None:
        return {'value': None, 'reasonCode': 'NO_CONFIRMED_APP_TOTAL', 'basis': 'HISTORICAL_ACTUAL'}
    if total_metadata.get('sourceUnit') != 'GB_DECIMAL':
        return {
            'value': None, 'reasonCode': 'APP_TOTAL_UNIT_UNCONFIRMED', 'basis': 'HISTORICAL_ACTUAL',
            'totalDatasetId': total_metadata['datasetId'], 'appTotalUnit': total_metadata.get('sourceUnit'),
        }
    totals = {}
    units = set()
    with localcontext() as context:
        context.prec = 512
        for row in store.app_daily_totals(total_metadata['datasetId'], app_id, start_date, end_date):
            totals[row['date']] = Decimal(row['totalTrafficBytes'])
            units.add(row['unit'])
    base = {
        'basis': 'HISTORICAL_ACTUAL', 'unit': 'PERCENT',
        'totalDatasetId': total_metadata['datasetId'], 'appTotalUnit': total_metadata.get('sourceUnit'),
        'topK': len(ordered), 'observedFileDays': len(app_days), 'appTotalDays': len(totals),
    }
    if not totals:
        return {'value': None, 'reasonCode': 'NO_APP_TOTAL_IN_WINDOW', **base}
    if units != {'GB_DECIMAL'}:
        return {'value': None, 'reasonCode': 'APP_TOTAL_UNIT_UNCONFIRMED', **base}
    missing_totals = sorted(app_days - totals.keys())
    if missing_totals:
        return {'value': None, 'reasonCode': 'APP_TOTAL_MISSING_FOR_OBSERVED_DAYS',
                'missingTotalDates': missing_totals[:100], **base}
    if missing_file_days := sorted(totals.keys() - app_days):
        return {'value': None, 'reasonCode': 'FILE_OBSERVATION_MISSING_IN_WINDOW',
                'missingFileDates': missing_file_days[:100], **base}
    with localcontext() as context:
        context.prec = 512
        numerator = sum((file_bytes[key] for key in ordered), Decimal(0))
        denominator = sum(totals.values(), Decimal(0))
        base.update({
            'numeratorBytes': format(numerator, 'f'),
            'denominatorBytes': format(denominator, 'f'),
        })
        if denominator <= 0:
            return {'value': None, 'reasonCode': 'NON_POSITIVE_APP_TOTAL', **base}
        if numerator > denominator:
            return {'value': None, 'reasonCode': 'FILE_TOTAL_EXCEEDS_APP_TOTAL', **base}
        value = (numerator * 100 / denominator).quantize(Decimal('0.000001'))
    return {'value': format(value, 'f'), 'reasonCode': None, **base}
