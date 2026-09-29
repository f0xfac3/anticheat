"""Record real client traffic while driving real keyboard/mouse input on a local lab."""
from __future__ import annotations
import argparse
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from core import (VERSION, angle_error, check_state, jump_at, phase_at, plan, read_live_json,
                  load_complete_trials, save_json, sha256, steering, trial_quality)

HOME = Path(__file__).resolve().parent
PROFILES = HOME / "profiles.json"
ROLES = ("legit", "hacking", "control")


def config():
    return json.loads((HOME / "config.json").read_text(encoding="utf-8-sig"))


def collector_module(cfg):
    spec = importlib.util.spec_from_file_location("dataset_tool", Path(cfg["collector_tools"]) / "dataset_tool.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def load_profiles():
    return json.loads(PROFILES.read_text(encoding="utf-8")) if PROFILES.exists() else {}


def token(value):
    if not re.fullmatch(r"[A-Za-z0-9_.+=,:-]{1,80}", value):
        raise ValueError("Use letters, digits, and _.+=,:- only (80 characters maximum).")
    return value


class Bridge:
    def __init__(self, cfg):
        self.url = cfg["bridge_url"]
        parsed = urllib.parse.urlparse(self.url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
            raise ValueError("This controller is restricted to the local lab bridge.")
        self.keyfile = Path(cfg["server"]) / "plugins/AutoSampleLab/token.txt"
        self.owner = uuid.uuid4().hex
        self.boot = None
        self.last_heartbeat = 0

    def request(self, path, data=None):
        key = self.keyfile.read_text(encoding="utf-8").strip()
        encoded = urllib.parse.urlencode(data).encode() if data is not None else None
        req = urllib.request.Request(self.url + path, data=encoded, headers={"X-Lab-Token": key})
        try:
            timeout = 20 if data is not None and data.get("op") == "prepare" else 1.5
            with urllib.request.urlopen(req, timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            raise RuntimeError(e.read().decode("utf-8", errors="replace")) from e

    def state(self):
        return self.request("/state")

    def action(self, op, **values):
        return self.request("/action", dict(op=op, owner=self.owner, **values))

    def beat(self):
        if time.monotonic() - self.last_heartbeat > .5:
            self.action("heartbeat"); self.last_heartbeat = time.monotonic()


def port_open(port):
    with socket.socket() as s:
        s.settimeout(.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def ensure_server(cfg, bridge):
    try:
        state = bridge.state()
        if state.get("schema") == 1 and abs(time.time()*1000-state["epoch_ms"]) < 3000:
            return
    except (OSError, RuntimeError):
        pass
    if port_open(cfg["port"]):
        raise RuntimeError("A server is running without a healthy AutoSampleLab bridge. Stop it normally, then run this launcher again.")
    if not (Path(cfg["server"]) / "plugins/AutoSampleLab.jar").exists():
        raise RuntimeError("Run setup.cmd first to build/install the observer plugin.")
    logs = Path(cfg["datasets"]) / "automation/server-logs"; logs.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    with (logs / (stamp + ".log")).open("wb") as out:
        process = subprocess.Popen([cfg["java"], "-Xms1G", "-Xmx2G", "-jar", "spigot-1.8.8.jar", "nogui"],
            cwd=cfg["server"], stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW)
    print("Starting the collection server. Log:", logs / (stamp + ".log"), flush=True)
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError("Server exited. Read the server log above.")
        try:
            if bridge.state().get("schema") == 1:
                return
        except (OSError, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError("Server did not become ready in two minutes. Inspect its log.")


def wait_online(bridge, player, timeout=180, controller=None):
    print(f"Waiting for {player} to join localhost:25565. Leave the other instance disconnected.", flush=True)
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if controller is not None:
            controller.verify(False)  # Keep F8 and process identity checks active while connecting.
        state = bridge.state()
        if len(state.get("players", [])) == 1 and state["players"][0]["name"] == player:
            return state
        time.sleep(.5)
    raise RuntimeError("The selected player did not connect alone within three minutes.")


def connected(bridge, player):
    state = bridge.state()
    players = state.get("players", [])
    return len(players) == 1 and players[0].get("name") == player


def await_connection(bridge, player, controller, seconds):
    """Wait briefly after a GUI action without treating an old session as success."""
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        controller.verify()
        if connected(bridge, player):
            return True
        time.sleep(.1)
    return False


def automatic_connect_attempt(bridge, profile, controller):
    """Attempt the complete stock-menu reconnect path once."""
    points = profile["connection"]
    controller.focus()
    # From a disconnect screen this opens Multiplayer. On a prior missed attempt
    # it lands harmlessly in the server-list area, after which the saved row and
    # Join Server button are selected again.
    controller.click(points["back"]); time.sleep(.7)
    controller.click(points["server"]); time.sleep(.5)
    controller.join_server()
    if await_connection(bridge, profile["player"], controller, 4):
        return True
    controller.join_server()
    if await_connection(bridge, profile["player"], controller, 3):
        return True
    controller.tap("enter")
    return await_connection(bridge, profile["player"], controller, 3)


def countdown(seconds=8):
    print(f"Switch to the selected Minecraft window, close menus, and leave it focused. Starting in {seconds} seconds.", flush=True)
    for i in range(seconds, 0, -1):
        print(i, end=" ", flush=True); time.sleep(1)
    print()


def register(role, cfg, bridge):
    from windows_input import foreground_profile
    profiles = load_profiles()
    names = [p["name"] for p in bridge.state().get("players", [])]
    default = names[0] if len(names) == 1 else "elleliska"
    player = token(input(f"Exact Minecraft username [{default}]: ").strip() or default)
    client = "vanilla" if role == "legit" else token(input("Hacked client name [vape]: ").strip() or "vape")
    speed = 1.0
    if role == "hacking":
        speed = float(input("Timer multiplier currently enabled [1.07]: ").strip() or "1.07")
        if not 1 < speed <= 2:
            raise ValueError("This Timer experiment accepts a multiplier above 1 and at most 2.")
    print("Use W=forward, LEFT CTRL=sprint, SPACE=jump, normal mouse look; disable smooth camera.")
    print("Use windowed mode. Keep the desktop awake and unlocked. F8 stops and releases keys.")
    declaration = ("Vanilla client with no gameplay modifications." if role == "legit" else
                   (f"Only Timer enabled at {speed:g}; every other gameplay module disabled." if role == "hacking" else
                    "Same hacked client with Timer and every other gameplay module disabled."))
    print("Declared condition:", declaration)
    if input("After checking the client settings, type CONFIRMED: ").strip() != "CONFIRMED":
        raise RuntimeError("Client condition was not confirmed; nothing recorded.")
    wait_online(bridge, player)
    countdown()
    identity = foreground_profile()
    profile = dict(role=role, player=player, client=client, multiplier=speed, identity=identity,
                   declaration=declaration, declared_epoch_ms=int(time.time()*1000), verification="operator_declared",
                   condition_notice="Window identity and movement are observed; module state is not independently read.")
    profiles[role] = profile; save_json(PROFILES, profiles)
    print(f"Registered {role}: {identity['title']} / PID {identity['pid']}.", flush=True)
    return profile


def wait_disconnected(bridge, timeout=15, controller=None):
    """Require fresh, stable empty snapshots before reusing a shared username."""
    deadline = time.monotonic() + timeout
    empty_since = None
    first_tick = None
    while time.monotonic() < deadline:
        if controller is not None:
            controller.verify(False)
        state = bridge.state()
        fresh = abs(time.time()*1000-state.get("epoch_ms", 0)) < 2500
        empty = fresh and not state.get("players") and not state.get("owner") and not state.get("recording")
        if empty:
            if empty_since is None:
                empty_since = time.monotonic()
                first_tick = state.get("tick")
            elif state.get("tick") != first_tick and time.monotonic()-empty_since >= .5:
                return
        else:
            empty_since = None
        time.sleep(.1)
    raise RuntimeError("Previous client has not fully disconnected or its controller lease is still active. "
                       "Leave both clients disconnected before starting paired collection.")


def connect(bridge, profile, automatic=False):
    from windows_input import Controller
    state = bridge.state()
    if not automatic and state.get("players"):
        if len(state["players"]) != 1 or state["players"][0]["name"] != profile["player"]:
            raise RuntimeError("Another player is online; disconnect that client before starting.")
        return
    points = profile.get("connection")
    if automatic:
        if not points:
            raise RuntimeError("Paired operation requires a reconnect profile for both clients. Run configure_clients.cmd.")
        c = Controller(profile["identity"])
        # Both clients use the same name. The old client's last cached snapshot
        # must never count as proof that the next client is already connected.
        wait_disconnected(bridge, controller=c)
        try:
            for attempt in range(1, 6):
                if automatic_connect_attempt(bridge, profile, c):
                    return
                print(f"Reconnect attempt {attempt}/5 did not join; retrying.", flush=True)
            raise RuntimeError("The selected client did not reconnect after five complete menu attempts.")
        finally:
            c.release()
    else:
        wait_online(bridge, profile["player"])


def calibrate_connection(role, cfg, bridge):
    from windows_input import standard_reconnect_points
    profiles = load_profiles(); profile = profiles.get(role)
    if profile is None:
        profile = register(role, cfg, bridge); profiles = load_profiles()
    wait_online(bridge, profile["player"])
    profile["connection"] = standard_reconnect_points(profile["identity"])
    profile["connection_method"] = "stock-minecraft-1.8.9-gui"
    profiles[role] = profile; save_json(PROFILES, profiles)
    bridge.action("acquire", player=profile["player"])
    bridge.action("disconnect")
    print("Testing automatic reconnect using the standard Minecraft 1.8.9 menu.", flush=True)
    connect(bridge, profile, automatic=True)
    bridge.action("acquire", player=profile["player"]); bridge.action("disconnect")
    print("Automatic reconnect verified. Leave this instance at its disconnect screen.")


def settled_state(bridge, profile, recording=False):
    bridge.beat(); state = bridge.state()
    p = check_state(state, profile["player"], bridge.owner, bridge.boot, recording)
    return state, p


def wait_focused(bridge, profile, c, seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        c.verify(); settled_state(bridge, profile); time.sleep(.1)


def calibrate_mouse(bridge, profile, c):
    _, before = settled_state(bridge, profile)
    c.mouse(60); wait_focused(bridge, profile, c, .5)
    _, after = settled_state(bridge, profile)
    change = angle_error(after["yaw"], before["yaw"])
    if not 1 <= abs(change) <= 100:
        raise RuntimeError("Mouse challenge failed: selected window is not driving this player, a menu is open, or sensitivity is unsupported.")
    # Observe reverse input too; this binds the chosen process to server movement.
    c.mouse(-60); wait_focused(bridge, profile, c, .5)
    _, restored = settled_state(bridge, profile)
    if abs(angle_error(restored["yaw"], before["yaw"])) > 5:
        raise RuntimeError("Mouse challenge did not reverse reliably. Disable smooth camera and close menus.")
    return change/60


def manifest_for(cfg, trial, timeout=12):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        candidates = list((Path(cfg["datasets"]) / "raw").glob(f"run-*/{trial}/manifest.json"))
        if len(candidates) == 1:
            path = candidates[0]; meta = read_live_json(path)
            if meta.get("status") != "open":
                return path, meta
        time.sleep(.2)
    raise RuntimeError("Recorder did not finalize the trial manifest.")


def run_trial(cfg, bridge, profile, c, session_dir, seed, index):
    if shutil.disk_usage(cfg["datasets"]).free < 2*1024**3:
        raise RuntimeError("Less than 2 GiB disk space remains; collection stopped.")
    p = plan(seed, cfg["trial_seconds"])
    print(f"Trial {index+1}: {profile['role']}, seed {seed}, {cfg['trial_seconds']} seconds", flush=True)
    c.release(); bridge.action("prepare"); time.sleep(.3)
    # Preparation is outside the recording; resets never create training windows.
    wait_focused(bridge, profile, c, cfg["warmup_seconds"])
    degrees = calibrate_mouse(bridge, profile, c)
    label = "cheat" if profile["role"] == "hacking" else "legit"
    started = bridge.action("start", label=label, module="timer" if label=="cheat" else "none",
        client=profile["client"] + "-auto", setting=f"speed={profile['multiplier']:g}", scenario=f"auto-v1-seed-{seed}")
    trial = started["trial"]; directory = session_dir / trial; directory.mkdir()
    save_json(directory / "plan.json", p)
    meta = dict(status="running", version=VERSION, trial=trial, profile=profile, seed=seed,
                plan_sha256=sha256(directory / "plan.json"), generator_sha256=sha256(HOME / "core.py"),
                scope="automated local flat-terrain Timer experiment", label_source="operator_declared",
                mouse_degrees_per_count=degrees, started_epoch_ms=int(time.time()*1000), server_boot=bridge.boot)
    meta["observer_sha256"] = sha256(Path(cfg["server"]) / "plugins/anticheat.jar")
    meta["lab_bridge_sha256"] = sha256(Path(cfg["server"]) / "plugins/AutoSampleLab.jar")
    save_json(directory / "automation.json", meta)
    completed = False; error = None
    try:
        time.sleep(.25)
        start = time.monotonic(); waypoint = 0; next_screen = 0; distance = 0; old = None
        last_motion = start; max_tick_ms = 0; counts = dict(walk=0,sprint=0,sprint_jump=0,idle=0)
        sprint_expected = sprint_observed = jump_observed = 0
        with (directory / "controller.jsonl").open("w", encoding="utf-8", buffering=1) as log:
            while time.monotonic() - start < p["seconds"]:
                frame = time.monotonic(); elapsed = frame-start
                c.verify(); state, observed = settled_state(bridge, profile, recording=True)
                if old:
                    delta = math.hypot(observed["x"]-old["x"], observed["z"]-old["z"])
                    distance += delta
                    if delta > .02:
                        last_motion = frame
                old = observed
                mode = phase_at(p, elapsed); counts[mode] += 1
                if mode in ("sprint", "sprint_jump"):
                    sprint_expected += 1
                    sprint_observed += bool(observed["sprinting"])
                if mode == "sprint_jump" and observed["y"] > 65.15:
                    jump_observed += 1
                yaw_error, waypoint = steering(p, observed, waypoint)
                # Closed-loop heading uses observed positions, never writes position/velocity.
                dx = round(max(-5, min(5, yaw_error)) / degrees)
                dy = round(max(-2, min(2, -observed["pitch"])) / abs(degrees))
                if dx or dy:
                    c.mouse(dx, dy)
                desired = set()
                if mode != "idle":
                    desired.add("forward")
                    if mode in ("sprint", "sprint_jump"):
                        desired.add("sprint")
                    if mode == "sprint_jump" and jump_at(p, elapsed):
                        desired.add("jump")
                    if frame-last_motion > 8:
                        raise RuntimeError("No movement for eight seconds while moving: menu, collision, or wrong key bindings.")
                else:
                    last_motion = frame
                c.keys(desired)
                max_tick_ms = max(max_tick_ms, state["tick_ms"])
                log.write(json.dumps(dict(elapsed=elapsed, intended=mode, keys=sorted(desired), mouse=[dx,dy],
                                          server=state, waypoint=waypoint)) + "\n")
                if cfg.get("screenshot_seconds",0)>0 and elapsed >= next_screen:
                    if shutil.disk_usage(cfg["datasets"]).free < 2*1024**3:
                        raise RuntimeError("Less than 2 GiB disk space remains; trial excluded.")
                    c.screenshot(directory / f"screen-{int(elapsed):05d}.png")
                    next_screen = elapsed + cfg["screenshot_seconds"]
                time.sleep(max(0, .05-(time.monotonic()-frame)))
        if distance < 30:
            raise RuntimeError("Trial did not cover enough distance.")
        if sprint_expected > 40 and sprint_observed < .1 * sprint_expected:
            raise RuntimeError("Sprint input was not observed. Check the LEFT CTRL sprint binding.")
        if counts["sprint_jump"] > 40 and jump_observed < 5:
            raise RuntimeError("Jump input was not observed. Check the SPACE jump binding.")
        c.release(); completed = True
        meta["movement"] = dict(distance_blocks=distance, controller_samples_by_mode=counts, max_tick_ms=max_tick_ms,
                                sprint_samples=sprint_observed, jump_air_samples=jump_observed)
    except BaseException as e:
        error = e; meta["error"] = str(e) or type(e).__name__
    finally:
        c.release()
        try:
            bridge.action("stop")
            manifest_path, raw = manifest_for(cfg, trial)
            collector = collector_module(cfg)
            meta["raw_manifest"] = str(manifest_path)
            meta["raw_manifest_sha256"] = sha256(manifest_path)
            meta["quality"] = trial_quality(collector, manifest_path.parent, raw)
            meta["status"] = "complete" if completed and raw["status"] == "complete" and meta["quality"]["ok"] else "excluded"
        except Exception as finish_error:
            meta["status"] = "excluded"; meta["finalization_error"] = str(finish_error)
            if error is None:
                error = finish_error
        meta["finished_epoch_ms"] = int(time.time()*1000)
        save_json(directory / "automation.json", meta)
    if error:
        raise error
    if meta["status"] != "complete":
        raise RuntimeError("Trial failed raw-data audit: " + str(meta.get("quality")))
    print(f"Saved and audited {trial}", flush=True)
    if cfg["trial_seconds"]==180:
        from analyze import ingest_trial
        ingest_trial(cfg,directory/"automation.json")
    return meta


def run_profile(cfg, role, seeds=None, automatic=False, refresh=False):
    from windows_input import Controller
    bridge = Bridge(cfg); ensure_server(cfg, bridge)
    profiles = load_profiles(); profile = profiles.get(role)
    newly_registered = False
    if profile is None or refresh:
        profile = register(role, cfg, bridge); newly_registered = True
    else:
        Controller(profile["identity"]).verify(False)
        print("Declared condition:", profile["declaration"])
        if not automatic:
            if input("Confirm this condition is still set (type CONFIRMED): ").strip() != "CONFIRMED":
                raise RuntimeError("Condition not confirmed.")
            profile = dict(profile, declared_epoch_ms=int(time.time()*1000))
    connect(bridge, profile, automatic)
    if not automatic and not newly_registered:
        countdown()
    c = Controller(profile["identity"])
    if automatic:
        c.focus()
    c.verify()
    initial = bridge.state(); check_state(initial, profile["player"])
    bridge.boot = bridge.action("acquire", player=profile["player"])["boot"]
    session = time.strftime("%Y%m%d-%H%M%S") + "-" + role + "-" + uuid.uuid4().hex[:8]
    directory = Path(cfg["datasets"]) / "automation/sessions" / session; directory.mkdir(parents=True)
    session_info = dict(status="running", session=session, profile=profile, config=cfg, version=VERSION,
                        started_epoch_ms=int(time.time()*1000), trials=[])
    save_json(directory / "session.json", session_info)
    try:
        sequence = seeds if seeds is not None else range(cfg["seed_base"], cfg["seed_base"]+cfg["trials"])
        for i, seed in enumerate(sequence):
            m = run_trial(cfg, bridge, profile, c, directory, seed, i)
            session_info["trials"].append(m["trial"]); save_json(directory / "session.json", session_info)
        session_info["status"] = "complete"
    except BaseException as e:
        session_info["status"] = "interrupted"; session_info["error"] = str(e) or type(e).__name__
        raise
    finally:
        c.release()
        try:
            bridge.action("disconnect" if cfg["disconnect_when_done"] else "release")
        except Exception as e:
            session_info["release_error"] = str(e)
        session_info["finished_epoch_ms"] = int(time.time()*1000); save_json(directory / "session.json", session_info)
        print("Session log:", directory, flush=True)
    return directory


def paired(cfg):
    from windows_input import Controller
    profiles = load_profiles()
    for role in ("legit", "hacking"):
        if role not in profiles or "connection" not in profiles[role]:
            raise RuntimeError(f"Missing reconnect profile for {role}. Run configure_clients.cmd and choose {role}.")
        actual = Controller(profiles[role]["identity"]).verify(False)
        for point in profiles[role]["connection"].values():
            if (actual["width"], actual["height"]) != (point["width"], point["height"]):
                raise RuntimeError(f"{role} window size differs from its reconnect profile. "
                                   f"Restore {point['width']}x{point['height']} client size or re-register {role}.")
    if profiles["legit"]["identity"]["pid"] == profiles["hacking"]["identity"]["pid"]:
        raise RuntimeError("Paired operation requires two different Minecraft processes.")
    print("Both instances must be at their disconnect screens, with unchanged settings and window sizes.")
    print("Vanilla condition:", profiles["legit"]["declaration"])
    print("Timer condition:", profiles["hacking"]["declaration"])
    if input("Verify both client conditions, then type CONFIRMED: ").strip() != "CONFIRMED":
        raise RuntimeError("Client conditions not confirmed.")
    for role in ("legit", "hacking"):
        profiles[role]["declared_epoch_ms"] = int(time.time()*1000)
    save_json(PROFILES, profiles)
    bridge=Bridge(cfg); ensure_server(cfg, bridge)
    if bridge.state().get("players"):
        raise RuntimeError("Disconnect both instances before starting paired operation.")
    completed = completed_conditions(cfg, profiles)
    skipped = 0
    for i in range(cfg["trials"]):
        # Counterbalance which condition runs first, keeping each seed in one analysis partition.
        order = ("legit", "hacking") if i % 2 == 0 else ("hacking", "legit")
        for role in order:
            seed = cfg["seed_base"] + i
            if (role, seed) in completed:
                print(f"Pair {i+1}/{cfg['trials']}: skipping already audited {role} seed {seed}", flush=True)
                skipped += 1
                continue
            print(f"Pair {i+1}/{cfg['trials']}: connecting {role} client", flush=True)
            run_profile(cfg, role, seeds=[seed], automatic=True)
    print(f"Paired collection complete: {cfg['trials']} pairs; {skipped} prior trials reused.", flush=True)


def completed_conditions(cfg, profiles):
    """Reuse only audited trials matching this duration and declared condition."""
    complete = set()
    for path, trial in load_complete_trials(cfg["datasets"]):
        try:
            role, profile = trial["profile"]["role"], trial["profile"]
            current = profiles.get(role)
            plan_data = json.loads((path.parent / "plan.json").read_text(encoding="utf-8"))
            if (current is not None and plan_data.get("seconds") == cfg["trial_seconds"] and
                    profile.get("client") == current.get("client") and
                    profile.get("multiplier") == current.get("multiplier")):
                complete.add((role, trial["seed"]))
        except (KeyError, OSError, ValueError):
            continue
    return complete


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=["legit", "hacking", "control", "paired", "configure", "doctor", "analyze", "plan", "stop"])
    p.add_argument("--trials", type=int); p.add_argument("--seconds", type=int); p.add_argument("--seed-base", type=int)
    p.add_argument("--register", action="store_true")
    p.add_argument("--collection-date", help="Analysis only: local trial start date, YYYY-MM-DD")
    p.add_argument("--report-seconds", type=int, help="Analysis only: include trials with this planned duration")
    args = p.parse_args(); cfg=config()
    if args.trials is not None: cfg["trials"]=args.trials
    if args.seconds is not None: cfg["trial_seconds"]=args.seconds
    if args.seed_base is not None: cfg["seed_base"]=args.seed_base
    if args.collection_date or args.report_seconds is not None:
        if args.command != "analyze": p.error("Report filters apply only to analyze.")
        if args.collection_date:
            import datetime
            try: datetime.date.fromisoformat(args.collection_date)
            except ValueError: p.error("Use --collection-date YYYY-MM-DD.")
            cfg["collection_date"]=args.collection_date
        if args.report_seconds is not None:
            if not 60 <= args.report_seconds <= 600: p.error("Use --report-seconds 60..600.")
            cfg["report_seconds"]=args.report_seconds
    if not 1 <= cfg["trials"] <= 1000 or not 60 <= cfg["trial_seconds"] <= 600:
        p.error("Use 1..1000 trials and 60..600 seconds per trial.")
    if args.command == "plan":
        print(json.dumps(plan(cfg["seed_base"],cfg["trial_seconds"]),indent=2)); return
    if args.command == "analyze":
        from analyze import analyze
        analyze(cfg); return
    if args.command == "stop":
        Bridge(cfg).action("shutdown")
        print("Server shutdown requested; saving worlds and recorder state.");return
    if args.command == "doctor":
        b=Bridge(cfg); ensure_server(cfg,b)
        print(json.dumps(b.state(),indent=2)); print("Dataset root:",cfg["datasets"]); return
    if args.command == "configure":
        b=Bridge(cfg); ensure_server(cfg,b)
        role=input("Profile to register: legit / hacking / control: ").strip()
        if role not in ROLES: raise ValueError("Unknown profile")
        register(role,cfg,b)
        calibrate_connection(role,cfg,b)
        return
    # One controller per computer. msvcrt releases this lock even after a process crash.
    import msvcrt
    with (HOME / "controller.lock").open("a+b") as lock:
        lock.seek(0)
        try: msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
        except OSError as e: raise RuntimeError("Another sample controller is already running.") from e
        try:
            if args.command == "paired": paired(cfg)
            else: run_profile(cfg,args.command,refresh=args.register)
        finally:
            lock.seek(0); msvcrt.locking(lock.fileno(),msvcrt.LK_UNLCK,1)
    from analyze import analyze
    analyze(cfg)


if __name__ == "__main__":
    try: main()
    except KeyboardInterrupt:
        print("\nStopped. Keys released; interrupted trial excluded. Completed trials remain saved."); sys.exit(130)
    except Exception as e:
        print("\nSTOPPED:",e, file=sys.stderr); sys.exit(1)
