"""Two-account stationary Reach experiment. Human clicks, automatic positioning and raw replay."""
from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

from core import read_live_json, save_json, sha256
from runner import Bridge, config, ensure_server

DISTANCES = (3.0, 3.4, 3.8, 4.2)  # Player centers, NOT eye-to-box distance.
FIXTURE = "stationary-reach-v1"


def parse_distances(value):
    try:
        distances = tuple(float(part) for part in value.split(","))
    except ValueError as error:
        raise argparse.ArgumentTypeError("Distances must be comma-separated numbers.") from error
    if len(distances) < 3 or len(distances) > 8 or any(not 2 <= d <= 5 for d in distances):
        raise argparse.ArgumentTypeError("Use 3..8 increasing center distances from 2 to 5.")
    if any(not left < right for left, right in zip(distances, distances[1:])):
        raise argparse.ArgumentTypeError("Distances must be strictly increasing.")
    return distances


def reach_setting(value):
    try:
        number = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("Reach setting must be a number.") from error
    if not 3 <= number <= 5:
        raise argparse.ArgumentTypeError("Reach setting must be 3.0..5.0.")
    return format(number, ".3f").rstrip("0").rstrip(".")


def on_file(setting):
    return "on-" + setting.replace(".", "_") + ".json"


def fixture_contract(distances, seconds):
    """Identity for measurements, deliberately independent of controller presentation code."""
    payload = dict(fixture=FIXTURE, protocol=47, server_model=10808,
        distances=list(distances), seconds=seconds, guard_seconds=5,
        target_state="stationary_survival_empty_hand", attacker_state="stationary_survival_empty_hand",
        attack_input="human_manual", position_input="server_scripted", damage="suppressed")
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def complete_capture(output, distances):
    phases = output.get("phases", ())
    return (len(phases) == len(distances)
        and not output.get("faults")
        and [p.get("center_distance") for p in phases] == list(distances)
        and len({p.get("trial") for p in phases}) == len(phases)
        and all(p.get("usable") and p.get("trial") for p in phases))


