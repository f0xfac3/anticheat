# Behavior framework

Python fits a weighted logistic model; Java evaluates its exported coefficients.
SQLite stores source hashes, reviews, data splits, experiments, deployments and
live decisions. The CLI and desktop console use the same commands.

## Reproduce Timer

Requires Python 3.11+. Run from the repository root:

```powershell
python -m pip install -r analytics/requirements.txt
python analytics/lab.py --store build/study.sqlite init
python analytics/lab.py --store build/study.sqlite import-timer examples/timer/anticheat.sqlite
python analytics/lab.py --store build/study.sqlite plan-next timer.cadence
python analytics/lab.py --store build/study.sqlite train timer.cadence --scope pilot
python -m unittest discover -s analytics -p 'test_*.py'
```

The 17 recordings yield 85 eligible windows. They are scripted, single-player,
single-day development data. A pilot is never eligible for ML enforcement.

## Next experiment

`plan-next <detection>` reads the existing registry and registered recipe. It uses
the trainer's window, label and review rules, then checks:

- Missing OFF controls for each observed client/network combination.
- Review status, development groups and class coverage.
- Independent human hours, participants, days and network conditions.
- Validation overlap with development or earlier tests of changed code/recipes.
- Calibration size, false-positive uncertainty and failed transport replays.

Output includes the next action, recording duration and procedure, required
metadata and remaining validation counts. `--format json` also supplies capture
templates and sample IDs. Fill every null field before passing a template's
`metadata` object to `plan PLAYER METADATA_JSON`; that command reserves a capture.
In the console, use **Detections → Next experiment**, then **Validation → Record
session** when the recommendation calls for collection.

For the bundled Timer recordings, the missing control is Vape with gameplay
modules disabled on `local-loopback`. The proposed pair uses the same build and
route, 100 seconds per condition: 10 seconds to settle, then 30 each walking,
sprinting and sprint-jumping. Record the exact ON setting; the planner does not
infer cheat settings from packet rates. Use human input for independent negatives.

This is a deterministic collection planner. It does not fit a model, reserve
captures, change labels or enable enforcement. Synthetic stress failures prompt
a development investigation before further validation. A repeated route, opponent
or script family cannot manufacture independent groups.

## Add a behavior

1. Copy a JSON recipe under `recipes/`. Give it a new ID and link the observed
   mechanism to its source. Pick only registered, server-observable features.
2. Register it with `lab.py --store <database> register <recipe>`.
3. Use `plan-next <detection>` to check evidence gaps. In **Validation**, declare input source, client, network,
   route, label, and development/validation purpose **before** recording. Record
   at least 100 seconds of eligible behavior for the default three-window rule.
4. Review captures against separate evidence. CLI equivalent:
   `review <sample> <reviewer> reviewed --reference <artifact>`.
   The artifact is hashed. This records an accountable human assertion; it does
   not authenticate the reviewer or prove the assertion itself.
5. Train with `train <detection> --scope independent`. Fit, calibration and test
   keep connected players, combat opponents, and script families together.
   Independent negative examples must be human. Test days must be separate.
6. Use `deploy <experiment> shadow <plugin-model-directory>`. Inspect live
   decisions and feature-support abstentions. `enforce` is refused until every
   recipe requirement passes. Restart the server to load the deployment.
   `rollback <detection> <directory>` restores the previous model in shadow mode.

**Attack automation** is a separate recipe with interval variation and repeated
intervals. No bot model is fitted from the scripted Timer negatives. It needs
human combat and separately grouped automation families.

## Validation requirements

Training weights independent groups equally. Legitimate calibration groups set a
conservative margin threshold. Reports include held-out trial confusion counts,
group-level false-positive uncertainty, exact split assignments, observed feature
ranges, and synthetic 100/250 ms batching and pause replay. No transport transform
counts as a real human or network validation sample.

The default coverage floors are five held-out human players, three days, three
network conditions, and 20 legitimate hours. These are **minimum coverage**, not
enough to guarantee promotion: a 1% false-positive upper bound with zero failures
requires at least 299 independent negative test groups; the first sequential tail
cutoff requires at least 199 independent legitimate calibration groups. These
defaults keep small studies in shadow. Under the current one-third calibration
split, 199 calibration groups require at least 597 development groups. Observed
errors can require more data; collecting the minimum does not establish accuracy.

Live evaluation abstains outside observed training ranges, resets on observation
gaps, and spends a summable per-session tail budget across repeated looks. A tail
rank is not a cheating probability. Its statistical interpretation depends on
exchangeable legitimate data; network/client drift can violate that assumption.
Persistent evidence precedes every model action. The server rechecks session,
world, global enforcement mode, and collection exemption before a ban.

## Code map

| File | Responsibility |
|---|---|
| `windows.py` | Streaming feature arithmetic; matched to Java by raw replay |
| `datasets.py` | Raw admission, immutable source identity, review evidence |
| `models.py` | Sample eligibility, grouping, training, calibration and deployment |
| `planner.py` | Collection recommendations and validation coverage |
| `stress.py` | Synthetic transport regression tests |
| `lab.py` | CLI used by both operators and the desktop |
| `schema.sql` | Portable registry schema |

Collection metadata JSON contains `client`, `network`, `input_source`,
`script_family`, `behavior`, `label`, `route`, and `purpose`. Script family is
required for scripted input; keep it stable across variants of one controller.
New collection plans also require `configuration`, such as `all-off` or
`timer=1.07`. Validation captures must be reserved before their raw start timestamp;
historical development data cannot be retroactively relabeled as reserved validation.
Raw captures are admitted with `admit <manifest> <metadata.json>`. The existing
`tools/dataset/dataset_tool.py` remains the raw format decoder and audit tool.
