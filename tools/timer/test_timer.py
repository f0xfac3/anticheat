import math
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from model import decision, episode_score, episodes, tail_probability
from timer import connect, publish


class TimerTests(unittest.TestCase):
    def test_no_false_certainty_from_small_reference(self):
        self.assertEqual(tail_probability([20] * 9, 21.4), 0.1)
        self.assertFalse(decision([20] * 9, 21.4, 11900)["eligible"])
        self.assertEqual(tail_probability([20] * 9, 20), 1)

    def test_sustained_rate_and_whole_episode_excess_are_required(self):
        self.assertEqual(episode_score([100] * 34), 20)
        counts = [100] * 34
        counts[10] = 200
        self.assertEqual(episode_score(counts), 20)
        counts[11:13] = [107, 107]
        self.assertEqual(episode_score(counts), 21.4)
        self.assertTrue(decision([20] * 1999, 21.4, 11900)["eligible"])
        self.assertFalse(decision([20] * 1999, 21.4, 500)["eligible"])
        self.assertFalse(decision([20] * 1999, 21.4, 11900, episode=2)["eligible"])

    def test_episode_time_and_invalid_context(self):
        events = [
            dict(
                kind=11,
                ns=i * 50_000_000,
                packet=i + 1,
                sampled_ns=i * 50_000_000,
                world="world",
                available=True,
            )
            for i in range(3601)
        ]
        result = list(episodes(events))
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["packets"], 3400)
        self.assertEqual(result[0]["score"], 20)
        self.assertEqual(result[0]["windows"][0]["start_ns"], "5000000000")
        events[2000]["available"] = False
        self.assertEqual(list(episodes(events)), [])

    def test_reference_uses_legit_seed_groups_and_excludes_own_seed_in_evaluation(self):
        with tempfile.TemporaryDirectory() as folder, closing(
            connect(Path(folder) / "test.sqlite")
        ) as db:
            trials = []
            for seed in range(9):
                for role, score in [("legit", 20), ("hacking", 21.4)]:
                    tid = f"{seed}-{role}"
                    db.execute(
                        "INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        (
                            tid,
                            seed,
                            role,
                            "test",
                            1,
                            "day",
                            180,
                            "path",
                            "hash",
                            "hash",
                            "hash",
                            "g",
                            "o",
                            "b",
                            score,
                            3400,
                            0,
                        ),
                    )
                    trials.append(
                        dict(
                            id=tid,
                            seed=seed,
                            condition=role,
                            multiplier=1 if role == "legit" else 1.07,
                            generator="g",
                            observer="o",
                            bridge="b",
                            source_sha256="hash",
                            score=score,
                            excess_ms=11900,
                        )
                    )
            mid = publish(db, trials)
            self.assertEqual(
                db.execute(
                    "SELECT COUNT(*) FROM timer_reference WHERE model_id=?", (mid,)
                ).fetchone()[0],
                9,
            )
            self.assertEqual(db.execute("SELECT MAX(score) FROM timer_reference").fetchone()[0], 20)
            self.assertEqual(
                db.execute("SELECT DISTINCT reference_count FROM timer_evaluation").fetchall(),
                [(8,)],
            )
            self.assertEqual(
                db.execute("SELECT MAX(eligible) FROM timer_evaluation").fetchone()[0], 0
            )
            self.assertEqual(mid, publish(db, trials))
            self.assertEqual(
                db.execute("SELECT COUNT(*) FROM timer_models WHERE active=1").fetchone()[0], 1
            )


if __name__ == "__main__":
    unittest.main()
