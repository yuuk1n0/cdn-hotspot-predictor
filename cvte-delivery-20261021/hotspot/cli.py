"""Command-line entry point for the hotspot algorithm.

Run from the repository root with ``python3 -m hotspot.cli``. This module does not
replace the unavailable predictor service or its task store.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .analysis import write_analysis
from .core.registry import DEFAULT_VERSION, available_versions, get_algorithm
from .core.v1.config import has_reviewed_config
from .core.v1.model import V1Algorithm
from .data.csv_reader import _date, read_observations
from .evaluation.compare import compare_v1_v2
from .startup import annotate_file_history, assess_app_history, suspect_partial_cutoff
from .training.auto import FALLBACK, auto_select_v1
from .training.pipeline import rolling_backtest, select_parameters
from .training.selection import load_selected_spec


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    temporary.replace(path)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Observed CDN file hotspot algorithm")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("predict", "backtest", "train"):
        command = commands.add_parser(name)
        command.add_argument("--input", required=True, type=Path,
                             help="READY normalized.csv or file-level source CSV")
        command.add_argument("--app-id", required=True)
        command.add_argument("--source-unit", required=True, choices=("B",),
                             help="Explicit confirmation that file traffic is bytes")
        command.add_argument("--horizon-days", required=True, type=int, choices=(1, 7))
        command.add_argument("--output", required=True, type=Path)
        command.add_argument("--model-version", default=DEFAULT_VERSION,
                             choices=available_versions())
    predict = commands.choices["predict"]
    predict.add_argument("--cutoff-date", required=True)
    predict.add_argument("--task-id", required=True)
    predict.add_argument("--dataset-id", required=True)
    predict.add_argument("--dataset-source", required=True, choices=("REAL", "FAKE"))
    predict.add_argument("--selection-file", type=Path,
                         help="App and horizon specific JSON from train; uses its selected parameters")
    predict.add_argument("--auto-select", action="store_true",
                         help="Re-evaluate recent completed windows even for a reviewed App")
    for name in ("backtest", "train"):
        commands.choices[name].add_argument(
            "--exclude-last-days", type=int, default=0,
            help="Exclude possibly truncated final dates from labels")
    analyze = commands.add_parser("analyze", help="Write metrics, predictions, and SVG charts")
    analyze.add_argument("--input", required=True, type=Path)
    analyze.add_argument("--app-id", required=True)
    analyze.add_argument("--source-unit", required=True, choices=("B",))
    analyze.add_argument("--horizon-days", choices=("1", "7", "both"), default="both")
    analyze.add_argument("--exclude-last-days", type=int, default=0)
    analyze.add_argument("--output-root", type=Path, default=Path("results"))
    analyze.add_argument("--model-version", default=DEFAULT_VERSION,
                         choices=available_versions())
    compare = commands.add_parser("compare", help="Compare v2 and v1 on the same holdout")
    compare.add_argument("--input", required=True, type=Path)
    compare.add_argument("--app-id", required=True)
    compare.add_argument("--source-unit", required=True, choices=("B",))
    compare.add_argument("--horizon-days", required=True, type=int, choices=(1, 7))
    compare.add_argument("--exclude-last-days", type=int, default=0)
    compare.add_argument("--output", required=True, type=Path)
    return parser


def main() -> None:
    args = _parser().parse_args()
    days = read_observations(args.input, args.app_id, args.source_unit,
                             allow_empty=args.command == "predict")
    if args.command == "compare":
        _write_json(args.output, compare_v1_v2(
            days, args.app_id, args.horizon_days, args.exclude_last_days
        ))
        print(f"Wrote {args.output}")
        return
    algorithm = get_algorithm(args.model_version)
    if args.command == "analyze":
        horizons = (1, 7) if args.horizon_days == "both" else (int(args.horizon_days),)
        folder = write_analysis(days, args.app_id, args.input, args.output_root,
                                horizons, args.exclude_last_days, algorithm)
        print(f"Wrote {folder}")
        return
    if args.command == "train":
        result = select_parameters(days, args.app_id, args.horizon_days,
                                   args.exclude_last_days, algorithm)
    elif args.command == "backtest":
        result = rolling_backtest(days, args.app_id, args.horizon_days,
                                  args.exclude_last_days, algorithm=algorithm)
    else:
        cutoff = _date(args.cutoff_date)
        partial_cutoff = suspect_partial_cutoff(days, cutoff)
        if partial_cutoff:
            days = {day: values for day, values in days.items() if day != cutoff}
        history = assess_app_history(days, cutoff, args.horizon_days)
        selected = None
        evidence = None
        spec = None
        if args.auto_select and not isinstance(algorithm, V1Algorithm):
            raise ValueError("--auto-select currently supports hotspot-simple-v1 only")
        if history.observed_days == 0:
            items = []
            parameter_source = "NO_APP_HISTORY"
        elif args.selection_file:
            selected, spec = load_selected_spec(
                args.selection_file, algorithm, args.app_id, args.horizon_days, cutoff
            )
            parameter_source = "TRAIN_SELECTION"
        elif history.state == "COLD" and isinstance(algorithm, V1Algorithm):
            selected, spec = "last_day", FALLBACK
            parameter_source = "APP_COLD_FALLBACK"
        elif isinstance(algorithm, V1Algorithm) and (
                args.auto_select or not has_reviewed_config(args.app_id, args.horizon_days)):
            selected, spec, evidence = auto_select_v1(
                days, args.app_id, cutoff, args.horizon_days
            )
            parameter_source = "AUTO_SELECTION"
        else:
            spec = algorithm.configured_spec(args.app_id, args.horizon_days)
            selected = next((name for name, candidate in algorithm.candidates.items()
                             if candidate == spec), None)
            if history.state == "COLD":
                parameter_source = "APP_COLD_FALLBACK"
            else:
                parameter_source = ("REVIEWED_CONFIG" if isinstance(algorithm, V1Algorithm)
                                    else "CONFIGURED")
        if spec is not None:
            items = algorithm.predict_items(days, args.app_id, cutoff, args.horizon_days, spec)
            annotate_file_history(items, days, cutoff)
        result = {
            "taskId": args.task_id,
            "runId": "run_" + hashlib.sha256(args.task_id.encode("utf-8")).hexdigest()[:24],
            "datasetId": args.dataset_id,
            "appId": args.app_id,
            "cutoffDate": cutoff.isoformat(),
            "horizonDays": args.horizon_days,
            "predictionStartDate": (cutoff + timedelta(days=1)).isoformat(),
            "predictionEndDate": (cutoff + timedelta(days=args.horizon_days)).isoformat(),
            "modelVersion": algorithm.version,
            "selectedMethod": selected,
            "selectedSpec": algorithm.describe_spec(spec) if spec is not None else None,
            "parameterSource": parameter_source,
            "appHistory": history.as_dict(),
            "dataQuality": {
                "excludedSuspectCutoffDate": cutoff.isoformat() if partial_cutoff else None,
                "reason": ("截止日文件记录数低于前7天中位数的5%，已从预测输入排除"
                           if partial_cutoff else None),
            },
            "selectionEvidence": evidence,
            "candidatePolicy": "observed files only; unseen files cannot be ranked",
            "datasetSource": args.dataset_source,
            "predictionSource": "MODEL" if items else "NONE",
            "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "total": len(items),
            "items": items,
        }
    _write_json(args.output, result)
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
