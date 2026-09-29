# Research evaluation

Evaluate the original audited Timer recordings without changing live enforcement.
Outputs are plain Markdown and JSON; no web dashboard or opaque model file.

```powershell
python -m pip install -r tools/research/requirements.txt
python -m unittest discover -s tools/research -p test_research.py
python tools/research/evaluate.py --database examples/timer/anticheat.sqlite --output examples/timer/research
```

`features.jsonl`: source hashes, operator labels, route grouping and exact features.
`results.json`: every fold, excluded calibration seed, predictions, weights, metrics,
paired differences, dependency versions and local extraction timing.
`REPORT.md`: concise review artifact linked from the repository README.

The database opens read-only. The extractor audits original packets, checks the
manifest hash, matches imported episode counts/scores, rejects malformed trials,
checks capture provenance and processes at most 50,000 events in one trial.
Missing attack intervals are explicitly ineligible; zero does not mean human.
Feature names are allowlisted: player IDs, route seeds, labels, client names,
timestamps, verdicts and evidence IDs cannot become predictor inputs.

## Experiments

- Leave one complete route seed out; hold out another legitimate seed for anomaly
  threshold calibration. All repeated routes stay in the same split.
- Compare a fixed sustained-rate threshold, regularized logistic regression,
  rate-only logistic regression, cadence ablation and legitimate-only Isolation Forest.
- Fit preprocessing on training rows only. Use fixed model parameters within each
  run; do not choose a cutoff from the test outcomes.
- Report confusion matrices, ROC AUC, precision-recall AP, false flags per legitimate
  episode-hour and a binomial false-positive upper bound with its assumptions.
- Compare matched vanilla/Timer route measurements and later-route holdout results.
- Perturb held-out receive timestamps with 100 ms batching and a 1,000 ms mid-trial
  pause. Preserve snapshot age to isolate cadence sensitivity. These are synthetic
  timing tests, not live network experiments or extra independent samples.

The fixed rate rule is a simple 20.5 sustained packets/s comparator, not a replay
of the live Timer budget state machine. Existing native replay verifies that path
separately. The rate-only model was added after seeing batching failures; it needs
fresh validation. All metrics on this already-inspected dataset are developmental.
The small calibration set cannot establish production thresholds. ROC/AP are
descriptive summaries of pooled fold margins, not calibrated cheating probability.

Attack cadence CV, entropy and repeated intervals provide bot/macro research inputs
when attack data exists. No bot classifier is trained from this Timer dataset:
both conditions used automated movement, so its vanilla label is not a human label.

References: [grouped validation](https://scikit-learn.org/stable/modules/cross_validation.html#cross-validation-iterators-for-grouped-data),
[novelty detection](https://scikit-learn.org/stable/modules/outlier_detection.html),
[classification metrics](https://scikit-learn.org/stable/modules/model_evaluation.html#classification-metrics).
