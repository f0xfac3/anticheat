"""Audit raw lab captures, create review sheets, and export non-overlapping feature windows.

Only standard Python libraries are required. No detector verdict is used as a label.
"""
import argparse
import csv
import gzip
import hashlib
import hmac
import json
import math
from pathlib import Path
import secrets
import statistics
import struct
import sys

HEADER = struct.Struct("<IHHQQQQQ")
FEATURES = ["movement_pps", "dt_p50_ms", "dt_p95_ms", "dt_std_ms", "same_batch_fraction",
            "position_fraction", "hstep_p95", "abs_dy_p95", "yaw_change_p95", "pitch_change_p95",
            "air_ground_fraction", "gravity_error_p95", "item_input_p95", "attack_pps",
            "attack_distance_p95", "impulse_count", "ack_count"]


class Cursor:
    def __init__(self, data): self.data, self.pos = data, 0
    def take(self, fmt):
        fmt = "<" + fmt
        values = struct.unpack_from(fmt, self.data, self.pos)
        self.pos += struct.calcsize(fmt)
        return values[0] if len(values) == 1 else values
    def text(self):
        length = self.take("H")
        if length > 1024 or self.pos + length > len(self.data): raise ValueError("invalid text")
        value = self.data[self.pos:self.pos + length].decode("ascii"); self.pos += length
        return value
    def flag(self):
        value = self.take("B")
        if value not in (0, 1): raise ValueError("invalid boolean")
        return bool(value)
    def vec(self): return self.take("ddd")


def decode(raw):
    magic, schema, kind, session, ordinal, ns, epoch, tick = HEADER.unpack_from(raw)
    if magic != 0x43415846 or schema != 3: raise ValueError("expected FXAC schema 3")
    c = Cursor(raw[48:])
    out = dict(kind=kind, session=session, ordinal=ordinal, ns=ns, epoch_ms=epoch, server_tick=tick)
    if kind == 1: out.update(player=c.text(), protocol=c.take("I"), model=c.take("I"))
    elif kind == 3: out["reason"] = c.text()
    elif kind in (5, 6):
        if kind == 5: c.take("QQQBB")
        c.take("iii")
        for _ in range(5): c.text()
        c.take("d"); c.flag()
    elif kind in (7, 8):
        if kind == 7: out["packet"], out["batch"], out["sampled_ns"] = c.take("QQQ")
        else: out["sampled_ns"] = c.take("Q")
        world, target, target_kind, reason = [c.text() for _ in range(4)]
        target_id, eye, low, high, ping, available = c.take("i"), c.vec(), c.vec(), c.vec(), c.take("i"), c.flag()
        yaw, pitch = c.take("dd"); rotation, sprint, target_player = c.flag(), c.flag(), c.flag()
        out.update(world=world, target=target, target_kind=target_kind, reason=reason, eye=eye,
                   low=low, high=high, ping=ping, available=available, yaw=yaw, pitch=pitch, target_player=target_player)
    elif kind == 9: out["packet"], out["batch"] = c.take("QQ")
    elif kind == 10:
        for _ in range(3): c.text()
        c.vec(); c.vec()
    elif kind == 11:
        packet, batch, sampled = c.take("QQQ"); position = c.vec(); yaw, pitch = c.take("dd")
        has_pos, has_look, ground = c.flag(), c.flag(), c.flag(); world, reason = c.text(), c.text()
        available, source, destination, clear, flat, item, sprint = [c.flag() for _ in range(7)]
        speed, friction, jump = c.take("ddd")
        out.update(packet=packet, batch=batch, sampled_ns=sampled, position=position, yaw=yaw, pitch=pitch,
                   has_pos=has_pos, has_look=has_look, ground=ground, world=world, reason=reason,
                   available=available, source=source, destination=destination, clear=clear,
                   flat=flat, item=item, sprint=sprint, speed=speed, friction=friction, jump=jump)
    elif kind == 12: out.update(token=c.take("Q"), velocity=c.vec(), additive=c.flag())
    elif kind == 13: out["token"] = c.take("Q")
    elif kind not in (2, 4, 14): raise ValueError("unknown observation kind")
    if c.pos != len(c.data): raise ValueError("trailing event bytes")
    return out


def records(directory):
    for path in sorted(directory.glob("events-*.acbin.gz")):
        if path.is_symlink(): raise ValueError("linked chunk not accepted")
        with gzip.open(path, "rb") as stream:
            while True:
                prefix = stream.read(4)
                if not prefix: break
                if len(prefix) != 4: raise ValueError("truncated record prefix")
                size, = struct.unpack("<I", prefix)
                if not 48 <= size <= 8192: raise ValueError("invalid record size")
                raw = stream.read(size)
                if len(raw) != size: raise ValueError("truncated record")
                yield decode(raw)


def manifests(root):
    for path in sorted(root.glob("run-*/trial-*/manifest.json")):
        if path.is_symlink(): continue
        try: yield path.parent, json.loads(path.read_text(encoding="utf-8"))
        except (ValueError, OSError) as e: print(f"Invalid manifest {path}: {e}", file=sys.stderr)


