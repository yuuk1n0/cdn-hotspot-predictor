"""Coordinate training and evaluation report generation."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

from .core.interface import Algorithm
from .core.registry import get_algorithm
from .evaluation.charts import method_chart, prediction_chart, trend_chart
from .evaluation.report import (METRICS, SHANGHAI, create_output_dir, prediction_snapshot,
                                report_markdown, write_csv, write_json)
from .training.pipeline import rolling_backtest


def write_analysis(days: dict, app_id: str, input_file: Path, output_root: Path,
                   horizons: tuple[int, ...] = (1, 7),
                   exclude_last_days: int = 0,
                   algorithm: Algorithm | None = None) -> Path:
    """Compute first, then write a dated folder with every figure and table."""
    if algorithm is None:
        algorithm = get_algorithm()
    prepared = {}
    for horizon in horizons:
        spec = algorithm.configured_spec(app_id, horizon)
        backtest = rolling_backtest(days, app_id, horizon, exclude_last_days, spec, algorithm)
        snapshot, top50, prediction = prediction_snapshot(
            days, app_id, horizon, backtest, spec, algorithm
        )
        prepared[horizon] = (backtest, snapshot, top50, prediction)
    now = datetime.now(SHANGHAI)
    folder = create_output_dir(output_root, now)
    metric_rows = []
    summary = {
        "generatedAt": now.isoformat(timespec="seconds"),
        "inputFile": str(input_file),
        "appId": app_id,
        "modelVersion": algorithm.version,
        "sourceUnit": "B (caller confirmed)",
        "metricScope": "observed-future-proxy; completeness UNKNOWN",
        "excludeLastDays": exclude_last_days,
        "horizons": {},
    }
    for horizon, (backtest, snapshot, top50, prediction) in prepared.items():
        key = str(horizon)
        summary["horizons"][key] = {"backtest": backtest, "latestPrediction": snapshot}
        write_json(folder / f"{key}-prediction.json", {
            **snapshot, "predictionSource": "MODEL", "items": prediction["items"]
        })
        write_csv(folder / f"{key}-top50.csv",
                   ["rank", "fileKey", "predictedBytes", "observedBytes", "observedInFuture"],
                   top50)
        groups = [("selection", name, evaluation)
                  for name, evaluation in backtest["selection"].items()]
        groups.extend((("test-selected", backtest["selectedMethod"], backtest["test"]),
                       ("test-configured", backtest["configuredMethod"],
                        backtest["configuredTest"])))
        for phase, method, evaluation in groups:
            for row in evaluation["rows"]:
                metric_rows.append({"horizonDays": horizon, "phase": phase,
                                    "method": method, **row})
        label = "次日" if horizon == 1 else "未来连续 7 日"
        method_chart(folder / "charts" / f"{key}-methods.svg",
                     f"{app_id} · {label} · 方法比较", backtest["selection"])
        trend_chart(folder / "charts" / f"{key}-trend.svg",
                    f"{app_id} · {label} · 测试期命中趋势",
                    backtest["test"], backtest["configuredTest"],
                    backtest["selectedMethod"], backtest["configuredMethod"])
        prediction_chart(folder / "charts" / f"{key}-prediction.svg",
                         f"{app_id} · {label} · Top20 预测与观测",
                         prediction["items"], prediction["actual"])
    write_csv(folder / "metrics.csv",
               ["horizonDays", "phase", "method", "cutoffDate", *METRICS], metric_rows)
    write_json(folder / "summary.json", summary)
    (folder / "report.md").write_text(report_markdown(summary), encoding="utf-8")
    return folder
