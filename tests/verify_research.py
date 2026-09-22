"""Verify shipped research hashes; optionally verify against the original capture JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--archive", type=Path, help="Original received-classes.jar (not distributed)")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1] / "docs/research/vape"
manifest = json.loads((root / "manifest.json").read_text())
digest = lambda data: hashlib.sha256(data).hexdigest()
for entry in manifest["files"]:
    path = (root / entry["file"]).resolve()
    if root.resolve() not in path.parents:
        raise SystemExit("Manifest path outside evidence directory")
    if digest(path.read_bytes()) != entry["sha256"]:
        raise SystemExit("Evidence hash mismatch: " + entry["file"])
if args.archive:
    if digest(args.archive.read_bytes()) != manifest["capture_jar_sha256"]:
        raise SystemExit("Original archive hash mismatch")
    with zipfile.ZipFile(args.archive) as archive:
        for entry in manifest["files"]:
            if "class_entry" in entry and digest(archive.read(entry["class_entry"])) != entry["class_sha256"]:
                raise SystemExit("Original class hash mismatch: " + entry["class_entry"])
print(f"Verified {len(manifest['files'])} evidence files" + (" and original class hashes" if args.archive else ""))