def inspect_trial(directory, meta):
    counts, total, gaps, previous, bad_float, first = {}, 0, 0, None, 0, True
    try:
        for event in records(directory):
            total += 1; counts[event["kind"]] = counts.get(event["kind"], 0) + 1
            if total == 1 and event["kind"] != 1: raise ValueError("missing initial SessionStart")
            if total > 1 and event["kind"] == 1: raise ValueError("multiple SessionStart events")
            if "session_id" in meta and event["session"] != meta["session_id"]: raise ValueError("session differs from manifest")
            if total == 1 and "player_uuid" in meta and event["player"] != meta["player_uuid"]: raise ValueError("player differs from manifest")
            # The cached START precedes the first recorded live ordinal; that one jump is intentional.
            if previous is not None and not first and event["ordinal"] != previous + 1: gaps += 1
            first = event["kind"] == 1; previous = event["ordinal"]
            values = list(event.values())
            for v in values:
                if isinstance(v, float) and not math.isfinite(v): bad_float += 1
                if isinstance(v, tuple) and any(isinstance(x, float) and not math.isfinite(x) for x in v): bad_float += 1
        if total == 0: raise ValueError("empty trial has no SessionStart")
        if total != meta.get("records"): raise ValueError("record count differs from finalized manifest")
        if len(list(directory.glob("events-*.acbin.gz"))) != meta.get("chunks"): raise ValueError("chunk count mismatch")
        if gaps or bad_float: raise ValueError(f"ordinal gaps={gaps}, nonfinite values={bad_float}")
        return dict(ok=True, events=total, kinds=counts)
    except (OSError, EOFError, ValueError, struct.error) as e:
        return dict(ok=False, events=total, error=str(e))


def quantile(values, q):
    if not values: return None
    ordered = sorted(values); return ordered[min(len(ordered)-1, int((len(ordered)-1)*q))]


def feature_window(events, seconds):
    moves = sorted((e for e in events if e["kind"] == 11), key=lambda e: e["packet"])
    if len(moves) < 10: return None
    if any(e["kind"] in (3, 10, 14) for e in events): return None
    dt, hs, dys, yaw_d, pitch_d, gravity, item_acc = [], [], [], [], [], [], []
    batches, airborne, false_ground = 0, 0, 0
    old, old_delta, look = None, None, None
    for index, e in enumerate(moves):
        if index:
            elapsed = (e["ns"]-moves[index-1]["ns"])/1e6
            if elapsed < 0: return None
            dt.append(elapsed); batches += e["batch"] == moves[index-1]["batch"]
        if e["has_look"]:
            if look:
                yaw_d.append(abs((e["yaw"]-look[0]+180)%360-180)); pitch_d.append(abs(e["pitch"]-look[1]))
            look = (e["yaw"], e["pitch"])
        if not e["has_pos"]: old = old_delta = None; continue
        if old is not None and old["world"] == e["world"]:
            delta = tuple(a-b for a, b in zip(e["position"], old["position"]))
            hs.append(math.hypot(delta[0], delta[2])); dys.append(abs(delta[1]))
            usable = e["available"] and old["available"] and e["clear"] and old["clear"]
            air = usable and not any((e["source"], e["destination"], old["source"], old["destination"]))
            if air:
                airborne += 1; false_ground += e["ground"] and delta[1] < -.03
                if old_delta is not None: gravity.append(abs(delta[1]-(old_delta[1]-.08)*.98))
            if usable and e["flat"] and old["flat"] and e["item"] and old["item"] and old_delta is not None:
                drag=.91*e["friction"]; item_acc.append(math.hypot(delta[0]-old_delta[0]*drag,delta[2]-old_delta[2]*drag))
            old_delta = delta
        else: old_delta = None
        old = e
    attacks = [e for e in events if e["kind"] == 7]
    distances = []
    for e in attacks:
        if e["available"]:
            distances.append(math.sqrt(sum(max(lo-.13125-p, 0, p-hi-.13125)**2 for p,lo,hi in zip(e["eye"],e["low"],e["high"]))))
    return dict(zip(FEATURES, [len(moves)/seconds,quantile(dt,.5),quantile(dt,.95),statistics.pstdev(dt) if dt else None,
        batches/max(1,len(moves)-1),sum(e["has_pos"] for e in moves)/len(moves),quantile(hs,.95),quantile(dys,.95),
        quantile(yaw_d,.95),quantile(pitch_d,.95),false_ground/airborne if airborne else None,quantile(gravity,.95),
        quantile(item_acc,.95),len(attacks)/seconds,quantile(distances,.95),sum(e["kind"]==12 for e in events),sum(e["kind"]==13 for e in events)]))


