"""Disk-backed validation for the independent App daily-total traffic CSV."""

import csv
import json
import os
import shutil
import sqlite3
from collections import Counter
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path

from .csv_import import ImportConfig, ImportProblem, normalize_decimal_gb, parse_date
from .staged_csv import _detect_encoding, _remove_sqlite_files, _write_csv, _write_json


@dataclass(frozen=True)
class StagedTotalResult:
    encoding: str | None
    row_count: int
    app_ids: tuple[str, ...]
    app_date_ranges: dict
    quality_path: Path
    normalized_path: Path
    daily_totals_path: Path
    error: dict | None


def _new_quality() -> dict:
    return {
        "encoding": None,
        "totalRows": 0,
        "validRows": 0,
        "duplicateRows": 0,
        "conflictRows": 0,
        "invalidRows": 0,
        "dateRange": None,
        "appDateRanges": {},
        "missingDates": [],
        "missingDateCount": 0,
        "missingDatesTruncated": False,
        "dailyRows": [],
        "warnings": [],
        "sourceUnit": "GB_DECIMAL",
        "unitConfirmed": True,
        "unitConfirmationSource": "UPLOAD_DECLARATION",
        "completeness": "UNKNOWN",
        "parserVersion": "app-daily-total-v1",
        "errorSamples": [],
    }


def _finalize(quality: dict, days_by_app: dict[str, set], daily_rows: Counter) -> None:
    quality["dailyRows"] = [
        {"appId": app, "date": day, "rowCount": count}
        for (app, day), count in sorted(daily_rows.items())
    ]
    if days_by_app:
        all_days = set().union(*days_by_app.values())
        quality["dateRange"] = {"start": min(all_days).isoformat(), "end": max(all_days).isoformat()}
    for app_id, days in sorted(days_by_app.items()):
        start, end = min(days), max(days)
        quality["appDateRanges"][app_id] = {"start": start.isoformat(), "end": end.isoformat()}
        quality["missingDateCount"] += (end - start).days + 1 - len(days)
        day = start
        while day < end and len(quality["missingDates"]) < 1000:
            if day not in days:
                quality["missingDates"].append({"appId": app_id, "date": day.isoformat()})
            day += timedelta(days=1)
    quality["missingDatesTruncated"] = quality["missingDateCount"] > len(quality["missingDates"])
    quality["warnings"].extend([
        "总流量为独立的 App 全量分母，不等于导入文件观测流量之和。",
        "flux(GB) 按用户声明使用十进制 GB（1 GB = 10^9 B）换算；缺日不补零。",
    ])
    if quality["missingDateCount"]:
        quality["warnings"].append("存在缺失日期，热点占比在这些窗口内不可计算。")


