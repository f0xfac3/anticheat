# Timer: evidence to enforcement

## Mechanism

Recovered `a/yG.f(rB)` writes Minecraft's `Timer.timerSpeed`; enable/disable handling
is preserved in [the recovered Timer listing](research/vape/recovered/Timer-yG.txt).
[Source mapping](VAPE_DETECTIONS.md) records the reverse-engineering evidence.
The server observes received movement packets, not the client's Timer field.

The original budget check charges 50 ms per Flying-family packet and subtracts
elapsed receive time. The baseline check below adds a measured legitimate reference.

## One score

`tools/timer/model.py` and `engine/src/checks/timer_baseline.cpp` implement the same rules:

1. Start at the first eligible movement observation. Discard a five-second warmup.
2. Count all Flying variants in 34 non-overlapping, half-open five-second windows.
3. Let `rate[i] = count[i] / 5`.
4. Score the episode as `max(min(rate[i], rate[i+1], rate[i+2]))`.
5. Measure total nominal client-time excess: `50 * packet_count - 170000` ms.

The score requires 15 seconds of sustained excess; an isolated burst cannot raise
it. Teleports, corrections, observation loss, sequence regression, gaps of at least
750 ms or unavailable/stale context reset the episode. Snapshot age must be 0–100 ms.
Scoring starts again after a new warmup. These exclusions suppress decisions when
observations are unreliable; they are not evidence that the player is legitimate.

## Reference and decision

Each legitimate route seed contributes one episode score. Repeated trials with the
same seed contribute their maximum, not extra statistical samples. Cheat trials
evaluate the model and never fit the reference. Imported source hashes are immutable.

```text
p = (1 + number of reference scores >= observed score) / (N + 1)
cutoff(k) = 0.001 / (k * (k + 1))
eligible = p <= cutoff(k) AND score >= 20.5 AND excess_ms >= 1000
```

`k` counts assessed episodes in the connection and survives observation resets.
The cutoffs sum to at most 0.001 per connection. This is an empirical upper-tail
rank, **not the probability the player is cheating**. Its statistical interpretation
requires exchangeable legitimate calibration and future episodes. Scripted local
routes do not establish that assumption for arbitrary players or networks.

With nine seeds the smallest possible rank is `1/10 = 0.1`. The first cutoff is
`0.0005`, so this dataset cannot authorize a ban. At least 1,999 distinct reference
seeds are needed even to resolve that first cutoff; more seeds alone do not establish
representative human coverage. Later episodes have stricter cutoffs and may abstain
when the finite reference cannot resolve them. Reconnecting starts a new session;
the stated spending bound is per connection, not per account or lifetime.

## Database

`plugin/src/main/resources/timer-schema.sql` defines six tables:

| Table | Purpose |
|---|---|
| `trials` | Declared condition, source hashes, raw manifest path and episode score |
| `timer_windows` | Exact start timestamp and packet count for each window |
| `timer_models` | Immutable model identity, provenance, scope and policy |
| `timer_reference` | Legitimate score per route seed |
| `timer_evaluation` | Evaluation with the trial's entire seed excluded |
| `timer_decisions` | Live assessments, native evidence and enforcement outcome |

The SQLite file is `plugins/FoxAntiCheat/anticheat.sqlite`. Python's standard library
imports trials; the plugin uses the SQLite driver bundled in the pinned Spigot JAR.
The server reads one model at startup. Training and database writes never run in the
packet callback; changing the published model requires a server restart.

Useful raw queries:

```sql
SELECT condition, seed, score, packets, excess_ms FROM trials ORDER BY seed, condition;
SELECT window_index, start_ns, packet_count FROM timer_windows WHERE trial_id = ?;
SELECT created_ms, player_uuid, score, tail_p, eligible, action FROM timer_decisions;
```

## Enforcement

`enforcement.properties` selects `mode=ban` or `mode=report` and an explicit world.
The shipped scope is `ac_auto_samples`. Other checks retain reporting behavior.

An eligible native assessment is queued, committed to SQLite with full evidence,
then scheduled on Bukkit's main thread. The adapter rechecks the exact session,
online state and allowed world. A session used for labeled collection is exempt.
The adapter adds a persistent Bukkit name ban and kicks the player. No command text
is constructed. Each decision has one id; repeated delivery cannot punish twice.
Database failure, saturation, malformed evidence or a changed session prevents action.
Pending actions from a crash are retained for inspection and never replayed blindly.

This pinned 1.8 API uses name bans. Identity guarantees depend on server authentication;
the existing offline local lab is not an identity-secure public deployment.

## Observed example — September 28

17 completed trials: nine vanilla and eight declared Vape Timer 1.07. The first eight
seeds are matched pairs. All nine legitimate episode scores are **20.0 packets/s**;
all eight Timer scores are **21.4 packets/s**. The latter correspond to 7% higher cadence.

The importer and compiled C++ engine agree on episode scores, total packet counts
and client-time excess for **all 17 original raw recordings**. The active reference
returns tail rank **1.0** for vanilla and **0.1** for Timer. No trial is ban-eligible.
Leave-one-seed-out evaluation uses eight reference seeds, so Timer's rank is `1/9`.

This replaces the exploratory classifier/HTML reports. It does not carry their
classification percentages into the live decision policy. A same-client Timer-off
control and held-out human/network trials remain necessary for deployment validation.

## Reproduce

```powershell
python tools/timer/timer.py import --datasets C:/anticheat-lab/datasets --database C:/anticheat-lab/demo/server/plugins/FoxAntiCheat/anticheat.sqlite --date 2026-09-28
python tools/timer/timer.py status --database C:/anticheat-lab/demo/server/plugins/FoxAntiCheat/anticheat.sqlite
python tools/timer/replay.py --database C:/anticheat-lab/demo/server/plugins/FoxAntiCheat/anticheat.sqlite --engine build/native/anticheat_replay.exe
python -m unittest discover -s tools/timer -p test_timer.py
```

Native regressions cover ordinary/coalesced cadence, stale context, partial-episode
resets, insufficient reference data and alpha spending. Java SQLite tests verify
durable evidence before an action, deduplication, model mismatch and report mode.
Synthetic tests use temporary databases; no synthetic data enters the real baseline.
