"""V3's zero labels, cutoff boundary, and rank fusion."""

import unittest
from datetime import date, timedelta
from unittest.mock import patch

from hotspot.core.registry import get_algorithm
from hotspot.core.v3.model import V3Algorithm, V3Spec, _training_rows


class ReverseRanker:
    def predict(self, features):
        return [-row[0] for row in features]


class LightGBMV3Tests(unittest.TestCase):
    def test_cold_app_uses_v1_baseline(self):
        start = date(2026, 1, 1)
        days = {start + timedelta(days=i): {"a": 100 + i} for i in range(8)}
        with patch("hotspot.core.v3.model._fit_ranker") as ranker:
            items = V3Algorithm().predict_items(days, "app", start + timedelta(days=7),
                                                1, V3Spec(min_training_groups=1))
        ranker.assert_not_called()
        self.assertIn("冷启动使用v1排序", items[0]["reason"])

    def test_zero_label_and_completed_window_only(self):
        start = date(2026, 1, 1)
        days = {
            start: {"a": 100, "b": 10, "c": 5},
            start + timedelta(days=1): {"a": 50, "b": 1},
            start + timedelta(days=2): {"a": 20},
        }
        spec = V3Spec(min_training_groups=1)
        x1, y1, groups1 = _training_rows(days, start + timedelta(days=1), 1, spec)
        self.assertEqual(groups1, [3])
        self.assertEqual(y1[-1], 0)  # c had no record on the future day
        changed = {day: dict(items) for day, items in days.items()}
        changed[start + timedelta(days=2)] = {"c": 10**20}
        self.assertEqual((x1, y1, groups1),
                         _training_rows(changed, start + timedelta(days=1), 1, spec))

    def test_blend_retains_v1_prior_and_full_candidate_pool(self):
        start = date(2026, 1, 1)
        days = {start + timedelta(days=i): {"a": 100, "b": 10, "c": 1}
                for i in range(45)}
        algorithm = get_algorithm("hotspot-lightgbm-v3")
        self.assertIsInstance(algorithm, V3Algorithm)
        cutoff = start + timedelta(days=35)
        with patch("hotspot.core.v3.model._fit_ranker", return_value=ReverseRanker()):
            blended = algorithm.predict_items(days, "app", cutoff, 1,
                                              V3Spec(min_training_groups=1))
            model_only = algorithm.predict_items(days, "app", cutoff, 1,
                                                 V3Spec(min_training_groups=1,
                                                        model_weight=1))
        self.assertEqual([item["fileKey"] for item in blended], ["a", "b", "c"])
        self.assertEqual([item["fileKey"] for item in model_only], ["c", "b", "a"])
        self.assertEqual([item["rank"] for item in blended], [1, 2, 3])
        self.assertTrue(all(item["score"] is None for item in blended))


if __name__ == "__main__":
    unittest.main()
