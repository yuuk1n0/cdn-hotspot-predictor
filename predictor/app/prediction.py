"""Shared request contract and replaceable computation; no task or HTTP concerns."""

import re
from dataclasses import asdict, dataclass
from datetime import date
from decimal import Decimal, localcontext

from .csv_import import ImportProblem


@dataclass(frozen=True)
class PredictionRequest:
    taskId: str
    datasetId: str
    appId: str
    cutoffDate: str
    horizonDays: int
    modelVersion: str

    @classmethod
    def from_json(cls, body):
        fields = {"taskId", "datasetId", "appId", "cutoffDate", "horizonDays", "modelVersion"}
        if not isinstance(body, dict) or set(body) != fields:
            raise ImportProblem("INVALID_REQUEST", "预测请求必须且只能包含 taskId 及五个业务参数。")
        for name, limit in (("taskId", 128), ("datasetId", 128), ("appId", 32), ("modelVersion", 64)):
            value = body[name]
            if not isinstance(value, str) or not value or len(value) > limit or value != value.strip() or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
                raise ImportProblem("INVALID_REQUEST", f"{name} 必须为非空字符串，不含首尾空白或控制字符，且不超过 {limit} 字符。")
        cutoff = body["cutoffDate"]
        try:
            if not isinstance(cutoff, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", cutoff) or date.fromisoformat(cutoff).year > 9998:
                raise ValueError
        except ValueError:
            raise ImportProblem("INVALID_REQUEST", "cutoffDate 必须为有效 YYYY-MM-DD，年份不超过 9998。") from None
        if type(body["horizonDays"]) is not int or body["horizonDays"] not in (1, 7):
            raise ImportProblem("INVALID_REQUEST", "horizonDays 必须为整数 1 或 7。")
        return cls(**body)

    def as_dict(self):
        return asdict(self)


def mock_items(rows, request: PredictionRequest):
    latest = {}
    for row in rows:
        if row["appId"] != request.appId or row["date"] > request.cutoffDate:
            continue
        previous = latest.get(row["fileKey"])
        if previous is None or row["date"] > previous["date"]:
            latest[row["fileKey"]] = row
    with localcontext() as context:
        # Imported integer observations have at most 128 digits; multiplying by 7 is exact.
        context.prec = 160
        values = [(key, Decimal(row["trafficBytes"]) * request.horizonDays) for key, row in latest.items()]
    # Unary minus would round under Decimal's default context for >28 significant digits.
    values.sort(key=lambda pair: (pair[1].copy_negate(), pair[0]))
    return [{
        "rank": rank, "fileKey": key, "predictedBytes": format(value, "f"), "score": None,
        "reason": "模拟结果，仅验证链路：最近一次已观测流量 × 目标天数；非真实算法。",
        "fileSizeBytes": None, "recentActualBytes": None, "compressionStatus": None, "fileType": None,
    } for rank, (key, value) in enumerate(values, 1)]
