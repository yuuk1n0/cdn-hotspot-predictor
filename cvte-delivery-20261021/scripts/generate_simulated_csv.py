"""Generate two deterministic, previously unseen App CSVs for manual validation."""

from __future__ import annotations

import argparse
import csv
import math
import random
from datetime import date, timedelta
from pathlib import Path


def generate(output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    rng = random.Random(20261021)
    start = date(2026, 1, 1)

    with (output_dir / "dense_new_app.csv").open(
            "w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(("fileKey", "date", "trafficBytes", "appId"))
        for index in range(100):
            day = start + timedelta(days=index)
            for file_number in range(60):
                level = 32_000_000 if file_number < 10 else 5_000_000
                traffic = int(level * (0.75 + 0.5 * rng.random())
                              * (1 + 0.12 * math.sin(index / 9 + file_number)))
                writer.writerow((f"dense/stable-{file_number:03}.mp4",
                                 day.isoformat(), traffic, "99001"))
            for file_number in range(15):
                slope = ((index + 5) / 105 if file_number < 8
                         else (105 - index) / 105)
                traffic = int(12_000_000 * slope * (0.8 + 0.4 * rng.random()))
                writer.writerow((f"dense/trend-{file_number:03}.mp4",
                                 day.isoformat(), max(1, traffic), "99001"))
            for file_number in range(15):
                active = 1.0 if day.weekday() == file_number % 7 else 0.08
                traffic = int(45_000_000 * active * (0.8 + 0.4 * rng.random()))
                writer.writerow((f"dense/weekly-{file_number:03}.mp4",
                                 day.isoformat(), traffic, "99001"))
            for file_number in range(10):
                traffic = int(1_500_000 * (0.5 + 5 * rng.random()))
                writer.writerow((f"dense/new-{index:03}-{file_number:02}.mp4",
                                 day.isoformat(), traffic, "99001"))
        partial_day = start + timedelta(days=100)
        for file_number in range(3):
            writer.writerow((f"dense/partial-{file_number}.mp4",
                             partial_day.isoformat(), 99_999_999, "99001"))

    with (output_dir / "sparse_new_app.csv").open(
            "w", encoding="gb18030", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(("文件标识", "日期", "当日流量（B）", "AppID"))
        for index in range(120):
            if index % 17 in (0, 1):
                continue
            day = start + timedelta(days=index)
            date_text = f"{day.year}/{day.month}/{day.day}"
            for file_number in range(8 + index % 23):
                level = 2_000_000 * (1 + file_number % 5)
                if index % 11 == 0 and file_number < 3:
                    level *= 8
                traffic = int(level * (0.7 + 0.6 * rng.random()))
                writer.writerow((f"sparse/recurring-{file_number % 35:02}.mp4",
                                 date_text, traffic, "99002"))
            for file_number in range(3):
                traffic = int(1_000_000 * (0.5 + 10 * rng.random()))
                writer.writerow((f"sparse/new-{index:03}-{file_number}.mp4",
                                 date_text, traffic, "99002"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path,
                        default=Path("/tmp/cvte-unknown-app-simulation"))
    arguments = parser.parse_args()
    generate(arguments.output_dir)
    print(arguments.output_dir)
