import csv
import io

import pytest

from app.csv_import import ImportConfig, ImportProblem, normalize_file_identity, parse_csv


MAPPING = {"fileKey": "文件标识", "date": "日期", "trafficBytes": "当日流量（B）", "appId": "AppID"}


def config(**changes):
    return ImportConfig(mapping=MAPPING, datasetType="FILE_DAILY", datasetSource="REAL", sourceUnit="B", **changes)


def csv_bytes(rows, encoding="gb18030"):
    stream = io.StringIO(newline="")
    writer = csv.writer(stream)
    writer.writerow(MAPPING.values())
    writer.writerows(rows)
    return stream.getvalue().encode(encoding)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (
            " https://easynote-store-bs.seewo.com/pro-ali-pri/encloud-162193381711225aa6202?token=xxx#preview ",
            {
                "fileKey": "pro-ali-pri/encloud-162193381711225aa6202",
                "sourceUrl": "https://easynote-store-bs.seewo.com/pro-ali-pri/encloud-162193381711225aa6202?token=xxx#preview",
                "parseStatus": "URL_PATH",
            },
        ),
        (
            "HTTP://cdn.example.test/a%2Fb?q=1",
            {
                "fileKey": "a%2Fb",
                "sourceUrl": "HTTP://cdn.example.test/a%2Fb?q=1",
                "parseStatus": "URL_PATH",
            },
        ),
        ("  ///plain/key  ", {"fileKey": "plain/key", "sourceUrl": None, "parseStatus": "PLAIN_KEY"}),
    ],
)
def test_normalize_file_identity_returns_stable_business_key(raw, expected):
    assert normalize_file_identity(raw) == expected


@pytest.mark.parametrize(
    ("raw", "code"),
    [
        ("", "EMPTY_FILE_KEY"),
        ("///", "EMPTY_FILE_KEY"),
        ("https://cdn.example.test", "INVALID_URL"),
        ("https:///missing-host", "INVALID_URL"),
        ("https://cdn.example.test:bad/path", "INVALID_URL"),
        ("https://cdn.example.test/a\nb", "INVALID_URL"),
        ("plain\x00key", "INVALID_FILE_KEY"),
    ],
)
def test_normalize_file_identity_rejects_empty_or_malformed_values(raw, code):
    with pytest.raises(ImportProblem) as captured:
        normalize_file_identity(raw)
    assert captured.value.code == code


def test_decimal_encoding_missing_days_and_normalized_file_identity():
    result = parse_csv(csv_bytes([
        ["https://host/a", "2026/1/5", "1.0000000000000001E16", "10350"],
        ["host/a", "2026/1/7", "2E3", "10350"],
        ["https://host/a", "2026/1/5", "10000000000000001", "10350"],
    ]), config())
    assert result.error is None
    assert result.rows == [
        {
            "fileKey": "a", "date": "2026-01-05", "trafficBytes": "10000000000000001",
            "appId": "10350", "sourceUrl": "https://host/a", "parseStatus": "URL_PATH",
        },
        {
            "fileKey": "host/a", "date": "2026-01-07", "trafficBytes": "2000",
            "appId": "10350", "sourceUrl": None, "parseStatus": "PLAIN_KEY",
        },
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
    assert quality["identityMappingVersion"] == "filekey-v1"
    assert quality["parserVersion"] == "file-daily-v3"
    assert quality["fileIdentityStatus"] == "NORMALIZED"
    assert quality["fullUrlRows"] == 2
    assert quality["plainKeyRows"] == 1
    assert quality["fileKeyParsedRows"] == 3
    assert quality["fileKeyParseSuccessRate"] == 1.0
    assert quality["warnings"]


def test_normalized_collision_is_visible_before_same_traffic_deduplication():
    result = parse_csv(csv_bytes([
        ["https://one.example.test/path/file?token=1", "2026/1/5", "100", "10350"],
        ["https://two.example.test/path/file?token=2", "2026/1/5", "100", "10350"],
    ]), config())
    assert result.error is None
    assert result.quality["validRows"] == 1
    assert result.quality["duplicateRows"] == 1
    assert result.quality["normalizationCollisionRows"] == 1
    assert result.rows[0]["fileKey"] == "path/file"


def test_normalized_collision_with_different_traffic_is_blocking():
    result = parse_csv(csv_bytes([
        ["https://one.example.test/path/file", "2026/1/5", "100", "10350"],
        ["https://two.example.test/path/file", "2026/1/5", "200", "10350"],
    ]), config())
    assert result.error["code"] == "DUPLICATE_CONFLICT"
    assert result.quality["conflictRows"] == 1
    assert result.quality["normalizationCollisionRows"] == 1


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
    ["a", "2026/2/30", "100", "10350"],
    ["a", "2026/1/5", "100", ""],
    ["a", "2026/1/5", "100", "10350", "extra"],
])
def test_invalid_date_empty_identity_app_or_row_shape_is_blocking(row):
    result = parse_csv(csv_bytes([row]), config())
    assert result.error["code"] == "INVALID_CSV"
    assert result.quality["invalidRows"] == 1


def test_empty_identity_is_classified_and_blocks_the_dataset():
    result = parse_csv(csv_bytes([["", "2026/1/5", "100", "10350"]]), config())
    assert result.error["code"] == "EMPTY_FILE_KEY"
    assert result.quality["emptyFileKeyRows"] == 1
    assert result.quality["invalidRows"] == 1
    assert result.quality["fileKeyParseSuccessRate"] == 0.0


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
