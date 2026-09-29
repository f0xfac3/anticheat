"""Deterministic transport stress replay. These are synthetic tests, never human evidence."""

import math
from pathlib import Path

from datasets import decoder
from windows import Window


def evaluate(samples, labels, spec, coefficients, intercept, threshold):
    raw = decoder()
    reports = {}
    for name, batch_ns, pause_ns in (
        ("batch_100ms", 100_000_000, 0),
        ("batch_250ms", 250_000_000, 0),
        ("pause_1s", 0, 1_000_000_000),
    ):
        result = dict(tp=0, fp=0, tn=0, fn=0, abstained=0, samples=len(samples))
        for sample in samples:
            state, runs, run, segment, start = Window(), [], [], None, None
            for original in raw.records(Path(sample["manifest_path"]).parent):
                event = dict(original)
                if start is None:
                    start = event["ns"]
                if event["kind"] in (7, 11):
                    ns = event["ns"]
                    shifted = (
                        ((ns + batch_ns - 1) // batch_ns) * batch_ns if batch_ns else ns
                    )
                    if pause_ns and ns - start >= 40_000_000_000:
                        shifted += pause_ns
                    event["sampled_ns"] += shifted - ns
                    event["ns"] = shifted
                values = state.accept(event)
                if values is None:
                    continue
                if (
                    segment != values["segment"]
                    or values["attacks"] < spec["minimum_attacks"]
                ):
                    if run:
                        runs.append(run)
                    run = []
                segment = values["segment"]
                if values["attacks"] >= spec["minimum_attacks"]:
                    run.append(
                        intercept
                        + sum(
                            c * values[f]
                            for c, f in zip(coefficients, spec["features"])
                        )
                    )
            runs.append(run)
            scores = [
                min(run[i : i + spec["consecutive_windows"]])
                for run in runs
                for i in range(len(run) - spec["consecutive_windows"] + 1)
            ]
            if not scores:
                result["abstained"] += 1
                continue
            candidate = max(scores) > threshold
            label = labels[sample["id"]]
            result[
                (
                    "tp"
                    if label and candidate
                    else "fn" if label else "fp" if candidate else "tn"
                )
            ] += 1
        reports[name] = result
    return reports
