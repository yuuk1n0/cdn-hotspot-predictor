import csv
import json
import re
import tempfile
import unittest
from datetime import date, datetime, timedelta
from pathlib import Path
from xml.etree import ElementTree
from zoneinfo import ZoneInfo

from hotspot.analysis import write_analysis
from hotspot.evaluation.charts import trend_chart
from hotspot.evaluation.report import create_output_dir
from hotspot.core.v1.selector import select_method


class AnalysisTests(unittest.TestCase):
    def test_trend_does_not_connect_missing_calendar_days(self):
        result = {"rows": [
            {"cutoffDate": "2026-01-01", "recallAt50": 0.5},
            {"cutoffDate": "2026-01-04", "recallAt50": 0.6},
        ]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trend.svg"
            trend_chart(path, "Trend", result, result, "same", "same")
            root = ElementTree.parse(path).getroot()
            lines = [element for element in root if element.tag.endswith("path")]
            self.assertEqual(len(lines), 1)
            self.assertEqual(lines[0].attrib["d"].count("M"), 2)

    def test_close_methods_choose_simpler_without_test_data(self):
        selection = {
            "last_day": {"mean": {"recallAt50": 0.50, "ndcgAt50": 0.61}},
            "mean_7": {"mean": {"recallAt50": 0.48, "ndcgAt50": 0.60}},
            "decay_3_half_0_5": {"mean": {"recallAt50": 0.505, "ndcgAt50": 0.615}},
            "decay_14_half_3": {"mean": {"recallAt50": 0.49, "ndcgAt50": 0.59}},
        }
        self.assertEqual(select_method(selection), "last_day")

    def test_timestamp_folder_and_collision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            now = datetime(2026, 10, 21, 9, 8, 7, tzinfo=ZoneInfo("Asia/Shanghai"))
            self.assertEqual(create_output_dir(root, now).name, "20261021_090807")
            self.assertEqual(create_output_dir(root, now).name, "20261021_090807_01")

    def test_analysis_writes_metrics_figures_and_unknown_observations(self):
        start = date(2026, 1, 1)
        days = {}
        for index in range(35):
            items = {"a": 100 + index, "b": 50}
            if index <= 27:
                items["gone"] = 20
            days[start + timedelta(days=index)] = items
        with tempfile.TemporaryDirectory() as directory:
            folder = write_analysis(days, "test-app", Path("example.csv"),
                                    Path(directory), (1, 7))
            self.assertRegex(folder.name, r"^\d{8}_\d{6}(?:_\d{2})?$")
            summary = json.loads((folder / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(set(summary["horizons"]), {"1", "7"})
            self.assertIn("configuredTest", summary["horizons"]["7"]["backtest"])
            self.assertIn("rows", summary["horizons"]["1"]["backtest"]["test"])
            with (folder / "1-top50.csv").open(encoding="utf-8", newline="") as stream:
                rows = list(csv.DictReader(stream))
            gone = next(row for row in rows if row["fileKey"] == "gone")
            self.assertEqual(gone["observedBytes"], "")
            self.assertEqual(gone["observedInFuture"], "False")
            with (folder / "metrics.csv").open(encoding="utf-8", newline="") as stream:
                metric_rows = list(csv.DictReader(stream))
            self.assertTrue(any(row["phase"] == "test-configured" for row in metric_rows))
            self.assertIn("未来可观测文件", (folder / "report.md").read_text(encoding="utf-8"))
            for image in (folder / "charts").glob("*.svg"):
                ElementTree.parse(image)
            self.assertEqual(len(list((folder / "charts").glob("*.svg"))), 6)


if __name__ == "__main__":
    unittest.main()