def windows(directory, meta, seconds=5):
    start = meta["start_observed_ns"] + meta.get("guard_ms",5000)*1_000_000
    stop = meta["end_observed_ns"] - meta.get("guard_ms",5000)*1_000_000
    size = int(seconds*1e9)
    # Stream by delivery order, with one bucket of lookahead for delayed network timestamps.
    buckets = {}; latest = -1
    for event in records(directory):
        ns = event["ns"]
        if not start <= ns < stop: continue
        index = (ns-start)//size
        if start+(index+1)*size > stop: continue
        if index < latest-1: raise ValueError("receive clock reordered across more than one feature window")
        latest=max(latest,index);buckets.setdefault(index,[]).append(event)
        if len(buckets[index])>20000:raise ValueError("feature window exceeds 20,000-event memory bound")
        for old in sorted(k for k in buckets if k < latest-1):
            yield start+old*size,buckets.pop(old)
    for index in sorted(buckets): yield start+index*size,buckets[index]


def file_hash(path):
    digest=hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""):digest.update(block)
    return digest.hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=["audit","review-template","export"])
    parser.add_argument("root",type=Path);parser.add_argument("--out",type=Path)
    parser.add_argument("--reviews",type=Path);parser.add_argument("--seconds",type=int,default=5)
    args=parser.parse_args()
    if not 2 <= args.seconds <= 60: parser.error("--seconds must be 2..60")
    trials=list(manifests(args.root))
    if args.command=="review-template":
        if args.out is None: parser.error("--out is required")
        with args.out.open("x",newline="",encoding="utf-8") as f:
            w=csv.DictWriter(f,fieldnames=["trial_id","approved","verified_label","verification","evidence","notes"]);w.writeheader()
            for _,m in trials:w.writerow(dict(trial_id=m["trial_id"],approved="no",verified_label=m["declared_label"],verification="unreviewed",evidence="",notes=""))
        print(f"Wrote {len(trials)} review rows; none automatically approved");return
    if args.command=="audit":
        summary=dict(trials=len(trials),complete=0,usable=0,events=0,declared_seconds={},problems=[])
        for d,m in trials:
            if m.get("status")!="complete":summary["problems"].append(dict(trial=m["trial_id"],status=m.get("status")));continue
            summary["complete"]+=1;q=inspect_trial(d,m);summary["events"]+=q["events"]
            if q["ok"]:
                summary["usable"]+=1;label=m["declared_label"]+":"+m["module"]
                summary["declared_seconds"][label]=summary["declared_seconds"].get(label,0)+(m["end_observed_ns"]-m["start_observed_ns"])/1e9
            else:summary["problems"].append(dict(trial=m["trial_id"],**q))
        print(json.dumps(summary,indent=2));return
    if args.out is None or args.reviews is None:parser.error("export requires --out and --reviews")
    with args.reviews.open(newline="",encoding="utf-8-sig") as f:reviews={r["trial_id"]:r for r in csv.DictReader(f)}
    key_file=args.root/".group-key"
    if not key_file.exists():key_file.write_bytes(secrets.token_bytes(32))
    key=key_file.read_bytes()
    if len(key)!=32:raise ValueError("invalid local .group-key")
    group=lambda s:hmac.new(key,s.encode(),hashlib.sha256).hexdigest()[:24]
    count=0;skips=[];players=set()
    if args.out.exists():raise ValueError("output already exists; choose a new dataset filename")
    temporary=args.out.with_suffix(args.out.suffix+".partial")
    with temporary.open("x",encoding="utf-8") as output:
        for directory,m in trials:
            r=reviews.get(m["trial_id"],{})
            if m.get("status")!="complete" or r.get("approved")!="yes":continue
            if r.get("verified_label") not in ("legit","cheat") or r.get("verification") not in ("legit_control","enabled_confirmed","mechanism_confirmed") or not r.get("evidence","").strip():
                skips.append(dict(trial=m["trial_id"],error="approved review lacks label, verification or evidence"));continue
            if r["verified_label"]=="legit" and (m["module"]!="none" or r["verification"]!="legit_control"):
                skips.append(dict(trial=m["trial_id"],error="legit review conflicts with module/verification"));continue
            if r["verified_label"]=="cheat" and (m["module"]=="none" or r["verification"]=="legit_control"):
                skips.append(dict(trial=m["trial_id"],error="cheat review conflicts with module/verification"));continue
            q=inspect_trial(directory,m)
            if not q["ok"]:skips.append(dict(trial=m["trial_id"],**q));continue
            player=group(m["player_uuid"]);players.add(player)
            for ns,events in windows(directory,m,args.seconds):
                features=feature_window(events,args.seconds)
                if features is None:continue
                targets=sorted({group(e["target"]) for e in events if e["kind"] in (7,8) and e["target_player"]})
                row=dict(trial=m["trial_id"],run=m["boot_id"],player_group=player,opponent_groups=targets,
                    start_ns=ns,seconds=args.seconds,label=r["verified_label"],module=m["module"],client=m["client"],setting=m["setting"],
                    scenario=m["scenario"],verification=r["verification"],features=features)
                output.write(json.dumps(row,allow_nan=False)+"\n");count+=1
    temporary.rename(args.out)
    report=dict(data_sha256=file_hash(args.out),reviews_sha256=file_hash(args.reviews),feature_version=1,windows=count,players=len(players),skipped=skips,window_seconds=args.seconds,
        target="reviewed module-enabled windows; not per-packet violation truth",features=FEATURES)
    args.out.with_suffix(args.out.suffix+".report.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))


if __name__=="__main__":main()
