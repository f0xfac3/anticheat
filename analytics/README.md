# Behavior framework

One registry, one feature contract, one command surface. Python fits an interpretable
weighted logistic model. Java evaluates exported coefficients; it never loads pickle
or executes Python in the server. SQLite retains raw-source hashes, reviews, split
assignments, experiments, deployments, and live decisions.

## Reproduce Timer

Requires Python 3.11+. Run from the repository root:

```powershell
python -m pip install -r analytics/requirements.txt
python analytics/lab.py --store build/study.sqlite init
python analytics/lab.py --store build/study.sqlite import-timer examples/timer/anticheat.sqlite
python analytics/lab.py --store build/study.sqlite train timer.cadence --scope pilot
python -m unittest discover -s analytics -p 'test_*.py'
```

The 17 recordings yield 85 eligible windows. They are scripted, single-player,
single-day development data. A pilot is never eligible for ML enforcement.

## Add a behavior

1. Copy a JSON recipe under `recipes/`. Give it a new ID and link the observed
   mechanism to its source. Pick only registered, server-observable features.
2. Register it with `lab.py --store <database> register <recipe>`.
3. In the desktop **Validation** workspace, declare input source, client, network,
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
   recipe gate passes. Restart the server to load an immutable deployment.
   `rollback <detection> <directory>` restores the previous model in shadow mode.

**Attack automation** is a separate recipe with interval variation and repeated
intervals. No bot model is fitted from the scripted Timer negatives. It needs
human combat and separately grouped automation families.

## What the gates mean

Training weights independent groups equally. Legitimate calibration groups set a
conservative margin threshold. Reports include held-out trial confusion counts,
group-level false-positive uncertainty, exact split assignments, observed feature
ranges, and synthetic 100/250 ms batching and pause replay. No transport transform
counts as a real human or network validation sample.

The default coverage floors are five held-out human players, three days, three
network conditions, and 20 legitimate hours. These are **minimum coverage**, not
enough to guarantee promotion: a 1% false-positive upper bound with zero failures
requires about 299 independent negative test groups; the first sequential tail
cutoff requires at least 199 independent legitimate calibration groups. These
strict defaults protect the ban path. Smaller studies remain useful in shadow.

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
| `models.py` | Grouping, training, calibration, validation, publication |
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
