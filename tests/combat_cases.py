"""Generate reproducible, structurally valid combat scenarios and replay them.

These are synthetic regression cases, not a labeled human/cheat training corpus.
Usage: python tests/combat_cases.py --cases 1000 --replay build/native/anticheat_replay.exe
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import random
import struct
import subprocess
from collections import Counter


def text(value: str) -> bytes:
    data = value.encode("ascii")
    if len(data) > 1024:
        raise ValueError("Identifier too long")
    return struct.pack("<H", len(data)) + data


def context(distance: float, *, eye_z: float = 0.0, ping: int = 10,
            available: bool = True) -> bytes:
    names = ["synthetic-world", "synthetic-target", "ZOMBIE", "" if available else "missing_target"]
    return b"".join(text(s) for s in names) + struct.pack(
        "<i9diB", 7,
        0.0, 65.62, eye_z,
        distance + 0.1, 64.0, -0.3,
        distance + 0.7, 65.8, 0.3,
        ping, available,
    )


class Case:
    def __init__(self, stream, session: int):
        self.stream = stream
        self.session = session
        self.ordinal = 0
        self.packet = 0
        self.time = session * 100_000_000_000
        self.send(1, text(f"synthetic-{session}") + struct.pack("<II", 47, 10808))

    def advance(self, ms: float) -> None:
        self.time += round(ms * 1_000_000)

    def send(self, kind: int, payload: bytes) -> None:
        self.ordinal += 1
        header = struct.pack("<IHH5Q", 0x43415846, 2, kind, self.session,
                             self.ordinal, self.time, 1_789_000_000_000 + self.time // 1_000_000,
                             self.time // 50_000_000)
        message = header + payload
        if len(message) > 8192:
            raise ValueError("Observation exceeds bridge bound")
        self.stream.write(struct.pack("<I", len(message)) + message)

    def frame(self, data: bytes) -> None:
        self.send(8, struct.pack("<Q", self.time) + data)

    def attack(self, data: bytes, *, same_batch: bool = False, queue_ms: float = 0) -> None:
        self.packet += 1
        self.send(7, struct.pack("<3Q", self.packet, 1 if same_batch else self.packet,
                                self.time + round(queue_ms * 1_000_000)) + data)

    def close(self) -> None:
        self.send(2, b"")


def generate(directory: Path, count: int, seed: int) -> list[dict]:
    directory.mkdir(parents=True, exist_ok=True)
    rng = random.Random(seed)
    manifest = []
    with (directory / "cases.events").open("wb") as stream:
        for session in range(1, count + 1):
            case = Case(stream, session)
            row = {"session": str(session), "synthetic": True, "seed": seed,
                   "human_or_cheat_label": "NOT_ASSIGNED", "expected_alerts": {}}
            if session % 2:
                # Restrict one experiment to stationary geometry, then vary one confounder.
                distance = rng.choice([2.8, 3.0, 3.08, 3.2, 3.4, 4.0])
                condition = rng.choice(["stationary", "stationary", "motion", "high_ping", "delayed"])
                ping = 300 if condition == "high_ping" else 10
                for i in range(17):
                    if i:
                        case.advance(50)
                    z = 0.2 if condition == "motion" and i == 16 else 0
                    case.frame(context(distance, eye_z=z, ping=ping))
                for i in range(3):
                    case.advance(80)
                    case.attack(context(distance, ping=ping), queue_ms=400 if condition == "delayed" else 0)
                expected = int(condition == "stationary" and distance > 3.08125)
                row.update(family="reach", distance=distance, condition=condition,
                           model="stationary_server_snapshots")
                row["expected_alerts"]["reach.stationary.v1"] = expected
            else:
                mode = rng.choice(["regular", "regular", "variable", "tick_quantized", "batched", "unavailable"])
                for i in range(121):
                    if i:
                        interval = 75.5
                        if mode == "variable":
                            interval = 55 if i % 2 else 105
                        elif mode == "tick_quantized":
                            interval = 100
                        case.advance(interval)
                    case.attack(context(2.0, available=mode != "unavailable"), same_batch=mode == "batched")
                row.update(family="cadence", condition=mode, model="attack_request_intervals")
                row["expected_alerts"]["autoclicker.cadence.v1"] = int(mode == "regular")
            case.close()
            manifest.append(row)

    with (directory / "manifest.jsonl").open("w", encoding="utf-8") as output:
        for row in manifest:
            output.write(json.dumps(row, separators=(",", ":")) + "\n")
    return manifest


def verify(directory: Path, executable: Path, manifest: list[dict]) -> None:
    # Never label generated data from a detector's own prediction.
    # This compares independently assigned test expectations with actual returned findings.
    output_path = directory / "findings.jsonl"
    with output_path.open("wb") as output:
        result = subprocess.run([str(executable.resolve()), str((directory / "cases.events").resolve())],
                                stdout=output, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
    observed = Counter()
    with output_path.open(encoding="utf-8") as output:
        for line in output:
            finding = json.loads(line)
            if finding["level"] == "suspicious":
                observed[(finding["session"], finding["check"])] += 1
    failures = []
    for row in manifest:
        for check in ("reach.stationary.v1", "autoclicker.cadence.v1"):
            expected = row["expected_alerts"].get(check, 0)
            actual = observed[(row["session"], check)]
            if expected != actual:
                failures.append({**row, "check": check, "expected": expected, "actual": actual})
    (directory / "failures.json").write_text(json.dumps(failures, indent=2), encoding="utf-8")
    if failures:
        raise RuntimeError(f"{len(failures)} mismatches; see {directory / 'failures.json'}")
    print(result.stderr.decode("utf-8", errors="replace").strip())
    print(f"PASS {len(manifest)} synthetic scenarios; {sum(observed.values())} expected alerts")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=int, default=100)
    parser.add_argument("--seed", type=int, default=47)
    parser.add_argument("--output", type=Path, default=Path("build/combat-cases"))
    parser.add_argument("--replay", type=Path)
    args = parser.parse_args()
    if not 1 <= args.cases <= 100_000:
        parser.error("--cases must be between 1 and 100000")
    rows = generate(args.output, args.cases, args.seed)
    print(f"Generated {len(rows)} synthetic scenarios in {args.output}")
    if args.replay:
        verify(args.output, args.replay, rows)


if __name__ == "__main__":
    main()
