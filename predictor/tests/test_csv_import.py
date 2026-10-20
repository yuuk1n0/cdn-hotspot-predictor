import csv
import io

import pytest

from app.csv_import import ImportConfig, parse_csv


MAPPING = {"fileKey": "文件标识", "date": "日期", "trafficBytes": "当日流量（B）", "appId": "AppID"}


def config(**changes):
    return ImportConfig(mapping=MAPPING, datasetType="FILE_DAILY", datasetSource="REAL", sourceUnit="B", **changes)


def csv_bytes(rows, encoding="gb18030"):
    stream = io.StringIO(newline="")
    writer = csv.writer(stream)
    writer.writerow(MAPPING.values())
    writer.writerows(rows)
    return stream.getvalue().encode(encoding)


def test_decimal_encoding_missing_days_and_opaque_file_identity():
    result = parse_csv(csv_bytes([
        ["https://host/a", "2026/1/5", "1.0000000000000001E16", "10350"],
        ["host/a", "2026/1/7", "2E3", "10350"],
        ["https://host/a", "2026/1/5", "10000000000000001", "10350"],
    ]), config())
    assert result.error is None
    assert result.rows == [
        {"fileKey": "https://host/a", "date": "2026-01-05", "trafficBytes": "10000000000000001", "appId": "10350"},
        {"fileKey": "host/a", "date": "2026-01-07", "trafficBytes": "2000", "appId": "10350"},
    ]
    quality = result.quality
    assert quality["encoding"] == "GB18030"
    assert quality["totalRows"] == 3
    assert quality["validRows"] == 2
    assert quality["duplicateRows"] == 1
    assert quality["conflictRows"] == quality["invalidRows"] == 0
    assert quality["dateRange"] == {"start": "2026-01-05", "end": "2026-01-07"}
    assert quality["missingDates"] == [{"appId": "10350", "date": "2026-01-06"}]
    assert quality["unitConfirmed"] is True
    assert quality["completeness"] == "UNKNOWN"
    assert quality["identityMappingVersion"] == "opaque-v1"
    assert quality["warnings"]


@pytest.mark.parametrize("traffic", ["-1", "NaN", "Infinity", "0.5", "", "abc", "1e999999"])
def test_invalid_traffic_blocks_the_whole_dataset(traffic):
    result = parse_csv(csv_bytes([["a", "2026/1/5", traffic, "10350"]]), config())
    assert result.error["code"] == "INVALID_CSV"
    assert result.quality["invalidRows"] == 1


def test_conflicting_observations_fail_instead_of_summing_or_replacing():
    result = parse_csv(csv_bytes([
        ["a", "2026/1/5", "100", "10350"],
        ["a", "2026/1/5", "200", "10350"],
    ]), config())
    assert result.error["code"] == "DUPLICATE_CONFLICT"
    assert result.quality["conflictRows"] == 1


@pytest.mark.parametrize("row", [
    ["", "2026/1/5", "100", "10350"],
    ["a", "2026/2/30", "100", "10350"],
    ["a", "2026/1/5", "100", ""],
    ["a", "2026/1/5", "100", "10350", "extra"],
])
def test_invalid_date_empty_identity_app_or_row_shape_is_blocking(row):
    result = parse_csv(csv_bytes([row]), config())
    assert result.error["code"] == "INVALID_CSV"
    assert result.quality["invalidRows"] == 1


def test_wrong_app_is_rejected_and_never_inferred_from_filename():
    result = parse_csv(csv_bytes([["a", "2026/1/5", "100", "10350"]]), config(appId="10066"))
    assert result.error["code"] == "APP_MISMATCH"


def test_utf8_bom_and_default_app_without_an_app_column():
    settings = ImportConfig(mapping={**MAPPING, "appId": ""}, datasetType="FILE_DAILY", datasetSource="FAKE", sourceUnit="B", appId="fixture")
    result = parse_csv("文件标识,日期,当日流量（B）\na,2026-01-05,100\n".encode("utf-8-sig"), settings)
    assert result.error is None
    assert result.quality["encoding"] == "UTF-8-BOM"
    assert result.rows[0]["appId"] == "fixture"


def test_header_only_bad_encoding_and_duplicate_headers_do_not_create_empty_success():
    for raw in [csv_bytes([]), b"\xff", "文件标识,日期,当日流量（B）,AppID,AppID\n".encode()]:
        result = parse_csv(raw, config())
        assert result.error["code"] == "INVALID_CSV"


def test_row_limit_fails_without_silent_truncation():
    result = parse_csv(csv_bytes([["a", "2026/1/5", "100", "10350"], ["b", "2026/1/5", "200", "10350"]]), config(), max_rows=1)
    assert result.error["code"] == "ROW_LIMIT_EXCEEDED"
    assert result.quality["totalRows"] == 2
