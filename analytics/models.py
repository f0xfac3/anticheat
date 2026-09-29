"""Fixed protocol: grouped fit, legitimate calibration, untouched validation, explicit promotion gates."""

import json
import math
import hashlib
from collections import Counter
from pathlib import Path

import numpy as np
from scipy.stats import beta
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

from store import audit, canonical, identity, now, ROOT


def implementation_hashes():
    return {
        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(ROOT.glob("*.py"))
    }


def groups(samples):
    # Connected identities: a person or script family cannot cross an independent split.
    parents = {}

    def root(x):
        parents.setdefault(x, x)
        if parents[x] != x:
            parents[x] = root(parents[x])
        return parents[x]

    for s in samples:
        player = "player:" + s["player_group"]
        root(player)
        if s["script_family"]:
            parents[root("script:" + s["script_family"])] = root(player)
        for opponent in s.get("opponent_groups", []):
            parents[root("player:" + opponent)] = root(player)
    return {s["id"]: root("player:" + s["player_group"]) for s in samples}


def partition(samples, scope):
    group = (
        {s["id"]: "route:" + s["route"] for s in samples}
        if scope == "pilot"
        else groups(samples)
    )
    ids = sorted(set(group.values()), key=lambda x: identity(x))
    if len(ids) < 5:
        raise ValueError(
            "Need at least five independent groups. Use pilot only for a route-scoped development study."
        )
    if scope == "independent":
        test = {group[s["id"]] for s in samples if s["purpose"] == "validation"}
        development = {group[s["id"]] for s in samples if s["purpose"] == "development"}
        if not test or test & development:
            raise ValueError(
                "Validation must be declared in advance and have disjoint player/script components"
            )
        rest = [g for g in ids if g not in test]
        if len(rest) < 3:
            raise ValueError(
                "Need additional development groups for fit and calibration"
            )
        calibration = set(rest[: max(1, len(rest) // 3)])
    else:
        count = max(1, len(ids) // 4)
        test, calibration = set(ids[:count]), set(ids[count : 2 * count])
    return {
        s["id"]: (
            "test"
            if group[s["id"]] in test
            else "calibration" if group[s["id"]] in calibration else "train"
        )
        for s in samples
    }, group


def sustained(values, count):
    if len(values) < count:
        return None
    return max(min(values[i : i + count]) for i in range(len(values) - count + 1))


def select_samples(db, spec, scope):
    """Shared eligibility rules for training and collection planning."""
    candidates = [
        dict(r)
        for r in db.execute(
            "SELECT * FROM samples WHERE review!='rejected' ORDER BY id"
        )
    ]
    samples, labels, values, excluded = [], {}, {}, Counter()
    scanned_windows = 0
    for s in candidates:
        if spec["target"] == "automation":
            if s["input_source"] == "human" and s["label"] == "legit":
                label = 0
            elif (
                s["input_source"] == "scripted"
                and s["behavior"] == spec["positive_behavior"]
            ):
                label = 1
            else:
                excluded["unrelated_label_or_input"] += 1
                continue
        elif s["label"] == "legit":
            label = 0
        elif s["behavior"] == spec["positive_behavior"]:
            label = 1
        else:
            excluded["unrelated_behavior"] += 1
            continue
        if scope == "independent" and s["review"] != "reviewed":
            excluded["needs_review"] += 1
            continue
        if scope == "independent" and label == 0 and s["input_source"] != "human":
            excluded["negative_input_not_human"] += 1
            continue
        s["opponent_groups"] = json.loads(s["metadata_json"]).get("opponent_groups", [])
        raw = [
            json.loads(r[0])
            for r in db.execute(
                "SELECT features_json FROM sample_windows WHERE sample_id=? ORDER BY ordinal",
                (s["id"],),
            )
        ]
        scanned_windows += len(raw)
        if scanned_windows > 200000:
            raise ValueError(
                "Training exceeds 200,000 windows; create a separately declared study registry instead of exhausting server memory"
            )
        # A missing/ineligible combat window breaks the sustained run; it must not be stitched over.
        if not raw or any(w["attacks"] < spec["minimum_attacks"] for w in raw):
            excluded["insufficient_attacks_or_windows"] += 1
            continue
        if len({w["segment"] for w in raw}) != 1:
            excluded["observation_discontinuity"] += 1
            continue
        if len(raw) < spec["consecutive_windows"]:
            excluded["too_few_consecutive_windows"] += 1
            continue
        x = [[w[f] for f in spec["features"]] for w in raw]
        if not all(math.isfinite(v) for line in x for v in line):
            raise ValueError("Nonfinite feature data")
        samples.append(s)
        labels[s["id"]], values[s["id"]] = label, x
    return samples, labels, values, dict(excluded)


def train(db, detection, scope):
    row = db.execute(
        "SELECT spec_json FROM detections WHERE id=?", (detection,)
    ).fetchone()
    if not row:
        raise ValueError("Register the detection recipe first")
    spec = json.loads(row[0])
    samples, labels, values, _ = select_samples(db, spec, scope)
    assignment, group = partition(samples, scope)
    for part in ("train", "calibration", "test"):
        if {labels[s["id"]] for s in samples if assignment[s["id"]] == part} != {0, 1}:
            raise ValueError(
                part
                + " lacks both classes; keep the split fixed and collect missing data"
            )
    training = [s for s in samples if assignment[s["id"]] == "train"]
    x = np.array([w for s in training for w in values[s["id"]]])
    y = np.array([labels[s["id"]] for s in training for _ in values[s["id"]]])
    # Each independent group receives equal weight, including repeats and differing capture lengths.
    totals = {}
    for s in training:
        totals[group[s["id"]]] = totals.get(group[s["id"]], 0) + len(values[s["id"]])
    weight = np.array(
        [1 / totals[group[s["id"]]] for s in training for _ in values[s["id"]]]
    )
    weight *= len(weight) / weight.sum()
    scaler = StandardScaler().fit(x, sample_weight=weight)
    classifier = LogisticRegression(C=1, solver="liblinear", random_state=42).fit(
        scaler.transform(x), y, sample_weight=weight
    )
    coefficients = classifier.coef_[0] / scaler.scale_
    intercept = float(classifier.intercept_[0] - np.dot(coefficients, scaler.mean_))
    scored = []
    for s in samples:
        margins = [float(np.dot(coefficients, w) + intercept) for w in values[s["id"]]]
        scored.append(
            dict(
                sample=s["id"],
                label=labels[s["id"]],
                group=group[s["id"]],
                split=assignment[s["id"]],
                score=sustained(margins, spec["consecutive_windows"]),
                windows=len(margins),
            )
        )
    references = {}
    for r in scored:
        if r["split"] == "calibration" and r["label"] == 0:
            references[r["group"]] = max(
                references.get(r["group"], -math.inf), r["score"]
            )
    reference = sorted(references.values())
    threshold = max(spec["minimum_margin"], max(reference))
    test = [r for r in scored if r["split"] == "test"]
    for r in scored:
        r["candidate"] = r["score"] > threshold
        r["tail_p"] = (1 + sum(v >= r["score"] for v in reference)) / (
            1 + len(reference)
        )
    negatives = [r for r in test if not r["label"]]
    positives = [r for r in test if r["label"]]
    fp = sum(r["candidate"] for r in negatives)
    tp = sum(r["candidate"] for r in positives)
    # Group-level validation rate: any candidate in a legitimate group counts as one failure.
    legit_groups = {r["group"] for r in negatives}
    failed_groups = {r["group"] for r in negatives if r["candidate"]}
    upper = (
        float(
            beta.ppf(
                0.95, len(failed_groups) + 1, len(legit_groups) - len(failed_groups)
            )
        )
        if len(failed_groups) < len(legit_groups)
        else 1.0
    )
    test_samples = [s for s in samples if assignment[s["id"]] == "test"]
    from stress import evaluate

    stress = evaluate(test_samples, labels, spec, coefficients, intercept, threshold)
    human = [
        s for s in test_samples if s["input_source"] == "human" and not labels[s["id"]]
    ]
    train_days = {s["day"] for s in samples if assignment[s["id"]] != "test"}
    positive_clients = {s["client"] for s in test_samples if labels[s["id"]]}
    negative_clients = {s["client"] for s in test_samples if not labels[s["id"]]}
    positive_networks = {s["network"] for s in test_samples if labels[s["id"]]}
    negative_networks = {s["network"] for s in test_samples if not labels[s["id"]]}
    coverage = dict(
        human_players=len({s["player_group"] for s in human}),
        days=len({s["day"] for s in test_samples}),
        network_conditions=len({s["network"] for s in test_samples}),
        legit_hours=sum(len(values[s["id"]]) * 30 / 3600 for s in human),
        same_client_control=bool(positive_clients)
        and positive_clients <= negative_clients,
        matched_network_controls=bool(positive_networks)
        and positive_networks <= negative_networks,
        days_disjoint=not (train_days & {s["day"] for s in test_samples}),
        reviewed=all(s["review"] == "reviewed" for s in samples),
    )
    rules, blockers = spec["validation"], []
    if scope != "independent":
        blockers.append(
            "pilot split does not test independent people or script families"
        )
    if not coverage["reviewed"]:
        blockers.append("labels have not all been independently reviewed")
    if not coverage["days_disjoint"]:
        blockers.append("validation days overlap development")
    if not coverage["matched_network_controls"]:
        blockers.append("network conditions are confounded with class labels")
    for key in ("human_players", "days", "network_conditions", "legit_hours"):
        if coverage[key] < rules["minimum_" + key]:
            blockers.append(f"{key}: {coverage[key]} < {rules['minimum_'+key]}")
    if rules["require_same_client_control"] and not coverage["same_client_control"]:
        blockers.append("same-client control missing")
    if upper > rules["maximum_fpr_upper_95"]:
        blockers.append("false-positive uncertainty exceeds policy")
    if tp / len(positives) < rules["minimum_recall"]:
        blockers.append("held-out recall below policy")
    if 1 / (len(reference) + 1) > spec["tail_cutoff"] / 2:
        blockers.append(
            "too few independent legitimate calibration groups for sequential testing"
        )
    if any(r["fp"] for r in stress.values()):
        blockers.append("transport stress replay produced legitimate candidates")
    model = dict(
        feature_version="behavior-v1",
        detection=detection,
        features=spec["features"],
        coefficients=list(map(float, coefficients)),
        intercept=intercept,
        threshold=threshold,
        references=reference,
        minimum_attacks=spec["minimum_attacks"],
        tail_cutoff=spec["tail_cutoff"],
        consecutive_windows=spec["consecutive_windows"],
        minimum=[float(v) for v in x.min(axis=0)],
        maximum=[float(v) for v in x.max(axis=0)],
        spec_hash=identity(spec),
    )
    import sklearn
    import scipy

    implementation = implementation_hashes()
    feature_summary = {}
    for column, feature in enumerate(spec["features"]):
        feature_summary[feature] = {}
        for label in (0, 1):
            measured = [
                w[column]
                for s in test_samples
                if labels[s["id"]] == label
                for w in values[s["id"]]
            ]
            feature_summary[feature][
                "cheat_or_automation" if label else "legitimate"
            ] = dict(
                windows=len(measured),
                minimum=min(measured),
                median=float(np.median(measured)),
                maximum=max(measured),
            )
    report = dict(
        scope=scope,
        coverage=coverage,
        blockers=blockers,
        predictions=scored,
        stress=stress,
        feature_summary=feature_summary,
        implementation=implementation,
        dependencies=dict(
            numpy=np.__version__, scipy=scipy.__version__, sklearn=sklearn.__version__
        ),
        assignments=assignment,
        calibration_groups=sorted(references),
        metrics=dict(
            tp=tp,
            fp=fp,
            tn=len(negatives) - fp,
            fn=len(positives) - tp,
            recall=tp / len(positives),
            fpr_upper_95=upper,
            legitimate_groups=len(legit_groups),
        ),
        sources={s["id"]: s["source_hash"] for s in samples},
        enforcement_ready=not blockers,
    )
    if scope == "independent":
        test_ids = {s["id"] for s in test_samples}
        for previous in db.execute(
            "SELECT report_json,model_json FROM experiments WHERE scope='independent'"
        ):
            old_report, old_model = json.loads(previous[0]), json.loads(previous[1])
            old_test = {k for k, v in old_report["assignments"].items() if v == "test"}
            if test_ids & old_test and (
                old_report.get("implementation") != implementation
                or old_model.get("spec_hash") != model["spec_hash"]
            ):
                blockers.append(
                    "validation data previously exposed to another hypothesis or implementation; reserve fresh validation"
                )
                break
        report["enforcement_ready"] = not blockers
    experiment = identity(dict(model=model, report=report))
    model["id"] = experiment
    db.execute(
        "INSERT OR IGNORE INTO experiments VALUES(?,?,?,?,?,?,?)",
        (
            experiment,
            detection,
            now(),
            scope,
            canonical(report),
            canonical(model),
            "validated" if not blockers else "gated",
        ),
    )
    audit(
        db,
        "experiment.complete",
        experiment,
        dict(detection=detection, blockers=blockers),
    )
    return experiment, report


def deploy(db, experiment, mode, directory):
    if mode not in ("shadow", "enforce", "disabled"):
        raise ValueError("Mode must be shadow, enforce or disabled")
    row = db.execute("SELECT * FROM experiments WHERE id=?", (experiment,)).fetchone()
    if not row:
        raise ValueError("Unknown experiment")
    model, report = json.loads(row["model_json"]), json.loads(row["report_json"])
    if mode == "enforce" and not report["enforcement_ready"]:
        raise ValueError("Promotion blocked: " + "; ".join(report["blockers"]))
    old = db.execute(
        "SELECT experiment_id FROM deployments WHERE detection_id=?",
        (row["detection_id"],),
    ).fetchone()
    properties = dict(
        id=experiment,
        detection=row["detection_id"],
        mode=mode,
        feature_version=model["feature_version"],
        intercept=model["intercept"],
        threshold=model["threshold"],
        minimum_attacks=model["minimum_attacks"],
        tail_cutoff=model["tail_cutoff"],
        consecutive_windows=model["consecutive_windows"],
        features=",".join(model["features"]),
        coefficients=",".join(map(str, model["coefficients"])),
        minimum=",".join(map(str, model["minimum"])),
        maximum=",".join(map(str, model["maximum"])),
        references=",".join(map(str, model["references"])),
    )
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / (row["detection_id"] + ".properties")
    temporary = path.with_suffix(".pending")
    temporary.write_text(
        "".join(f"{k}={v}\n" for k, v in properties.items()), encoding="ascii"
    )
    temporary.replace(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    db.execute(
        "INSERT OR REPLACE INTO deployments VALUES(?,?,?,?,?,?)",
        (row["detection_id"], experiment, mode, old[0] if old else None, now(), digest),
    )
    audit(
        db,
        "model.deploy",
        experiment,
        dict(mode=mode, previous=old[0] if old else None),
    )
    return str(path)
