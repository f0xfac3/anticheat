# Timer research evaluation

Status: DEVELOPMENT ONLY — not a production acceptance test.

17 audited trials; 9 route seeds; 1 player(s); 1 day(s).
124340 raw events; 0.425 legitimate episode-hours.
All current routes were automated, including the vanilla condition. Labels were declared by the operator.

## Held-out route results

All trials/windows from one seed stay outside that fold's training and calibration.
Scaling fits training rows only. Hyperparameters and thresholds are fixed within this run.
Rate-only logistic is an exploratory refinement after observing full-model batching failures.
These recordings were already inspected during development; this is not a pristine final holdout.

| Model | TP | FP | TN | FN | ROC AUC | PR AP |
|---|---:|---:|---:|---:|---:|---:|
| fixed_rate_rule | 8 | 0 | 9 | 0 | 1.000 | 1.000 |
| legit_isolation_forest | 5 | 6 | 3 | 3 | 0.542 | 0.636 |
| logistic | 8 | 0 | 9 | 0 | 1.000 | 1.000 |
| logistic_rate_only | 8 | 0 | 9 | 0 | 1.000 | 1.000 |
| logistic_without_cadence | 3 | 5 | 4 | 5 | 0.389 | 0.426 |

Logistic false-positive rate: 0.0%; one-sided 95% binomial upper bound: 28.3%.
That bound assumes independent trials, which this one-player scripted dataset does not establish.
Isolation Forest fits legitimate training trials only; its threshold is the maximum score on a separate legitimate seed.
One calibration seed cannot justify a low false-positive operating point. Scores are margins, not cheating probabilities.

## Matched measurements

| Seed | Vanilla packets/s | Timer packets/s | Difference |
|---|---:|---:|---:|
| 240925 | 20.0000 | 21.4000 | 1.4000 |
| 240926 | 20.0000 | 21.4000 | 1.4000 |
| 240927 | 20.0000 | 21.4000 | 1.4000 |
| 240928 | 20.0000 | 21.4000 | 1.4000 |
| 240929 | 19.9941 | 21.4000 | 1.4059 |
| 240930 | 20.0000 | 21.4000 | 1.4000 |
| 240931 | 20.0000 | 21.4000 | 1.4000 |
| 240932 | 20.0000 | 21.4000 | 1.4000 |

Exploratory paired sign-test p=0.007812; this is not P(cheating).

## Receive-timing sensitivity

Synthetic perturbations of each held-out trial, scored by its original training-fold model.
These are not additional samples or a live network test. Snapshot age is held constant to isolate receive cadence.

| Scenario/model | Scored | Abstained | TP | FP |
|---|---:|---:|---:|---:|
| batch_100ms/fixed_rate_rule | 17 | 0 | 8 | 0 |
| batch_100ms/logistic | 17 | 0 | 8 | 9 |
| batch_100ms/logistic_rate_only | 17 | 0 | 8 | 0 |
| pause_1000ms/fixed_rate_rule | 0 | 17 | - | - |
| pause_1000ms/logistic | 0 | 17 | - | - |
| pause_1000ms/logistic_rate_only | 0 | 17 | - | - |

Abstention means insufficient trustworthy observation, not a legitimate verdict.

## What remains unproven

- Human and network generalization: one player/day, local scripted routes.
- Client confounding: add Vape with Timer off using the same client and settings.
- Bot/macro classification: vanilla samples are automated too. Never label them as human negatives.
- Combat macro feature coverage: 0 trial(s) with sufficient unbatched attack intervals.
- Large-scale performance: measured extraction throughput below is local, not a network capacity claim.
- Research classifiers do not authorize bans. The separate Timer budget rule is report-only by default; an apparent legitimate Lunar flag remains unresolved.

## Reproducibility

Run ID: `01e8eafdf96f90902779a741be2bf54d93416ef7447f2e38a4890f7759648d6e`
Extraction: 1.334s; 93223 audited source events/s, bounded to one trial at a time.
features.jsonl contains exact per-trial inputs and source hashes; results.json contains every split, prediction, coefficient and metric.
See docs/ROLE_EVIDENCE.md for the role requirement mapping and missing evidence.
