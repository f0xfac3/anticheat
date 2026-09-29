import unittest

from evaluate import metrics, split
from features import attack_features, stress


class ResearchTests(unittest.TestCase):
    def test_receive_batching_preserves_source_and_snapshot_age(self):
        source = [dict(kind=11, ns=i*50_000_000, sampled_ns=i*50_000_000+100) for i in range(6)]
        batched = stress(source, "batch_100ms")
        self.assertEqual(source[1]["ns"], 50_000_000)
        self.assertEqual(batched[1]["ns"], batched[2]["ns"])
        self.assertTrue(all(e["sampled_ns"]-e["ns"] == 100 for e in batched))

    def test_entire_route_and_repeats_stay_out_of_training(self):
        rows = [dict(seed=seed, label=label) for seed in range(5) for label in (0, 1, 0)]
        tested = []
        for fold in split(rows):
            train = {rows[i]["seed"] for i in fold["train"]}
            test = {rows[i]["seed"] for i in fold["test"]}
            calibration = {rows[i]["seed"] for i in fold["calibration"]}
            self.assertFalse(train & test or train & calibration or test & calibration)
            self.assertTrue(all(rows[i]["label"] == 0 for i in fold["calibration"]))
            tested.extend(fold["test"])
        self.assertEqual(sorted(tested), list(range(len(rows))))

    def test_zero_false_positives_is_not_proof_of_safety(self):
        rows = [dict(label=0, flagged=False, score=-1) for _ in range(9)]
        rows += [dict(label=1, flagged=True, score=1) for _ in range(8)]
        m = metrics(rows)
        self.assertEqual((m["tp"], m["fp"], m["tn"], m["fn"]), (8, 0, 9, 0))
        self.assertAlmostEqual(m["fpr_upper_95_iid"], 1 - .05 ** (1 / 9))
        self.assertGreater(m["fpr_upper_95_iid"], .28)

    def test_macro_missing_and_batched_data_do_not_become_clean(self):
        self.assertFalse(attack_features([])["eligible"])
        self.assertFalse(attack_features([0] * 100)["eligible"])
        regular = attack_features([i * 100_000_000 for i in range(100)])
        self.assertTrue(regular["eligible"])
        self.assertEqual(regular["interval_cv"], 0)
        self.assertEqual(regular["interval_entropy_bits"], 0)
        self.assertEqual(regular["repeat_within_1ms"], 1)


if __name__ == "__main__":
    unittest.main()
