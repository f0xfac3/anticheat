"""Compare raw recordings through the shipped C++ engine with the SQLite baseline.

Temporary decompressed streams never enter the dataset. Findings remain assessments.
"""

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile


def replay(database, executable):
    db = sqlite3.connect(database)
    model, alpha = db.execute("SELECT id,alpha FROM timer_models WHERE active=1").fetchone()
    reference = [
        r[0]
        for r in db.execute(
            "SELECT score FROM timer_reference WHERE model_id=? ORDER BY score", (model,)
        )
    ]
    config = (
        "trace=false\ntimer.baseline.enabled=true\ntimer.baseline.model="
        + model
        + "\ntimer.baseline.alpha="
        + str(alpha)
        + "\ntimer.baseline.reference="
        + ",".join(map(str, reference))
        + "\n"
    )
    checked = 0
    conditions = {}
    with tempfile.TemporaryDirectory(prefix="anticheat-replay-") as temp:
        temp = Path(temp)
        (temp / "engine.conf").write_text(config, encoding="ascii")
        query = "SELECT t.id,t.manifest_path,t.score,t.packets,t.excess_ms,t.condition,t.manifest_sha256 FROM trials t JOIN timer_evaluation e ON t.id=e.trial_id WHERE e.model_id=?"
        for tid, path, score, packets, excess, role, expected in db.execute(query, (model,)):
            manifest = Path(path)
            if not manifest.is_absolute():
                manifest = Path(database).resolve().parent / manifest
            if hashlib.sha256(manifest.read_bytes()).hexdigest() != expected:
                raise AssertionError(f"{tid}: manifest hash mismatch")
            with (temp / "events.bin").open("wb") as stream:
                for chunk in sorted(manifest.parent.glob("events-*.acbin.gz")):
                    with gzip.open(chunk, "rb") as source:
                        while block := source.read(1 << 20):
                            stream.write(block)
            run = subprocess.run(
                [str(executable), str(temp / "events.bin"), str(temp / "engine.conf")],
                capture_output=True,
                text=True,
                check=True,
            )
            findings = [json.loads(line) for line in run.stdout.splitlines()]
            baseline = [f for f in findings if f["check"] == "timer.baseline.v1"]
            if len(baseline) != 1:
                raise AssertionError(f"{tid}: native episodes {len(baseline)} != 1")
            f = baseline[0]["evidence"]
            if (
                abs(float(f["score_pps"]) - score) > 1e-9
                or int(f["packets"]) != packets
                or float(f["excess_ms"]) != excess
            ):
                raise AssertionError(f"{tid}: Python/native episode mismatch")
            checked += 1
            conditions.setdefault(role, []).append(
                dict(trial=tid, score=score, tail=float(f["tail_p"]), eligible=f["eligible"])
            )
    db.close()
    return dict(ok=True, trials=checked, model=model, conditions=conditions)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--database", required=True)
    p.add_argument("--engine", required=True)
    a = p.parse_args()
    print(json.dumps(replay(a.database, Path(a.engine)), indent=2))
