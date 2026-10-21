import csv
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

from hotspot.adapters.pagination import paginate_result
from hotspot.core.v1.predictor import predict_items
from hotspot.core.v1.spec import ModelSpec
from hotspot.data.csv_reader import _bytes, read_observations
from hotspot.training.pipeline import rolling_backtest, select_parameters


class HotspotTests(unittest.TestCase):
    def test_large_bytes_scientific_notation_and_invalid_values(self):
        self.assertEqual(_bytes("1.23E+40"), 123 * 10**38)
        with self.assertRaises(ValueError):
            _bytes("1.2")
        with self.assertRaises(ValueError):
            _bytes("-1")
        with self.assertRaises(ValueError):
            _bytes("1E+128")

    def test_csv_app_isolation_and_conflicting_duplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "normalized.csv"
            with path.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(("fileKey", "date", "trafficBytes", "appId"))
                writer.writerow(("same", "2026-01-01", "1E+3", "a"))
                writer.writerow(("same", "2026-01-01", "1000", "a"))
                writer.writerow(("same", "2026-01-01", "9999", "b"))
            self.assertEqual(read_observations(path, "a", "B"),
                             {date(2026, 1, 1): {"same": 1000}})
            with path.open("a", encoding="utf-8") as stream:
                stream.write("same,2026-01-01,1001,a\n")
            with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
                read_observations(path, "a", "B")

    def test_no_future_leakage_stable_order_and_full_window_bytes(self):
        start = date(2026, 1, 1)
        days = {
            start: {"b": 10, "a": 10, **{f"zero-{i}": 0 for i in range(48)}},
            start + timedelta(days=1): {"future": 10**100},
        }
        items = predict_items(days, "a", start, 7, ModelSpec("mean", 1))
        self.assertEqual([(i["rank"], i["fileKey"], i["predictedBytes"])
                          for i in items[:2]], [(1, "a", "70"), (2, "b", "70")])

    def test_missing_cutoff_expands_and_marks_fallback(self):
        start = date(2026, 1, 1)
        days = {start: {"file": 8}, start + timedelta(days=2): {"other": 1}}
        items = predict_items(days, "a", start + timedelta(days=1), 1,
                              ModelSpec("last_day", 1))
        self.assertEqual(items[0]["fileKey"], "file")
        self.assertGreater(int(items[0]["predictedBytes"]), 0)
        self.assertIn("截止日无观测", items[0]["reason"])

    def test_backtest_uses_past_for_selection_and_future_for_test(self):
        start = date(2026, 1, 1)
        days = {start + timedelta(days=i): {"a": 10 + i, "b": 5}
                for i in range(35)}
        report = rolling_backtest(days, "a", 7)
        self.assertLess(date.fromisoformat(report["selectionEndDate"]) + timedelta(days=7),
                        date.fromisoformat(report["testStartDate"]))
        self.assertGreater(report["test"]["cutoffCount"], 0)
        self.assertEqual(set(report["selection"]),
                         {"last_day", "mean_7", "decay_3_half_0_5", "decay_14_half_3"})

    def test_train_reports_selection_without_held_out_metrics(self):
        start = date(2026, 1, 1)
        days = {start + timedelta(days=i): {"a": 10 + i, "b": 5}
                for i in range(35)}
        trained = select_parameters(days, "a", 7)
        backtest = rolling_backtest(days, "a", 7)
        self.assertNotIn("test", trained)
        self.assertEqual(trained["selectedSpec"], backtest["selectedSpec"])
        self.assertLess(date.fromisoformat(trained["selectionEndDate"]) + timedelta(days=7),
                        date.fromisoformat(trained["testStartDate"]))

    def test_pagination_keeps_global_ranks_and_total(self):
        run = {"total": 3, "items": [{"rank": n} for n in (1, 2, 3)]}
        page = paginate_result(run, 2, 2)
        self.assertEqual((page["page"], page["pageSize"], page["total"]), (2, 2, 3))
        self.assertEqual(page["items"], [{"rank": 3}])


if __name__ == "__main__":
    unittest.main()
