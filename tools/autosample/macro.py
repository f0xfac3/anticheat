"""Matched human/AutoClicker attack-cadence study using the two-player lab fixture."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import statistics
import sys
import time
import uuid

from core import read_live_json, save_json, sha256
from reach import raw_manifest, wait
from runner import Bridge, config, ensure_server

FIXTURE = "stationary-macro-v1"
SECONDS = 100
FEATURES = ("attack_pps", "attack_cv", "attack_repeat")


def eligible(windows):
    return (len(windows) >= 3 and len({w.get("segment") for w in windows}) == 1
            and all(w.get("attacks", 0) >= 100 for w in windows))


def summarize(rows):
    if not rows:
        return {name: None for name in FEATURES}
    return {name: statistics.median(float(row[name]) for row in rows) for name in FEATURES}


def profile():
    path = Path(__file__).with_name("reach-profiles.json")
    if not path.is_file():
        raise RuntimeError("Run the existing Reach setup first; reach-profiles.json is missing.")
    data = read_live_json(path)
    return data["attacker"], data["target"]


def study_paths(cfg):
    root = Path(cfg["datasets"]) / "macro"
    repo = Path(cfg["server"]).resolve().parent / "source/anticheat"
    database = Path(cfg["server"]) / "plugins/FoxAntiCheat/analytics.sqlite"
    return root, repo, database


def wait_for_pair(bridge, expected, timeout=180):
    print("Join localhost:25565 on BOTH " + " and ".join(expected) + ".", flush=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = bridge.state()
        names = sorted(p["name"] for p in state.get("players", []))
        unexpected = sorted(set(names) - set(expected))
        if unexpected:
            raise RuntimeError("Disconnect unexpected account(s): " + ", ".join(unexpected))
        if names == sorted(expected):
            return state
        time.sleep(.5)
    raise RuntimeError("Both registered accounts did not join within three minutes.")


def latest_pair(root, requested=None):
    candidates = [root / requested / "plan.json"] if requested else sorted(root.glob("*/plan.json"))
    candidates = [p for p in candidates if p.is_file() and (p.parent / "human.json").is_file()
                  and read_live_json(p.parent / "human.json").get("complete")
                  and not ((p.parent / "macro.json").is_file()
                           and read_live_json(p.parent / "macro.json").get("complete"))]
    if not candidates:
        raise RuntimeError("Run macro_human.cmd first; no unmatched eligible human capture exists.")
    return candidates[-1].parent, read_live_json(candidates[-1])


def event_counts(raw, manifest, meta, target):
    start = meta["start_observed_ns"] + 5_000_000_000
    end = meta["end_observed_ns"] - 5_000_000_000
    counts, wrong, resets = Counter(), 0, 0
    for event in raw.records(manifest.parent):
        if event["kind"] in (2, 3, 10, 14):
            resets += 1
        if not start <= event["ns"] < end:
            continue
        counts[event["kind"]] += 1
        if event["kind"] == 7 and event.get("target") != target:
            wrong += 1
    return dict(swings=counts[9], attack_requests=counts[7], wrong_target=wrong,
                observation_resets=resets)


def collect(args):
    cfg = config()
    root, repo, database = study_paths(cfg)
    sys.path.insert(0, str(repo / "analytics"))
    from datasets import admit, decoder
    from lab import register
    from store import connect

    attacker, target = profile()
    bridge = Bridge(cfg)
    ensure_server(cfg, bridge)
    state = bridge.state()
    if "reach" not in state:
        raise RuntimeError("Install the current AutoSampleLab plugin and restart the server.")
    wait_for_pair(bridge, (attacker, target))

    root.mkdir(parents=True, exist_ok=True)
    if args.condition == "human":
        ident = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]
        pair_dir = root / ident
        plan = dict(id=ident, fixture=FIXTURE, attacker=attacker, target=target,
                    seconds=args.seconds, center_distance=3.0, network=args.network,
                    target_client="lunar-1.8.9", purpose="development")
        pair_dir.mkdir(parents=True, exist_ok=False)
        save_json(pair_dir / "plan.json", plan)
    else:
        pair_dir, plan = latest_pair(root, args.pair)

    human = args.condition == "human"
    module = "none" if human else "attack_macro"
    label = "legit" if human else "cheat"
    setting = "all-off" if human else args.setting
    source = "human" if human else "scripted"
    family = "" if human else args.family
    route = "macro-" + plan["id"]
    metadata = dict(client="vape", network=plan["network"], input_source=source,
                    script_family=family, behavior=module, label=label, route=route,
                    purpose="development", configuration=setting, study=FIXTURE,
                    opponent_account=target, fixture_input="server-positioned",
                    attack_input="human-manual" if human else "declared-autoclicker")

    if human:
        instructions = [
            "Vape may be loaded, but EVERY gameplay module must be OFF.",
            "Click the target naturally at 4-7 CPS for the full recording.",
            "Change rhythm occasionally. Do not use a metronome or click automation.",
        ]
    else:
        instructions = [
            "Enable ONLY Vape AutoClicker; every other gameplay module must be OFF.",
            f"Declared configuration: {setting}; script family: {family}.",
            "Hold the normal attack button for the full recording; do not click manually.",
        ]
    print(f"Attacker: {attacker} | AFK target: {target}")
    print("Both clients: 1.8.9, survival, empty hand, no potions, no movement.")
    for line in instructions:
        print(line)
    print(f"The controller positions both players and records {args.seconds} seconds automatically.")
    if input("Verify the condition, then type CONFIRMED: ").strip().upper() != "CONFIRMED":
        raise RuntimeError("Condition not confirmed; nothing recorded.")

    raw = decoder()
    acquired = False
    output = dict(condition=args.condition, complete=False, metadata=metadata, problems=[])
    try:
        bridge.action("reach_acquire", attacker=attacker, target=target)
        acquired = True
        bridge.action("reach_position", distance="3.0")
        print(f"Focus {attacker}. Start attacking after the countdown: 8 7 6 5 4 3 2 1", flush=True)
        wait(bridge, 8)
        started = bridge.action("reach_start", label=label, module=module, client="vape",
                                setting=setting, scenario=f"macro-{plan['id']}-{args.condition}")
        print(f"RECORDING {args.condition.upper()}: keep attacking for {args.seconds} seconds.", flush=True)
        wait(bridge, args.seconds)
        bridge.action("reach_stop")
        manifest, raw_meta = raw_manifest(Path(cfg["datasets"]) / "raw", started["trial"], bridge)
        audit = raw.inspect_trial(manifest.parent, raw_meta)
        if not audit["ok"]:
            raise RuntimeError("Raw audit failed: " + str(audit))
        counts = event_counts(raw, manifest, raw_meta, started["target_uuid"])
        with connect(database) as db:
            register(db, repo / "analytics/recipes/attack_macro.json")
            sample = admit(db, manifest, metadata)
            windows = [json.loads(r[0]) for r in db.execute(
                "SELECT features_json FROM sample_windows WHERE sample_id=? ORDER BY ordinal", (sample,))]
        output.update(trial=started["trial"], sample=sample, manifest=str(manifest),
                      manifest_sha256=sha256(manifest), audit=audit, counts=counts,
                      windows=windows, summary=summarize(windows))
        if counts["wrong_target"]:
            output["problems"].append("attacks targeted another entity")
        if counts["observation_resets"]:
            output["problems"].append("observation reset or loss")
        if not eligible(windows):
            output["problems"].append("need three consecutive windows with at least 100 attack requests each")
        output["complete"] = not output["problems"]
    finally:
        if acquired:
            try:
                bridge.action("reach_release")
            except Exception as error:
                output["problems"].append("fixture release: " + str(error))
        save_json(pair_dir / (args.condition + ".json"), output)

    if not output["complete"]:
        raise RuntimeError("Capture saved but ineligible: " + "; ".join(output["problems"]))
    print(f"Saved {output['sample']}: {len(output['windows'])} windows, "
          f"minimum {min(w['attacks'] for w in output['windows']):.0f} attacks/window.")
    print("Pair folder:", plan["id"])
    print("Next: macro_vape.cmd" if human else "Pair complete. Repeat until five pairs, then run macro_analyze.cmd.")


def analyze(args):
    cfg = config()
    root, repo, database = study_paths(cfg)
    sys.path.insert(0, str(repo / "analytics"))
    from lab import register
    from models import train
    from store import connect

    pairs = []
    for plan_path in sorted(root.glob("*/plan.json")):
        folder = plan_path.parent
        if not (folder / "human.json").is_file() or not (folder / "macro.json").is_file():
            continue
        human, macro = read_live_json(folder / "human.json"), read_live_json(folder / "macro.json")
        if human.get("complete") and macro.get("complete"):
            pairs.append((read_live_json(plan_path), human, macro))
    human_windows = [w for _, h, _ in pairs for w in h["windows"]]
    macro_windows = [w for _, _, m in pairs for w in m["windows"]]
    result = dict(complete_pairs=len(pairs), human_windows=len(human_windows),
                  macro_windows=len(macro_windows), human=summarize(human_windows),
                  macro=summarize(macro_windows), experiment=None, blockers=[], error=None)
    if len(pairs) >= 5:
        try:
            with connect(database) as db:
                register(db, repo / "analytics/recipes/attack_macro.json")
                experiment, report = train(db, "macro.attack", "pilot")
            result.update(experiment=experiment, blockers=report["blockers"],
                          metrics=report["metrics"], feature_summary=report["feature_summary"],
                          predictions=report["predictions"], stress=report["stress"])
        except Exception as error:
            result["error"] = str(error)
    else:
        result["error"] = f"Need five complete matched pairs; have {len(pairs)}."
    save_json(root / "report.json", result)

    lines = ["# Human versus AutoClicker pilot", "",
             f"Complete matched pairs: **{len(pairs)} / 5 minimum**", "",
             "Same attacker/client, stationary consenting target, 100-second captures. "
             "Labels are declared and this is development data, not production validation.", "",
             "| Input | Windows | Attack/s median | Interval CV median | Repeated intervals median |",
             "|---|---:|---:|---:|---:|",
             f"| Human | {len(human_windows)} | {result['human']['attack_pps'] or 0:.4f} | "
             f"{result['human']['attack_cv'] or 0:.4f} | {result['human']['attack_repeat'] or 0:.4f} |",
             f"| AutoClicker | {len(macro_windows)} | {result['macro']['attack_pps'] or 0:.4f} | "
             f"{result['macro']['attack_cv'] or 0:.4f} | {result['macro']['attack_repeat'] or 0:.4f} |", ""]
    if result["experiment"]:
        metrics = result["metrics"]
        lines += [f"Pilot experiment: `{result['experiment']}`", "",
                  f"Held-out route results: TP={metrics['tp']}, FP={metrics['fp']}, "
                  f"TN={metrics['tn']}, FN={metrics['fn']}.", "",
                  "Promotion blockers:"] + [f"- {item}" for item in result["blockers"]]
    else:
        lines += ["Model status: **waiting for data**", "", result["error"] or ""]
    lines += ["", "Raw manifests and per-window features remain referenced in each pair folder.",
              "The pilot split holds out entire route pairs. It does not establish performance across people, days or script families."]
    (root / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    print("Report:", root / "REPORT.md")
    if result["error"]:
        raise RuntimeError(result["error"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("human", "vape"):
        p = sub.add_parser(name)
        p.add_argument("--seconds", type=int, default=SECONDS)
        p.add_argument("--network", default="local-loopback")
        p.add_argument("--pair")
        p.add_argument("--setting", default="autoclicker=8-8cps")
        p.add_argument("--family", default="vape-autoclicker-fixed-v1")
    sub.add_parser("analyze")
    args = parser.parse_args()
    if args.command == "analyze":
        analyze(args)
    else:
        if not 100 <= args.seconds <= 600:
            raise RuntimeError("Use 100..600 seconds so three full behavior windows can be evaluated.")
        args.condition = "human" if args.command == "human" else "macro"
        collect(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, RuntimeError) as error:
        print("STOPPED: " + str(error), file=sys.stderr)
        sys.exit(2)
