import csv
import json

from app.app_total_csv import normalize_total_staged_csv
from app.csv_import import ImportConfig


def test_decimal_gb_preserves_raw_value_and_fractional_byte_equivalent(tmp_path):
    source = tmp_path / "totals.csv"
    source.write_text(
        "日期,AppId,费用,flux(GB)\n2026-01-05,10350,0,0.33978334069252014\n",
        encoding="utf-8",
    )
    config = ImportConfig(
        mapping={"date": "日期", "appId": "AppId", "totalTraffic": "flux(GB)"},
        datasetType="APP_DAILY_TOTAL",
        datasetSource="REAL",
        sourceUnit="GB_DECIMAL",
        appId="10350",
    )

    result = normalize_total_staged_csv(source, config, tmp_path / "work", max_rows=10)

    with result.normalized_path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    quality = json.loads(result.quality_path.read_text(encoding="utf-8"))
    assert result.error is None
    assert rows == [{
        "appId": "10350",
        "date": "2026-01-05",
        "rawTrafficValue": "0.33978334069252014",
        "totalTrafficBytes": "339783340.69252014000000000",
    }]
    assert quality["sourceUnit"] == "GB_DECIMAL"
