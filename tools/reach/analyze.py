"""Audit and replay the recorded Reach study. Standard library; no live server required."""

import argparse
from collections import Counter
from contextlib import closing
import gzip
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[2]
CHECK = "reach.stationary.v1"
GUARD_NS = 5_000_000_000


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def decoder():
    spec = importlib.util.spec_from_file_location("reach_raw", REPO / "tools/dataset/dataset_tool.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def local(root, name):
    path = (root / name).resolve()
    if root.resolve() not in path.parents:
        raise ValueError("Evidence path escapes study: " + name)
    return path


def checked(root, item):
    path = local(root, item["path"])
    if digest(path) != item["sha256"]:
        raise ValueError("Evidence changed: " + item["path"])
    return path


def export(collection, output, config):
    """Copy original immutable captures, not server binaries or local account configuration."""
    if (output / "catalog.json").exists():
        raise ValueError("An exported study already exists; use a new output directory")
    output.mkdir(parents=True, exist_ok=True)
    catalog = dict(version=1, check=CHECK, guard_ns=GUARD_NS, samples=[], plans=[])
    seen = set()
    for plan_path in sorted(collection.glob("*/plan.json")):
        plan = read(plan_path)
        if plan["engine_config_sha256"] != digest(config):
            raise ValueError("Provide the original engine configuration for " + plan["id"])
        destination = output / "plans" / plan_path.parent.name / "plan.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(plan_path, destination)
        catalog["plans"].append(dict(path=destination.relative_to(output).as_posix(), sha256=digest(destination)))
        for result_path in sorted(plan_path.parent.glob("*.json")):
            if result_path.stem != "off" and not result_path.stem.startswith("on"):
                continue
            result = read(result_path)
            if not result.get("complete") or result.get("faults"):
                raise ValueError("Incomplete study result: " + str(result_path))
            if [p["center_distance"] for p in result["phases"]] != plan["distances"]:
                raise ValueError("Study result does not match its planned distances")
            for phase in result["phases"]:
                trial = phase["trial"]
                if trial in seen or not phase["usable"]:
                    raise ValueError("Duplicate or unusable trial: " + trial)
                seen.add(trial)
                source = Path(phase["manifest"])
                files = {"manifest.json": phase["manifest_sha256"], **phase["chunks"]}
                folder = output / "raw" / trial
                folder.mkdir(parents=True, exist_ok=True)
                evidence = {}
                for name, expected in files.items():
                    path = local(source.parent, name)
                    if digest(path) != expected:
                        raise ValueError("Capture changed: " + str(path))
                    shutil.copyfile(path, folder / name)
                    evidence[name] = dict(path=(folder / name).relative_to(output).as_posix(), sha256=expected)
                native = Path(phase["native"]["path"])
                if digest(native) != phase["native"]["sha256"]:
                    raise ValueError("Native evidence changed: " + str(native))
                trace = output / "native" / (trial + ".jsonl")
                trace.parent.mkdir(exist_ok=True)
                shutil.copyfile(native, trace)
                catalog["samples"].append(dict(id=trial, pair=plan["id"],
                    center=phase["center_distance"], declaration=phase["metadata"],
                    target=phase["fixture_end"]["target"], files=evidence,
                    native=dict(path=trace.relative_to(output).as_posix(), sha256=digest(trace)),
                    original_engine_sha256=plan["engine_sha256"],
                    original_counts=dict(swings=phase["swings"], attacks=phase["attacks"])))
    if not seen:
        raise ValueError("No complete Reach recordings found")
    shutil.copyfile(config, output / "engine.conf")
    catalog["engine_config"] = dict(path="engine.conf", sha256=digest(output / "engine.conf"))
    (output / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    return len(seen)


def replay(manifest, engine, config):
    with tempfile.TemporaryDirectory(prefix="reach-verify-") as temporary:
        root = Path(temporary)
        with (root / "events.bin").open("wb") as dst:
            for chunk in sorted(manifest.parent.glob("events-*.acbin.gz")):
                with gzip.open(chunk, "rb") as src:
                    shutil.copyfileobj(src, dst)
        lines = [s for s in config.read_text().splitlines() if not s.strip().startswith("trace=")]
        (root / "engine.conf").write_text("\n".join(lines) + "\ntrace=true\n")
        result = subprocess.run([str(engine.resolve()), str(root / "events.bin"), str(root / "engine.conf")],
            capture_output=True, text=True, check=True, timeout=30)
        return [r for line in result.stdout.splitlines() if (r := json.loads(line)).get("check") == CHECK]


def verdicts(findings):
    return [(f["event"], f["message"], f["evidence"]) for f in findings]


def measure(root, sample, raw, engine, config):
    paths = {name: checked(root, value) for name, value in sample["files"].items()}
    manifest = paths["manifest.json"]
    meta = read(manifest)
    expected_chunks = set(sample["files"]) - {"manifest.json"}
    if {p.name for p in manifest.parent.glob("events-*.acbin.gz")} != expected_chunks:
        raise ValueError("Unexpected/missing chunks: " + sample["id"])
    if meta["trial_id"] != sample["id"] or meta["status"] != "complete":
        raise ValueError("Capture identity/status mismatch")
    declared = sample["declaration"]
    for source, key in (("declared_label", "label"), ("module", "behavior"),
                        ("client", "client"), ("setting", "configuration")):
        if meta[source] != declared[key]:
            raise ValueError("Catalog differs from original declaration: " + key)
    quality = raw.inspect_trial(manifest.parent, meta)
    if not quality["ok"]:
        raise ValueError("Raw audit failed: " + str(quality))
    trace = checked(root, sample["native"])
    findings = [json.loads(line) for line in trace.read_text().splitlines()]
    if any(f["check"] != CHECK for f in findings):
        raise ValueError("Unexpected check in Reach evidence")
    if engine and verdicts(replay(manifest, engine, config)) != verdicts(findings):
        raise ValueError("Native replay differs from archived evidence: " + sample["id"])
    # Retain guards in the native trace, but use one common window for every table value.
    start, end = meta["start_observed_ns"] + GUARD_NS, meta["end_observed_ns"] - GUARD_NS
    if end <= start:
        raise ValueError("No evaluation interval")
    outcomes = {}
    alerts = 0
    for finding in findings:
        if not start <= int(finding["observed_ns"]) < end:
            continue
        if finding["level"] == "suspicious":
            alerts += 1
            continue
        ordinal = int(finding["event"])
        if ordinal in outcomes:
            raise ValueError("Duplicate native attack verdict")
        outcomes[ordinal] = finding
    swings, attacks, rows, source_events = 0, 0, [], quality["events"]
    for e in raw.records(manifest.parent):
        if e["kind"] == 1 and (e["protocol"], e["model"]) != (47, 10808):
            raise ValueError("Unsupported protocol/model")
        if e["kind"] in (2, 3, 10, 14):
            raise ValueError("Observation discontinuity in capture")
        if not start <= e["ns"] < end:
            continue
        swings += e["kind"] == 9
        if e["kind"] != 7:
            continue
        attacks += 1
        outcome = outcomes.pop(e["ordinal"], None)
        if outcome is None:
            raise ValueError("Attack lacks a native verdict")
        ev = outcome["evidence"]
        if e["target"] != ev["target"]:
            raise ValueError("Native/raw target mismatch")
        distance = math.sqrt(sum(max(lo - p, 0, p - hi)**2
            for p, lo, hi in zip(e["eye"], e["low"], e["high"]))) if e["available"] else None
        rows.append(dict(trial=sample["id"], ordinal=e["ordinal"], packet=e["packet"],
            observed_ns=e["ns"], raw_distance=distance, verdict=outcome["message"],
            minimum_distance=float(ev["minimum_distance"]) if "minimum_distance" in ev else None,
            allowed_distance=float(ev["allowed_distance"]) if "allowed_distance" in ev else None,
            skip_reason=ev.get("reason"), target=e["target"]))
    if outcomes or {"swings": swings, "attacks": attacks} != sample["original_counts"]:
        raise ValueError("Recorded/controller/native counts do not agree")
    if swings < 20:
        raise ValueError("Insufficient manual input")
    counts = Counter(r["verdict"] for r in rows)
    evaluated = counts["reach_suspicious_sample"] + counts["reach_within_bound"]
    distances = [r["raw_distance"] for r in rows if r["raw_distance"] is not None]
    minimum = [r["minimum_distance"] for r in rows if r["minimum_distance"] is not None]
    summary = dict(trial=sample["id"], pair=sample["pair"], label=declared["label"],
        configuration=declared["configuration"], center=sample["center"], events=source_events,
        seconds=(end-start)/1e9, swings=swings, attacks=attacks, evaluated=evaluated,
        suspicious=counts["reach_suspicious_sample"], skipped=counts["reach_skipped"], alerts=alerts,
        raw_min=min(distances) if distances else None, raw_max=max(distances) if distances else None,
        conservative_min=min(minimum) if minimum else None)
    return summary, rows


def write_database(path, run_id, summaries, attacks, provenance):
    with closing(sqlite3.connect(path)) as db, db:
        db.executescript("""
            CREATE TABLE IF NOT EXISTS study (id TEXT PRIMARY KEY, provenance_json TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS trials (
                trial TEXT PRIMARY KEY, pair TEXT, label TEXT, configuration TEXT, center REAL,
                events INTEGER, seconds REAL, swings INTEGER, attacks INTEGER, evaluated INTEGER,
                suspicious INTEGER, skipped INTEGER, alerts INTEGER, raw_min REAL, raw_max REAL,
                conservative_min REAL);
            CREATE TABLE IF NOT EXISTS attacks (
                trial TEXT REFERENCES trials(trial), ordinal INTEGER, packet INTEGER, observed_ns INTEGER,
                raw_distance REAL, verdict TEXT, minimum_distance REAL, allowed_distance REAL,
                skip_reason TEXT, target TEXT, PRIMARY KEY(trial,ordinal));
        """)
        old = db.execute("SELECT id FROM study").fetchall()
        if old and old != [(run_id,)]:
            raise ValueError("Output database belongs to another study; choose another output directory")
        db.execute("INSERT OR REPLACE INTO study VALUES(?,?)", (run_id, json.dumps(provenance, sort_keys=True)))
        db.executemany("INSERT OR REPLACE INTO trials VALUES(" + ",".join("?" * 16) + ")",
            [tuple(s.values()) for s in summaries])
        db.executemany("INSERT OR REPLACE INTO attacks VALUES(" + ",".join("?" * 10) + ")",
            [tuple(s.values()) for s in attacks])


def report(summaries, run_id, replayed):
    controls = [s for s in summaries if s["label"] == "legit"]
    lines = ["# Recorded Reach evaluation", "",
        f"{len(summaries)} audited 45-second recordings; {len(controls)} all-off controls; "
        f"{len(summaries)-len(controls)} declared Reach recordings. Two accounts, one day, localhost.", "",
        "Human clicks, scripted stationary positions, empty hands, damage and velocity suppressed. "
        "Labels are operator declarations. All counts below exclude five seconds at each end.", "",
        "| Pair | Setting | Center | Swings | Requests | Evaluated | Out of bound | Skipped | Raw eye/box | Conservative min |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    pairs = {pair: i+1 for i, pair in enumerate(sorted({s["pair"] for s in summaries}))}
    for s in summaries:
        f = lambda value: "—" if value is None else f"{value:.3f}"
        lines.append(f"| {pairs[s['pair']]} | {s['configuration']} | {s['center']:g} | {s['swings']} | "
            f"{s['attacks']} | {s['evaluated']} | {s['suspicious']} | {s['skipped']} | {f(s['raw_min'])} | {f(s['conservative_min'])} |")
    lines += ["", "## Interpretation", "",
        f"The {len(controls)} control recordings produced {sum(s['attacks'] for s in controls)} attack requests "
        f"and {sum(s['suspicious'] for s in controls)} out-of-bound samples in the evaluation intervals. "
        "This is a local experiment, not an estimated population false-positive rate.", "",
        "At center distances 3.5 and 3.6, the paired OFF control sent no attacks; both declared "
        "3.2 and 3.3 runs sent attacks. The native check classified those requests as out of bound. "
        "The two settings share an observed boundary on this 0.1-block grid; it cannot establish equal "
        "effective reach. Pair 2 reuses the same OFF control across settings; these are not independent controls.", "",
        "The rule measures the shortest distance between the historical eye envelope and target bounds "
        "expanded by 0.13125 blocks, then compares with 3.05. It requires stationary history and separate "
        "packet batches. The 3.5-center result clears that bound by only about 0.019 blocks; the 3.6-center "
        "result clears it by about 0.119. Native distances are serialized to three decimals. The raw "
        "double-precision distances are retained in SQLite and the original records.", "",
        "Zero requests with recorded swings is a useful client-selection observation. It is neither a "
        "successful-hit measurement nor proof that the native detector evaluated those swings. "
        "ON recordings can contain in-range requests or no requests; the module label is not a per-attack verdict.", "",
        "## Detection and ML boundary", "",
        "Reach uses the existing stationary geometry check, not a fitted classifier. Moving players/targets, "
        "unsupported latency and stale observations cause abstention. Server snapshots do not reconstruct "
        "the target updates received by the attacking client. These stationary samples cannot validate "
        "moving-combat bans. The separate Timer study demonstrates ML, ablation and transport robustness.", "",
        "## Reproduce and inspect", "",
        f"Study ID: `{run_id}`. Native replay verified in this run: **{replayed}**.", "",
        "```powershell",
        "python tools/reach/analyze.py --study examples/reach --output build/reach-study --engine build/native/anticheat_replay.exe",
        "```", "",
        "Omit `--engine` to audit the captures and archived native evidence without compiling. "
        "`catalog.json` pins every source hash. `results.sqlite` contains one row per trial and evaluated/"
        "skipped attack, including original packet and event identifiers. No model or enforcement policy is changed.", "",
        "```sql",
        "SELECT configuration, center, swings, attacks, suspicious, skipped",
        "FROM trials ORDER BY pair, configuration, center;", "",
        "SELECT packet, raw_distance, minimum_distance, allowed_distance, verdict",
        "FROM attacks WHERE trial='trial-011e6c3f-e56d-4de7-93c7-989d61221096'",
        "ORDER BY ordinal LIMIT 5;",
        "```", ""]
    return "\n".join(lines)


def analyze(study, output, engine=None):
    catalog = read(study / "catalog.json")
    if catalog["version"] != 1 or catalog["guard_ns"] != GUARD_NS or catalog["check"] != CHECK:
        raise ValueError("Unsupported study contract")
    config = checked(study, catalog["engine_config"])
    for plan in catalog["plans"]:
        checked(study, plan)
    ids = [s["id"] for s in catalog["samples"]]
    if len(ids) != len(set(ids)) or not ids:
        raise ValueError("Empty/duplicate study samples")
    raw, summaries, attacks = decoder(), [], []
    for sample in catalog["samples"]:
        summary, records = measure(study, sample, raw, engine, config)
        summaries.append(summary)
        attacks.extend(records)
    run_id = digest(study / "catalog.json")
    provenance = dict(catalog_sha256=run_id, analyzer_sha256=digest(Path(__file__)),
        decoder_sha256=digest(REPO / "tools/dataset/dataset_tool.py"),
        replay_engine_sha256=digest(engine) if engine else None, native_replay_verified=bool(engine))
    output.mkdir(parents=True, exist_ok=True)
    write_database(output / "results.sqlite", run_id, summaries, attacks, provenance)
    (output / "REPORT.md").write_text(report(summaries, run_id, bool(engine)), encoding="utf-8")
    return dict(trials=len(summaries), raw_events=sum(s["events"] for s in summaries),
        attack_requests=len(attacks), native_replay_verified=bool(engine), report=str(output / "REPORT.md"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--study", type=Path, default=REPO / "examples/reach")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--engine", type=Path)
    parser.add_argument("--export", type=Path, help="Export completed local Reach pair folders first")
    parser.add_argument("--config", type=Path, help="Original engine.conf, required for export")
    args = parser.parse_args()
    if args.export:
        if not args.config:
            parser.error("--export requires --config")
        export(args.export, args.study, args.config)
    print(json.dumps(analyze(args.study, args.output, args.engine), indent=2))


if __name__ == "__main__":
    main()
