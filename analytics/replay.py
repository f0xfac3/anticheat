"""Verify that training features exactly match the live Java implementation."""

import argparse
import json
import math
from pathlib import Path
import sqlite3
import subprocess

from datasets import decoder, REPO
from windows import Window


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument(
        "--database", type=Path, default=REPO / "examples/timer/anticheat.sqlite"
    )
    args = parser.parse_args()
    raw = decoder()
    count = windows = 0
    with sqlite3.connect(args.database) as db:
        paths = [Path(r[0]) for r in db.execute("SELECT manifest_path FROM trials")]
    for path in paths:
        path = path if path.is_absolute() else args.database.parent / path
        state, expected = Window(), []
        for event in raw.records(path.parent):
            values = state.accept(event)
            if values is not None:
                expected.append(values)
        output = subprocess.check_output(
            [
                args.java,
                "-cp",
                str(REPO / "build/adapter-test-classes"),
                "dev.fox.anticheat.report.BehaviorReplayTest",
                *map(str, sorted(path.parent.glob("events-*.acbin.gz"))),
            ],
            text=True,
        )
        actual = [json.loads(line) for line in output.splitlines()]
        assert len(expected) == len(actual), (path, len(expected), len(actual))
        for a, b in zip(expected, actual):
            for key, value in a.items():
                assert math.isclose(value, b[key], rel_tol=1e-8, abs_tol=1e-8), (
                    path,
                    key,
                    value,
                    b[key],
                )
        count += 1
        windows += len(actual)
    print(
        f"Feature parity: {count} real captures, {windows} windows, all features agree"
    )


if __name__ == "__main__":
    main()
