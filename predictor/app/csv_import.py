"""CSV validation only: no prediction, zero filling or identity reconciliation."""

import csv
import io
import re
from collections import Counter
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation, localcontext
from urllib.parse import urlsplit


PARSER_VERSION = "file-daily-v3"
IDENTITY_VERSION = "filekey-v1"
TOTAL_PARSER_VERSION = "app-daily-total-v1"
GB_DECIMAL_BYTES = Decimal(1_000_000_000)
_HTTP_URL = re.compile(r"^https?://", re.IGNORECASE)
_CONTROL_CHARACTER = re.compile(r"[\x00-\x1f\x7f]")


class ImportProblem(Exception):
    def __init__(self, code: str, message: str, status: int = 422):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status

    def as_dict(self):
        return {"code": self.code, "message": self.message}


def normalize_file_identity(raw: str) -> dict[str, str | None]:
    value = raw.strip()
    if not value:
        raise ImportProblem("EMPTY_FILE_KEY", "文件标识不能为空。")
    is_url = _HTTP_URL.match(value) is not None
    if _CONTROL_CHARACTER.search(value):
        code = "INVALID_URL" if is_url else "INVALID_FILE_KEY"
        raise ImportProblem(code, "文件 URL 或标识含控制字符。")
    if is_url:
        try:
            parsed = urlsplit(value)
            hostname = parsed.hostname
            parsed.port
        except ValueError:
            raise ImportProblem("INVALID_URL", "HTTP(S) URL 格式无效。") from None
        file_key = parsed.path.lstrip("/")
        if parsed.scheme.lower() not in {"http", "https"} or not hostname or not file_key:
            raise ImportProblem("INVALID_URL", "HTTP(S) URL 必须包含主机和非空路径。")
        return {"fileKey": file_key, "sourceUrl": value, "parseStatus": "URL_PATH"}
    file_key = value.lstrip("/")
    if not file_key:
        raise ImportProblem("EMPTY_FILE_KEY", "文件标识不能为空。")
    return {"fileKey": file_key, "sourceUrl": None, "parseStatus": "PLAIN_KEY"}


_IDENTITY_ERROR_COUNTER = {
    "EMPTY_FILE_KEY": "emptyFileKeyRows",
    "INVALID_URL": "invalidUrlRows",
    "INVALID_FILE_KEY": "invalidFileKeyRows",
}


def _normalize_file_identity_for_quality(raw: str, quality: dict) -> dict[str, str | None]:
    try:
        identity = normalize_file_identity(raw)
    except ImportProblem as error:
        counter = _IDENTITY_ERROR_COUNTER.get(error.code)
        if counter:
            quality[counter] += 1
        raise
    quality["fileKeyParsedRows"] += 1
    counter = "fullUrlRows" if identity["parseStatus"] == "URL_PATH" else "plainKeyRows"
    quality[counter] += 1
    return identity


@dataclass(frozen=True)
class ImportConfig:
    mapping: dict
    datasetType: str
    datasetSource: str
    sourceUnit: str
    appId: str | None = None

    def __post_init__(self):
        if self.datasetType not in {"FILE_DAILY", "APP_DAILY_TOTAL"}:
            raise ImportProblem("DATASET_TYPE_UNSUPPORTED", "仅支持 FILE_DAILY 或 APP_DAILY_TOTAL。")
        if self.datasetSource not in {"REAL", "FAKE"}:
            raise ImportProblem("INVALID_REQUEST", "datasetSource 必须为 REAL 或 FAKE。")
        if not isinstance(self.mapping, dict):
            raise ImportProblem("INVALID_MAPPING", "mapping 必须为字段映射 JSON 对象。")
        if self.datasetType == "FILE_DAILY":
            if self.sourceUnit != "B":
                raise ImportProblem("UNIT_UNCONFIRMED", "FILE_DAILY 必须明确确认 sourceUnit=B，不推断未知流量单位。")
            allowed = {"fileKey", "date", "trafficBytes", "appId"}
            required_names = ("fileKey", "date", "trafficBytes")
        else:
            if self.sourceUnit != "GB_DECIMAL":
                raise ImportProblem("UNIT_UNCONFIRMED", "APP_DAILY_TOTAL 必须明确确认 sourceUnit=GB_DECIMAL（1 GB = 10^9 B）。")
            allowed = {"date", "totalTraffic", "appId"}
            required_names = ("date", "totalTraffic")
        if set(self.mapping) - allowed:
            raise ImportProblem("INVALID_MAPPING", "mapping 必须为字段映射 JSON 对象。")
        required = [self.mapping.get(key) for key in required_names]
        values = list(self.mapping.values())
        if any(not isinstance(v, str) for v in values) or any(not isinstance(v, str) or not v.strip() for v in required):
            raise ImportProblem("INVALID_MAPPING", "缺少日期或流量等必需列名映射。")
        columns = [v for v in values if v]
        if len(columns) != len(set(columns)):
            raise ImportProblem("INVALID_MAPPING", "不同字段不能映射到相同 CSV 列。")
        if not self.mapping.get("appId") and not self.appId:
            raise ImportProblem("INVALID_MAPPING", "没有 App 列时必须提供默认 appId。")
        if self.appId is not None and not self.appId.strip():
            raise ImportProblem("INVALID_REQUEST", "默认 appId 不能为空白字符串。")


