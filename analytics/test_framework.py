import json
from pathlib import Path
import tempfile
import unittest

from contracts import validate
from lab import register, plan_collection
from models import partition, groups, sustained, deploy
from store import connect
from windows import Window


class FrameworkTests(unittest.TestCase):
    def test_combat_opponents_stay_in_same_partition(self):
        rows = [
            dict(id="a", player_group="p1", script_family="", opponent_groups=["p2"]),
            dict(id="b", player_group="p2", script_family="", opponent_groups=[]),
        ]
        self.assertEqual(groups(rows)["a"], groups(rows)["b"])

    def test_numeric_recipe_policy_cannot_be_nan_or_empty(self):
        spec = json.loads((Path(__file__).parent / "recipes/timer.json").read_text())
        spec["validation"]["minimum_days"] = float("nan")
        with self.assertRaisesRegex(ValueError, "Positive validation"):
            validate(spec)

    def test_shared_script_family_cannot_cross_participant_split(self):
        samples = [
            dict(
                id=str(i),
                player_group=str(i),
                script_family="shared" if i < 2 else "",
                route=str(i),
                purpose="development",
            )
            for i in range(7)
        ]
        group = groups(samples)
        self.assertEqual(group["0"], group["1"])
        samples[0]["purpose"] = "validation"
        with self.assertRaisesRegex(ValueError, "disjoint"):
            partition(samples, "independent")

    def test_pilot_keeps_repeated_routes_together(self):
        rows = [dict(id=f"{i}-{j}", route=str(i)) for i in range(9) for j in range(3)]
        assignment, _ = partition(rows, "pilot")
        for i in range(9):
            self.assertEqual(len({assignment[f"{i}-{j}"] for j in range(3)}), 1)

    def test_recipe_is_immutable_and_unsafe_fields_rejected(self):
        with tempfile.TemporaryDirectory() as tmp, connect(Path(tmp) / "db") as db:
            path = Path(__file__).parent / "recipes/timer.json"
            register(db, path)
            spec = json.loads(path.read_text())
            spec["features"] = ["player_uuid"]
            with self.assertRaisesRegex(ValueError, "Unregistered"):
                validate(spec)
            spec = json.loads(path.read_text())
            spec["name"] = "Changed"
            changed = Path(tmp) / "changed.json"
            changed.write_text(json.dumps(spec))
            with self.assertRaisesRegex(ValueError, "immutable"):
                register(db, changed)

    def test_shadow_does_not_bypass_enforcement_gate(self):
        with tempfile.TemporaryDirectory() as tmp, connect(Path(tmp) / "db") as db:
            register(db, Path(__file__).parent / "recipes/timer.json")
            report = dict(enforcement_ready=False, blockers=["no human validation"])
            db.execute(
                "INSERT INTO experiments VALUES(?,?,?,?,?,?,?)",
                (
                    "a" * 64,
                    "timer.cadence",
                    0,
                    "pilot",
                    json.dumps(report),
                    "{}",
                    "gated",
                ),
            )
            with self.assertRaisesRegex(ValueError, "Promotion blocked"):
                deploy(db, "a" * 64, "enforce", Path(tmp) / "models")
            self.assertFalse((Path(tmp) / "models").exists())

    def test_pauses_reset_feature_windows(self):
        state = Window()
        output = []
        for i in range(1500):
            ns = i * 50_000_000 + (1_000_000_000 if i >= 400 else 0)
            value = state.accept(
                dict(
                    kind=11,
                    ns=ns,
                    sampled_ns=ns,
                    packet=i + 1,
                    available=True,
                    world="lab",
                    ground=True,
                    has_look=False,
                    yaw=0,
                )
            )
            if value is not None:
                output.append(value)
        self.assertEqual(len(output), 1)
        self.assertEqual(output[0]["movement_pps"], 20)
        self.assertEqual(sustained([1, 8, 1, 1], 3), 1)


if __name__ == "__main__":
    unittest.main()
