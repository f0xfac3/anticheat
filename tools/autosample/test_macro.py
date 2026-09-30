import unittest
from unittest.mock import patch

from macro import eligible, summarize, wait_for_pair


class MacroQualityTest(unittest.TestCase):
    def test_three_contiguous_eligible_windows(self):
        rows = [dict(segment=2, attacks=120, attack_pps=4, attack_cv=.2, attack_repeat=.1)] * 3
        self.assertTrue(eligible(rows))
        self.assertEqual(summarize(rows)["attack_pps"], 4)

    def test_gap_or_low_attack_window_is_ineligible(self):
        gap = [dict(segment=i, attacks=120, attack_pps=4, attack_cv=.2, attack_repeat=.1)
               for i in (1, 1, 2)]
        low = [dict(segment=1, attacks=n, attack_pps=4, attack_cv=.2, attack_repeat=.1)
               for n in (120, 99, 120)]
        self.assertFalse(eligible(gap))
        self.assertFalse(eligible(low))

    @patch("macro.time.sleep", return_value=None)
    def test_pair_waits_until_both_registered_accounts_join(self, _sleep):
        class Bridge:
            states = iter((
                {"players": []},
                {"players": [{"name": "elleliska"}]},
                {"players": [{"name": "evilgirlscout"}, {"name": "elleliska"}]},
            ))

            def state(self):
                return next(self.states)

        state = wait_for_pair(Bridge(), ("elleliska", "evilgirlscout"), timeout=1)
        self.assertEqual(len(state["players"]), 2)


if __name__ == "__main__":
    unittest.main()
