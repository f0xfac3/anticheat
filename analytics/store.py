"""SQLite registry and immutable evidence identities. No model deserialization."""

from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import sqlite3
import time

ROOT = Path(__file__).resolve().parent


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def identity(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def now():
    return int(time.time() * 1000)


@contextmanager
def connect(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=5)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA busy_timeout=5000")
    db.executescript((ROOT / "schema.sql").read_text())
    if "artifact_sha256" not in {
        r[1] for r in db.execute("PRAGMA table_info(deployments)")
    }:
        db.execute(
            "ALTER TABLE deployments ADD COLUMN artifact_sha256 TEXT NOT NULL DEFAULT ''"
        )
    try:
        with db:
            yield db
    finally:
        db.close()


def audit(db, operation, subject, detail):
    db.execute(
        "INSERT INTO audit(created_ms,operation,subject,detail_json) VALUES(?,?,?,?)",
        (now(), operation, subject, canonical(detail)),
    )
