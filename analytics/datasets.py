"""Admission requires raw captures; review changes are audited independently of labels."""

from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3

from store import audit, canonical, identity, now
from windows import Window

REPO = Path(__file__).resolve().parents[1]


def decoder():
    spec = importlib.util.spec_from_file_location(
        "fxac_records", REPO / "tools/dataset/dataset_tool.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def hash_file(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def admit(db, manifest, metadata):
    manifest = Path(manifest).resolve()
    meta = json.loads(manifest.read_text(encoding="utf-8"))
    if meta.get("status") != "complete":
        raise ValueError("Only finalized captures are admitted")
    required = (
        "client",
        "network",
        "input_source",
        "script_family",
        "behavior",
        "label",
        "route",
        "purpose",
    )
    if any(k not in metadata for k in required):
        raise ValueError(
            "Missing metadata: " + ", ".join(k for k in required if k not in metadata)
        )
    if metadata["input_source"] not in ("human", "scripted", "unknown"):
        raise ValueError("Input source must be human, scripted or unknown")
    if metadata["label"] not in ("legit", "cheat") or metadata["purpose"] not in (
        "development",
        "validation",
    ):
        raise ValueError("Invalid label or data purpose")
    if metadata["label"] != meta["declared_label"]:
        raise ValueError("Metadata cannot silently relabel a raw capture")
    # The original controller added an input-mode suffix; its Timer DB stored the product ID.
    # Preserve that exact source declaration while accepting only these known legacy aliases.
    aliases = {"vanilla-auto": "vanilla", "vape-auto": "vape"}
    client_ids = {meta["client"], aliases.get(meta["client"], meta["client"])}
    if metadata["behavior"] != meta["module"] or metadata["client"] not in client_ids:
        raise ValueError("Behavior and client must match the original declaration")
    if "configuration" in metadata and metadata["configuration"] != meta["setting"]:
        raise ValueError("Client configuration must match the raw declaration")
    if metadata["purpose"] == "validation":
        job = db.execute(
            "SELECT created_ms,metadata_json FROM collection_jobs WHERE id=?",
            (meta["scenario"],),
        ).fetchone()
        if (
            not job
            or job["created_ms"] > meta["start_epoch_ms"]
            or json.loads(job["metadata_json"]) != metadata
        ):
            raise ValueError(
                "Validation captures must be reserved with plan before recording"
            )
    if metadata["input_source"] == "scripted" and not metadata["script_family"]:
        raise ValueError("Scripted samples need a script-family grouping key")
    source = {
        "manifest": hash_file(manifest),
        "chunks": {
            p.name: hash_file(p)
            for p in sorted(manifest.parent.glob("events-*.acbin.gz"))
        },
    }
    raw = decoder()
    quality = raw.inspect_trial(manifest.parent, meta)
    if not quality["ok"]:
        raise ValueError("Capture audit failed: " + str(quality))
    windows, state, opponents = [], Window(), set()
    for index, e in enumerate(raw.records(manifest.parent)):
        if index == 0 and (e.get("protocol") != 47 or e.get("model") != 10808):
            raise ValueError(
                "Only direct protocol 47 / server model 10808 is supported"
            )
        if e["kind"] == 7 and e.get("target_player") and e.get("target"):
            opponents.add(hashlib.sha256(e["target"].encode()).hexdigest()[:24])
        result = state.accept(e)
        if result is not None:
            windows.append(result)
        if len(windows) > 10000:
            raise ValueError("Shard captures longer than 10000 eligible windows")
    if not windows:
        raise ValueError("No complete trustworthy 30-second windows")
    if source["chunks"] != {
        p.name: hash_file(p) for p in sorted(manifest.parent.glob("events-*.acbin.gz"))
    }:
        raise ValueError("Capture changed during admission")
    if hash_file(manifest) != source["manifest"]:
        raise ValueError("Manifest changed during admission")
    source_hash = identity(source)
    sample_id = meta["trial_id"]
    old = db.execute(
        "SELECT source_hash,metadata_json FROM samples WHERE id=?", (sample_id,)
    ).fetchone()
    if old:
        if (
            old["source_hash"] != source_hash
            or json.loads(old["metadata_json"])["declared"] != metadata
        ):
            raise ValueError(
                "Sample identity already exists with different evidence or declarations"
            )
        return sample_id
    player = hashlib.sha256(meta["player_uuid"].encode()).hexdigest()[:24]
    day = (
        datetime.fromtimestamp(meta["start_epoch_ms"] / 1000, timezone.utc)
        .date()
        .isoformat()
    )
    record = dict(
        raw=source,
        declared=metadata,
        audit=quality,
        opponent_groups=sorted(opponents),
        window_version="behavior-v1",
        source_client=meta["client"],
    )
    db.execute(
        "INSERT INTO samples VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (
            sample_id,
            str(manifest),
            source_hash,
            player,
            day,
            metadata["client"],
            metadata["network"],
            metadata["input_source"],
            metadata["script_family"],
            metadata["behavior"],
            metadata["label"],
            str(metadata["route"]),
            "declared",
            "",
            metadata["purpose"],
            canonical(record),
            now(),
        ),
    )
    db.executemany(
        "INSERT INTO sample_windows VALUES(?,?,?)",
        [(sample_id, n, canonical(values)) for n, values in enumerate(windows)],
    )
    audit(
        db,
        "sample.admit",
        sample_id,
        dict(source_hash=source_hash, windows=len(windows)),
    )
    return sample_id


def import_timer(db, database):
    database = Path(database).resolve()
    with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as old:
        old.row_factory = sqlite3.Row
        trials = list(
            old.execute(
                """SELECT t.* FROM trials t JOIN timer_evaluation e ON t.id=e.trial_id
                                    JOIN timer_models m ON m.id=e.model_id WHERE m.active=1"""
            )
        )
    for t in trials:
        path = Path(t["manifest_path"])
        if not path.is_absolute():
            path = database.parent / path
        if hash_file(path) != t["manifest_sha256"]:
            raise ValueError("Original Timer manifest changed")
        admit(
            db,
            path,
            dict(
                client=t["client"],
                network="local-loopback",
                input_source="scripted",
                script_family=t["generator"],
                behavior="timer" if t["condition"] == "hacking" else "none",
                label="cheat" if t["condition"] == "hacking" else "legit",
                route=str(t["seed"]),
                purpose="development",
            ),
        )
    return len(trials)


def review(db, sample, reviewer, outcome, reference=None):
    if not reviewer.strip() or outcome not in ("reviewed", "rejected"):
        raise ValueError("Provide reviewer and reviewed/rejected outcome")
    row = db.execute("SELECT * FROM samples WHERE id=?", (sample,)).fetchone()
    if not row:
        raise ValueError("Unknown sample")
    evidence = None
    if outcome == "reviewed":
        if (
            not reference
            or not Path(reference).is_file()
            or Path(reference).stat().st_size == 0
        ):
            raise ValueError(
                "Reviewed labels require a nonempty independent review artifact (video, signed notes, or network capture)"
            )
        path = Path(reference).resolve()
        if path == Path(row["manifest_path"]).resolve():
            raise ValueError("The raw declaration is not independent review evidence")
        evidence = dict(path=str(path), sha256=hash_file(path))
    metadata = json.loads(row["metadata_json"])
    metadata["review_evidence"] = evidence
    db.execute(
        "UPDATE samples SET review=?,reviewer=?,metadata_json=? WHERE id=?",
        (outcome, reviewer, canonical(metadata), sample),
    )
    audit(
        db,
        "sample.review",
        sample,
        dict(reviewer=reviewer, outcome=outcome, evidence=evidence),
    )
