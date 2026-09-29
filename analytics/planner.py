"""Suggest the next collection from existing evidence and fixed recipe requirements."""

import json
import math

from contracts import validate
from models import groups, implementation_hashes, select_samples
from store import identity


def plan_next(db, detection):
    row = db.execute(
        "SELECT spec_json FROM detections WHERE id=?", (detection,)
    ).fetchone()
    if not row:
        raise ValueError("Unknown detection; register its recipe first")
    spec = validate(json.loads(row[0]))
    rules = spec["validation"]
    available, labels, _, excluded = select_samples(db, spec, "pilot")
    reviewed, _, windows, _ = select_samples(db, spec, "independent")
    connected = groups(reviewed)
    # Unreviewed development still exposes participants and dates to the investigator.
    exposed_groups = groups(available)
    development = [s for s in available if s["purpose"] == "development"]
    used_groups = {exposed_groups[s["id"]] for s in development}
    used_days = {s["day"] for s in development}
    held = [s for s in reviewed if s["purpose"] == "validation"]
    old_tests = set()
    implementation = implementation_hashes()
    for old in db.execute(
        "SELECT report_json,model_json FROM experiments WHERE scope='independent'"
    ):
        report, model = json.loads(old[0]), json.loads(old[1])
        if report.get("implementation") != implementation or model.get(
            "spec_hash"
        ) != identity(spec):
            old_tests.update(
                k for k, v in report.get("assignments", {}).items() if v == "test"
            )
    contaminated = [
        s
        for s in held
        if exposed_groups[s["id"]] in used_groups
        or s["day"] in used_days
        or s["id"] in old_tests
    ]
    held = [s for s in held if s not in contaminated]
    negative = [s for s in held if labels[s["id"]] == 0]
    positive = [s for s in held if labels[s["id"]] == 1]

    # Same connected identities and hash ordering as models.partition.
    dev = [s for s in reviewed if s["purpose"] == "development"]
    dev_groups = sorted({connected[s["id"]] for s in dev}, key=identity)
    calibration = (
        set(dev_groups[: max(1, len(dev_groups) // 3)])
        if len(dev_groups) >= 3
        else set()
    )
    calibration_negatives = {
        connected[s["id"]]
        for s in dev
        if connected[s["id"]] in calibration and labels[s["id"]] == 0
    }
    negative_groups = {connected[s["id"]] for s in negative}
    # Necessary sample-count floors only: observed errors can require more evidence.
    calibration_floor = math.ceil(2 / spec["tail_cutoff"] - 1)
    zero_error_floor = math.ceil(
        math.log(0.05) / math.log1p(-rules["maximum_fpr_upper_95"])
    )
    required = dict(
        human_players=math.ceil(rules["minimum_human_players"]),
        days=math.ceil(rules["minimum_days"]),
        network_conditions=math.ceil(rules["minimum_network_conditions"]),
        legit_hours=rules["minimum_legit_hours"],
        negative_test_groups=zero_error_floor,
        legitimate_calibration_groups=calibration_floor,
    )
    measured = dict(
        human_players=len({s["player_group"] for s in negative}),
        days=len({s["day"] for s in held}),
        network_conditions=len({s["network"] for s in held}),
        legit_hours=sum(
            len(windows[s["id"]]) * spec["window_seconds"] / 3600 for s in negative
        ),
        negative_test_groups=len(negative_groups),
        legitimate_calibration_groups=len(calibration_negatives),
    )
    gaps = {
        k: dict(observed=measured[k], required=v, missing=max(0, v - measured[k]))
        for k, v in required.items()
    }

    # Same-client/network controls must accompany each positive combination, not just
    # exist somewhere else in the registry. Unknown/short captures cannot fill a cell.
    def cells(samples, label):
        return {
            (s["client"], s["network"]) for s in samples if labels[s["id"]] == label
        }

    missing_controls = sorted(cells(available, 1) - cells(available, 0))
    held_controls = sorted(cells(held, 1) - cells(held, 0))
    pending_review = [
        s["id"]
        for s in available
        if s["review"] != "reviewed"
        and (labels[s["id"]] == 1 or s["input_source"] == "human")
    ]
    missing_classes = [
        part
        for part, ids in (
            ("train", set(dev_groups) - calibration),
            ("calibration", calibration),
        )
        if {labels[s["id"]] for s in dev if connected[s["id"]] in ids} != {0, 1}
    ]

    previous = db.execute(
        "SELECT id,scope,report_json FROM experiments WHERE detection_id=? "
        "ORDER BY created_ms DESC,id DESC LIMIT 1",
        (detection,),
    ).fetchone()
    prior = json.loads(previous["report_json"]) if previous else {}
    stress_failures = sorted(
        name for name, result in prior.get("stress", {}).items() if result.get("fp", 0)
    )
    actions = []

    def add(kind, reason, task):
        actions.append(dict(kind=kind, reason=reason, task=task))

    if stress_failures:
        add(
            "reproduce_failure",
            "Latest experiment flagged legitimate transport replays: "
            + ", ".join(stress_failures),
            "Reproduce the failing condition with declared legitimate input. Keep this investigation in development; reserve fresh validation after changing the model.",
        )
    if missing_controls:
        add(
            "matched_control",
            "Eligible positive client/network cells lack an all-off control.",
            "Record OFF and ON with the same client build, network, route and actions. Change only the declared behavior.",
        )
    if pending_review:
        add(
            "review",
            f"{len(pending_review)} eligible recordings need label review.",
            "Compare the declarations with independent video, settings or network evidence. Review only what that evidence supports.",
        )
    if (
        len(dev_groups) < 3
        or missing_classes
        or gaps["legitimate_calibration_groups"]["missing"]
    ):
        add(
            "development",
            "Development needs both classes in fit/calibration and enough independent legitimate calibration groups.",
            "Collect both classes in development. Keep each player, opponent and script family in one connected group; repeats do not add independent groups.",
        )
    if contaminated:
        add(
            "replace_validation",
            f"{len(contaminated)} reviewed validation recordings share development identities/days or were used to test changed code/recipes.",
            "Reserve fresh participants, opponents, script families and dates. Keep the overlapping recordings as failed validation evidence.",
        )
    if any(g["missing"] for g in gaps.values()) or held_controls or not positive:
        add(
            "validation",
            "Independent coverage remains below the recipe requirements.",
            "Reserve validation before recording. Use new connected groups and dates, include both classes on matched networks, and preserve evidence for review.",
        )
    if not actions:
        add(
            "evaluate",
            "The registry meets collection floors; model acceptance has not been inferred.",
            "Run independent training/evaluation and inspect recall, error bounds, transport failures and support limits.",
        )

    choice = actions[0]
    purpose = (
        "validation"
        if choice["kind"] in ("validation", "replace_validation")
        else "development"
    )
    client, network = missing_controls[0] if missing_controls else (None, None)
    metadata = dict(
        client=client,
        network=network,
        input_source="human",
        script_family="",
        behavior="none",
        label="legit",
        route=None,
        purpose=purpose,
        configuration="all-off",
    )
    enabled = dict(
        metadata, behavior=spec["positive_behavior"], label="cheat", configuration=None
    )
    if spec["target"] == "automation":
        enabled.update(input_source="scripted", script_family=None)
    capture = dict(
        seconds=10 + spec["window_seconds"] * spec["consecutive_windows"],
        consecutive_windows=spec["consecutive_windows"],
        window_seconds=spec["window_seconds"],
        minimum_attacks_per_window=spec["minimum_attacks"],
        acceptance="Every capture must pass the raw audit and window eligibility rules. Keep failed or inconclusive results.",
        controls=missing_controls,
        templates=[
            dict(player=None, metadata=metadata),
            dict(player=None, metadata=enabled),
        ],
    )
    if spec["minimum_attacks"]:
        capture["input_note"] = (
            "Use real human input for the negative. Do not automate clicks to meet the minimum; insufficient-attack windows remain ineligible."
        )
    capture["steps"] = [
        "Use one client build and network for both conditions. Save a settings/input reference for independent review.",
        "Set the exact ON configuration before reserving each capture; change only that behavior. Alternate OFF/ON order on repeats.",
    ]
    if spec["positive_behavior"] == "timer":
        phase = spec["window_seconds"] * spec["consecutive_windows"] / 3
        capture["steps"].append(
            f"Each capture: settle for 10 s, then walk {phase:g} s, sprint {phase:g} s, sprint-jump {phase:g} s on the same clear route."
        )
    elif spec["minimum_attacks"]:
        capture["steps"].append(
            "Use a consenting target in the same controlled position and reach range. Record human attacks for OFF; record only the declared behavior for ON."
        )
    else:
        capture["steps"].append(
            "Write a timed route/action sequence before recording; repeat it under both conditions."
        )
    if choice["kind"] == "reproduce_failure":
        capture["templates"] = [
            dict(player=None, metadata=dict(metadata, client=None, network=None))
        ]
        capture["steps"] = [
            "Record legitimate behavior under each failing transport condition; declare its measured network profile and keep all gameplay modules off."
        ]
    if choice["kind"] in ("review", "evaluate"):
        capture = None
    return dict(
        detection=detection,
        recipe_sha256=identity(spec),
        evidence=dict(
            eligible_development_or_pilot=len(available),
            reviewed_independent_eligible=len(reviewed),
            sources={s["id"]: s["source_hash"] for s in available},
            excluded=excluded,
            pending_review=pending_review,
            contaminated_validation=[s["id"] for s in contaminated],
        ),
        next_experiment=choice,
        actions=actions,
        capture=capture,
        coverage=gaps,
        validation_controls_missing=held_controls,
        development_group_floor=3 * calibration_floor,
        latest_experiment=(
            dict(
                id=previous["id"],
                scope=previous["scope"],
                blockers=prior.get("blockers", []),
            )
            if previous
            else None
        ),
        limits=[
            "Collection floors do not imply model accuracy or permission to enforce.",
            "The negative test-group floor assumes zero false positives and independent groups.",
            "Calibration counts depend on the fixed one-third development split; every split also needs both classes.",
            "Templates with null values must be completed before using plan. No capture is reserved by plan-next.",
        ],
    )


def render(plan):
    task = plan["next_experiment"]
    capture = plan["capture"]
    evidence = plan["evidence"]
    lines = [
        f"Next experiment: {plan['detection']}",
        f"Eligible: {evidence['eligible_development_or_pilot']} recordings; {evidence['reviewed_independent_eligible']} reviewed for independent evaluation.",
        task["reason"],
        task["task"],
    ]
    if capture:
        conditions = "OFF only" if len(capture["templates"]) == 1 else "OFF / ON"
        lines += [
            "",
            f"Capture: {conditions}, {capture['seconds']} seconds per condition; {capture['consecutive_windows']} consecutive {capture['window_seconds']}-second windows.",
        ]
        for client, network in capture["controls"]:
            lines.append(f"Missing control: client={client}, network={network}")
        lines += [f"{n}. {step}" for n, step in enumerate(capture["steps"], 1)]
        if capture["minimum_attacks_per_window"]:
            lines += [
                f"Eligibility: at least {capture['minimum_attacks_per_window']} attack requests per window.",
                capture["input_note"],
            ]
        lines += [capture["acceptance"]]
    lines += ["", "Independent coverage (observed / required / missing):"]
    for name, row in plan["coverage"].items():
        lines.append(
            f"  {name}: {row['observed']:g} / {row['required']:g} / {row['missing']:g}"
        )
    lines += [
        f"  Development group floor under the current split: {plan['development_group_floor']}"
    ]
    if len(plan["actions"]) > 1:
        lines += ["", "Also required:"]
    for action in plan["actions"]:
        if action != task:
            lines.append("  " + action["reason"] + " " + action["task"])
    if capture:
        lines += [
            "",
            "Reserve before recording: Validation > Record session, or plan PLAYER METADATA_JSON.",
            "Required: player, client build, network, input source, route, purpose, label, behavior, configuration; script family for automation.",
            "Use --format json for metadata templates and affected sample IDs.",
        ]
    lines += ["", *plan["limits"][:3]]
    return "\n".join(lines)
