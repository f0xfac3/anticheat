"""Original evidence must remain auditable; missing observations cannot become negatives."""
import json
from contextlib import closing
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

from analyze import REPO, analyze, checked, local, read

STUDY = REPO / "examples/reach"


class RecordedReachTests(unittest.TestCase):
    def test_original_captures_join_to_native_verdicts(self):
        with tempfile.TemporaryDirectory() as directory:
            result = analyze(STUDY, Path(directory))
            self.assertEqual(result["trials"], 23)
            self.assertEqual(result["raw_events"], 49456)
            self.assertEqual(result["attack_requests"], 890)
            self.assertFalse(result["native_replay_verified"])
            with closing(sqlite3.connect(Path(directory) / "results.sqlite")) as db:
                self.assertEqual(db.execute("SELECT SUM(suspicious) FROM trials WHERE label='legit'").fetchone()[0], 0)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM trials WHERE label='legit'").fetchone()[0], 9)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM trials WHERE configuration='reach=3.2' AND suspicious>0").fetchone()[0], 2)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM trials WHERE attacks=0 AND (raw_min IS NOT NULL OR evaluated!=0)").fetchone()[0], 0)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM attacks WHERE minimum_distance>allowed_distance AND verdict!='reach_suspicious_sample'").fetchone()[0], 0)

    def test_controller_counts_are_verified_against_raw(self):
        with tempfile.TemporaryDirectory() as directory:
            study = Path(directory) / "study"
            shutil.copytree(STUDY, study)
            catalog = read(study / "catalog.json")
            catalog["samples"][0]["original_counts"]["attacks"] += 1
            (study / "catalog.json").write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "counts do not agree"):
                analyze(study, Path(directory) / "out")

    def test_modified_or_external_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "evidence").write_text("changed")
            with self.assertRaisesRegex(ValueError, "Evidence changed"):
                checked(root, dict(path="evidence", sha256="0"*64))
            with self.assertRaisesRegex(ValueError, "escapes study"):
                local(root, "../outside")


if __name__ == "__main__":
    unittest.main()
