"""Read and validate file-level observations."""

from __future__ import annotations

import csv
from datetime import date
from decimal import Decimal
from pathlib import Path

MAX_TRAFFIC_DIGITS = 128

def _date(raw: str) -> date:
    parts = raw.strip().split("/") if "/" in raw else raw.strip().split("-")
    if len(parts) != 3:
        raise ValueError(f"Invalid date: {raw!r}")
    try:
        parsed = date(*(int(part) for part in parts))
    except ValueError as exc:
        raise ValueError(f"Invalid date: {raw!r}") from exc
    return parsed


def _bytes(raw: str) -> int:
    try:
        value = Decimal(raw.strip())
    except Exception as exc:
        raise ValueError(f"Invalid trafficBytes: {raw!r}") from exc
    if not value.is_finite() or value < 0 or value != value.to_integral_value():
        raise ValueError(f"trafficBytes must be a non-negative integer: {raw!r}")
    if value and value.adjusted() + 1 > MAX_TRAFFIC_DIGITS:
        raise ValueError("trafficBytes exceeds 128 digits")
    return int(value)


def _encoding(path: Path) -> str:
    with path.open("rb") as stream:
        sample = stream.read(65536)
    try:
        sample.decode("utf-8-sig")
        return "utf-8-sig"
    except UnicodeDecodeError:
        sample.decode("gb18030")
        return "gb18030"


def read_observations(path: str | Path, app_id: str, source_unit: str,
                      allow_empty: bool = False) -> dict[date, dict[str, int]]:
    """Read normalized or the two supplied file-level CSV shapes.

    source_unit is mandatory because the 10066 source unit is unconfirmed.
    App daily totals are deliberately rejected: they are not file labels.
    """
    if source_unit != "B":
        raise ValueError("source_unit must be explicitly confirmed as B")
    path = Path(path)
    result: dict[date, dict[str, int]] = {}
    with path.open("r", encoding=_encoding(path), newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames:
            raise ValueError("CSV header is missing")
        aliases = {
            "file": ("fileKey", "文件标识", "url"),
            "date": ("date", "日期"),
            "bytes": ("trafficBytes", "当日流量（B）", "日流量"),
            "app": ("appId", "AppID"),
        }
        columns = {}
        for field, options in aliases.items():
            columns[field] = next((item for item in options if item in reader.fieldnames), None)
        if any(value is None for value in columns.values()):
            raise ValueError("Expected file-level file/date/traffic/AppID columns")
        for line_number, row in enumerate(reader, 2):
            if row[columns["app"]] != app_id:
                continue
            file_key = row[columns["file"]]
            if not file_key:
                raise ValueError(f"Line {line_number}: empty fileKey")
            try:
                day = _date(row[columns["date"]])
                traffic = _bytes(row[columns["bytes"]])
            except (ValueError, TypeError) as exc:
                raise ValueError(f"Line {line_number}: {exc}") from exc
            day_items = result.setdefault(day, {})
            previous = day_items.get(file_key)
            if previous is not None and previous != traffic:
                raise ValueError(f"Line {line_number}: conflicting duplicate observation")
            day_items[file_key] = traffic
    if not result and not allow_empty:
        raise ValueError(f"No observations for AppID {app_id}")
    return result
