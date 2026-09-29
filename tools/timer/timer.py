"""Import audited trials into SQLite and publish a native Timer reference.

python timer.py import --datasets PATH --database PATH [--date YYYY-MM-DD]
python timer.py status --database PATH
"""

from __future__ import annotations
import argparse
from contextlib import closing
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys

from model import ALGORITHM, decision, episodes

REPO = Path(__file__).resolve().parents[2]
SCHEMA = REPO / "plugin/src/main/resources/timer-schema.sql"


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def connect(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=5)
    db.execute("PRAGMA busy_timeout=5000")
    db.executescript(SCHEMA.read_text(encoding="utf-8"))
    return db


def collector():
    spec = importlib.util.spec_from_file_location(
        "dataset_tool", REPO / "tools/dataset/dataset_tool.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_trial(path, root, decoder):
    a = json.loads(path.read_text(encoding="utf-8"))
    if a.get("status") != "complete" or not a.get("quality", {}).get("ok"):
        raise ValueError("incomplete or unaudited")
    plan = path.with_name("plan.json")
    p = json.loads(plan.read_text(encoding="utf-8"))
    if p["seconds"] != 180:
        raise ValueError("requires 180-second trials")
    manifest = Path(a["raw_manifest"]).resolve()
    manifest.relative_to((root / "raw").resolve())
    if digest(manifest) != a["raw_manifest_sha256"] or digest(plan) != a["plan_sha256"]:
        raise ValueError("source hash changed")
    meta = json.loads(manifest.read_text(encoding="utf-8"))
    role = a["profile"]["role"]
    label = "cheat" if role == "hacking" else "legit"
    if (
        meta["status"] != "complete"
        or meta["declared_label"] != label
        or meta["module"] != ("timer" if label == "cheat" else "none")
    ):
        raise ValueError("manifest condition mismatch")
    if a["trial"] != meta["trial_id"] or a["seed"] != p["seed"]:
        raise ValueError("trial/seed identity mismatch")
    audit = decoder.inspect_trial(manifest.parent, meta)
    if not audit["ok"] or any(audit["kinds"].get(k, 0) for k in (3, 10, 14)):
        raise ValueError("raw audit/discontinuity failed")
    first = next(decoder.records(manifest.parent))
    if first.get("protocol") != 47 or first.get("model") != 10808:
        raise ValueError("Timer baseline requires direct protocol 47 / model 10808")
    chunks = {f.name: digest(f) for f in sorted(manifest.parent.glob("events-*.acbin.gz"))}
    measured = list(episodes(decoder.records(manifest.parent)))
    if len(measured) != 1:
        raise ValueError("requires one complete eligible Timer episode")
    if chunks != {f.name: digest(f) for f in sorted(manifest.parent.glob("events-*.acbin.gz"))}:
        raise ValueError("chunks changed during import")
    source = dict(
        automation=digest(path), manifest=digest(manifest), plan=digest(plan), chunks=chunks
    )
    return dict(
        id=a["trial"],
        seed=a["seed"],
        condition=role,
        client=a["profile"]["client"],
        multiplier=a["profile"]["multiplier"],
        day=datetime.fromtimestamp(a["started_epoch_ms"] / 1000).date().isoformat(),
        seconds=p["seconds"],
        manifest_path=str(manifest),
        manifest_sha256=source["manifest"],
        source_sha256=hashlib.sha256(json.dumps(source, sort_keys=True).encode()).hexdigest(),
        plan_sha256=a["plan_sha256"],
        generator=a["generator_sha256"],
        observer=a["observer_sha256"],
        bridge=a["lab_bridge_sha256"],
        **measured[0],
    )


def store_trial(db, t):
    old = db.execute("SELECT source_sha256 FROM trials WHERE id=?", (t["id"],)).fetchone()
    if old and old[0] != t["source_sha256"]:
        raise ValueError("immutable trial changed after database import")
    columns = [k for k in t if k != "windows"]
    with db:
        db.execute(
            "INSERT OR IGNORE INTO trials ("
            + ",".join(columns)
            + ") VALUES ("
            + ",".join("?" for _ in columns)
            + ")",
            [t[k] for k in columns],
        )
        db.executemany(
            "INSERT OR IGNORE INTO timer_windows VALUES (?,?,?,?)",
            [(t["id"], w["index"], w["start_ns"], w["count"]) for w in t["windows"]],
        )


def ingest(path, datasets, database):
    """Called after each finalized trial. Updating the database never mutates a live model."""
    t = read_trial(Path(path), Path(datasets), collector())
    with closing(connect(database)) as db:
        store_trial(db, t)
        db.row_factory = sqlite3.Row
        trials = [
            dict(r)
            for r in db.execute(
                "SELECT * FROM trials WHERE generator=? AND observer=? AND bridge=?",
                (t["generator"], t["observer"], t["bridge"]),
            )
        ]
        seeds = {
            r["seed"]
            for r in trials
            if r["condition"] in ("legit", "control") and r["multiplier"] == 1
        }
        if len(seeds) >= 5:
            publish(db, trials)
    return t["id"]


def import_trials(db, root, day=None):
    decoder = collector()
    accepted = []
    excluded = []
    for path in sorted(Path(root).glob("automation/sessions/*/trial-*/automation.json")):
        try:
            a = json.loads(path.read_text(encoding="utf-8"))
            if (
                day
                and datetime.fromtimestamp(a["started_epoch_ms"] / 1000).date().isoformat() != day
            ):
                continue
            t = read_trial(path, Path(root), decoder)
            store_trial(db, t)
            accepted.append(t)
        except (OSError, ValueError, KeyError, TypeError) as error:
            excluded.append(dict(path=str(path), reason=str(error)))
    return accepted, excluded


def publish(db, trials, scope="scripted-local-1.8", alpha=0.001):
    if not trials:
        raise ValueError("No eligible 180-second trials")
    provenance = {(t["generator"], t["observer"], t["bridge"]) for t in trials}
    if len(provenance) != 1:
        raise ValueError("Mixed recorder/controller versions; import a single collection date")
    # Use at most one conservative legitimate calibration point per route seed.
    reference = {}
    for t in trials:
        if t["condition"] not in ("legit", "control") or t["multiplier"] != 1:
            continue
        reference[t["seed"]] = max(reference.get(t["seed"], 0), t["score"])
    if len(reference) < 5:
        raise ValueError("Need at least five distinct legitimate route seeds")
    if len(reference) > 4096:
        raise ValueError("Maximum 4096 reference seeds; explicitly select a collection")
    sources = [
        dict(id=t["id"], sha256=t["source_sha256"]) for t in sorted(trials, key=lambda t: t["id"])
    ]
    content = dict(
        algorithm=ALGORITHM,
        scope=scope,
        alpha=alpha,
        reference=sorted(reference.items()),
        sources=sources,
        extractor_sha256=digest(Path(__file__).with_name("model.py")),
        decoder_sha256=digest(REPO / "tools/dataset/dataset_tool.py"),
    )
    serialized = json.dumps(content, sort_keys=True, separators=(",", ":"))
    model_id = hashlib.sha256(serialized.encode()).hexdigest()
    with db:
        db.execute("UPDATE timer_models SET active=0")
        db.execute(
            "INSERT OR IGNORE INTO timer_models VALUES (?,?,?,?,?,?,?,?,?)",
            (
                model_id,
                ALGORITHM,
                datetime.now(timezone.utc).isoformat(),
                scope,
                alpha,
                len(reference),
                1 / (len(reference) + 1),
                0,
                serialized,
            ),
        )
        db.execute("UPDATE timer_models SET active=1 WHERE id=?", (model_id,))
        db.executemany(
            "INSERT OR IGNORE INTO timer_reference VALUES (?,?,?)",
            [(model_id, seed, score) for seed, score in sorted(reference.items())],
        )
        for t in trials:
            # Entire route seed stays out of its evaluation reference in both conditions.
            ref = [v for seed, v in reference.items() if seed != t["seed"]]
            d = decision(ref, t["score"], t["excess_ms"], alpha=alpha)
            db.execute(
                "INSERT OR REPLACE INTO timer_evaluation VALUES (?,?,?,?,?)",
                (model_id, t["id"], d["tail_p"], len(ref), int(d["eligible"])),
            )
    return model_id


def status(db):
    row = db.execute(
        "SELECT id,reference_count,minimum_tail,alpha FROM timer_models WHERE active=1"
    ).fetchone()
    if not row:
        return "Timer: no active baseline"
    mid, n, floor, alpha = row
    lines = [
        f"Timer | model {mid[:12]} | {n} legitimate route seeds",
        f"Empirical tail floor {floor:.6f} | first-episode cutoff {alpha/2:.6f}",
        "Ban eligibility: "
        + (
            "baseline resolution sufficient"
            if floor <= alpha / 2
            else "ABSTAIN - insufficient independent baseline data"
        ),
        "",
        "condition   trials  median score (/s)  eligible bans (leave-one-seed-out)",
    ]
    for role in ("legit", "control", "hacking"):
        rows = db.execute(
            "SELECT t.score,e.eligible FROM trials t JOIN timer_evaluation e ON t.id=e.trial_id WHERE e.model_id=? AND t.condition=?",
            (mid, role),
        ).fetchall()
        if rows:
            from statistics import median

            lines.append(
                f"{role:10} {len(rows):6} {median(r[0] for r in rows):17.3f} {sum(r[1] for r in rows):15}/{len(rows)}"
            )
    lines.extend(
        [
            "",
            "Score: maximum sustained rate across three consecutive 5-second windows.",
            "Tail rank is relative to recorded legitimate episodes, not P(cheating).",
            "Reference scope: scripted local 1.8. Restart the server to load a new model.",
        ]
    )
    return "\n".join(lines)


def sync(datasets, database, day=None):
    with closing(connect(database)) as db:
        trials, excluded = import_trials(db, Path(datasets), day)
        if trials and not day:
            last = trials[-1]
            compatible = lambda t: all(t[k] == last[k] for k in ("generator", "observer", "bridge"))
            excluded.extend(
                dict(
                    path=t["manifest_path"],
                    reason="retained in database; different collection provenance",
                )
                for t in trials
                if not compatible(t)
            )
            trials = [t for t in trials if compatible(t)]
        seeds = {
            t["seed"]
            for t in trials
            if t["condition"] in ("legit", "control") and t["multiplier"] == 1
        }
        mid = publish(db, trials) if len(seeds) >= 5 else None
        text = (
            status(db)
            if mid
            else f"Imported {len(trials)} trials. Need five legitimate route seeds to publish this collection."
        )
        print(text)
        if excluded:
            print(f"Excluded: {len(excluded)} trials; details in import log.")
    result = dict(
        model=mid, imported=len(trials), excluded=excluded, database=str(Path(database).resolve())
    )
    log = Path(datasets) / "automation/latest-import.json"
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return text


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=["import", "status"])
    p.add_argument("--database", required=True)
    p.add_argument("--datasets")
    p.add_argument("--date")
    a = p.parse_args()
    if a.command == "import":
        if not a.datasets:
            p.error("--datasets required for import")
        sync(a.datasets, a.database, a.date)
    else:
        with closing(connect(a.database)) as db:
            print(status(db))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, sqlite3.Error) as error:
        print("ERROR:", error, file=sys.stderr)
        sys.exit(1)
