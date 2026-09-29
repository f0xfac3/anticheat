"""Integration validation only: abandoned UNKNOWN trial must finalize automatically."""
import json
from pathlib import Path
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runner import Bridge,config,collector_module,manifest_for
from core import save_json
cfg=config();b=Bridge(cfg);state=b.state();assert len(state["players"])==1
name=state["players"][0]["name"]
b.action("acquire",player=name)
b.action("prepare")
trial=b.action("start",label="unknown",module="none",client="integration-test",setting="unknown",scenario="watchdog-validation")["trial"]
try:
    b.action("shutdown")
    raise AssertionError("Shutdown accepted during recording")
except RuntimeError:pass
time.sleep(7)
state=b.state();assert state["owner"]=="" and not state["recording"]
path,meta=manifest_for(cfg,trial)
audit=collector_module(cfg).inspect_trial(path.parent,meta)
assert audit["ok"] and meta["status"]=="complete"
result=dict(trial=trial,raw_manifest=str(path),raw_audit=audit,watchdog="abandoned recording finalized",shutdown_during_recording="rejected")
save_json(Path(cfg["datasets"])/"automation/validation/live-watchdog.json",result)
print(json.dumps(result,indent=2))
