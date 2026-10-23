"""Render reproducible metrics, snapshots, and a human report."""

from __future__ import annotations

import csv
import json
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from ..core.interface import Algorithm
from .metrics import future_totals, prediction_metrics

METRICS = ("recallAt50", "ndcgAt50", "observedTrafficCoverageAt50",
           "candidateCoverageAt50")
SHANGHAI = ZoneInfo("Asia/Shanghai")


def create_output_dir(root: Path, now: datetime | None = None) -> Path:
    """Create one YYYYMMDD_HHMMSS directory without overwriting another run."""
    root.mkdir(parents=True, exist_ok=True)
    timestamp = (now or datetime.now(SHANGHAI)).astimezone(SHANGHAI).strftime("%Y%m%d_%H%M%S")
    for number in range(100):
        suffix = "" if number == 0 else f"_{number:02d}"
        folder = root / f"{timestamp}{suffix}"
        try:
            folder.mkdir()
            return folder
        except FileExistsError:
            continue
    raise FileExistsError(f"Too many report folders for {timestamp}")


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def prediction_snapshot(days: dict, app_id: str, horizon: int, report: dict,
                        spec: object, algorithm: Algorithm) -> tuple[dict, list[dict], dict]:
    cutoff = date.fromisoformat(report["configuredTest"]["rows"][-1]["cutoffDate"])
    items = algorithm.predict_items(days, app_id, cutoff, horizon, spec)
    actual = future_totals(days, cutoff, horizon)
    metrics = prediction_metrics(items, actual)
    shown = items[:50]
    matched = [(int(item["predictedBytes"]), actual[item["fileKey"]])
               for item in shown if item["fileKey"] in actual]
    denominator = sum(value for _, value in matched)
    matched_wape = (sum(abs(predicted - observed) for predicted, observed in matched)
                    / denominator if denominator else None)
    snapshot = {
        "appId": app_id,
        "modelVersion": algorithm.version,
        "configuredMethod": report["configuredMethod"],
        "cutoffDate": cutoff.isoformat(),
        "horizonDays": horizon,
        "predictionStartDate": (cutoff + timedelta(days=1)).isoformat(),
        "predictionEndDate": (cutoff + timedelta(days=horizon)).isoformat(),
        "candidateCount": len(items),
        "observedFutureFileCount": len(actual),
        "top50ObservedMatchCount": len(matched),
        "top50UnobservedCount": len(shown) - len(matched),
        "matchedObservedWape": matched_wape,
        "metrics": metrics,
    }
    rows = [{
        "rank": item["rank"],
        "fileKey": item["fileKey"],
        "predictedBytes": item["predictedBytes"],
        "observedBytes": str(actual[item["fileKey"]]) if item["fileKey"] in actual else None,
        "observedInFuture": item["fileKey"] in actual,
    } for item in shown]
    return snapshot, rows, {"items": items, "actual": actual}