def normalize_total_staged_csv(
    source: Path,
    config: ImportConfig,
    work_dir: Path,
    max_rows: int,
) -> StagedTotalResult:
    source, work_dir = Path(source), Path(work_dir)
    if config.datasetType != "APP_DAILY_TOTAL":
        raise ValueError("总流量规范化器只接受 APP_DAILY_TOTAL。")
    if max_rows <= 0:
        raise ValueError("数据行上限必须大于零。")
    work_dir.mkdir(parents=True)
    database_path = work_dir / "total-normalization.sqlite"
    connection = None
    quality = _new_quality()
    days_by_app: dict[str, set] = {}
    daily_rows = Counter()
    first_error = None

    def record_error(code: str, message: str, row_number: int | None) -> None:
        nonlocal first_error
        first_error = first_error or {"code": code, "message": message}
        if len(quality["errorSamples"]) < 20:
            quality["errorSamples"].append({"row": row_number, "code": code, "message": message})

    try:
        connection = sqlite3.connect(database_path)
        connection.execute("""
            CREATE TABLE total(
              app_id TEXT NOT NULL,
              observed_date TEXT NOT NULL,
              raw_traffic_value TEXT NOT NULL,
              total_bytes TEXT NOT NULL,
              PRIMARY KEY(app_id, observed_date)
            )
        """)
        try:
            codec, quality["encoding"] = _detect_encoding(source)
            with source.open("r", encoding=codec, newline="") as stream, connection:
                reader = csv.DictReader(stream, strict=True)
                headers = reader.fieldnames
                columns = [column for column in config.mapping.values() if column]
                if not headers or len(headers) != len(set(headers)) or not set(columns).issubset(headers):
                    raise ImportProblem("INVALID_CSV", "CSV 表头缺失、重复，或与 mapping 不匹配。")
                for row_number, row in enumerate(reader, start=2):
                    quality["totalRows"] += 1
                    if quality["totalRows"] > max_rows:
                        record_error("ROW_LIMIT_EXCEEDED", f"数据行超过 {max_rows} 行限制。", row_number)
                        continue
                    try:
                        if None in row or any(value is None for value in row.values()):
                            raise ImportProblem("INVALID_CSV", "CSV 行列数与表头不一致。")
                        app_id = row[config.mapping["appId"]].strip() if config.mapping.get("appId") else config.appId
                        if not app_id:
                            raise ImportProblem("INVALID_CSV", "App ID 不能为空。")
                        if config.appId is not None and config.appId != app_id:
                            raise ImportProblem("APP_MISMATCH", "行 App ID 与提交的默认 appId 不一致。")
                        observed = parse_date(row[config.mapping["date"]])
                        observed_date = observed.isoformat()
                        raw_value, total_bytes = normalize_decimal_gb(row[config.mapping["totalTraffic"]])
                        try:
                            connection.execute(
                                "INSERT INTO total(app_id,observed_date,raw_traffic_value,total_bytes) VALUES(?,?,?,?)",
                                (app_id, observed_date, raw_value, total_bytes),
                            )
                        except sqlite3.IntegrityError:
                            existing = connection.execute(
                                "SELECT raw_traffic_value,total_bytes FROM total WHERE app_id=? AND observed_date=?",
                                (app_id, observed_date),
                            ).fetchone()
                            if existing and existing == (raw_value, total_bytes):
                                quality["duplicateRows"] += 1
                            else:
                                quality["conflictRows"] += 1
                                record_error(
                                    "DUPLICATE_CONFLICT",
                                    "同 App、日期存在不同总流量值。",
                                    row_number,
                                )
                            continue
                        days_by_app.setdefault(app_id, set()).add(observed)
                        daily_rows[(app_id, observed_date)] += 1
                    except ImportProblem as error:
                        quality["invalidRows"] += 1
                        record_error(error.code, error.message, row_number)
                if not quality["totalRows"]:
                    raise ImportProblem("INVALID_CSV", "CSV 没有数据行。")
        except (UnicodeDecodeError, csv.Error):
            record_error("INVALID_CSV", "CSV 编码或格式无效，不能完整解析。", None)
        except ImportProblem as error:
            record_error(error.code, error.message, None)

        quality["validRows"] = connection.execute("SELECT COUNT(*) FROM total").fetchone()[0]
        _finalize(quality, days_by_app, daily_rows)

        normalized_path = work_dir / "normalized.csv"
        daily_totals_path = work_dir / "daily_totals.csv"
        quality_path = work_dir / "quality.json"
        rows = connection.execute(
            "SELECT app_id,observed_date,raw_traffic_value,total_bytes FROM total ORDER BY app_id,observed_date"
        )
        _write_csv(normalized_path, ["appId", "date", "rawTrafficValue", "totalTrafficBytes"], rows)
        rows = connection.execute(
            "SELECT app_id,observed_date,raw_traffic_value,total_bytes FROM total ORDER BY app_id,observed_date"
        )
        _write_csv(daily_totals_path, ["appId", "date", "rawTrafficValue", "totalTrafficBytes"], rows)
        _write_json(quality_path, quality)
        connection.close()
        connection = None
        _remove_sqlite_files(database_path)
        return StagedTotalResult(
            encoding=quality["encoding"],
            row_count=quality["validRows"],
            app_ids=tuple(sorted(days_by_app)),
            app_date_ranges=dict(quality["appDateRanges"]),
            quality_path=quality_path,
            normalized_path=normalized_path,
            daily_totals_path=daily_totals_path,
            error=first_error,
        )
    except BaseException:
        if connection is not None:
            connection.close()
        shutil.rmtree(work_dir, ignore_errors=True)
        raise
