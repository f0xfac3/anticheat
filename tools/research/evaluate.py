"""Development evaluation only. No live policy changes, synthetic labels or model deployment."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import time

import numpy as np
import scipy
from scipy.stats import beta, binomtest
import sklearn
from sklearn.ensemble import IsolationForest
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from features import FEATURES, CADENCE, digest, load


def split(rows):
    """Hold out the entire route seed; reserve another seed for legitimate calibration."""
    seeds = sorted({r["seed"] for r in rows})
    if len(seeds) < 5:
        raise ValueError("Need five distinct route seeds")
    for i, test in enumerate(seeds):
        calibration = seeds[(i + 1) % len(seeds)]
        yield {
            "test_seed": test, "calibration_seed": calibration,
            "train": [j for j, r in enumerate(rows) if r["seed"] not in (test, calibration)],
            "calibration": [j for j, r in enumerate(rows) if r["seed"] == calibration and r["label"] == 0],
            "test": [j for j, r in enumerate(rows) if r["seed"] == test],
        }


def metrics(predictions):
    y = np.array([p["label"] for p in predictions])
    flagged = np.array([p["flagged"] for p in predictions])
    score = np.array([p["score"] for p in predictions])
    tp, fp = int(sum(flagged & (y == 1))), int(sum(flagged & (y == 0)))
    tn, fn = int(sum(~flagged & (y == 0))), int(sum(~flagged & (y == 1)))
    n = tn + fp
    return {
        "tp": tp, "fp": fp, "tn": tn, "fn": fn,
        "precision": tp / (tp + fp) if tp + fp else None,
        "recall": tp / (tp + fn) if tp + fn else None,
        "false_positive_rate": fp / n if n else None,
        # Descriptive binomial bound only: independence is not established for this one-player lab.
        "fpr_upper_95_iid": float(beta.ppf(.95, fp + 1, tn)) if tn else 1.0,
        "false_flags_per_legit_hour": fp / (n * 170 / 3600) if n else None,
        "roc_auc": float(roc_auc_score(y, score)) if len(set(y)) == 2 else None,
        "average_precision": float(average_precision_score(y, score)) if sum(y) else None,
    }


def evaluate(rows):
    x = np.array([[r["features"][f] for f in FEATURES] for r in rows])
    y = np.array([r["label"] for r in rows])
    noncadence = [i for i, f in enumerate(FEATURES) if f not in CADENCE]
    folds, predictions, coefficients, stress_predictions = [], [], [], []
    for fold in split(rows):
        train, calibration, test = [np.array(fold[k], dtype=int) for k in ("train", "calibration", "test")]
        if len(set(y[train])) != 2 or not len(calibration):
            raise ValueError("Each training fold needs both classes and legitimate calibration")
        fold["train_trials"] = [rows[i]["trial"] for i in train]
        fold["test_trials"] = [rows[i]["trial"] for i in test]
        fold["calibration_trials"] = [rows[i]["trial"] for i in calibration]
        folds.append(fold)
        logistic = make_pipeline(StandardScaler(), LogisticRegression(C=1, solver="liblinear", max_iter=2000, random_state=42))
        logistic.fit(x[train], y[train])
        rate_only = make_pipeline(StandardScaler(), LogisticRegression(C=1, solver="liblinear", max_iter=2000, random_state=42))
        rate_only.fit(x[train][:, :2], y[train])
        ablated = make_pipeline(StandardScaler(), LogisticRegression(C=1, solver="liblinear", max_iter=2000, random_state=42))
        ablated.fit(x[train][:, noncadence], y[train])
        forest = IsolationForest(n_estimators=100, max_samples="auto", random_state=42, n_jobs=1)
        forest.fit(x[train[y[train] == 0]])
        threshold = float(max(-forest.score_samples(x[calibration])))
        weights = logistic[-1].coef_[0]
        coefficients.append(dict(test_seed=fold["test_seed"], standardized_weights=dict(zip(FEATURES, map(float, weights)))))
        scores = {
            "fixed_rate_rule": x[test, 1] - 20.5,
            "logistic": logistic.decision_function(x[test]),
            "logistic_rate_only": rate_only.decision_function(x[test][:, :2]),
            "logistic_without_cadence": ablated.decision_function(x[test][:, noncadence]),
            "legit_isolation_forest": -forest.score_samples(x[test]) - threshold,
        }
        for model, values in scores.items():
            for index, value in zip(test, values):
                predictions.append(dict(
                    model=model, trial=rows[index]["trial"], seed=rows[index]["seed"],
                    label=int(y[index]), score=float(value), flagged=bool(value > 0),
                    score_meaning="signed margin above decision threshold; not P(cheating)",
                ))
        for index in test:
            for scenario, data in rows[index].get("synthetic_stress", {}).items():
                for model in ("logistic", "logistic_rate_only", "fixed_rate_rule"):
                    prediction = dict(trial=rows[index]["trial"], label=int(y[index]),
                                      scenario=scenario, model=model, status=data["status"])
                    if data["status"] == "scored":
                        vector = np.array([[data["features"][f] for f in FEATURES]])
                        if model == "logistic":
                            value = float(logistic.decision_function(vector)[0])
                        elif model == "logistic_rate_only":
                            value = float(rate_only.decision_function(vector[:, :2])[0])
                        else:
                            value = float(vector[0, 1] - 20.5)
                        prediction.update(score=value, flagged=value > 0)
                    stress_predictions.append(prediction)
    summaries = {
        model: metrics([p for p in predictions if p["model"] == model])
        for model in sorted({p["model"] for p in predictions})
    }
    # A later-route holdout is another development test, not an unseen human/day test.
    seeds = sorted({r["seed"] for r in rows})
    boundary = seeds[max(2, len(seeds) * 2 // 3)]
    train = [i for i, r in enumerate(rows) if r["seed"] < boundary]
    test = [i for i, r in enumerate(rows) if r["seed"] >= boundary]
    forward = make_pipeline(StandardScaler(), LogisticRegression(C=1, solver="liblinear", max_iter=2000, random_state=42))
    forward.fit(x[train], y[train])
    temporal_predictions = [dict(label=int(y[i]), score=float(v), flagged=bool(v > 0))
                            for i, v in zip(test, forward.decision_function(x[test]))]
    stress_summary = {}
    for scenario, model in sorted({(p["scenario"], p["model"]) for p in stress_predictions}):
        subset = [p for p in stress_predictions if p["scenario"] == scenario and p["model"] == model]
        scored = [p for p in subset if p["status"] == "scored"]
        stress_summary[scenario + "/" + model] = dict(scored=len(scored), abstained=len(subset)-len(scored),
                                       metrics=metrics(scored) if scored else None)
    return dict(folds=folds, predictions=predictions, metrics=summaries, coefficients=coefficients,
                synthetic_stress=dict(summary=stress_summary, predictions=stress_predictions),
                later_route_holdout=dict(first_test_seed=boundary, train_trials=len(train),
                                        test_trials=len(test), metrics=metrics(temporal_predictions)))


def paired_effect(rows):
    pairs = []
    for seed in sorted({r["seed"] for r in rows}):
        legit = [r["features"]["movement_pps"] for r in rows if r["seed"] == seed and not r["label"]]
        cheat = [r["features"]["movement_pps"] for r in rows if r["seed"] == seed and r["label"]]
        if legit and cheat:
            a, b = float(np.mean(legit)), float(np.mean(cheat))
            pairs.append(dict(seed=seed, legit_pps=a, timer_pps=b, delta_pps=b-a))
    nonzero = [p["delta_pps"] for p in pairs if p["delta_pps"] != 0]
    return dict(pairs=pairs, mean_delta_pps=float(np.mean([p["delta_pps"] for p in pairs])) if pairs else 0,
                sign_test_p=float(binomtest(sum(d > 0 for d in nonzero), len(nonzero)).pvalue) if nonzero else 1,
                interpretation="Exploratory paired local effect; pairs share player/day and are not independent human samples")


def render(report):
    c = report["coverage"]
    lines = ["# Timer research evaluation", "", "Status: DEVELOPMENT ONLY — not a production acceptance test.", "",
             f"{c['trials']} audited trials; {c['route_seeds']} route seeds; {c['players']} player(s); {c['days']} day(s).",
             f"{c['raw_events']} raw events; {c['legit_hours']:.3f} legitimate episode-hours.",
             "All current routes were automated, including the vanilla condition. Labels were declared by the operator.", "",
             "## Held-out route results", "",
             "All trials/windows from one seed stay outside that fold's training and calibration.",
             "Scaling fits training rows only. Hyperparameters and thresholds are fixed within this run.",
             "Rate-only logistic is an exploratory refinement after observing full-model batching failures.",
             "These recordings were already inspected during development; this is not a pristine final holdout.", "",
             "| Model | TP | FP | TN | FN | ROC AUC | PR AP |", "|---|---:|---:|---:|---:|---:|---:|"]
    for name, m in report["evaluation"]["metrics"].items():
        lines.append(f"| {name} | {m['tp']} | {m['fp']} | {m['tn']} | {m['fn']} | {m['roc_auc']:.3f} | {m['average_precision']:.3f} |")
    logistic = report["evaluation"]["metrics"]["logistic"]
    lines += ["", f"Logistic false-positive rate: {logistic['false_positive_rate']:.1%}; one-sided 95% binomial upper bound: {logistic['fpr_upper_95_iid']:.1%}.",
              "That bound assumes independent trials, which this one-player scripted dataset does not establish.",
              "Isolation Forest fits legitimate training trials only; its threshold is the maximum score on a separate legitimate seed.",
              "One calibration seed cannot justify a low false-positive operating point. Scores are margins, not cheating probabilities.", "",
              "## Matched measurements", "", "| Seed | Vanilla packets/s | Timer packets/s | Difference |", "|---|---:|---:|---:|"]
    for p in report["paired"]["pairs"]:
        lines.append(f"| {p['seed']} | {p['legit_pps']:.4f} | {p['timer_pps']:.4f} | {p['delta_pps']:.4f} |")
    lines += ["", f"Exploratory paired sign-test p={report['paired']['sign_test_p']:.6f}; this is not P(cheating).", "",
              "## Receive-timing sensitivity", "",
              "Synthetic perturbations of each held-out trial, scored by its original training-fold model.",
              "These are not additional samples or a live network test. Snapshot age is held constant to isolate receive cadence.",
              "", "| Scenario/model | Scored | Abstained | TP | FP |", "|---|---:|---:|---:|---:|"]
    for scenario, data in report["evaluation"]["synthetic_stress"]["summary"].items():
        m = data["metrics"]
        lines.append(f"| {scenario} | {data['scored']} | {data['abstained']} | {m['tp'] if m else '-'} | {m['fp'] if m else '-'} |")
    lines += ["", "Abstention means insufficient trustworthy observation, not a legitimate verdict.", "",
              "## What remains unproven", "",
              "- Human and network generalization: one player/day, local scripted routes.",
              "- Client confounding: add Vape with Timer off using the same client and settings.",
              "- Bot/macro classification: vanilla samples are automated too. Never label them as human negatives.",
              f"- Combat macro feature coverage: {c['macro_eligible_trials']} trial(s) with sufficient unbatched attack intervals.",
              "- Large-scale performance: measured extraction throughput below is local, not a network capacity claim.",
              "- Research classifiers do not authorize bans. The separate Timer budget rule is report-only by default; an apparent legitimate Lunar flag remains unresolved.", "",
              "## Reproducibility", "",
              f"Run ID: `{report['run_id']}`",
              f"Extraction: {report['performance']['extraction_seconds']:.3f}s; {report['performance']['events_per_second']:.0f} audited source events/s, bounded to one trial at a time.",
              "features.jsonl contains exact per-trial inputs and source hashes; results.json contains every split, prediction, coefficient and metric.",
              "See docs/ROLE_EVIDENCE.md for the role requirement mapping and missing evidence.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    start = time.perf_counter()
    rows, rejected = load(args.database)
    extraction = time.perf_counter() - start
    if not rows:
        raise ValueError("No valid trials")
    result = evaluate(rows)
    hashes = {name: digest(Path(__file__).with_name(name)) for name in ("evaluate.py", "features.py")}
    root = Path(__file__).resolve().parents[2]
    for name in ("tools/timer/model.py", "tools/dataset/dataset_tool.py"):
        hashes[name] = digest(root / name)
    identity = json.dumps(dict(rows=rows, source=hashes), sort_keys=True).encode()
    coverage = dict(trials=len(rows), rejected=len(rejected), route_seeds=len({r["seed"] for r in rows}),
                    players=len({r["player"] for r in rows}), days=len({r["day"] for r in rows}),
                    raw_events=sum(r["raw_events"] for r in rows),
                    legit_hours=sum(170 for r in rows if not r["label"]) / 3600,
                    macro_eligible_trials=sum(r["macro"]["eligible"] for r in rows))
    report = dict(run_id=hashlib.sha256(identity).hexdigest(), created=datetime.now(timezone.utc).isoformat(),
                  source_hashes=hashes, environment=dict(python=platform.python_version(), sklearn=sklearn.__version__,
                  numpy=np.__version__, scipy=scipy.__version__), coverage=coverage, rejected=rejected,
                  performance=dict(extraction_seconds=extraction, events_per_second=coverage["raw_events"] / extraction),
                  paired=paired_effect(rows), evaluation=result, deployment="research_only")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "features.jsonl").write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in rows), encoding="utf-8")
    (args.output / "results.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    (args.output / "REPORT.md").write_text(render(report), encoding="utf-8")
    print(render(report))


if __name__ == "__main__":
    main()
