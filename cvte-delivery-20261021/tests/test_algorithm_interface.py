"""Version routing must not depend on the v1 parameter type."""

import json
import tempfile
import unittest
from contextlib import redirect_stderr
from datetime import date, timedelta
from io import StringIO
from pathlib import Path

from hotspot.analysis import write_analysis
from hotspot.cli import _parser
from hotspot.core.registry import DEFAULT_VERSION, get_algorithm


class AlternateAlgorithm:
    version = "test-v2"
    candidates = {"constant": 5}
    selection_rule = "only available candidate"

    def __init__(self):
        self.calls = 0

    def configured_spec(self, app_id, horizon_days):
        return 5

    def predict_items(self, days, app_id, cutoff, horizon_days, spec):
        self.calls += 1
        if spec != 5:
            raise AssertionError("wrong algorithm specification")
        return [{"rank": 1, "fileKey": "a", "predictedBytes": str(5 * horizon_days),
                 "score": None, "reason": "constant", "fileType": None,
                 "fileSizeBytes": None, "recentActualBytes": None,
                 "compressionStatus": None}]

    def select_candidate(self, selection_results):
        return "constant"

    def describe_spec(self, spec):
        return {"constant": spec}


class AlgorithmInterfaceTests(unittest.TestCase):
    def test_analysis_accepts_another_algorithm_and_spec_type(self):
        start = date(2026, 1, 1)
        days = {start + timedelta(days=index): {"a": 10} for index in range(35)}
        algorithm = AlternateAlgorithm()
        with tempfile.TemporaryDirectory() as directory:
            folder = write_analysis(days, "app", Path("example.csv"), Path(directory),
                                    horizons=(1,), algorithm=algorithm)
            summary = json.loads((folder / "summary.json").read_text(encoding="utf-8"))
            prediction = json.loads((folder / "1-prediction.json").read_text(encoding="utf-8"))
            report = (folder / "report.md").read_text(encoding="utf-8")
        self.assertGreater(algorithm.calls, 0)
        self.assertEqual(summary["modelVersion"], algorithm.version)
        self.assertEqual(prediction["modelVersion"], algorithm.version)
        self.assertEqual(summary["horizons"]["1"]["backtest"]["selectedSpec"],
                         {"constant": 5})
        self.assertEqual(summary["horizons"]["1"]["backtest"]["selectionRule"],
                         algorithm.selection_rule)
        self.assertIn(algorithm.selection_rule, report)

    def test_cli_selects_version_for_every_action(self):
        parser = _parser()
        common = ["--input", "example.csv", "--app-id", "app", "--source-unit", "B"]
        output = ["--horizon-days", "1", "--output", "result.json"]
        actions = {
            "train": common + output,
            "backtest": common + output,
            "predict": common + output + ["--cutoff-date", "2026-01-01",
                                          "--task-id", "task", "--dataset-id", "data",
                                          "--dataset-source", "REAL"],
            "analyze": common + ["--output-root", "results"],
        }
        for action, args in actions.items():
            with self.subTest(action=action):
                parsed = parser.parse_args([action, *args, "--model-version", DEFAULT_VERSION])
                self.assertEqual(parsed.model_version, get_algorithm().version)
        with redirect_stderr(StringIO()), self.assertRaises(SystemExit):
            parser.parse_args(["train", *actions["train"], "--model-version", "missing"])


if __name__ == "__main__":
    unittest.main()