def write_csv(path: Path, fieldnames: list[str], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def _percent(value: float | None) -> str:
    return "—" if value is None else f"{value:.2%}"


def report_markdown(summary: dict) -> str:
    lines = ["# CDN 热点文件预测指标分析", "",
             f"生成时间：{summary['generatedAt']}（Asia/Shanghai）  ",
             f"输入文件：`{summary['inputFile']}`  ",
             f"AppID：`{summary['appId']}`；模型版本：`{summary['modelVersion']}`", "",
             "**口径：**以下排序、流量和误差指标仅基于未来可观测文件。未进入观测表的文件不能当作真实零流量；数据完整性尚未认证，因此这些结果不能直接解释为完整真实 Top50 或降本金额。", "",
             "Recall@50 是未来观测 Top50 的命中比例；NDCG@50 衡量顺序质量；观测流量覆盖率是预测前 50 对未来已观测流量的覆盖比例；候选覆盖率衡量未来观测 Top50 中有多少进入候选池。", "",
             "测试期不参与模型选型；各算法的选型规则见对应周期。", ""]
    for key, result in summary["horizons"].items():
        backtest = result["backtest"]
        snapshot = result["latestPrediction"]
        label = "次日" if key == "1" else "未来连续 7 日"
        lines += [f"## {label}（H={key}）", "",
                  f"模型选择规则：{backtest['selectionRule']}", "",
                  f"参数选择期截至 {backtest['selectionEndDate']}；独立测试期从 {backtest['testStartDate']} 开始。"
                  f" 有效切点 {backtest['eligibleCutoffCount']} 个；选择期 {backtest['selection'][backtest['selectedMethod']]['cutoffCount']} 个，"
                  f"测试期 {backtest['test']['cutoffCount']} 个。", "",
                  "| 选择期方法 | Recall@50 | NDCG@50 | 观测流量覆盖率@50 | 候选覆盖率@50 |", "| --- | ---: | ---: | ---: | ---: |"]
        for name, row in backtest["selection"].items():
            avg = row["mean"]
            lines.append(f"| {name} | {_percent(avg['recallAt50'])} | {_percent(avg['ndcgAt50'])} | "
                         f"{_percent(avg['observedTrafficCoverageAt50'])} | {_percent(avg['candidateCoverageAt50'])} |")
        lines += ["", "| 独立测试期 | 方法 | Recall@50 | NDCG@50 | 观测流量覆盖率@50 | 候选覆盖率@50 |",
                  "| --- | --- | ---: | ---: | ---: | ---: |"]
        for name, row in (("回测选出", backtest["test"]),
                          ("当前预测配置", backtest["configuredTest"])):
            method = backtest["selectedMethod"] if name == "回测选出" else backtest["configuredMethod"]
            avg = row["mean"]
            lines.append(f"| {name} | {method} | {_percent(avg['recallAt50'])} | {_percent(avg['ndcgAt50'])} | "
                         f"{_percent(avg['observedTrafficCoverageAt50'])} | {_percent(avg['candidateCoverageAt50'])} |")
        chosen = backtest["test"]["mean"]
        configured = backtest["configuredTest"]["mean"]
        recall_delta = 100 * (chosen["recallAt50"] - configured["recallAt50"])
        direction = "高" if recall_delta >= 0 else "低"
        if chosen["candidateCoverageAt50"] < 0.6:
            diagnostic = ("候选覆盖率偏低；当前版本只能预测历史出现过的文件。"
                          if summary["modelVersion"] == "hotspot-lightgbm-v3"
                          else "候选覆盖率偏低，应先排查近期候选池与原始数据覆盖。")
        else:
            diagnostic = "候选覆盖率高于命中率，可继续排查候选内排序。"
        if backtest["selectedSpec"] == backtest["configuredSpec"]:
            lines += ["", f"**分析：**当前只有一组生效规格。{diagnostic}"
                      "这是离线代理结果，不自动修改当前模型版本的配置。"]
        else:
            lines += ["", f"**分析：**回测选出方案在测试期的 Recall@50 比当前配置{direction} "
                      f"{abs(recall_delta):.2f} 个百分点。{diagnostic}"
                      "这是离线代理结果，不自动修改当前模型版本的配置。"]
        if summary["modelVersion"] == "hotspot-lightgbm-v2":
            lines += ["", "LightGBM 仅学习未来有记录文件的相对流量；未记录文件没有零标签。"
                      "每个切点只使用该日及之前已结束的标签窗口训练。"
                      "predictedBytes 是 v1 的历史外推代理，排序由 LightGBM 得分决定；"
                      "因此匹配文件 WAPE 不衡量 LightGBM 的流量预测能力。"]
        elif summary["modelVersion"] == "hotspot-lightgbm-v3":
            lines += ["", "LightGBM v3 把完整观测日期内未出现的候选文件作为零流量样本，"
                      "仅用预测截止日前已结束的标签窗口训练，并与 v1 历史排名等权融合。"
                      "predictedBytes 仍是 v1 的历史外推代理；"
                      "匹配文件 WAPE 不衡量 LightGBM 的流量预测能力。"]
        lines += ["", f"最近可评价预测：截止日 {snapshot['cutoffDate']}，目标窗口 "
                  f"{snapshot['predictionStartDate']}～{snapshot['predictionEndDate']}；"
                  f"候选 {snapshot['candidateCount']} 个，前 50 名中有 {snapshot['top50ObservedMatchCount']} 个"
                  f"在未来观测表出现，{snapshot['top50UnobservedCount']} 个未观测。"
                  f"匹配文件上的 WAPE 为 {_percent(snapshot['matchedObservedWape'])}（仅作数值诊断，存在选择偏差）。", "",
                  f"![{label}方法对比](charts/{key}-methods.svg)", "",
                  f"![{label}测试期趋势](charts/{key}-trend.svg)", "",
                  f"![{label}预测与观测](charts/{key}-prediction.svg)", "",
                  f"明细：[前 50 名 CSV]({key}-top50.csv) · [完整候选 JSON]({key}-prediction.json)", ""]
    lines += ["逐切点指标见 [metrics.csv](metrics.csv)，完整数据见 [summary.json](summary.json)。", ""]
    return "\n".join(lines)
