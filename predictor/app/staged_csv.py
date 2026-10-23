"""Disk-backed CSV normalization for large immutable import snapshots."""

import csv
import hashlib
import json
import os
import shutil
import sqlite3
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from .csv_import import (
    ImportConfig,
    ImportProblem,
    _normalize_file_identity_for_quality,
    finalize_quality,
    new_quality,
    parse_bytes,
    parse_date,
)


@dataclass(frozen=True)
class StagedCsvResult:
    encoding: str | None
    row_count: int
    app_ids: tuple[str, ...]
    app_date_ranges: dict
    quality_path: Path
    normalized_path: Path
    daily_totals_path: Path
    error: dict | None


def _detect_encoding(source: Path) -> tuple[str, str]:
    with source.open("rb") as stream:
        sample = stream.read(64 * 1024)
    if sample.startswith(b"\xef\xbb\xbf"):
        sample.decode("utf-8-sig")
        return "utf-8-sig", "UTF-8-BOM"
    try:
        sample.decode("utf-8")
        return "utf-8", "UTF-8"
    except UnicodeDecodeError:
        sample.decode("gb18030")
        return "gb18030", "GB18030"


def _write_csv(path: Path, fieldnames: list[str], rows) -> None:
    with path.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(fieldnames)
        writer.writerows(rows)
        stream.flush()
        os.fsync(stream.fileno())


def _write_json(path: Path, value: dict) -> None:
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        stream.flush()
        os.fsync(stream.fileno())


def _remove_sqlite_files(database_path: Path) -> None:
    for suffix in ("", "-journal", "-shm", "-wal"):
        try:
            Path(f"{database_path}{suffix}").unlink()
        except FileNotFoundError:
            pass


