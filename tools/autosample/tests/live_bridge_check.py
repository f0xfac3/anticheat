"""Run by the developer against one connected client. No client input is injected.

This deliberately labels the trial UNKNOWN and stores its artifacts under validation.
It is an integration check, not a legitimate or cheating gameplay sample.
"""
import json
from pathlib import Path
import sys
import time
import urllib.request
import urllib.error
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runner import Bridge, config, collector_module, manifest_for
from core import save_json

cfg=config(); b=Bridge(cfg); state=b.state()
assert len(state["players"])==1,"Connect a single client first"
name=state["players"][0]["name"]
try:
    urllib.request.urlopen(cfg["bridge_url"]+"/state")
    raise AssertionError("Unauthenticated state request accepted")
except urllib.error.HTTPError as e:
    assert e.code==403
result={"unauthenticated_request": "rejected", "player":name}
b.action("acquire",player=name)
other=Bridge(cfg)
try:
    other.action("acquire",player=name)
    raise AssertionError("Second controller accepted")
except RuntimeError:
    result["second_controller"]="rejected"
b.action("prepare")
for _ in range(15):
    b.beat();time.sleep(.2)
state=b.state();assert state["players"][0]["world"]=="ac_auto_samples"
assert state["players"][0]["mode"]=="SURVIVAL"
trial=b.action("start",label="unknown",module="none",client="integration-test",setting="unknown",scenario="bridge-validation")["trial"]
print("Recording UNKNOWN integration trial:",trial,flush=True)
for _ in range(20):
    b.beat();time.sleep(.5)
b.action("stop")
manifest,meta=manifest_for(cfg,trial)
result["raw_audit"]=collector_module(cfg).inspect_trial(manifest.parent,meta)
assert result["raw_audit"]["ok"],result
result["trial"]=trial; result["raw_manifest"]=str(manifest)
print("Checking six-second watchdog without heartbeats",flush=True)
time.sleep(7)
assert b.state()["owner"]==""
result["watchdog"]="lease released after heartbeat loss"
save_json(Path(cfg["datasets"])/"automation/validation/live-bridge.json",result)
print(json.dumps(result,indent=2))
