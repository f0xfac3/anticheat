# Recorded Timer example

17 original three-minute recordings: nine vanilla and eight declared Vape Timer 1.07.
The SQLite copy contains the active legitimate reference, exact window counts and
seed-excluded evaluation. Raw manifests and compressed events are unchanged.
Manifest paths in this database copy are relative so the example is portable.
No screenshots, old reports or synthetic training data are included.

From the repository root, after building the native replay executable:

```powershell
python tools/timer/timer.py status --database examples/timer/anticheat.sqlite
python tools/timer/replay.py --database examples/timer/anticheat.sqlite --engine build/native/anticheat_replay.exe
```

Expected: 17 matching native/Python episode scores. Legitimate score 20.0/s;
Timer score 21.4/s. The nine-seed baseline abstains from banning.

These are operator-declared, scripted local trials. They demonstrate the measured
mechanism and reproducibility, not a production false-positive rate.
