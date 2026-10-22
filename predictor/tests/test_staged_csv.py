import csv
import json

import pytest

import app.staged_csv as staged_csv
from app.csv_import import ImportConfig
from app.staged_csv import normalize_staged_csv
from test_csv_import import MAPPING, csv_bytes


def config(**changes):
    return ImportConfig(
        mapping=MAPPING,
        datasetType="FILE_DAILY",
        datasetSource="REAL",
        sourceUnit="B",
        **changes,
    )


def read_csv(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def test_staged_normalizer_writes_sorted_observations_and_exact_daily_totals(tmp_path):
    source = tmp_path / "source.csv"
    source.write_bytes(csv_bytes([
        ["b", "2026/1/1", "11", "10066"],
        ["a", "2026/1/1", "10", "10066"],
        ["a", "2026/1/1", "10", "10066"],
    ]))

    result = normalize_staged_csv(source, config(), tmp_path / "work", max_rows=10)

    assert result.row_count == 2
    assert result.app_ids == ("10066",)
    assert not hasattr(result, "rows")
    rows = read_csv(result.normalized_path)
    assert [(row["fileKey"], row["trafficBytes"], row["sourceRowCount"], row["sourceIdentityCount"], row["aggregationRule"]) for row in rows] == [
        ("a", "10", "1", "1", "NONE"),
        ("b", "11", "1", "1", "NONE"),
    ]
    assert all(len(row["sourceIdentityHash"]) == 64 for row in rows)
    assert all("sourceUrl" not in row for row in rows)
    assert read_csv(result.daily_totals_path) == [
        {"appId": "10066", "date": "2026-01-01", "totalTrafficBytes": "21", "fileCount": "2"}
    ]
    quality = json.loads(result.quality_path.read_text(encoding="utf-8"))
    assert result.error is None
    assert quality["encoding"] == "GB18030"
    assert quality["totalRows"] == 3
    assert quality["validRows"] == 2
    assert quality["duplicateRows"] == 1
    assert not list((tmp_path / "work").glob("*.sqlite*"))


def test_staged_normalizer_sums_distinct_raw_identities_after_normalization(tmp_path):
    source = tmp_path / "source.csv"
    source.write_bytes(csv_bytes([
        ["https://one.example.test/path/a%2Fb?token=1", "2026/1/1", "10", "10066"],
        ["https://two.example.test/path/a%2Fb#preview", "2026/1/1", "10", "10066"],
        ["/plain/key", "2026/1/1", "11", "10066"],
    ]))

    result = normalize_staged_csv(source, config(), tmp_path / "work", max_rows=10)
    quality = json.loads(result.quality_path.read_text(encoding="utf-8"))

    assert result.error is None
    rows = read_csv(result.normalized_path)
    assert [(row["fileKey"], row["trafficBytes"], row["sourceRowCount"], row["sourceIdentityCount"], row["aggregationRule"]) for row in rows] == [
        ("path/a%2Fb", "20", "2", "2", "SUM_NORMALIZED_SLICES"),
        ("plain/key", "11", "1", "1", "NONE"),
    ]
    assert quality["fullUrlRows"] == 2
    assert quality["plainKeyRows"] == 1
    assert quality["normalizationCollisionRows"] == 1
    assert quality["duplicateRows"] == 0
    assert quality["aggregatedRows"] == 1
    assert quality["fileKeyParseSuccessRate"] == 1.0


def test_staged_normalizer_reports_invalid_url_without_publishable_success(tmp_path):
    source = tmp_path / "source.csv"
    source.write_bytes(csv_bytes([["https:///missing-host", "2026/1/1", "10", "10066"]]))

    result = normalize_staged_csv(source, config(), tmp_path / "work", max_rows=10)
    quality = json.loads(result.quality_path.read_text(encoding="utf-8"))

    assert result.error["code"] == "INVALID_URL"
    assert quality["invalidUrlRows"] == 1
    assert quality["invalidRows"] == 1
    assert quality["validRows"] == 0


def test_utf8_bom_and_128_digit_integer_are_preserved_exactly(tmp_path):
    source = tmp_path / "source.csv"
    huge = "9" * 128
    source.write_bytes(csv_bytes([["file", "2026-01-02", huge, "10350"]], encoding="utf-8-sig"))

    result = normalize_staged_csv(source, config(), tmp_path / "work", max_rows=1)

    assert result.encoding == "UTF-8-BOM"
    assert read_csv(result.normalized_path)[0]["trafficBytes"] == huge
    assert read_csv(result.daily_totals_path)[0]["totalTrafficBytes"] == huge


@pytest.mark.parametrize(
    ("rows", "code"),
    [
        ([['a', '2026/1/1', '10', '10066'], ['a', '2026/1/1', '11', '10066']], "DUPLICATE_CONFLICT"),
        ([['a', '2026/2/30', '10', '10066']], "INVALID_CSV"),
    ],
)
def test_blocking_row_errors_return_quality_without_publishable_success(tmp_path, rows, code):
    source = tmp_path / "source.csv"
    source.write_bytes(csv_bytes(rows))

    result = normalize_staged_csv(source, config(), tmp_path / "work", max_rows=10)
    quality = json.loads(result.quality_path.read_text(encoding="utf-8"))

    assert result.error["code"] == code
    assert quality["errorSamples"]


def test_row_limit_counts_the_complete_input_and_does_not_truncate_to_success(tmp_path):
    source = tmp_path / "source.csv"
    source.write_bytes(csv_bytes([
        ["a", "2026/1/1", "1", "10066"],
        ["b", "2026/1/1", "2", "10066"],
        ["c", "2026/1/1", "3", "10066"],
    ]))

    result = normalize_staged_csv(source, config(), tmp_path / "work", max_rows=2)
    quality = json.loads(result.quality_path.read_text(encoding="utf-8"))

    assert result.error["code"] == "ROW_LIMIT_EXCEEDED"
    assert quality["totalRows"] == 3
    assert quality["validRows"] == 2


def test_export_storage_failure_removes_incomplete_work_directory(tmp_path, monkeypatch):
    source = tmp_path / "source.csv"
    source.write_bytes(csv_bytes([["a", "2026/1/1", "1", "10066"]]))

    def fail_export(*_args, **_kwargs):
        raise OSError("simulated export failure")

    monkeypatch.setattr(staged_csv, "_write_csv", fail_export)
    work = tmp_path / "work"

    with pytest.raises(OSError, match="simulated export failure"):
        normalize_staged_csv(source, config(), work, max_rows=10)

    assert not work.exists()

