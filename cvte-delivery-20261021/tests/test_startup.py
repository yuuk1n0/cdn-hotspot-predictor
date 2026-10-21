import csv
import json
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

from hotspot.cli import main
from hotspot.core.v1.predictor import predict_items
from hotspot.core.v1.spec import ModelSpec
from hotspot.startup import assess_app_history, suspect_partial_cutoff
from hotspot.training.auto import auto_select_v1


class StartupTests(unittest.TestCase):
    def _predict(self, rows, app_id, cutoff):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.csv"
            output = Path(directory) / "prediction.json"
            with source.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(("fileKey", "date", "trafficBytes", "appId"))
                writer.writerows(rows)
            args = ["hotspot.cli", "predict", "--input", str(source),
                    "--app-id", app_id, "--source-unit", "B", "--horizon-days", "1",
                    "--cutoff-date", cutoff.isoformat(), "--task-id", "startup-test",
                    "--dataset-id", "test", "--dataset-source", "REAL",
                    "--output", str(output)]
            with patch.object(sys, "argv", args):
                main()
            return json.loads(output.read_text(encoding="utf-8"))

    def test_no_app_history_returns_empty_with_reason(self):
        result = self._predict([("file", "2026-01-01", 10, "other")],
                               "new-app", date(2026, 1, 1))
        self.assertEqual(result["total"], 0)
        self.assertEqual(result["parameterSource"], "NO_APP_HISTORY")
        self.assertEqual(result["appHistory"]["state"], "COLD")
        self.assertEqual(result["predictionSource"], "NONE")

    def test_cold_app_falls_back_and_marks_cold_file(self):
        start = date(2026, 1, 1)
        rows = [("steady", (start + timedelta(days=i)).isoformat(), 10, "new-app")
                for i in range(5)]
        rows.append(("new-file", (start + timedelta(days=4)).isoformat(), 100,
                     "new-app"))
        result = self._predict(rows, "new-app", start + timedelta(days=4))
        self.assertEqual(result["parameterSource"], "APP_COLD_FALLBACK")
        self.assertEqual(result["selectedSpec"]["method"], "last_day")
        items = {item["fileKey"]: item for item in result["items"]}
        self.assertEqual(items["steady"]["fileStartState"], "WARM")
        self.assertEqual(items["new-file"]["fileStartState"], "COLD")
        self.assertIn("预测不确定", items["new-file"]["reason"])

    def test_new_app_with_history_selects_on_its_own_completed_windows(self):
        start = date(2026, 1, 1)
        rows = [("steady", (start + timedelta(days=i)).isoformat(), 10 + i,
                 "unseen-app") for i in range(40)]
        result = self._predict(rows, "unseen-app", start + timedelta(days=39))
        self.assertEqual(result["appHistory"]["state"], "HOT")
        self.assertEqual(result["parameterSource"], "AUTO_SELECTION")
        self.assertIn(result["selectedMethod"],
                      {"last_day", "mean_7", "decay_3_half_0_5", "decay_14_half_3"})
        self.assertGreater(result["selectionEvidence"]["testCutoffCount"], 0)

    def test_missing_calendar_day_is_not_a_zero_in_prediction_mean(self):
        start = date(2026, 1, 1)
        days = {start: {"a": 10}, start + timedelta(days=2): {"a": 20}}
        prediction = predict_items(days, "app", start + timedelta(days=2), 1,
                                   ModelSpec("mean", 7))
        self.assertEqual(prediction[0]["predictedBytes"], "15")

    def test_stale_app_is_cold_even_with_long_history(self):
        start = date(2026, 1, 1)
        days = {start + timedelta(days=i): {"a": 1} for i in range(40)}
        assessed = assess_app_history(days, start + timedelta(days=50), 1)
        self.assertEqual(assessed.state, "COLD")
        self.assertEqual(assessed.recent_observed_days, 0)

    def test_extreme_last_day_truncation_is_excluded(self):
        start = date(2026, 1, 1)
        rows = []
        for i in range(7):
            rows.extend((f"file-{n}", (start + timedelta(days=i)).isoformat(),
                         100 - n, "app") for n in range(100))
        rows.append(("partial-only", (start + timedelta(days=7)).isoformat(),
                     100000, "app"))
        result = self._predict(rows, "app", start + timedelta(days=7))
        self.assertEqual(result["dataQuality"]["excludedSuspectCutoffDate"],
                         "2026-01-08")
        self.assertNotIn("partial-only", {item["fileKey"] for item in result["items"]})
        self.assertTrue(suspect_partial_cutoff(
            {start + timedelta(days=i): {f"f-{n}": 1 for n in range(100)}
             for i in range(7)} | {start + timedelta(days=7): {"partial-only": 1}},
            start + timedelta(days=7)))

    def test_auto_selection_uses_only_cutoff_history_and_rejects_loss(self):
        start = date(2026, 1, 1)
        days = {start + timedelta(days=i): {"a": i + 1} for i in range(41)}
        cutoff = start + timedelta(days=39)
        report = {
            "selectedMethod": "mean_7", "selectionEndDate": "2026-02-01",
            "testStartDate": "2026-02-03", "metricScope": "observed",
            "test": {"cutoffCount": 5, "mean": {
                "recallAt50": 0.4, "ndcgAt50": 0.4,
                "observedTrafficCoverageAt50": 0.4}},
            "configuredTest": {"mean": {
                "recallAt50": 0.5, "ndcgAt50": 0.5,
                "observedTrafficCoverageAt50": 0.5}},
        }
        with patch("hotspot.training.auto.rolling_backtest", return_value=report) as run:
            method, _, evidence = auto_select_v1(days, "app", cutoff, 1)
        self.assertEqual(method, "last_day")
        self.assertEqual(evidence["decision"], "FALLBACK")
        self.assertLessEqual(max(run.call_args.args[0]), cutoff)


if __name__ == "__main__":
    unittest.main()