def normalize_staged_csv(
    source: Path,
    config: ImportConfig,
    work_dir: Path,
    max_rows: int,
) -> StagedCsvResult:
    source, work_dir = Path(source), Path(work_dir)
    if max_rows <= 0:
        raise ValueError("数据行上限必须大于零。")
    work_dir.mkdir(parents=True)
    database_path = work_dir / "normalization.sqlite"
    connection = None
    quality = new_quality()
    quality["aggregatedRows"] = 0
    quality["aggregationRule"] = "SUM_NORMALIZED_SLICES"
    days_by_app: dict[str, set] = {}
    daily_rows = Counter()
    daily_totals: dict[tuple[str, str], list[int]] = {}
    first_error = None

    def record_error(code: str, message: str, row_number: int | None) -> None:
        nonlocal first_error
        first_error = first_error or {"code": code, "message": message}
        if len(quality["errorSamples"]) < 20:
            quality["errorSamples"].append({"row": row_number, "code": code, "message": message})

    try:
        connection = sqlite3.connect(database_path)
        connection.executescript("""
            CREATE TABLE observation(
              app_id TEXT NOT NULL,
              file_key TEXT NOT NULL,
              observed_date TEXT NOT NULL,
              traffic_bytes TEXT NOT NULL,
              parse_status TEXT NOT NULL,
              source_identity_hash TEXT NOT NULL,
              source_row_count INTEGER NOT NULL,
              source_identity_count INTEGER NOT NULL,
              aggregation_rule TEXT NOT NULL,
              PRIMARY KEY(app_id, file_key, observed_date)
            );
            CREATE TABLE source_slice(
              app_id TEXT NOT NULL,
              file_key TEXT NOT NULL,
              observed_date TEXT NOT NULL,
              source_identity TEXT NOT NULL,
              traffic_bytes TEXT NOT NULL,
              PRIMARY KEY(app_id, file_key, observed_date, source_identity)
            );
            CREATE TABLE daily_total(
              app_id TEXT NOT NULL,
              observed_date TEXT NOT NULL,
              total_bytes TEXT NOT NULL,
              file_count INTEGER NOT NULL,
              PRIMARY KEY(app_id, observed_date)
            );
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
                        raw_identity = row[config.mapping["fileKey"]]
                        identity = _normalize_file_identity_for_quality(raw_identity, quality)
                        file_key = identity["fileKey"]
                        source_identity = raw_identity.strip()
                        app_id = row[config.mapping["appId"]].strip() if config.mapping.get("appId") else config.appId
                        if not app_id:
                            raise ImportProblem("INVALID_CSV", "App ID 不能为空。")
                        if config.appId is not None and config.appId != app_id:
                            raise ImportProblem("APP_MISMATCH", "行 App ID 与提交的默认 appId 不一致。")
                        observed = parse_date(row[config.mapping["date"]])
                        observed_date = observed.isoformat()
                        traffic_text = row[config.mapping["trafficBytes"]].strip()
                        traffic_bytes = parse_bytes(traffic_text)
                        if "e" in traffic_text.lower():
                            quality["scientificNotationRows"] += 1
                        slice_inserted = False
                        try:
                            connection.execute(
                                "INSERT INTO source_slice(app_id,file_key,observed_date,source_identity,traffic_bytes) "
                                "VALUES(?,?,?,?,?)",
                                (app_id, file_key, observed_date, source_identity, traffic_bytes),
                            )
                            slice_inserted = True
                        except sqlite3.IntegrityError:
                            existing = connection.execute(
                                "SELECT traffic_bytes FROM source_slice WHERE app_id=? AND file_key=? "
                                "AND observed_date=? AND source_identity=?",
                                (app_id, file_key, observed_date, source_identity),
                            ).fetchone()
                            if existing and existing[0] == traffic_bytes:
                                quality["duplicateRows"] += 1
                            else:
                                quality["conflictRows"] += 1
                                record_error(
                                    "DUPLICATE_CONFLICT",
                                    "同 App、规范 fileKey 和日期存在不同流量值。",
                                    row_number,
                                )
                            continue
                        existing = connection.execute(
                            "SELECT traffic_bytes,parse_status,source_identity_hash,source_row_count,source_identity_count "
                            "FROM observation WHERE app_id=? AND file_key=? AND observed_date=?",
                            (app_id, file_key, observed_date),
                        ).fetchone()
                        if existing is None:
                            connection.execute(
                                "INSERT INTO observation(app_id,file_key,observed_date,traffic_bytes,parse_status,"
                                "source_identity_hash,source_row_count,source_identity_count,aggregation_rule) "
                                "VALUES(?,?,?,?,?,?,?,?,?)",
                                (app_id, file_key, observed_date, traffic_bytes, identity["parseStatus"],
                                 hashlib.sha256(source_identity.encode("utf-8")).hexdigest(), 1, 1, "NONE"),
                            )
                            days_by_app.setdefault(app_id, set()).add(observed)
                            daily_rows[(app_id, observed_date)] += 1
                            is_new_observation = True
                        else:
                            quality["normalizationCollisionRows"] += 1
                            quality["aggregatedRows"] += 1
                            combined = str(int(existing[0]) + int(traffic_bytes))
                            digest = hashlib.sha256((existing[2] + "\n" + source_identity).encode("utf-8")).hexdigest()
                            parse_status = existing[1] if existing[1] == identity["parseStatus"] else "MIXED"
                            connection.execute(
                                "UPDATE observation SET traffic_bytes=?,parse_status=?,source_identity_hash=?,"
                                "source_row_count=?,source_identity_count=?,aggregation_rule=? "
                                "WHERE app_id=? AND file_key=? AND observed_date=?",
                                (combined, parse_status, digest, existing[3] + 1, existing[4] + 1,
                                 "SUM_NORMALIZED_SLICES", app_id, file_key, observed_date),
                            )
                            is_new_observation = False
                        aggregate = daily_totals.setdefault((app_id, observed_date), [0, 0])
                        aggregate[0] += int(traffic_bytes)
                        aggregate[1] += 1 if is_new_observation else 0
                    except ImportProblem as error:
                        quality["invalidRows"] += 1
                        record_error(error.code, error.message, row_number)
                if not quality["totalRows"]:
                    raise ImportProblem("INVALID_CSV", "CSV 没有数据行。")
        except (UnicodeDecodeError, csv.Error):
            record_error("INVALID_CSV", "CSV 编码或格式无效，不能完整解析。", None)
        except ImportProblem as error:
            record_error(error.code, error.message, None)

        with connection:
            connection.executemany(
                "INSERT INTO daily_total(app_id,observed_date,total_bytes,file_count) VALUES(?,?,?,?)",
                ((app, day, str(total), count) for (app, day), (total, count) in sorted(daily_totals.items())),
            )
        quality["validRows"] = connection.execute("SELECT COUNT(*) FROM observation").fetchone()[0]
        finalize_quality(quality, days_by_app, daily_rows)

        normalized_path = work_dir / "normalized.csv"
        daily_totals_path = work_dir / "daily_totals.csv"
        quality_path = work_dir / "quality.json"
        _write_csv(
            normalized_path,
            ["fileKey", "date", "trafficBytes", "appId", "sourceIdentityHash", "parseStatus",
             "sourceRowCount", "sourceIdentityCount", "aggregationRule"],
            (
                (file_key, observed_date, traffic_bytes, app_id, source_hash, parse_status,
                 source_rows, source_identities, aggregation_rule)
                for app_id, file_key, observed_date, traffic_bytes, parse_status, source_hash,
                    source_rows, source_identities, aggregation_rule in connection.execute(
                    "SELECT app_id,file_key,observed_date,traffic_bytes,parse_status,source_identity_hash,"
                    "source_row_count,source_identity_count,aggregation_rule FROM observation "
                    "ORDER BY app_id,file_key,observed_date"
                )
            ),
        )
        _write_csv(
            daily_totals_path,
            ["appId", "date", "totalTrafficBytes", "fileCount"],
            connection.execute(
                "SELECT app_id,observed_date,total_bytes,file_count FROM daily_total "
                "ORDER BY app_id,observed_date"
            ),
        )
        _write_json(quality_path, quality)
        connection.close()
        connection = None
        _remove_sqlite_files(database_path)
        return StagedCsvResult(
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
