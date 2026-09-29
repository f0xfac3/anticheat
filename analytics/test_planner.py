"""Collection recommendations must follow evidence eligibility and split rules."""

import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from datasets import import_timer, REPO
from lab import register
from models import implementation_hashes, partition, select_samples
from planner import plan_next, render
from store import canonical, connect, identity, read_only
from windows import FEATURES


class PlannerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "registry.sqlite"
        self.context = connect(self.path)
        self.db = self.context.__enter__()
        self.addCleanup(lambda: self.context.__exit__(None, None, None))
        register(self.db, Path(__file__).parent / "recipes/timer.json")

    def sample(
        self,
        name,
        *,
        positive=False,
        player=None,
        day="2026-09-01",
        client="vape",
        network="local",
        purpose="development",
        review="reviewed",
        source="human",
        family="",
        opponents=(),
        windows=3,
        attacks=0,
    ):
        self.db.execute(
            "INSERT INTO samples VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                name,
                "unused",
                name,
                player or name,
                day,
                client,
                network,
                source,
                family,
                "timer" if positive else "none",
                "cheat" if positive else "legit",
                name,
                review,
                "reviewer",
                purpose,
                canonical(dict(opponent_groups=list(opponents))),
                0,
            ),
        )
        window = dict.fromkeys(FEATURES, 0)
        window.update(segment=0, attacks=attacks, movement_pps=21.4 if positive else 20)
        self.db.executemany(
            "INSERT INTO sample_windows VALUES(?,?,?)",
            [(name, n, canonical(window)) for n in range(windows)],
        )

    def test_original_recordings_need_same_client_control_not_more_route_seeds(self):
        self.assertEqual(
            import_timer(self.db, REPO / "examples/timer/anticheat.sqlite"), 17
        )
        before = self.db.total_changes
        plan = plan_next(self.db, "timer.cadence")
        self.assertEqual(self.db.total_changes, before)
        self.assertEqual(plan, plan_next(self.db, "timer.cadence"))
        self.assertEqual(plan["next_experiment"]["kind"], "matched_control")
        self.assertEqual(plan["capture"]["controls"], [("vape", "local-loopback")])
        self.assertEqual(plan["capture"]["seconds"], 100)
        self.assertEqual(plan["evidence"]["eligible_development_or_pilot"], 17)
        self.assertTrue(all(g["observed"] == 0 for g in plan["coverage"].values()))
        self.assertEqual(plan["coverage"]["negative_test_groups"]["required"], 299)
        self.assertEqual(
            plan["coverage"]["legitimate_calibration_groups"]["required"], 199
        )
        self.assertIn("sprint-jump 30 s", render(plan))
        # A 45-second Reach OFF capture cannot supply a three-window Timer control.
        self.sample("short-reach-off", windows=1)
        again = plan_next(self.db, "timer.cadence")
        self.assertEqual(again["capture"]["controls"], plan["capture"]["controls"])
        self.assertEqual(
            again["evidence"]["excluded"]["too_few_consecutive_windows"], 1
        )

    def test_review_recommendation_does_not_also_request_a_capture(self):
        self.sample("off", review="declared")
        self.sample("on", positive=True, review="declared")
        plan = plan_next(self.db, "timer.cadence")
        self.assertEqual(plan["next_experiment"]["kind"], "review")
        self.assertIsNone(plan["capture"])
        self.assertEqual(plan["evidence"]["pending_review"], ["off", "on"])
        self.assertNotIn("Capture:", render(plan))

    def test_groups_days_and_scripted_negatives_do_not_inflate_coverage(self):
        self.sample("development", player="dev")
        self.sample("off", player="human", day="2026-09-02", purpose="validation")
        self.sample("repeat", player="human", day="2026-09-02", purpose="validation")
        self.sample(
            "on", player="human", day="2026-09-02", purpose="validation", positive=True
        )
        self.sample(
            "shared-opponent", opponents=["dev"], day="2026-09-02", purpose="validation"
        )
        self.sample("same-day", purpose="validation")
        self.sample(
            "scripted-negative",
            day="2026-09-02",
            purpose="validation",
            source="scripted",
            family="route-bot",
        )
        plan = plan_next(self.db, "timer.cadence")
        self.assertEqual(plan["coverage"]["negative_test_groups"]["observed"], 1)
        self.assertEqual(plan["coverage"]["human_players"]["observed"], 1)
        self.assertAlmostEqual(plan["coverage"]["legit_hours"]["observed"], 0.05)
        self.assertEqual(
            set(plan["evidence"]["contaminated_validation"]),
            {"shared-opponent", "same-day"},
        )

    def test_calibration_count_matches_the_trainer_partition(self):
        for i in range(9):
            for positive in (False, True):
                self.sample(
                    f"dev-{i}-{positive}", player=f"person-{i}", positive=positive
                )
        for i in range(2):
            for positive in (False, True):
                self.sample(
                    f"test-{i}-{positive}",
                    player=f"new-{i}",
                    positive=positive,
                    day="2026-09-02",
                    purpose="validation",
                )
        spec = json.loads(
            self.db.execute("SELECT spec_json FROM detections").fetchone()[0]
        )
        samples, labels, _, _ = select_samples(self.db, spec, "independent")
        assignment, groups = partition(samples, "independent")
        expected = {
            groups[s["id"]]
            for s in samples
            if assignment[s["id"]] == "calibration" and labels[s["id"]] == 0
        }
        plan = plan_next(self.db, "timer.cadence")
        self.assertEqual(
            plan["coverage"]["legitimate_calibration_groups"]["observed"], len(expected)
        )
        self.assertEqual(len(expected), 3)

    def test_transport_failure_precedes_more_validation_and_old_test_is_excluded(self):
        self.sample("off", day="2026-09-02", purpose="validation")
        self.sample("on", day="2026-09-02", purpose="validation", positive=True)
        report = dict(
            stress={"batch100": dict(fp=1)},
            assignments={"off": "test", "on": "test"},
            implementation={"old.py": "old"},
        )
        self.db.execute(
            "INSERT INTO experiments VALUES(?,?,?,?,?,?,?)",
            (
                "old",
                "timer.cadence",
                0,
                "independent",
                canonical(report),
                "{}",
                "gated",
            ),
        )
        plan = plan_next(self.db, "timer.cadence")
        self.assertEqual(plan["next_experiment"]["kind"], "reproduce_failure")
        self.assertEqual(len(plan["capture"]["templates"]), 1)
        self.assertEqual(plan["coverage"]["negative_test_groups"]["observed"], 0)
        self.assertEqual(
            set(plan["evidence"]["contaminated_validation"]), {"off", "on"}
        )
        # Identical code/recipe evaluation is reproducible, not a new hypothesis.
        spec = json.loads(
            self.db.execute("SELECT spec_json FROM detections").fetchone()[0]
        )
        report.update(implementation=implementation_hashes())
        self.db.execute(
            "UPDATE experiments SET report_json=?,model_json=?",
            (canonical(report), canonical(dict(spec_hash=identity(spec)))),
        )
        self.assertEqual(
            plan_next(self.db, "timer.cadence")["coverage"]["negative_test_groups"][
                "observed"
            ],
            1,
        )

    def test_policy_changes_recalculate_floors_and_capture_duration(self):
        spec = json.loads(
            self.db.execute("SELECT spec_json FROM detections").fetchone()[0]
        )
        spec.update(id="timer.test", tail_cutoff=0.05, consecutive_windows=4)
        spec["validation"]["maximum_fpr_upper_95"] = 0.05
        path = Path(self.tmp.name) / "recipe.json"
        path.write_text(json.dumps(spec))
        register(self.db, path)
        plan = plan_next(self.db, "timer.test")
        self.assertEqual(plan["capture"]["seconds"], 130)
        self.assertEqual(plan["coverage"]["negative_test_groups"]["required"], 59)
        self.assertEqual(
            plan["coverage"]["legitimate_calibration_groups"]["required"], 39
        )

    def test_macro_plan_requires_human_control_and_separate_script_family(self):
        register(self.db, Path(__file__).parent / "recipes/attack_macro.json")
        # Automated movement is neither a human control nor attack automation.
        self.sample(
            "movement-bot", source="scripted", family="timer-routes", attacks=120
        )
        plan = plan_next(self.db, "macro.attack")
        self.assertEqual(plan["evidence"]["eligible_development_or_pilot"], 0)
        off, on = [t["metadata"] for t in plan["capture"]["templates"]]
        self.assertEqual(off["input_source"], "human")
        self.assertEqual(on["input_source"], "scripted")
        self.assertIsNone(on["script_family"])
        self.assertIn("Do not automate clicks", plan["capture"]["input_note"])

    def test_cli_is_read_only_and_missing_registry_is_not_created(self):
        self.db.commit()
        args = [
            sys.executable,
            str(Path(__file__).parent / "lab.py"),
            "--store",
            str(self.path),
            "plan-next",
            "timer.cadence",
            "--format",
            "json",
        ]
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["detection"], "timer.cadence")
        with read_only(self.path) as readonly:
            with self.assertRaises(sqlite3.OperationalError):
                readonly.execute("DELETE FROM samples")
        args[3] = str(Path(self.tmp.name) / "missing.sqlite")
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("Registry does not exist", result.stderr)
        self.assertFalse(Path(args[3]).exists())


if __name__ == "__main__":
    unittest.main()