def wait(bridge, seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        bridge.action("reach_heartbeat")
        time.sleep(min(.3, max(0, end - time.monotonic())))


def raw_manifest(root, trial, bridge):
    end = time.monotonic() + 10
    while time.monotonic() < end:
        bridge.action("reach_heartbeat")
        for path in root.glob("run-*/" + trial + "/manifest.json"):
            meta = read_live_json(path)
            if meta.get("status") == "complete":
                return path, meta
            if meta.get("status") == "failed":
                raise RuntimeError("Recorder failed: " + str(path))
        time.sleep(.1)
    raise RuntimeError("Capture did not finalize: " + trial)


def metrics(events, meta, target, center, near_control=DISTANCES[0]):
    start = meta["start_observed_ns"] + 5_000_000_000
    end = meta["end_observed_ns"] - 5_000_000_000
    swings = attacks = wrong_target = resets = 0
    distances, packets = [], []
    for event in events:
        if event["kind"] == 1:
            if event["protocol"] != 47 or event["model"] != 10808:
                raise ValueError("Requires direct protocol 47 / Spigot 1.8.8 observations")
            continue
        # Resets anywhere inside the actual capture are disqualifying, including its guards.
        if event["kind"] in (2, 3, 10, 14):
            resets += 1
        if not start <= event["ns"] < end:
            continue
        if event["kind"] == 9:
            swings += 1
        if event["kind"] != 7:
            continue
        attacks += 1
        packets.append(event["packet"])
        if event.get("target") != target:
            wrong_target += 1
        if event.get("available") and event.get("target") == target:
            # Raw, unexpanded nearest point distance. Native replay supplies conservative envelopes.
            distances.append(sum(max(lo-p, 0, p-hi)**2
                for p, lo, hi in zip(event["eye"], event["low"], event["high"])) ** .5)
    problems = []
    if end <= start: problems.append("capture_too_short")
    if resets: problems.append("observation_reset_or_loss")
    if swings < 20: problems.append("too_few_arm_swings_check_focus_and_clicking")
    if wrong_target: problems.append("wrong_target")
    if center == near_control and attacks < 5: problems.append("near_target_control_failed_check_aim")
    return dict(usable=not problems, problems=problems, seconds=(end-start)/1e9,
        center_distance=center, swings=swings, attacks=attacks, wrong_target=wrong_target,
        raw_eye_box_min=min(distances) if distances else None,
        raw_eye_box_max=max(distances) if distances else None,
        first_attack_packet=packets[0] if packets else None,
        last_attack_packet=packets[-1] if packets else None)


def native_replay(manifest, engine, engine_config, output):
    with tempfile.TemporaryDirectory(prefix="reach-replay-") as temp:
        temp = Path(temp)
        with (temp / "events.bin").open("wb") as dst:
            for chunk in sorted(manifest.parent.glob("events-*.acbin.gz")):
                with gzip.open(chunk, "rb") as src:
                    shutil.copyfileobj(src, dst)
        # Trace is the only changed setting; detector thresholds are preserved exactly.
        text = engine_config.read_text(encoding="utf-8")
        lines = [line for line in text.splitlines() if not line.strip().startswith("trace=")]
        (temp / "engine.conf").write_text("\n".join(lines) + "\ntrace=true\n", encoding="utf-8")
        with (temp / "all.jsonl").open("w", encoding="utf-8") as dst:
            subprocess.run([str(engine), str(temp / "events.bin"), str(temp / "engine.conf")],
                stdout=dst, stderr=subprocess.PIPE, text=True, check=True, timeout=30)
        counts, skips = Counter(), Counter()
        with output.open("w", encoding="utf-8") as dst:
            for line in (temp / "all.jsonl").read_text(encoding="utf-8").splitlines():
                finding = json.loads(line)
                if finding.get("check") != "reach.stationary.v1":
                    continue
                dst.write(line + "\n")
                counts[finding["message"]] += 1
                if finding["message"] == "reach_skipped":
                    skips[finding["evidence"].get("reason", "unknown")] += 1
        return dict(counts=dict(counts), skips=dict(skips), path=str(output), sha256=sha256(output))


def comparison(pair_dir, plan, on_result="on.json"):
    results = {}
    for condition, filename in (("off", "off.json"), ("on", on_result)):
        path = pair_dir / filename
        if path.exists():
            results[condition] = read_live_json(path)
    lines = ["Reach / stationary request geometry", "Pair: " + plan["id"],
        "Damage and velocity suppressed. Attack requests do not establish accepted hits.",
        "Center distance is not reach distance. Exact raw eye/box distances are in each phase JSON.",
        "Human mouse clicks; scripted positioning. Development data; operator-declared labels.", "",
        "center  OFF swings/attacks  ON swings/attacks  OFF/ON suspicious samples"]
    for distance in plan.get("distances", DISTANCES):
        entries = {key: next((p for p in value.get("phases", [])
            if p["center_distance"] == distance and p["usable"]), None) for key, value in results.items()}
        cells, suspicious = [], []
        for key in ("off", "on"):
            row = entries.get(key)
            cells.append(f'{row["swings"]}/{row["attacks"]}' if row else "pending/invalid")
            suspicious.append(str(row["native"]["counts"].get("reach_suspicious_sample", 0)) if row else "-")
        lines.append(f"{distance:5.3f}   {cells[0]:18} {cells[1]:17} {'/'.join(suspicious)}")
    complete = all(results.get(c, {}).get("complete") for c in ("off", "on"))
    lines += ["", "Paired run complete: " + str(complete),
        "Native replay includes capture guards; swing/attack totals exclude 5 seconds at each end.",
        "Missing attacks with recorded swings is an observation, not proof of a client setting.",
        "No model is fitted or deployed by this experiment."]
    suffix = "" if on_result == "on.json" else "-" + on_result.removesuffix(".json")
    path = pair_dir / ("COMPARISON" + suffix + ".txt")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def collect(args):
    cfg = config()
    repo = Path(cfg["server"]).resolve().parent / "source/anticheat"
    sys.path.insert(0, str(repo / "analytics"))
    from datasets import decoder, admit
    from store import connect
    raw = decoder()
    engine = repo / "build/native/anticheat_replay.exe"
    installed_config = Path(cfg["server"]) / "plugins/FoxAntiCheat/engine.conf"
    database = Path(cfg["server"]) / "plugins/FoxAntiCheat/analytics.sqlite"
    root = Path(cfg["datasets"]) / "reach"
    if not engine.is_file(): raise RuntimeError("Build the native replay executable before collecting.")
    profiles = json.loads((Path(__file__).parent / "reach-profiles.json").read_text(encoding="utf-8-sig"))
    attacker, target = profiles["attacker"], profiles["target"]
    bridge = Bridge(cfg); ensure_server(cfg, bridge)
    state = bridge.state()
    if "reach" not in state: raise RuntimeError("Install the Reach fixture plugin, then restart the server.")
    if sorted(p["name"] for p in state["players"]) != sorted((attacker, target)):
        raise RuntimeError(f"Join localhost:25565 on BOTH {attacker} and {target}. Leave other accounts disconnected.")
    if args.condition == "off":
        ident = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]
        pair_dir = root / ident
        plan = dict(id=ident, fixture=FIXTURE, attacker=attacker, target=target, distances=args.distances,
            seconds=args.seconds, client="vape", target_client="lunar-1.8.9", input_source="human",
            script_family="", fixture_input="scripted-positioning", attack_input="human-manual", network="local-loopback",
            purpose="development", verification="operator_declared_unreviewed",
            fixture_contract=fixture_contract(args.distances, args.seconds),
            source_sha256=sha256(Path(__file__)),
            engine_sha256=sha256(engine), engine_config_sha256=sha256(installed_config),
            plugin_sha256=sha256(Path(cfg["server"]) / "plugins/AutoSampleLab.jar"))
    else:
        candidates = sorted(root.glob("*/plan.json")) if root.exists() else []
        if args.pair: candidates = [root / args.pair / "plan.json"]
        output_file = on_file(args.setting)
        candidates = [p for p in candidates if p.exists() and (p.parent / "off.json").exists()
            and read_live_json(p.parent / "off.json").get("complete")
            and (not (p.parent / output_file).exists() or not read_live_json(p.parent / output_file).get("complete"))]
        if not candidates: raise RuntimeError("Finish reach_off.cmd first. No unmatched complete OFF run exists.")
        pair_dir = candidates[-1].parent; plan = read_live_json(candidates[-1])
        if (plan["attacker"], plan["target"]) != (attacker, target): raise RuntimeError("The paired accounts differ.")
        expected_contract = fixture_contract(plan["distances"], plan["seconds"])
        legacy_contract = "fixture_contract" not in plan
        if not legacy_contract and plan["fixture_contract"] != expected_contract:
            raise RuntimeError("The paired fixture contract changed; record a new OFF control.")
        for key, path in (("engine_sha256", engine),
            ("engine_config_sha256", installed_config),
            ("plugin_sha256", Path(cfg["server"]) / "plugins/AutoSampleLab.jar")):
            if plan[key] != sha256(path): raise RuntimeError("Code/configuration changed; record a new OFF control.")
    condition = "ALL gameplay modules OFF" if args.condition == "off" else (
        f"ONLY Reach ON: fixed {args.setting} blocks (both range endpoints), 100% chance where available; "
        "disable moving-only/sprint-only gates for this stationary experiment")
    print(f"Attacker: {attacker} | Target: {target}\n{condition}", flush=True)
    print("Both: Minecraft 1.8.9, survival, empty hands, no potions; stand still. Target stays AFK.")
    print("Click manually about 2-4 times/second throughout. No autoclicker. Do not move/jump/sneak.")
    print("After confirming, focus the ATTACKER window. Positions/aim, recording and stopping are automatic.")
    print(f"{len(plan['distances'])} distances, {plan['seconds']} seconds each. Ctrl+C in this terminal stops.")
    if input("Verify these settings, then type CONFIRMED: ").strip().upper() != "CONFIRMED":
        raise RuntimeError("Condition not confirmed; nothing recorded.")
    if args.condition == "off": save_json(pair_dir / "plan.json", plan)
    if args.condition == "off":
        print("Pair folder:", plan["id"], flush=True)
    output_file = "off.json" if args.condition == "off" else on_file(args.setting)
    attempt = pair_dir / "attempts" / (output_file.removesuffix(".json") + "-" + uuid.uuid4().hex[:8])
    attempt.mkdir(parents=True, exist_ok=False)
    output = dict(condition=args.condition, complete=False, phases=[], faults=[], attempt=str(attempt),
        fixture_contract=fixture_contract(plan["distances"], plan["seconds"]),
        controller_revision=dict(current=sha256(Path(__file__)),
            paired_off=plan["source_sha256"], source_changed=plan["source_sha256"] != sha256(Path(__file__)),
            legacy_contract=locals().get("legacy_contract", False)))
    save_json(pair_dir / output_file, output)
    acquired = False
    try:
        bridge.action("reach_acquire", attacker=attacker, target=target); acquired = True
        print(f"Focus {attacker}'s window now. Starting in 8 seconds.", flush=True)
        wait(bridge, 8)
        for index, distance in enumerate(plan["distances"]):
            bridge.action("reach_position", distance=str(distance)); wait(bridge, 3)
            label = "legit" if args.condition == "off" else "cheat"
            setting = "all-off" if args.condition == "off" else "reach=" + args.setting
            route = f"{FIXTURE}-center-{distance:g}"
            metadata = dict(client="vape", configuration=setting, network=plan["network"],
                input_source="human", script_family="", fixture_input="scripted-positioning", attack_input="human-manual",
                behavior="none" if label == "legit" else "reach", label=label, route=route,
                purpose="development", fixture=FIXTURE, pair=plan["id"],
                damage_and_velocity_suppressed=True, target_client=plan["target_client"])
            started = bridge.action("reach_start", label=label, client="vape", setting=setting,
                scenario=f"reach-{plan['id']}-{index}")
            save_json(attempt / f"{index}-declaration.json", dict(metadata=metadata, started=started))
            print(f"{index+1}/{len(plan['distances'])}: center {distance:g} blocks; click for {plan['seconds']} seconds.", flush=True)
            wait(bridge, plan["seconds"])
            finished = bridge.action("reach_stop")
            # Release heartbeat pressure while replaying only after this phase has safely stopped.
            manifest, meta = raw_manifest(Path(cfg["datasets"]) / "raw", started["trial"], bridge)
            quality = raw.inspect_trial(manifest.parent, meta)
            if not quality["ok"]: raise RuntimeError("Raw audit failed: " + str(quality))
            values = metrics(raw.records(manifest.parent), meta, started["target_uuid"], distance, plan["distances"][0])
            values.update(trial=started["trial"], manifest=str(manifest), manifest_sha256=sha256(manifest),
                chunks={p.name: sha256(p) for p in sorted(manifest.parent.glob("events-*.acbin.gz"))},
                metadata=metadata, fixture_end=finished, audit=quality)
            output["phases"].append(values)
            save_json(pair_dir / output_file, output)
            if not values["usable"]: raise RuntimeError("Trial excluded: " + ", ".join(values["problems"]))
            print(f"Saved {started['trial']}: {values['swings']} swings, {values['attacks']} attack requests.", flush=True)
            bridge.action("reach_heartbeat")
    except BaseException as error:
        output["faults"].append(str(error) or type(error).__name__)
        raise
    finally:
        if acquired:
            try: bridge.action("reach_release")
            except Exception as error: output["faults"].append("Release: " + str(error))
        # Replay after releasing the real-time fixture; do not hold a lease during disk/CPU work.
        for index, phase in enumerate(output["phases"]):
            if not phase["usable"]: continue
            try:
                phase["native"] = native_replay(Path(phase["manifest"]), engine, installed_config,
                    attempt / f"{index}-native.jsonl")
                with connect(database) as db:
                    phase["registry_sample"] = admit(db, Path(phase["manifest"]), phase["metadata"])
            except Exception as error:
                phase["usable"] = False; phase["problems"].append("analysis_or_admission: " + str(error))
                output["faults"].append(str(error))
        output["complete"] = complete_capture(output, plan["distances"])
        save_json(attempt / "result.json", output)
        save_json(pair_dir / output_file, output)
        report = comparison(pair_dir, plan, "on.json" if args.condition == "off" else output_file)
        print("Comparison:", report, flush=True)
    if not output["complete"]: raise RuntimeError("Run incomplete; inspect the saved JSON faults.")
    print("OFF recording complete. Use the printed pair folder for the matching ON run." if args.condition == "off" else "Reach pair complete. Raw evidence and comparison saved.")


