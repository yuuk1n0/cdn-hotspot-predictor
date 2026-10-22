"""Recent-window, App-specific parameter review over real MySQL observations."""

from datetime import datetime, timezone

from hotspot.core.registry import get_algorithm
from hotspot.core.v1.candidates import COMPARISONS
from hotspot.core.v1.spec import ModelSpec
from hotspot.evaluation.backtest import evaluate_spec
from hotspot.evaluation.split import possible_cutoffs

from .algorithm_adapter import MODEL_VERSION


RECALL_MARGIN = 0.01
NDCG_TOLERANCE = 0.01


def _spec_dict(spec):
    return {"method": spec.method, "window": spec.window, "halfLife": spec.half_life}


def _metric_name(spec):
    for name, candidate in COMPARISONS.items():
        if candidate == spec:
            return name
    return f"custom_{spec.method}_{spec.window}_{spec.half_life or 'none'}"


def review_model(days, app_id, horizon_days, current_spec, *, current_source="REVIEWED_CONFIG",
                 app_config_version=None, max_cutoffs=None):
    """Compare the configured parameter with v1 candidates on the same recent cutoffs."""
    if not isinstance(current_spec, ModelSpec):
        raise TypeError("current_spec must be ModelSpec")
    cutoffs = list(possible_cutoffs(days, horizon_days))
    if not cutoffs:
        raise ValueError("no complete observed future window")
    max_cutoffs = max_cutoffs or (5 if horizon_days == 1 else 4)
    cutoffs = cutoffs[-max_cutoffs:]
    algorithm = get_algorithm(MODEL_VERSION)

    def evaluate(spec):
        report = evaluate_spec(days, app_id, horizon_days, cutoffs, spec, algorithm)
        return {
            "mean": report["mean"],
            "worstCutoffs": report["worstCutoffs"],
            "cutoffCount": report["cutoffCount"],
        }

    current = evaluate(current_spec)
    compared = []
    seen = set()
    for name, spec in [("current", current_spec), *COMPARISONS.items()]:
        described = _spec_dict(spec)
        key = (described["method"], described["window"], described["halfLife"])
        if key in seen:
            continue
        seen.add(key)
        report = current if spec == current_spec else evaluate(spec)
        compared.append({
            "name": name,
            "method": described["method"],
            "spec": described,
            "mean": report["mean"],
            "improvementRecall": report["mean"]["recallAt50"] - current["mean"]["recallAt50"],
            "improvementNdcg": report["mean"]["ndcgAt50"] - current["mean"]["ndcgAt50"],
        })

    best = max(compared, key=lambda item: (item["mean"]["recallAt50"], item["mean"]["ndcgAt50"]))
    can_switch = (
        best["spec"] != _spec_dict(current_spec)
        and best["improvementRecall"] >= RECALL_MARGIN
        and best["improvementNdcg"] >= -NDCG_TOLERANCE
    )
    reasons = ["REAL_OBSERVED_FUTURE_ROLLING_REVIEW", "OBSERVED_TOPK_PROXY", "COMPLETENESS_UNCONFIRMED"]
    if can_switch:
        reasons += ["PARAMETER_REPLACEMENT_RECOMMENDED", "SAME_MODEL_VERSION_ONLY"]
        recommendations = [
            f"建议将 {app_id} 的 {'DAY' if horizon_days == 1 else 'WEEK'} 参数替换为 {best['method']}",
            "替换只改变同日/周跨度参数，不新增或伪造模型版本",
            "人工确认后，新参数从下一次预测任务开始生效",
        ]
    else:
        reasons += ["CURRENT_CONFIG_NONINFERIOR", "NO_MATERIAL_RECALL_GAIN"]
        recommendations = [
            "当前参数在 1 个百分点 Recall 非劣门槛内，保持现有配置",
            "继续积累成熟观测后再评审；不得用缺失文件补零制造提升",
        ]
    generated = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    return {
        "appId": app_id,
        "horizonDays": horizon_days,
        "modelVersion": MODEL_VERSION,
        "generatedAt": generated,
        "metricBasis": "REAL_OBSERVED_FUTURE_ROLLING_WINDOW",
        "labelBasis": "OBSERVED_TOPK_PROXY",
        "formalSelectionEligible": False,
        "currentMethod": _metric_name(current_spec),
        "currentSpec": _spec_dict(current_spec),
        "currentSource": current_source,
        "appConfigVersion": app_config_version,
        "currentMetrics": current["mean"],
        "dataStartDate": min(days).isoformat(),
        "dataEndDate": max(days).isoformat(),
        "cutoffStartDate": cutoffs[0].isoformat(),
        "cutoffEndDate": cutoffs[-1].isoformat(),
        "cutoffCount": len(cutoffs),
        "candidates": compared,
        "bestMethod": best["method"],
        "bestSpec": best["spec"],
        "bestMetrics": best["mean"],
        "targetModelVersion": MODEL_VERSION,
        "targetMethod": best["method"] if can_switch else _metric_name(current_spec),
        "targetSpec": best["spec"] if can_switch else _spec_dict(current_spec),
        "improvementRecall": best["improvementRecall"],
        "improvementNdcg": best["improvementNdcg"],
        "canSwitch": can_switch,
        "reasonCodes": reasons,
        "recommendations": recommendations,
        "assumptions": [
            "只使用 MySQL 中已观测的 App 文件日流量",
            "缺失日期不补零；未观测文件不推定为零流量",
            "指标是已观测 Top50 代理，不是完整 CDN 流量准确率",
        ],
    }
