"""The v2 experiment must respect the cutoff and unknown-label policy."""

import unittest
from datetime import date, timedelta
from unittest.mock import patch

from hotspot.core.registry import available_versions, get_algorithm
from hotspot.core.v2.model import V2Algorithm, V2Spec, _training_rows
from hotspot.evaluation.compare import compare_v1_v2


class FakeRanker:
    def predict(self, features):
        return [-row[0] for row in features]


class LightGBMV2Tests(unittest.TestCase):
    def setUp(self):
        start = date(2026, 1, 1)
        self.days = {
            start + timedelta(days=index): {
                "a": 100 + index, "b": 10 + (index % 5),
                "c": 5 + (index % 3),
            }
            for index in range(45)
        }
        self.cutoff = start + timedelta(days=35)

    def test_version_is_registered_without_importing_lightgbm(self):
        self.assertIn("hotspot-lightgbm-v2", available_versions())
        self.assertIsInstance(get_algorithm("hotspot-lightgbm-v2"), V2Algorithm)

    def test_training_ignores_labels_after_cutoff_and_absent_files(self):
        spec = V2Spec(min_training_groups=1)
        x1, y1, groups1 = _training_rows(self.days, self.cutoff, 7, spec)
        changed = {day: dict(items) for day, items in self.days.items()}
        changed[self.cutoff + timedelta(days=1)] = {"a": 10**30, "future": 10**30}
        x2, y2, groups2 = _training_rows(changed, self.cutoff, 7, spec)
        self.assertEqual((x1, y1, groups1), (x2, y2, groups2))
        self.assertGreater(len(groups1), 0)
        self.assertEqual(sum(groups1), len(y1))
        first = date(2026, 2, 1)
        sparse = {
            first: {"a": 10, "b": 10, "unobserved": 10},
            first + timedelta(days=1): {"a": 100, "b": 1},
        }
        _, _, sparse_groups = _training_rows(
            sparse, first + timedelta(days=1), 1, spec
        )
        self.assertEqual(sparse_groups, [2])

    def test_prediction_uses_lightgbm_order_and_keeps_full_candidate_pool(self):
        algorithm = V2Algorithm()
        spec = V2Spec(min_training_groups=1)
        with patch("hotspot.core.v2.model._fit_ranker", return_value=FakeRanker()) as ranker:
            items = algorithm.predict_items(self.days, "app", self.cutoff, 1, spec)
        ranker.assert_called_once()
        self.assertEqual({item["fileKey"] for item in items}, {"a", "b", "c"})
        self.assertEqual([item["rank"] for item in items], [1, 2, 3])
        self.assertEqual(items[0]["fileKey"], "c")
        self.assertTrue(all(item["score"] is None for item in items))
        self.assertTrue(all(item["predictedBytes"].isdigit() for item in items))

    def test_small_history_falls_back_to_v1_without_fake_labels(self):
        items = V2Algorithm().predict_items(self.days, "app", min(self.days), 1,
                                            V2Spec(min_training_groups=20))
        self.assertTrue(items)
        self.assertIn("冷启动使用v1排序", items[0]["reason"])

    def test_compare_uses_same_holdout_and_candidate_pool(self):
        with patch("hotspot.core.v2.model._fit_ranker", return_value=FakeRanker()):
            result = compare_v1_v2(self.days, "app", 1)
        v1_rows = result["v1SameCandidatePoolTest"]["rows"]
        v2_rows = result["v2Test"]["rows"]
        self.assertEqual([row["cutoffDate"] for row in v1_rows],
                         [row["cutoffDate"] for row in v2_rows])
        self.assertEqual([row["candidateCoverageAt50"] for row in v1_rows],
                         [row["candidateCoverageAt50"] for row in v2_rows])


if __name__ == "__main__":
    unittest.main()
