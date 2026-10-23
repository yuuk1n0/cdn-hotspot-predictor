"""Walk-forward LightGBM ranking for the supplied complete file-day data.

An absent file in a recorded future day is treated as zero traffic. Entire
unrecorded calendar days still cannot form a training label window.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Mapping

from ..interface import Observations
from ..v1.predictor import candidate_window, predict_items as v1_predict_items
from ..v1.spec import ModelSpec
from ..v2.model import V2Spec, _features, _fit_ranker
from ...evaluation.metrics import future_totals
from ...evaluation.split import possible_cutoffs
from ...startup import assess_app_history

MODEL_VERSION = "hotspot-lightgbm-v3"


@dataclass(frozen=True)
class V3Spec(V2Spec):
    model_weight: float = 0.5


SPEC = V3Spec()


def _grades(amounts: list[int]) -> list[int]:
    """Reserve zero for files absent in the complete future window."""
    positive = sorted({amount for amount in amounts if amount > 0})
    if not positive:
        return [0] * len(amounts)
    grades = {amount: 1 + min(2, 3 * index // len(positive))
              for index, amount in enumerate(positive)}
    return [grades.get(amount, 0) for amount in amounts]


def _training_rows(days: Observations, cutoff: date, horizon_days: int,
                   spec: V3Spec) -> tuple[list[list[float]], list[int], list[int]]:
    features: list[list[float]] = []
    labels: list[int] = []
    groups: list[int] = []
    for historical in possible_cutoffs(days, horizon_days):
        if historical + timedelta(days=horizon_days) > cutoff:
            break
        _, candidates = candidate_window(days, historical, spec.candidate_window)
        if len(candidates) < 2:
            continue
        future = future_totals(days, historical, horizon_days)
        ordered = sorted(candidates)
        grades = _grades([future.get(key, 0) for key in ordered])
        if len(set(grades)) < 2:
            continue
        vectors = _features(days, historical, candidates)
        features.extend(vectors[key] for key in ordered)
        labels.extend(grades)
        groups.append(len(ordered))
    return features, labels, groups


class V3Algorithm:
    version = MODEL_VERSION
    candidates: Mapping[str, V3Spec] = {"lightgbm_zero_blend": SPEC}
    selection_rule = "固定规格；历史完整标签窗口训练；零流量负例；与v1排名等权融合"

    def configured_spec(self, app_id: str, horizon_days: int) -> V3Spec:
        if horizon_days not in (1, 7):
            raise ValueError("horizonDays must be 1 or 7")
        return SPEC

    def predict_items(self, days: Observations, app_id: str, cutoff: date,
                      horizon_days: int, spec: object) -> list[dict]:
        if not isinstance(spec, V3Spec):
            raise TypeError("v3 requires a V3Spec")
        baseline = v1_predict_items(
            days, app_id, cutoff, horizon_days, ModelSpec("last_day", spec.candidate_window)
        )
        if not baseline:
            return baseline
        if assess_app_history(days, cutoff, horizon_days).state == "COLD":
            for item in baseline:
                item["reason"] += "; App历史不足，冷启动使用v1排序"
            return baseline
        training_x, training_y, groups = _training_rows(days, cutoff, horizon_days, spec)
        if len(groups) < spec.min_training_groups:
            for item in baseline:
                item["reason"] += "; LightGBM历史训练组不足，使用v1排序"
            return baseline
        ranker = _fit_ranker(spec, training_x, training_y, groups)
        vectors = _features(days, cutoff, {item["fileKey"] for item in baseline})
        scores = ranker.predict([vectors[item["fileKey"]] for item in baseline])
        model_order = sorted(range(len(baseline)),
                             key=lambda index: (-scores[index], baseline[index]["fileKey"]))
        model_rank = {index: rank for rank, index in enumerate(model_order)}
        for index, item in enumerate(baseline):
            item["_fused_rank"] = ((1 - spec.model_weight) * index
                                   + spec.model_weight * model_rank[index])
            item["reason"] += "; LightGBM零流量样本排序与v1历史排序融合"
        baseline.sort(key=lambda item: (item["_fused_rank"], item["rank"], item["fileKey"]))
        for rank, item in enumerate(baseline, 1):
            item["rank"] = rank
            del item["_fused_rank"]
        return baseline

    def select_candidate(self, selection_results: Mapping[str, dict]) -> str:
        return "lightgbm_zero_blend"

    def describe_spec(self, spec: object) -> dict:
        if not isinstance(spec, V3Spec):
            raise TypeError("v3 requires a V3Spec")
        return {
            "candidateWindow": spec.candidate_window,
            "minTrainingGroups": spec.min_training_groups,
            "nEstimators": spec.n_estimators,
            "baselineWeight": 1 - spec.model_weight,
            "lightgbmWeight": spec.model_weight,
            "labelPolicy": "absent file in recorded future window is zero",
            "trainingMode": "walk-forward; completed labels at or before cutoff",
            "predictedBytes": "v1 last-day history proxy; ranking is blended",
        }
