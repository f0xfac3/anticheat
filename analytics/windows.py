"""Thirty-second behavioral windows; same arithmetic as BehaviorTelemetry.java."""

import math

VERSION = "behavior-v1"
FEATURES = (
    "movement_pps",
    "movement_cv",
    "attack_pps",
    "attack_cv",
    "attack_repeat",
    "ground_fraction",
    "turn_mean",
    "attacks",
)
SPAN = 30_000_000_000
GUARD = 5_000_000_000


class Moments:
    def __init__(self):
        self.n = 0
        self.total = self.squares = 0.0

    def add(self, value):
        self.n += 1
        self.total += value
        self.squares += value * value

    def cv(self):
        if not self.n or self.total <= 0:
            return 0.0
        mean = self.total / self.n
        return math.sqrt(max(0, self.squares / self.n - mean * mean)) / mean


class Window:
    def __init__(self):
        self.reset()

    def reset(self):
        self.segment = getattr(self, "segment", 0) + 1
        self.ready = self.previous = self.packet = None
        self.clear()

    def clear(self):
        self.moves = self.attacks = self.ground = self.repeats = self.pairs = 0
        self.turns = self.turn_sum = 0
        self.last_move = self.last_attack = self.last_interval = self.yaw = None
        self.move_dt, self.attack_dt = Moments(), Moments()

    def values(self):
        return dict(
            movement_pps=self.moves / 30,
            movement_cv=self.move_dt.cv(),
            attack_pps=self.attacks / 30,
            attack_cv=self.attack_dt.cv(),
            attack_repeat=self.repeats / self.pairs if self.pairs else 0,
            ground_fraction=self.ground / self.moves if self.moves else 0,
            turn_mean=self.turn_sum / self.turns if self.turns else 0,
            attacks=float(self.attacks),
            segment=self.segment,
        )

    def accept(self, e):
        if e["kind"] in (1, 2, 3, 10, 14):
            self.reset()
            return None
        if e["kind"] not in (7, 11):
            return None
        ns = e["ns"]
        age = e["sampled_ns"] - ns
        if not e["available"] or not e["world"] or not 0 <= age <= 100_000_000:
            self.reset()
            return None
        if e["kind"] == 7:
            if self.ready is None or not self.ready <= ns < self.ready + SPAN:
                return None
            if self.attacks >= 10000 or (
                self.last_attack is not None and ns <= self.last_attack
            ):
                self.reset()
                return None
            self.attacks += 1
            if self.last_attack is not None:
                dt = (ns - self.last_attack) / 1e6
                self.attack_dt.add(dt)
                if self.last_interval is not None:
                    self.pairs += 1
                    self.repeats += abs(dt - self.last_interval) <= 1
                self.last_interval = dt
            self.last_attack = ns
            return None
        if self.packet is not None and e["packet"] <= self.packet:
            self.reset()
            return None
        if self.previous is not None and (
            ns < self.previous or ns - self.previous >= 750_000_000
        ):
            self.reset()
        if self.ready is None:
            self.ready = ns + GUARD
        self.previous, self.packet = ns, e["packet"]
        if ns < self.ready:
            return None
        result = None
        if ns >= self.ready + SPAN:
            result = self.values()
            self.ready += SPAN
            self.clear()
        if self.moves >= 20000:
            self.reset()
            return None
        self.moves += 1
        self.ground += e["ground"]
        if self.last_move is not None:
            self.move_dt.add((ns - self.last_move) / 1e6)
        self.last_move = ns
        if e["has_look"]:
            if self.yaw is not None:
                self.turn_sum += abs((e["yaw"] - self.yaw + 180) % 360 - 180)
                self.turns += 1
            self.yaw = e["yaw"]
        return result
