"""Move explicitly UNKNOWN integration fixtures out of gameplay raw recordings."""
import json
from pathlib import Path
import shutil
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from core import save_json
from runner import config
root=Path(config()["datasets"]).resolve();raw=(root/"raw").resolve();validation=(root/"automation/validation").resolve()
validation.relative_to(root)
archived=[]
for source in raw.glob("run-*/trial-*"):
    manifest=source/"manifest.json"
    if not manifest.is_file():continue
    meta=json.loads(manifest.read_text(encoding="utf-8"))
    if meta.get("declared_label")!="unknown" or meta.get("client")!="integration-test":continue
    if meta.get("scenario") not in ("bridge-validation","watchdog-validation"):continue
    resolved=source.resolve();resolved.relative_to(raw)
    if resolved.is_symlink() or source.is_symlink():raise RuntimeError("Refusing linked fixture")
    destination=(validation/"raw"/source.parent.name/source.name).resolve();destination.relative_to(validation)
    if destination.exists():raise RuntimeError("Fixture destination already exists")
    destination.parent.mkdir(parents=True,exist_ok=True)
    for name in ("boot.json","close.json"):
        f=source.parent/name
        if f.exists():shutil.copy2(f,destination.parent/name)
    shutil.move(str(resolved),str(destination))
    archived.append(dict(trial=source.name,status=meta["status"],manifest=str(destination/"manifest.json")))
    for resultpath in validation.glob("live-*.json"):
        result=json.loads(resultpath.read_text(encoding="utf-8"))
        if result.get("trial")==source.name:
            result["raw_manifest"]=str(destination/"manifest.json");save_json(resultpath,result)
save_json(validation/"archived-fixtures.json",archived)
print(json.dumps(archived,indent=2))
