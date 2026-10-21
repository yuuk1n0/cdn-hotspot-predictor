"""Walk-forward LightGBM ranking over the v1 candidate pool.

Only files with an observed future traffic value enter training. An absent
future file is unknown, not a zero label. Every model used at a cutoff is fit
from label windows that ended no later than that cutoff.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from math import log1p
from typing import Mapping

from ..interface import Observations
from ..v1.predictor import candidate_window, predict_items as v1_predict_items
from ..v1.spec import ModelSpec
from ...evaluation.metrics import future_totals
from ...evaluation.split import possible_cutoffs
from ...startup import assess_app_history

MODEL_VERSION = "hotspot-lightgbm-v2"
FEATURE_NAMES = (
    "latest_log_bytes", "max_3_log_bytes", "sum_3_log_bytes",
    "sum_7_log_bytes", "sum_14_log_bytes", "sum_30_log_bytes",
    "seen_3", "seen_7", "seen_14", "seen_30", "days_since_seen",
    "observed_calendar_days_7", "observed_calendar_days_30",
)


@dataclass(frozen=True)
class V2Spec:
    candidate_window: int = 1
    min_training_groups: int = 20
    n_estimators: int = 40


SPEC = V2Spec()


def _features(days: Observations, cutoff: date, keys: set[str]) -> dict[str, list[float]]:
    """Encode recorded traffic; zero with a zero count means no record, not a label."""
    daily = [days.get(cutoff - timedelta(days=age)) for age in range(30)]
    calendar_7 = sum(day is not None for day in daily[:7])
    calendar_30 = sum(day is not None for day in daily)
    result = {}
    for key in keys:
        recorded = [(age, day[key]) for age, day in enumerate(daily)
                    if day is not None and key in day]
        amounts = {age: amount for age, amount in recorded}
        def values(window: int) -> list[int]:
            return [amount for age, amount in recorded if age < window]
        v3, v7, v14, v30 = (values(n) for n in (3, 7, 14, 30))
        result[key] = [
            log1p(amounts.get(0, 0)), log1p(max(v3, default=0)),
            log1p(sum(v3)), log1p(sum(v7)), log1p(sum(v14)), log1p(sum(v30)),
            len(v3), len(v7), len(v14), len(v30),
            min(amounts) if amounts else 30, calendar_7, calendar_30,
        ]
    return result


def _rank_labels(amounts: list[int]) -> list[int]:
    """Four within-cutoff relevance grades from observed future traffic."""
    ordered = sorted(set(amounts))
    if len(ordered) < 2:
        return [0] * len(amounts)
    positions = {value: index for index, value in enumerate(ordered)}
    return [min(3, 4 * positions[value] // len(ordered)) for value in amounts]


def _training_rows(days: Observations, cutoff: date, horizon_days: int,
                   spec: V2Spec) -> tuple[list[list[float]], list[int], list[int]]:
    features: list[list[float]] = []
    labels: list[int] = []
    groups: list[int] = []
    for historical in possible_cutoffs(days, horizon_days):
        if historical + timedelta(days=horizon_days) > cutoff:
            break
        _, candidates = candidate_window(days, historical, spec.candidate_window)
        future = future_totals(days, historical, horizon_days)
        observed = sorted(candidates.intersection(future))
        if len(observed) < 2:
            continue
        amounts = [future[key] for key in observed]
        grades = _rank_labels(amounts)
        if len(set(grades)) < 2:
            continue
        vectors = _features(days, historical, set(observed))
        features.extend(vectors[key] for key in observed)
        labels.extend(grades)
        groups.append(len(observed))
    return features, labels, groups


def _fit_ranker(spec: V2Spec, features: list[list[float]], labels: list[int],
                groups: list[int]):
    try:
        import lightgbm as lgb
        import numpy as np
    except ImportError as error:
        raise RuntimeError(
            "LightGBM v2 requires the cvte Conda environment; create it from environment.yml"
        ) from error
    dataset = lgb.Dataset(
        np.asarray(features, dtype=float), label=labels, group=groups,
        feature_name=list(FEATURE_NAMES)
    )
    booster = lgb.train(
        {
            "objective": "lambdarank", "metric": "ndcg", "learning_rate": 0.05,
            "num_leaves": 7, "max_depth": 3, "min_data_in_leaf": 10,
            "seed": 42, "num_threads": 1, "verbosity": -1,
            "deterministic": True, "force_col_wise": True,
        },
        dataset, num_boost_round=spec.n_estimators,
    )
    return _NativeRanker(booster, np)


class _NativeRanker:
    def __init__(self, booster, numpy_module):
        self.booster = booster
        self.numpy = numpy_module

    def predict(self, vectors: list[list[float]]):
        return self.booster.predict(self.numpy.asarray(vectors, dtype=float))


class V2Algorithm:
    version = MODEL_VERSION
    candidates: Mapping[str, V2Spec] = {"lightgbm_ranker": SPEC}
    selection_rule = "固定 LightGBM 排序规格；仅用预测截止日前已结束的可观测标签窗口训练"

    def configured_spec(self, app_id: str, horizon_days: int) -> V2Spec:
        if horizon_days not in (1, 7):
            raise ValueError("horizonDays must be 1 or 7")
        return SPEC

    def predict_items(self, days: Observations, app_id: str, cutoff: date,
                      horizon_days: int, spec: object) -> list[dict]:
        if not isinstance(spec, V2Spec):
            raise TypeError("v2 requires a V2Spec")
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
        keys = {item["fileKey"] for item in baseline}
        vectors = _features(days, cutoff, keys)
        scores = ranker.predict([vectors[item["fileKey"]] for item in baseline])
        for item, score in zip(baseline, scores):
            item["_ranking_score"] = float(score)
            item["reason"] += "; LightGBM按已观测文件相对流量排序，预测字节为v1历史代理"
        baseline.sort(key=lambda item: (-item["_ranking_score"], item["fileKey"]))
        for rank, item in enumerate(baseline, 1):
            item["rank"] = rank
            del item["_ranking_score"]
        return baseline

    def select_candidate(self, selection_results: Mapping[str, dict]) -> str:
        return "lightgbm_ranker"

    def describe_spec(self, spec: object) -> dict:
        if not isinstance(spec, V2Spec):
            raise TypeError("v2 requires a V2Spec")
        return {
            "candidateWindow": spec.candidate_window,
            "minTrainingGroups": spec.min_training_groups,
            "nEstimators": spec.n_estimators,
            "labelPolicy": "observed-future-only; missing is unknown",
            "trainingMode": "walk-forward; completed labels at or before cutoff",
            "predictedBytes": "v1 last-day history proxy; ranking uses LightGBM",
        }
