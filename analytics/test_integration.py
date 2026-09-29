"""The original recordings exercise the entire admission -> train -> gated deployment path."""

import json
from pathlib import Path
import tempfile
import unittest

from datasets import import_timer, review, admit, REPO
from lab import register
from models import train, deploy
from store import connect


class RecordedEvidenceTests(unittest.TestCase):
    def test_original_timer_cohort_stays_a_gated_pilot(self):
        with tempfile.TemporaryDirectory() as temporary:
            with connect(Path(temporary) / "analytics.sqlite") as db:
                register(db, Path(__file__).parent / "recipes/timer.json")
                self.assertEqual(
                    import_timer(db, REPO / "examples/timer/anticheat.sqlite"), 17
                )
                self.assertEqual(
                    db.execute("SELECT COUNT(*) FROM sample_windows").fetchone()[0], 85
                )
                sample = db.execute("SELECT id FROM samples LIMIT 1").fetchone()[0]
                row = db.execute(
                    "SELECT * FROM samples WHERE id=?", (sample,)
                ).fetchone()
                metadata = json.loads(row["metadata_json"])["declared"]
                metadata["purpose"] = "validation"
                with self.assertRaisesRegex(ValueError, "reserved with plan"):
                    admit(db, row["manifest_path"], metadata)
                with self.assertRaisesRegex(ValueError, "independent review artifact"):
                    review(db, sample, "reviewer", "reviewed")
                experiment, report = train(db, "timer.cadence", "pilot")
                self.assertEqual(report["metrics"]["tp"], 2)
                self.assertEqual(report["metrics"]["fp"], 0)
                self.assertEqual(report["coverage"]["human_players"], 0)
                self.assertFalse(report["enforcement_ready"])
                self.assertTrue(all(r["fp"] == 0 for r in report["stress"].values()))
                output = Path(
                    deploy(db, experiment, "shadow", Path(temporary) / "models")
                )
                self.assertIn("mode=shadow", output.read_text())
                with self.assertRaisesRegex(ValueError, "Promotion blocked"):
                    deploy(db, experiment, "enforce", Path(temporary) / "models")
                self.assertIn("mode=shadow", output.read_text())
                self.assertEqual(
                    db.execute("SELECT mode FROM deployments").fetchone()[0], "shadow"
                )
                with self.assertRaises(ValueError):
                    train(db, "timer.cadence", "independent")


if __name__ == "__main__":
    unittest.main()
