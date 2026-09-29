"""One command surface for detection recipes, evidence review, experiments and deployment."""

import argparse
import json
from pathlib import Path
import re
import sys
import uuid

from contracts import validate
from store import audit, canonical, connect, identity, now, ROOT


def register(db, path):
    spec = validate(json.loads(Path(path).read_text(encoding="utf-8")))
    old = db.execute(
        "SELECT source_hash FROM detections WHERE id=?", (spec["id"],)
    ).fetchone()
    digest = identity(spec)
    if old and old[0] != digest:
        raise ValueError(
            "Recipe IDs are immutable. Register a new versioned ID for a changed hypothesis."
        )
    db.execute(
        "INSERT OR IGNORE INTO detections VALUES(?,?,?,?,?,?)",
        (spec["id"], spec["version"], spec["name"], canonical(spec), digest, now()),
    )
    if not old:
        audit(db, "detection.register", spec["id"], spec)
    return spec["id"]


def plan_collection(db, player, metadata):
    if not re.fullmatch(r"[A-Za-z0-9_]{1,16}", player):
        raise ValueError("Invalid Minecraft player name")
    for key in ("label", "behavior", "client", "configuration"):
        if not re.fullmatch(r"[A-Za-z0-9_.=-]{1,80}", metadata[key]):
            raise ValueError(
                "Use simple identifiers for label, behavior, client and configuration"
            )
    if metadata["label"] not in ("legit", "cheat"):
        raise ValueError("Label must be legit or cheat")
    if metadata["input_source"] not in ("human", "scripted", "unknown"):
        raise ValueError("Invalid input source")
    if (
        metadata["purpose"] not in ("development", "validation")
        or not metadata["network"].strip()
    ):
        raise ValueError("Choose a data purpose and network condition")
    if metadata["input_source"] == "scripted" and not metadata["script_family"]:
        raise ValueError("Scripted input requires script family")
    token = "study-" + uuid.uuid4().hex[:16]
    db.execute(
        "INSERT INTO collection_jobs VALUES(?,?,?,?,?,?)",
        (token, player, now(), canonical(metadata), "planned", ""),
    )
    audit(db, "collection.plan", token, dict(player=player, metadata=metadata))
    return f"acdata start {player} {metadata['label']} {metadata['behavior']} {metadata['client']} {metadata['configuration']} {token}"


def sync_collection(db, root):
    from datasets import admit

    jobs = {
        r["id"]: r
        for r in db.execute("SELECT * FROM collection_jobs WHERE status='planned'")
    }
    if not jobs:
        return 0
    count = 0
    for path in Path(root).rglob("manifest.json"):
        try:
            meta = json.loads(path.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            continue
        job = jobs.get(meta.get("scenario"))
        if not job or meta.get("status") != "complete":
            continue
        try:
            sample = admit(db, path, json.loads(job["metadata_json"]))
            db.execute(
                "UPDATE collection_jobs SET status='admitted',detail=? WHERE id=?",
                (sample, job["id"]),
            )
            count += 1
        except (ValueError, OSError) as error:
            db.execute(
                "UPDATE collection_jobs SET status='rejected',detail=? WHERE id=?",
                (str(error), job["id"]),
            )
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", type=Path, required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init")
    p = commands.add_parser("register")
    p.add_argument("recipe", type=Path)
    p = commands.add_parser("import-timer")
    p.add_argument("database", type=Path)
    p = commands.add_parser("admit")
    p.add_argument("manifest", type=Path)
    p.add_argument("metadata", type=Path)
    p = commands.add_parser("review")
    p.add_argument("sample")
    p.add_argument("reviewer")
    p.add_argument("outcome", choices=["reviewed", "rejected"])
    p.add_argument("--reference", type=Path)
    p = commands.add_parser("train")
    p.add_argument("detection")
    p.add_argument("--scope", choices=["pilot", "independent"], default="independent")
    p = commands.add_parser("deploy")
    p.add_argument("experiment")
    p.add_argument("mode", choices=["shadow", "enforce", "disabled"])
    p.add_argument("directory", type=Path)
    p = commands.add_parser("rollback")
    p.add_argument("detection")
    p.add_argument("directory", type=Path)
    p = commands.add_parser("plan")
    p.add_argument("player")
    p.add_argument("metadata_json")
    p = commands.add_parser("sync")
    p.add_argument("raw", type=Path)
    commands.add_parser("status")
    args = parser.parse_args()
    with connect(args.store) as db:
        if args.command == "init":
            result = [
                register(db, p) for p in sorted((ROOT / "recipes").glob("*.json"))
            ]
        elif args.command == "register":
            result = register(db, args.recipe)
        elif args.command == "import-timer":
            from datasets import import_timer

            result = {"admitted": import_timer(db, args.database)}
        elif args.command == "admit":
            from datasets import admit

            result = admit(db, args.manifest, json.loads(args.metadata.read_text()))
        elif args.command == "review":
            from datasets import review

            review(db, args.sample, args.reviewer, args.outcome, args.reference)
            result = args.outcome
        elif args.command == "train":
            from models import train

            experiment, report = train(db, args.detection, args.scope)
            result = dict(
                experiment=experiment,
                metrics=report["metrics"],
                blockers=report["blockers"],
            )
        elif args.command in ("deploy", "rollback"):
            from models import deploy

            if args.command == "rollback":
                previous = db.execute(
                    "SELECT previous_id FROM deployments WHERE detection_id=?",
                    (args.detection,),
                ).fetchone()
                if not previous or not previous[0]:
                    raise ValueError("No previous deployment")
                result = deploy(db, previous[0], "shadow", args.directory)
            else:
                result = deploy(db, args.experiment, args.mode, args.directory)
        elif args.command == "plan":
            result = plan_collection(db, args.player, json.loads(args.metadata_json))
        elif args.command == "sync":
            result = {"admitted": sync_collection(db, args.raw)}
        else:
            result = {
                t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                for t in (
                    "detections",
                    "samples",
                    "experiments",
                    "deployments",
                    "behavior_windows",
                    "model_decisions",
                )
            }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        sys.exit(2)
