import json
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

from hotspot.core.v1.model import V1Algorithm
from hotspot.core.v1.config import config_for
from hotspot.training.pipeline import select_parameters
from hotspot.training.selection import load_selected_spec


class SelectionTests(unittest.TestCase):
    def test_reviewed_app_configs_match_selection_artifacts(self):
        algorithm = V1Algorithm()
        root = Path(__file__).resolve().parents[1]
        for app in ("10066", "10350"):
            for horizon in (1, 7):
                with self.subTest(app=app, horizon=horizon):
                    path = root / "configs" / f"{app}-h{horizon}.json"
                    _, spec = load_selected_spec(path, algorithm, app, horizon,
                                                 date(2026, 10, 21))
                    self.assertEqual(config_for(app, horizon), spec)

    def test_selection_is_scoped_to_app_horizon_version_and_past_labels(self):
        algorithm = V1Algorithm()
        start = date(2026, 1, 1)
        days = {start + timedelta(days=index): {"a": 20 + index, "b": 10}
                for index in range(40)}
        report = select_parameters(days, "app-a", 7, algorithm=algorithm)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "selection.json"

            def write(value):
                path.write_text(json.dumps(value), encoding="utf-8")

            write(report)
            method, spec = load_selected_spec(path, algorithm, "app-a", 7, max(days))
            self.assertEqual(method, report["selectedMethod"])
            self.assertEqual(algorithm.describe_spec(spec), report["selectedSpec"])
            for app, horizon in (("app-b", 7), ("app-a", 1)):
                with self.subTest(app=app, horizon=horizon):
                    with self.assertRaisesRegex(ValueError, "does not match"):
                        load_selected_spec(path, algorithm, app, horizon, max(days))
            with self.assertRaisesRegex(ValueError, "after the prediction cutoff"):
                load_selected_spec(path, algorithm, "app-a", 7,
                                   date.fromisoformat(report["selectionEndDate"]))
            changed = {**report, "selectedSpec": {"method": "last_day", "window": 30}}
            write(changed)
            with self.assertRaisesRegex(ValueError, "selectedSpec does not match"):
                load_selected_spec(path, algorithm, "app-a", 7, max(days))


if __name__ == "__main__":
    unittest.main()