@dataclass
class ParsedCsv:
    rows: list[dict]
    quality: dict
    error: dict | None


def new_quality() -> dict:
    return {
        "encoding": None, "totalRows": 0, "validRows": 0, "duplicateRows": 0, "aggregatedRows": 0,
        "conflictRows": 0, "invalidRows": 0, "dateRange": None,
        "appDateRanges": {}, "missingDates": [], "missingDateCount": 0,
        "missingDatesTruncated": False, "dailyRows": [], "warnings": [],
        "sourceUnit": "B", "unitConfirmed": True, "unitConfirmationSource": "UPLOAD_DECLARATION",
        "fileIdentityStatus": "NORMALIZED", "identityMappingVersion": IDENTITY_VERSION,
        "completeness": "UNKNOWN", "parserVersion": PARSER_VERSION,
        "scientificNotationRows": 0, "errorSamples": [],
        "fullUrlRows": 0, "plainKeyRows": 0, "emptyFileKeyRows": 0,
        "invalidUrlRows": 0, "invalidFileKeyRows": 0, "fileKeyParsedRows": 0,
        "fileKeyParseSuccessRate": 0.0, "normalizationCollisionRows": 0,
        "aggregationRule": "SUM_NORMALIZED_SLICES",
    }


def finalize_quality(quality: dict, days_by_app: dict, daily_rows: dict) -> None:
    quality["fileKeyParseSuccessRate"] = (
        round(quality["fileKeyParsedRows"] / quality["totalRows"], 6)
        if quality["totalRows"] else 0.0
    )
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
        "文件覆盖完整性未确认；READY 不代表全量数据。",
        "完整 URL 已按 path 提取 fileKey 并保持百分号转义；该映射不证明外部文件存在。",
    ])
    if quality["missingDateCount"]:
        quality["warnings"].append("存在缺失日期，表示无观测；未补为零流量。")
    if quality["scientificNotationRows"]:
        quality["warnings"].append("科学计数法已用 Decimal 按原文本解析；无法恢复导出前可能舍入的精度。")


