"""Regression cases for capture eligibility and replaying the actual native Reach predicate."""
import gzip
import json
from pathlib import Path
import struct
import tempfile
import unittest

from reach import (metrics, native_replay, comparison, parse_distances, reach_setting,
    on_file, complete_capture, fixture_contract)

REPO = Path(__file__).resolve().parents[2]
NS = 1_000_000_000
META = dict(start_observed_ns=0, end_observed_ns=45*NS)


def swings():
    return [dict(kind=9, ns=(6+i)*NS) for i in range(25)]


class ReachTests(unittest.TestCase):
    def test_far_control_swings_without_attacks_are_valid(self):
        result = metrics(swings(), META, "target", 3.8)
        self.assertTrue(result["usable"])
        self.assertEqual(result["attacks"], 0)
        self.assertIsNone(result["raw_eye_box_min"])

    def test_no_input_and_bad_near_control_are_rejected(self):
        self.assertFalse(metrics([], META, "target", 3.8)["usable"])
        self.assertIn("near_target_control_failed_check_aim", metrics(swings(), META, "target", 3.0)["problems"])

    def test_loss_in_guard_and_wrong_target_are_rejected(self):
        events = swings() + [dict(kind=14, ns=NS), dict(kind=7, ns=10*NS,
            packet=1, target="wrong", available=False)]
        result = metrics(events, META, "target", 3.8)
        self.assertEqual(result["problems"], ["observation_reset_or_loss", "wrong_target"])

    def test_measured_eye_box_distance_is_not_center_distance(self):
        events = swings() + [dict(kind=7, ns=10*NS, packet=50, target="target",
            available=True, eye=(.5,66.62,.5), low=(4.,65,.2), high=(4.6,66.8,.8))]
        result = metrics(events, META, "target", 3.8)
        self.assertEqual(result["raw_eye_box_min"], 3.5)
        self.assertEqual(result["first_attack_packet"], 50)

    def test_invalid_phase_does_not_become_zero_in_comparison(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "off.json").write_text(json.dumps(dict(complete=False, phases=[
                dict(center_distance=3.8, usable=False)])))
            report = comparison(root, dict(id="test"))
            self.assertIn("pending/invalid", report.read_text())
            self.assertIn("Paired run complete: False", report.read_text())

    def test_edge_grid_and_setting_are_constrained_and_named(self):
        self.assertEqual(parse_distances("3.4,3.5,3.6,3.7,3.8"), (3.4, 3.5, 3.6, 3.7, 3.8))
        self.assertEqual(reach_setting("3.300"), "3.3")
        self.assertEqual(on_file("3.3"), "on-3_3.json")
        with self.assertRaises(Exception): parse_distances("3.8,3.6,3.7")
        with self.assertRaises(Exception): reach_setting("2.9")

    def test_completion_depends_on_its_own_phases_not_a_future_on_pair(self):
        phases = [dict(usable=True, center_distance=d, trial=str(d)) for d in (3.4, 3.5, 3.6, 3.7, 3.8)]
        self.assertTrue(complete_capture(dict(phases=phases, faults=[]), (3.4, 3.5, 3.6, 3.7, 3.8)))
        self.assertFalse(complete_capture(dict(phases=phases[:-1], faults=[]), (3.4, 3.5, 3.6, 3.7, 3.8)))
        self.assertFalse(complete_capture(dict(phases=phases[::-1], faults=[]), (3.4, 3.5, 3.6, 3.7, 3.8)))
        self.assertFalse(complete_capture(dict(phases=[phases[0]]*5, faults=[]), (3.4, 3.5, 3.6, 3.7, 3.8)))

    def test_fixture_contract_changes_only_when_measurement_contract_changes(self):
        grid = (3.4, 3.5, 3.6, 3.7, 3.8)
        self.assertEqual(fixture_contract(grid, 45), fixture_contract(grid, 45))
        self.assertNotEqual(fixture_contract(grid, 45), fixture_contract(grid, 60))

    def test_real_native_replay_distinguishes_stationary_ranges(self):
        engine = REPO / "build/native/anticheat_replay.exe"
        if not engine.exists(): self.skipTest("Build native replay first")
        text = lambda s: struct.pack("<H", len(s)) + s.encode("ascii")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = root / "engine.conf"
            config.write_text("trace=false\nreach.enabled=true\n")
            for center, suspicious in ((3.0, False), (3.8, True)):
                records = []
                def frame(kind, ms, body=b""):
                    ordinal = len(records) + 1
                    data = struct.pack("<IHHQQQQQ", 0x43415846, 3, kind, 1, ordinal,
                        ms*1_000_000, 1700000000000+ms, ms//50) + body
                    records.append(struct.pack("<I", len(data)) + data)
                frame(1, 0, text("attacker") + struct.pack("<II", 47, 10808))
                context = (text("world") + text("target") + text("player") + text("")
                    + struct.pack("<i", 2) + struct.pack("<ddd", .5,66.62,.5)
                    + struct.pack("<ddd", .5+center-.3,65,.2)
                    + struct.pack("<ddd", .5+center+.3,66.8,.8)
                    + struct.pack("<iBddBBB", 0,1,-90,0,1,0,1))
                for ms in range(50, 4001, 50):
                    frame(8, ms, struct.pack("<Q", ms*1_000_000) + context)
                    if ms % 250 == 0:
                        frame(7, ms, struct.pack("<QQQ", ms//50, ms//50, ms*1_000_000) + context)
                (root / "events-00000.acbin.gz").write_bytes(gzip.compress(b"".join(records)))
                result = native_replay(root / "manifest.json", engine, config, root / "native.jsonl")
                count = result["counts"].get("reach_suspicious_sample", 0)
                self.assertEqual(count > 0, suspicious)
                if suspicious:
                    self.assertGreater(result["counts"].get("repeated_out_of_range_attack_requests", 0), 0)
                else:
                    self.assertGreater(result["counts"].get("reach_within_bound", 0), 0)


if __name__ == "__main__": unittest.main()