def repair_off(args):
    root = Path(config()["datasets"]) / "reach"
    pair_dir = root / args.pair
    plan_path, result_path = pair_dir / "plan.json", pair_dir / "off.json"
    if not plan_path.is_file() or not result_path.is_file():
        raise RuntimeError("Pair folder has no Reach OFF control.")
    plan, output = read_live_json(plan_path), read_live_json(result_path)
    if output.get("condition") != "off" or not complete_capture(output, plan.get("distances", ())):
        raise RuntimeError("OFF capture is not complete and auditable; do not repair it.")
    # A status repair must recheck original bytes, not trust an earlier 'usable' flag.
    repo = Path(config()["server"]).resolve().parent / "source/anticheat"
    sys.path.insert(0, str(repo / "analytics"))
    from datasets import decoder
    raw = decoder()
    for phase in output["phases"]:
        manifest = Path(phase["manifest"])
        if sha256(manifest) != phase["manifest_sha256"] or phase["chunks"] != {
            p.name: sha256(p) for p in sorted(manifest.parent.glob("events-*.acbin.gz"))
        }:
            raise RuntimeError("Original capture changed; repair refused.")
        if not raw.inspect_trial(manifest.parent, read_live_json(manifest))["ok"]:
            raise RuntimeError("Original capture failed audit; repair refused.")
    output["complete"] = True
    save_json(result_path, output)
    attempt = Path(output.get("attempt", ""))
    if attempt.is_dir(): save_json(attempt / "result.json", output)
    report = comparison(pair_dir, plan, "on.json")
    print("OFF control finalized:", result_path, flush=True)
    print("Comparison:", report, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("condition", choices=("off", "on", "repair"))
    parser.add_argument("--seconds", type=int, default=45)
    parser.add_argument("--pair", help="Existing pair folder name for ON recording")
    parser.add_argument("--distances", type=parse_distances, default=DISTANCES,
        help="Comma-separated player-center distances for a new OFF control")
    parser.add_argument("--setting", type=reach_setting, default="3.5",
        help="Fixed enabled Reach value for an ON recording")
    args = parser.parse_args()
    if not 45 <= args.seconds <= 180: parser.error("Use 45..180 seconds per distance.")
    if args.pair and (Path(args.pair).name != args.pair or args.pair in (".", "..")):
        parser.error("Pair must be a folder name under datasets/reach.")
    if args.condition == "on" and args.distances != DISTANCES:
        parser.error("Distances belong to the OFF control; ON uses the paired control's exact grid.")
    try:
        if args.condition == "repair":
            if not args.pair:
                parser.error("Repair requires --pair PAIR-FOLDER.")
            repair_off(args)
        else:
            collect(args)
    except (Exception, KeyboardInterrupt) as error:
        print("STOPPED:", str(error) or "Interrupted; incomplete run excluded.", flush=True)
        sys.exit(1)
