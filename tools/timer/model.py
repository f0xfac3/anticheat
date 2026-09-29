"""Timer v1: a fixed episode statistic and an empirical legitimate reference.

One reference value per route seed, never one per correlated packet/window.
The tail rank is not P(cheating). No cheat labels fit the reference distribution.
Keep episode extraction equivalent to engine/src/checks/timer_baseline.cpp.
"""

from bisect import bisect_left

ALGORITHM = "timer-episode-v1"
WINDOW_NS = 5_000_000_000
WINDOWS = 34
GUARD_NS = 5_000_000_000
GAP_NS = 750_000_000
QUEUE_NS = 100_000_000


def episode_score(counts):
    if len(counts) != WINDOWS:
        raise ValueError("Timer episodes require 34 complete five-second windows")
    return max(min(counts[i : i + 3]) / 5 for i in range(WINDOWS - 2))


def tail_probability(reference, score):
    if not reference:
        raise ValueError("Empty legitimate reference")
    return (1 + len(reference) - bisect_left(sorted(reference), score)) / (len(reference) + 1)


def decision(reference, score, excess_ms, episode=1, alpha=0.001):
    # Sum alpha/(k*(k+1)) over a session is at most alpha. Resets keep k.
    spent = alpha / (episode * (episode + 1))
    tail = tail_probability(reference, score)
    eligible = tail <= spent and score >= 20.5 and excess_ms >= 1000
    reason = (
        "sustained_timer_excess"
        if eligible
        else "insufficient_reference" if 1 / (len(reference) + 1) > spent else "within_policy"
    )
    return dict(tail_p=tail, alpha_spent=spent, eligible=eligible, reason=reason)


def episodes(events):
    """Raw receive timestamps, including all Flying variants; invalid context resets."""
    start = previous_ns = previous_packet = None
    counts = [0] * WINDOWS
    for e in events:
        if e["kind"] in (1, 2, 3, 10, 14):
            start = previous_ns = previous_packet = None
            counts = [0] * WINDOWS
            continue
        if e["kind"] != 11:
            continue
        ns = e["ns"]
        valid = e["available"] and bool(e["world"]) and 0 <= e["sampled_ns"] - ns <= QUEUE_NS
        ordered = previous_packet is None or e["packet"] > previous_packet
        timely = previous_ns is None or 0 <= ns - previous_ns < GAP_NS
        if not valid or not ordered or not timely:
            start = previous_ns = previous_packet = None
            counts = [0] * WINDOWS
            if not valid or not ordered:
                continue
        if start is None:
            start = ns
        previous_ns, previous_packet = ns, e["packet"]
        ready = start + GUARD_NS
        if ns >= ready + WINDOWS * WINDOW_NS:
            yield dict(
                score=episode_score(counts),
                packets=sum(counts),
                excess_ms=sum(counts) * 50 - WINDOWS * 5000,
                windows=[
                    dict(index=i, start_ns=str(ready + i * WINDOW_NS), count=count)
                    for i, count in enumerate(counts)
                ],
            )
            start = ns
            counts = [0] * WINDOWS
        elif ns >= ready:
            counts[(ns - ready) // WINDOW_NS] += 1
            if counts[(ns - ready) // WINDOW_NS] > 20000:
                start = previous_ns = previous_packet = None
                counts = [0] * WINDOWS
