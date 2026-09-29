import math
import re
from windows import FEATURES


def validate(spec):
    if not re.fullmatch(r"[a-z][a-z0-9_.-]{2,63}", spec["id"]):
        raise ValueError("Use a stable lowercase detection ID")
    if spec["version"] < 1 or spec["window_seconds"] != 30:
        raise ValueError("Unsupported detection/feature version")
    if not spec["name"] or not spec["mechanism"] or not spec["source"]:
        raise ValueError("Name, observed mechanism and evidence source are required")
    if not spec["features"] or len(set(spec["features"])) != len(spec["features"]):
        raise ValueError("Features must be unique and nonempty")
    if set(spec["features"]) - set(FEATURES):
        raise ValueError(
            "Unregistered features; extend the shared telemetry contract first"
        )
    if spec["target"] not in ("cheat", "automation"):
        raise ValueError("Target must be cheat or automation")
    if (
        not 0 < spec["tail_cutoff"] <= 0.05
        or not 1 <= spec["consecutive_windows"] <= 20
    ):
        raise ValueError("Invalid response threshold")
    if not 0 <= spec["minimum_attacks"] <= 10000 or not math.isfinite(
        spec["minimum_margin"]
    ):
        raise ValueError("Invalid feature eligibility")
    rules = spec["validation"]
    for key in (
        "minimum_human_players",
        "minimum_days",
        "minimum_network_conditions",
        "minimum_legit_hours",
    ):
        if (
            not isinstance(rules[key], (int, float))
            or not math.isfinite(rules[key])
            or rules[key] <= 0
        ):
            raise ValueError("Positive validation coverage required: " + key)
    if (
        not 0 < rules["maximum_fpr_upper_95"] <= 0.05
        or not 0.5 <= rules["minimum_recall"] <= 1
    ):
        raise ValueError("Invalid validation error budget")
    if not isinstance(rules["require_same_client_control"], bool):
        raise ValueError("Same-client control must be a boolean")
    return spec
