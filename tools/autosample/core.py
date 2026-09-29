"""Platform-independent experiment plans, checks, and packet export."""
from __future__ import annotations
import hashlib
import json
import math
import random
import os
import time
from pathlib import Path

VERSION = "auto-input-v1"
TIMING_FEATURES = ["movement_pps", "dt_p50_ms", "dt_p95_ms", "dt_std_ms", "same_batch_fraction"]


def save_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    for attempt in range(40):
        try:
            tmp.replace(path)
            break
        except PermissionError:
            if attempt == 39:raise
            time.sleep(.025)


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()


def read_live_json(path):
    """Allow a Windows writer to replace the manifest while it is being read."""
    if os.name != "nt":
        return json.loads(Path(path).read_text(encoding="utf-8"))
    import ctypes as c
    from ctypes import wintypes as w
    import msvcrt
    kernel=c.WinDLL("kernel32",use_last_error=True)
    kernel.CreateFileW.argtypes=[w.LPCWSTR,w.DWORD,w.DWORD,c.c_void_p,w.DWORD,w.DWORD,w.HANDLE]
    kernel.CreateFileW.restype=w.HANDLE
    kernel.CloseHandle.argtypes=[w.HANDLE]
    for attempt in range(20):
        handle=kernel.CreateFileW(str(path),0x80000000,7,None,3,0x80,None)
        if handle != c.c_void_p(-1).value:break
        error=c.get_last_error()
        if error not in (2,5,32) or attempt==19:raise c.WinError(error)
        time.sleep(.01)
    try: fd=msvcrt.open_osfhandle(handle,os.O_RDONLY)
    except BaseException:
        kernel.CloseHandle(handle);raise
    with os.fdopen(fd,"r",encoding="utf-8") as f:
        return json.load(f)


def plan(seed, seconds):
    """Identical seed creates identical wall-clock action intentions in every profile."""
    rng = random.Random(seed)
    phases, t = [], 0.0
    while t < seconds:
        modes = ["walk", "sprint", "sprint_jump"]
        rng.shuffle(modes)
        for mode in modes:
            end = min(seconds, t + rng.uniform(19, 31))
            phases.append(dict(start=t, end=end, mode=mode))
            t = end
            if t >= seconds:
                break
            end = min(seconds, t + rng.uniform(1.0, 3.5))
            phases.append(dict(start=t, end=end, mode="idle"))
            t = end
    # A varied loop gives smooth turns and stays inside pregenerated terrain.
    angle = rng.uniform(-math.pi, math.pi)
    points = []
    for i in range(12):
        a = angle + i * math.tau / 12
        radius = rng.uniform(35, 60)
        points.append([math.cos(a) * radius, math.sin(a) * radius])
    jumps, next_jump = [], 0.0
    while next_jump < seconds:
        jumps.append([next_jump, rng.uniform(.09, .18)])
        next_jump += rng.uniform(.5, .95)
    return dict(version=VERSION, seed=seed, seconds=seconds, phases=phases, waypoints=points, jumps=jumps)


def phase_at(p, elapsed):
    for phase in p["phases"]:
        if phase["start"] <= elapsed < phase["end"]:
            return phase["mode"]
    return "idle"


def jump_at(p, elapsed):
    return any(start <= elapsed < start + duration for start, duration in p["jumps"])


def angle_error(desired, current):
    return (desired - current + 180) % 360 - 180


def steering(p, player, waypoint):
    points = p["waypoints"]
    x, z = player["x"], player["z"]
    tx, tz = points[waypoint % len(points)]
    if math.hypot(tx-x, tz-z) < 4:
        waypoint += 1
        tx, tz = points[waypoint % len(points)]
    yaw = math.degrees(math.atan2(-(tx-x), tz-z))
    return angle_error(yaw, player["yaw"]), waypoint


def check_state(state, player_name, owner=None, boot=None, recording=False):
    if abs(time.time() * 1000 - state.get("epoch_ms", 0)) > 2500:
        raise RuntimeError("Server observation is stale.")
    if boot and state.get("boot") != boot:
        raise RuntimeError("Server restarted during the experiment.")
    players = state.get("players", [])
    if len(players) != 1 or players[0]["name"] != player_name:
        raise RuntimeError("Only the selected player may be connected.")
    if owner is not None and state.get("owner") != owner:
        raise RuntimeError("Controller lease was lost: " + state.get("fault", ""))
    p = players[0]
    if owner is not None:
        if p["world"] != "ac_auto_samples" or p["dead"] or p["mode"] != "SURVIVAL" or p["allow_flight"]:
            raise RuntimeError("Player left the survival collection arena or died.")
        if abs(p["x"]) > 88 or abs(p["z"]) > 88 or p["y"] < 64:
            raise RuntimeError("Player left the valid collection area.")
        if state["tick_ms"] > 100:
            raise RuntimeError("Server tick time exceeded 100 ms; trial excluded.")
    if recording and not state.get("recording"):
        raise RuntimeError("Server recording stopped unexpectedly.")
    return p


def trial_quality(collector, directory, meta):
    result = collector.inspect_trial(directory, meta)
    if not result["ok"]:
        return result
    reset = sum(v for k, v in result["kinds"].items() if k in (3, 10, 14))
    if reset:
        return dict(ok=False, error="Observation discontinuity, teleport, or nearby world/position correction in trial",
                    boundary_counts={str(k): result["kinds"].get(k, 0) for k in (3, 10, 14)}, raw_audit=result)
    return result


def load_complete_trials(root):
    """Only this runner's finalized, audited observations enter provisional models."""
    for path in sorted(Path(root).glob("automation/sessions/*/trial-*/automation.json")):
        a = json.loads(path.read_text(encoding="utf-8"))
        if a.get("status") != "complete" or not a.get("quality", {}).get("ok"):
            continue
        yield path, a
