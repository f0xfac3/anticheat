"""Save the installed generator source in the anticheat repository and a source ZIP."""
from pathlib import Path
import shutil
import zipfile
HOME=Path(__file__).resolve().parent
LAB=HOME.parent
REPO=LAB/"demo/source/anticheat"
TARGET=REPO/"tools/autosample"
allowed={".py",".ps1",".cmd",".md",".json",".yml",".txt",".java"}
paths=[]
for path in HOME.rglob("*"):
    relative=path.relative_to(HOME)
    if any(p in {".venv","build","backups","__pycache__"} for p in relative.parts):continue
    if path.name=="profiles.json":continue
    if path.is_file() and (path.suffix in allowed or path.name==".gitignore"):
        target=TARGET/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
        paths.append((path,Path("automation")/relative))
launchers=["legit_example.cmd","hacking_example.cmd","timer_off_control.cmd","paired_examples.cmd",
           "configure_clients.cmd","analyze_samples.cmd","timer_status.cmd","view_samples.cmd","audit_samples.cmd","stop_server.cmd","AUTOMATED-SAMPLES.md"]
for name in launchers:
    path=LAB/name;paths.append((path,Path(name)))
    target=TARGET/"lab-launchers"/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
recorder=REPO/"plugin/src/main/java/dev/fox/anticheat/capture/CaptureRecorder.java"
paths.append((recorder,Path("recorder-patch/CaptureRecorder.java")))
for name in ("MovementObservations.java", "ChunkBoundaryScope.java"):
    paths.append((REPO/"plugin/src/main/java/dev/fox/anticheat/observation"/name, Path("observation-patch")/name))
out=LAB/"automation-source.zip"
for path in (REPO/"tools/timer").glob("*.py"):
    paths.append((path,Path("demo/source/anticheat/tools/timer")/path.name))
for relative in ("tools/dataset/dataset_tool.py","plugin/src/main/resources/timer-schema.sql"):
    paths.append((REPO/relative,Path("demo/source/anticheat")/relative))
with zipfile.ZipFile(out,"w",zipfile.ZIP_DEFLATED) as z:
    for path,relative in paths:z.write(path,str(relative).replace("\\","/"))
print("Source snapshot:",TARGET)
print("Source archive (requires the existing Minecraft lab):",out)
