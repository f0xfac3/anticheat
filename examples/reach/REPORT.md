# Recorded Reach evaluation

23 audited 45-second recordings; 9 all-off controls; 14 declared Reach recordings. Two accounts, one day, localhost.

Human clicks, scripted stationary positions, empty hands, damage and velocity suppressed. Labels are operator declarations. All counts below exclude five seconds at each end.

| Pair | Setting | Center | Swings | Requests | Evaluated | Out of bound | Skipped | Raw eye/box | Conservative min |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | all-off | 3 | 91 | 91 | 91 | 0 | 0 | 2.700 | 2.569 |
| 1 | all-off | 3.4 | 90 | 90 | 90 | 0 | 0 | 3.100 | 2.969 |
| 1 | all-off | 3.8 | 94 | 0 | 0 | 0 | 0 | — | — |
| 1 | all-off | 4.2 | 97 | 0 | 0 | 0 | 0 | — | — |
| 1 | reach=3.5 | 3 | 74 | 74 | 74 | 0 | 0 | 2.700 | 2.569 |
| 1 | reach=3.5 | 3.4 | 70 | 70 | 70 | 0 | 0 | 3.100 | 2.969 |
| 1 | reach=3.5 | 3.8 | 78 | 62 | 62 | 62 | 0 | 3.500 | 3.369 |
| 1 | reach=3.5 | 4.2 | 76 | 0 | 0 | 0 | 0 | — | — |
| 2 | all-off | 3.4 | 77 | 77 | 77 | 0 | 0 | 3.100 | 2.969 |
| 2 | all-off | 3.5 | 77 | 0 | 0 | 0 | 0 | — | — |
| 2 | all-off | 3.6 | 75 | 0 | 0 | 0 | 0 | — | — |
| 2 | all-off | 3.7 | 73 | 0 | 0 | 0 | 0 | — | — |
| 2 | all-off | 3.8 | 78 | 0 | 0 | 0 | 0 | — | — |
| 2 | reach=3.2 | 3.4 | 72 | 72 | 72 | 0 | 0 | 3.100 | 2.969 |
| 2 | reach=3.2 | 3.5 | 79 | 66 | 66 | 66 | 0 | 3.200 | 3.069 |
| 2 | reach=3.2 | 3.6 | 79 | 68 | 68 | 68 | 0 | 3.300 | 3.169 |
| 2 | reach=3.2 | 3.7 | 77 | 0 | 0 | 0 | 0 | — | — |
| 2 | reach=3.2 | 3.8 | 81 | 0 | 0 | 0 | 0 | — | — |
| 2 | reach=3.3 | 3.4 | 84 | 84 | 84 | 0 | 0 | 3.100 | 2.969 |
| 2 | reach=3.3 | 3.5 | 85 | 68 | 68 | 68 | 0 | 3.200 | 3.069 |
| 2 | reach=3.3 | 3.6 | 87 | 68 | 68 | 68 | 0 | 3.300 | 3.169 |
| 2 | reach=3.3 | 3.7 | 80 | 0 | 0 | 0 | 0 | — | — |
| 2 | reach=3.3 | 3.8 | 86 | 0 | 0 | 0 | 0 | — | — |

## Interpretation

The 9 control recordings produced 258 attack requests and 0 out-of-bound samples in the evaluation intervals. This is a local experiment, not an estimated population false-positive rate.

At center distances 3.5 and 3.6, the paired OFF control sent no attacks; both declared 3.2 and 3.3 runs sent attacks. The native check classified those requests as out of bound. The two settings share an observed boundary on this 0.1-block grid; it cannot establish equal effective reach. Pair 2 reuses the same OFF control across settings; these are not independent controls.

The rule measures the shortest distance between the historical eye envelope and target bounds expanded by 0.13125 blocks, then compares with 3.05. It requires stationary history and separate packet batches. The 3.5-center result clears that bound by only about 0.019 blocks; the 3.6-center result clears it by about 0.119. Native distances are serialized to three decimals. The raw double-precision distances are retained in SQLite and the original records.

Zero requests with recorded swings is a useful client-selection observation. It is neither a successful-hit measurement nor proof that the native detector evaluated those swings. ON recordings can contain in-range requests or no requests; the module label is not a per-attack verdict.

## Detection and ML boundary

Reach uses the existing stationary geometry check, not a fitted classifier. Moving players/targets, unsupported latency and stale observations cause abstention. Server snapshots do not reconstruct the target updates received by the attacking client. These stationary samples cannot validate moving-combat bans. The separate Timer study demonstrates ML, ablation and transport robustness.

## Reproduce and inspect

Study ID: `ac9c38f4784e004c587c608c3097be49c5e5af8f2c76623a783eb74e60ea5f1a`. Native replay verified in this run: **True**.

```powershell
python tools/reach/analyze.py --study examples/reach --output build/reach-study --engine build/native/anticheat_replay.exe
```

Omit `--engine` to audit the captures and archived native evidence without compiling. `catalog.json` pins every source hash. `results.sqlite` contains one row per trial and evaluated/skipped attack, including original packet and event identifiers. No model or enforcement policy is changed.

```sql
SELECT configuration, center, swings, attacks, suspicious, skipped
FROM trials ORDER BY pair, configuration, center;

SELECT packet, raw_distance, minimum_distance, allowed_distance, verdict
FROM attacks WHERE trial='trial-011e6c3f-e56d-4de7-93c7-989d61221096'
ORDER BY ordinal LIMIT 5;
```