def parse_csv(raw: bytes, config: ImportConfig, max_rows: int = 20000) -> ParsedCsv:
    quality = new_quality()
    observations = {}
    observation_sources = {}
    days_by_app = {}
    daily_rows = Counter()
    first_error = None

    def record_error(code, message, row_number):
        nonlocal first_error
        first_error = first_error or {"code": code, "message": message}
        if len(quality["errorSamples"]) < 20:
            quality["errorSamples"].append({"row": row_number, "code": code, "message": message})

    try:
        text, quality["encoding"] = decode_csv(raw)
        reader = csv.DictReader(io.StringIO(text, newline=""), strict=True)
        headers = reader.fieldnames
        columns = [column for column in config.mapping.values() if column]
        if not headers or len(headers) != len(set(headers)) or not set(columns).issubset(headers):
            raise ImportProblem("INVALID_CSV", "CSV 表头缺失、重复，或与 mapping 不匹配。")
        for row_number, row in enumerate(reader, start=2):
            quality["totalRows"] += 1
            if quality["totalRows"] > max_rows:
                # Keep counting records but never silently accept a truncated dataset.
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
                observed_date = parse_date(row[config.mapping["date"]])
                traffic_text = row[config.mapping["trafficBytes"]].strip()
                traffic = parse_bytes(traffic_text)
                if "e" in traffic_text.lower():
                    quality["scientificNotationRows"] += 1
                key = (app_id, file_key, observed_date.isoformat())
                if key in observations:
                    prior = observation_sources[key].get(source_identity)
                    if prior == traffic:
                        quality["duplicateRows"] += 1
                    elif prior is not None:
                        quality["conflictRows"] += 1
                        record_error("DUPLICATE_CONFLICT", "同 App、规范 fileKey 和日期存在不同流量值。", row_number)
                    else:
                        quality["normalizationCollisionRows"] += 1
                        quality["aggregatedRows"] += 1
                        observations[key]["trafficBytes"] = str(int(observations[key]["trafficBytes"]) + int(traffic))
                        if observations[key]["parseStatus"] != identity["parseStatus"]:
                            observations[key]["parseStatus"] = "MIXED"
                        observation_sources[key][source_identity] = traffic
                    continue
                observations[key] = {
                    "fileKey": file_key,
                    "date": observed_date.isoformat(),
                    "trafficBytes": traffic,
                    "appId": app_id,
                    "sourceUrl": identity["sourceUrl"],
                    "parseStatus": identity["parseStatus"],
                }
                observation_sources[key] = {source_identity: traffic}
                days_by_app.setdefault(app_id, set()).add(observed_date)
                daily_rows[(app_id, observed_date.isoformat())] += 1
            except ImportProblem as error:
                quality["invalidRows"] += 1
                record_error(error.code, error.message, row_number)
        if not quality["totalRows"]:
            raise ImportProblem("INVALID_CSV", "CSV 没有数据行。")
    except (UnicodeDecodeError, csv.Error) as error:
        record_error("INVALID_CSV", "CSV 编码或格式无效，不能完整解析。", None)
    except ImportProblem as error:
        record_error(error.code, error.message, None)

    quality["validRows"] = len(observations)
    finalize_quality(quality, days_by_app, daily_rows)
    return ParsedCsv(list(observations.values()), quality, first_error)


def decode_csv(raw: bytes) -> tuple[str, str]:
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig"), "UTF-8-BOM"
    try:
        return raw.decode("utf-8"), "UTF-8"
    except UnicodeDecodeError:
        return raw.decode("gb18030"), "GB18030"


def parse_date(value: str) -> date:
    value = value.strip()
    if not re.fullmatch(r"\d{4}[-/]\d{1,2}[-/]\d{1,2}", value):
        raise ImportProblem("INVALID_CSV", "日期须为 YYYY-MM-DD 或 YYYY/M/D。")
    try:
        return date(*(int(part) for part in re.split(r"[-/]", value)))
    except ValueError:
        raise ImportProblem("INVALID_CSV", "日期不存在或超出范围。") from None


def parse_bytes(value: str) -> str:
    # Bound pathological exponents/text before converting to fixed decimal notation.
    if len(value) > 256 or not re.fullmatch(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", value):
        raise ImportProblem("INVALID_CSV", "流量必须为非负整数字节值。")
    try:
        number = Decimal(value)
        if not number.is_finite() or number < 0 or number.adjusted() >= 128 or number != number.to_integral_value():
            raise InvalidOperation
        return "0" if number == 0 else format(number, "f").split(".")[0]
    except InvalidOperation:
        raise ImportProblem("INVALID_CSV", "流量必须为非负整数 B，且整数部分不超过 128 位。") from None


def parse_decimal_gb(value: str) -> str:
    """Convert an explicitly declared decimal-GB value to exact bytes without float/rounding."""
    if len(value) > 256 or not re.fullmatch(r"[+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", value):
        raise ImportProblem("INVALID_TOTAL_TRAFFIC", "总流量必须为非负十进制 GB 数值。")
    try:
        number = Decimal(value)
        if not number.is_finite() or number < 0 or number.adjusted() >= 128:
            raise InvalidOperation
        with localcontext() as context:
            context.prec = 160
            converted = number * GB_DECIMAL_BYTES
        return format(converted, "f")
    except InvalidOperation:
        raise ImportProblem("INVALID_TOTAL_TRAFFIC", "总流量必须为非负有限十进制 GB，且整数部分不超过 128 位。") from None


def normalize_decimal_gb(value: str) -> tuple[str, str]:
    total_bytes = parse_decimal_gb(value)
    number = Decimal(value)
    raw_value = "0" if number == 0 else format(number, "f")
    return raw_value, total_bytes
