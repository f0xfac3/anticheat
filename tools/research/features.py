"""Audited per-trial features. Labels and identifiers never enter the feature vector."""

import hashlib
import json
import math
from pathlib import Path
import sqlite3
import sys

import numpy as np

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/timer"))
from timer import collector
from model import episodes

FEATURES = [
    "movement_pps", "sustained_pps", "interval_median_ms", "interval_cv",
    "burst_fraction", "window_rate_std", "horizontal_step_p95",
    "vertical_step_p95", "turn_p95", "ground_fraction",
]
CADENCE = set(FEATURES[:6])


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def attack_features(times):
    """Macro-analysis inputs, not a bot verdict. Missing attacks stay missing."""
    if len(times) < 100:
        return {"eligible": False, "attacks": len(times), "reason": "needs_100_attacks"}
    intervals = np.diff(times) / 1e6
    if np.any(intervals <= 0):
        return {"eligible": False, "attacks": len(times), "reason": "batched_or_unordered"}
    bins = np.floor(intervals / 5).astype(int)
    counts = np.unique(bins, return_counts=True)[1]
    p = counts / counts.sum()
    return {
        "eligible": True, "attacks": len(times),
        "interval_cv": float(intervals.std() / intervals.mean()),
        "interval_entropy_bits": float(-np.sum(p * np.log2(p))),
        "repeat_within_1ms": float(np.mean(np.abs(np.diff(intervals)) <= 1)),
    }


def extract(events, measured):
    start = int(measured["windows"][0]["start_ns"])
    end = start + 170_000_000_000
    moves = [e for e in events if e["kind"] == 11 and start <= e["ns"] < end]
    intervals = np.diff([e["ns"] for e in moves]) / 1e6
    if len(intervals) < 100 or intervals.mean() <= 0:
        raise ValueError("insufficient movement")
    positioned = [e for e in moves if e["has_pos"]]
    delta = np.diff([e["position"] for e in positioned], axis=0)
    if len(delta) < 2:
        raise ValueError("insufficient positions")
    angles = [e["yaw"] for e in moves if e["has_look"]]
    turn = np.abs((np.diff(angles) + 180) % 360 - 180)
    values = [
        measured["packets"] / 170, measured["score"], np.median(intervals),
        intervals.std() / intervals.mean(), np.mean(intervals < 1),
        np.std([w["count"] / 5 for w in measured["windows"]]),
        np.quantile(np.linalg.norm(delta[:, [0, 2]], axis=1), .95),
        np.quantile(np.abs(delta[:, 1]), .95),
        np.quantile(turn, .95) if len(turn) else 0,
        np.mean([e["ground"] for e in moves]),
    ]
    if not all(math.isfinite(v) for v in values):
        raise ValueError("nonfinite features")
    return dict(zip(FEATURES, map(float, values)))


def stress(events, scenario):
    """Synthetic receive-clock perturbation, never a new independent gameplay trial."""
    first = next(e["ns"] for e in events if e["kind"] == 11)
    result = []
    for event in events:
        e = dict(event)
        if e["kind"] == 11:
            old = e["ns"]
            if scenario == "batch_100ms":
                e["ns"] = first + ((old - first + 99_999_999) // 100_000_000) * 100_000_000
            elif scenario == "pause_1000ms":
                e["ns"] = old + (1_000_000_000 if old - first >= 90_000_000_000 else 0)
            else:
                raise ValueError("unknown stress scenario")
            # Isolate cadence sensitivity; this does not simulate a real server snapshot queue.
            e["sampled_ns"] += e["ns"] - old
        result.append(e)
    return result


def load(database):
    database = Path(database).resolve()
    decoder = collector()
    accepted, rejected = [], []
    with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as db:
        db.row_factory = sqlite3.Row
        models = db.execute("SELECT id FROM timer_models WHERE active=1").fetchall()
        if len(models) != 1:
            raise ValueError("Expected exactly one active Timer model")
        query = """SELECT t.* FROM trials t JOIN timer_evaluation e ON t.id=e.trial_id
                   WHERE e.model_id=? ORDER BY t.seed,t.condition,t.id"""
        trials = list(db.execute(query, (models[0][0],)))
    for t in trials:
        try:
            manifest = Path(t["manifest_path"])
            if not manifest.is_absolute():
                manifest = database.parent / manifest
            if digest(manifest) != t["manifest_sha256"]:
                raise ValueError("manifest hash mismatch")
            meta = json.loads(manifest.read_text(encoding="utf-8"))
            audit = decoder.inspect_trial(manifest.parent, meta)
            if not audit["ok"] or meta["status"] != "complete":
                raise ValueError("raw audit failed: " + str(audit))
            if t["condition"] not in ("legit", "control", "hacking"):
                raise ValueError("unknown declared condition")
            label = int(t["condition"] == "hacking")
            if meta["declared_label"] != ("cheat" if label else "legit"):
                raise ValueError("label mismatch")
            chunks = {p.name: digest(p) for p in manifest.parent.glob("events-*.acbin.gz")}
            events = []
            for event in decoder.records(manifest.parent):
                if len(events) >= 50000:
                    raise ValueError("trial exceeds bounded 50000-event extractor")
                events.append(event)
            if events[0].get("protocol") != 47 or events[0].get("model") != 10808:
                raise ValueError("unsupported client/server protocol")
            measured = list(episodes(events))
            if len(measured) != 1:
                raise ValueError("requires one uninterrupted episode")
            e = measured[0]
            if e["packets"] != t["packets"] or abs(e["score"] - t["score"]) > 1e-9:
                raise ValueError("raw episode disagrees with imported database")
            if chunks != {p.name: digest(p) for p in manifest.parent.glob("events-*.acbin.gz")}:
                raise ValueError("chunks changed during extraction")
            stress_results = {}
            for scenario in ("batch_100ms", "pause_1000ms"):
                perturbed = stress(events, scenario)
                synthetic = list(episodes(perturbed))
                stress_results[scenario] = (
                    dict(status="scored", features=extract(perturbed, synthetic[0]))
                    if len(synthetic) == 1 else dict(status="abstain_incomplete_episode")
                )
            accepted.append({
                "trial": t["id"], "seed": t["seed"], "label": label,
                "condition": t["condition"], "client": t["client"], "day": t["day"],
                "player": meta["player_uuid"], "verification": meta.get("verification"),
                "generator": t["generator"], "observer": t["observer"], "bridge": t["bridge"],
                "manifest_sha256": t["manifest_sha256"], "chunks": chunks,
                "raw_events": len(events), "episode_seconds": 170,
                "features": extract(events, e),
                "synthetic_stress": stress_results,
                "macro": attack_features([x["ns"] for x in events if x["kind"] == 7]),
            })
        except (ValueError, OSError, KeyError) as error:
            rejected.append({"trial": t["id"], "reason": str(error)})
    if len({(r["generator"], r["observer"], r["bridge"]) for r in accepted}) > 1:
        raise ValueError("Mixed capture provenance: evaluate cohorts separately")
    return accepted, rejected
