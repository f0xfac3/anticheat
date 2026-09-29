# anticheat

C++ checks, Java packet capture, Python analysis and a desktop console for Minecraft
1.8. Includes raw Timer and Reach recordings, reproducible comparisons and a SQLite
registry for samples, models and decisions.

## Recorded results

| Study | Recorded result | What it demonstrates |
|---|---|---|
| [Timer: ML comparison](examples/timer/research/REPORT.md) | 17 recordings, eight matched pairs; approximately 20.0 vs 21.4 movement packets/s | Logistic regression, Isolation Forest, grouped evaluation, feature ablation and transport stress |
| [Reach: geometry](examples/reach/REPORT.md) | 23 recordings; 890 attack requests after guards; all 258 control requests within the bound | Controlled OFF/ON trials, exact packet-to-verdict joins and native replay |

The Timer logistic model separates all observed conditions under route-held-out
evaluation, but its original feature set falsely flags every legitimate recording
after synthetic 100 ms batching. Removing cadence also destroys useful separation.
Those failures are retained in the report. A simpler rate model survives that
particular stress test; it still needs fresh human and network validation.

Reach 3.2 and 3.3 produce requests at tested distances where the paired OFF control
does not. A conservative stationary geometry rule explains those observations.
The current 0.1-block grid cannot distinguish the two settings' exact boundaries.
Movement causes the rule to abstain; client-view reconstruction is not implemented.

These are local development results with operator-declared settings. Independent
human/network validation and a trained bot classifier remain unfinished. ML runs
in shadow; enforcement defaults to report only. An apparent legitimate Timer-budget
flag is documented in [known limitations](docs/TIMER.md#local-live-enforcement-test).

![Recorded Timer experiment in the security console](docs/security-console.png)

## Workflow

1. Register a versioned behavior recipe with its reverse-engineering source.
2. Run `plan-next <detection>` to find missing controls and validation coverage.
   Collect the proposed conditions and preserve the original packets.
3. Review human, client and network conditions against separate evidence.
4. Fit on development groups; calibrate on legitimate groups; evaluate reserved
   people, opponents, script families and days. Replay transport stress cases.
5. Deploy in shadow, inspect trends and enable enforcement only after validation passes.

[Behavior framework](analytics/README.md) · [Desktop console](tools/monitor/README.md) ·
[Role evidence and remaining gaps](docs/ROLE_EVIDENCE.md)

```text
packet -> Java observation -> JNI -> C++ check -> SQLite decision -> Bukkit action
raw trial -> audit -> SQLite registry -> model loaded at server startup
```

## Read the code

| Path | Responsibility |
|---|---|
| `analytics/` | Sample admission, reviews, grouped ML, calibration and model registry |
| `analytics/planner.py` | Next experiment from eligible samples, missing controls and validation requirements |
| `analytics/recipes/` | Configurable mechanisms, features, validation requirements and response policy |
| `tools/research/evaluate.py` | Recorded Timer model comparison, ablation and transport stress |
| `tools/reach/analyze.py` | Reach capture audit, original-packet joins, native replay and SQLite results |
| `engine/src/checks/reach.cpp` | Stationary geometry; skips unsupported observations |
| `plugin/.../report/BehaviorTelemetry.java` | Live features matched exactly to the Python extractor |
| `plugin/.../report/BehaviorRuntime.java` | Live inference, feature limits, repeated testing and recorded actions |
| `tools/monitor/` | Overview, incidents, detections and validation workflows |
| `engine/src/checks/timer_baseline.cpp` | Episode scoring, empirical tail rank, decision |
| `engine/src/checks/movement_checks.cpp` | Mechanistic Timer budget and movement checks |
| `tools/timer/model.py` | Matching offline episode extractor |
| `tools/timer/timer.py` | Audited import, baseline publication, text status |
| `plugin/.../report/TimerStore.java` | Reference loading, asynchronous SQLite writes and action checks |
| `plugin/.../AntiCheatPlugin.java` | Session checks, Bukkit ban and disconnect |
| `plugin/src/main/resources/timer-schema.sql` | Trials, windows, models and decisions |
| `tools/autosample/` | Optional Windows collection controller |

[Timer design and measured results](docs/TIMER.md) explains the equations, raw queries
and enforcement boundary. [Reverse-engineering notes](docs/VAPE_DETECTIONS.md)
link recovered methods to the existing movement/combat checks.

## Reproduce without Minecraft

```powershell
python -m pip install -r analytics/requirements.txt
python tools/research/evaluate.py --database examples/timer/anticheat.sqlite --output build/timer-research
python tools/reach/analyze.py --study examples/reach --output build/reach-study
```

Both commands audit original captures. Timer writes exact model inputs, folds,
coefficients and predictions. Reach writes a short report and `results.sqlite`
with trial summaries and individual attack observations. Raw packets and native
traces remain unchanged. Add `--engine build/native/anticheat_replay.exe` to the
Reach command after building to reproduce every archived native verdict.

The [ML example](analytics/README.md#reproduce-timer) fits a deployment candidate,
calibrates it against legitimate groups and reports failed validation requirements.
Java/Python feature extraction agrees on all 85 Timer windows. Scores and empirical
tail ranks are not probabilities of cheating.

## Choose the next experiment

After importing the example into the analytics registry:

```powershell
python analytics/lab.py --store build/study.sqlite plan-next timer.cadence
```

The same command runs from **Detections → Next experiment** in the console.
It reports missing controls, a recording procedure and independent validation
requirements. `--format json` includes metadata templates and affected sample IDs.
On the bundled Timer data, the next experiment is a same-client OFF/ON pair: the
existing controls used vanilla, while the Timer trials used Vape.

Recommendations use fixed rules and the trainer's eligibility checks. The planner
reads the registry without changing it. Labels still require review, and model
changes still require separate validation.

## Build and test

Requires x64 MSVC, CMake, Ninja, JDK 11+ and a local Spigot 1.8.8 JAR.
From a Visual Studio x64 developer PowerShell:

```powershell
.\build.ps1 -Jdk 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' -ServerJar 'C:\anticheat-lab\demo\server\spigot-1.8.8.jar'
python -m unittest discover -s tools/timer -p test_timer.py
python -m pip install -r analytics/requirements.txt
python -m unittest discover -s analytics -p 'test_*.py'
python -m unittest discover -s tools/reach -p 'test_*.py'
python -m unittest discover -s tools/research -p test_research.py
```

Builds `build/libs/anticheat.jar` and `anticheat_native.dll`; runs native, JNI,
adapter, recording, reporting and SQLite enforcement tests. Python Timer tools use
the standard library. Optional input automation uses psutil and Pillow.

Native-only build:

```text
cmake -S . -B build/core -G Ninja -DAC_BUILD_JNI=OFF
cmake --build build/core
ctest --test-dir build/core --output-on-failure
```

## Run

Stop the server before replacing binaries. Install the JAR under `plugins/` and the
DLL under `plugins/FoxAntiCheat/`. Import recordings with `tools/timer/timer.py`;
restart to load the published reference. `enforcement.properties` controls Timer
actions and the allowed world. Other checks report findings.

In the prepared local lab:

```powershell
C:\anticheat-lab\analyze_samples.cmd --collection-date 2026-09-28
C:\anticheat-lab\timer_status.cmd
```

Each new audited 180-second automated trial enters SQLite. Original recordings
are preserved. Collection sessions are exempt from punishment.

The adapter is pinned to direct protocol-47 clients and `v1_8_R3`. Protocol
translation, human false-positive rates and broad network/terrain coverage remain
outside the measured example. See [bridge schema](bridge/README.md) for the wire format.

## Live monitor

[Desktop security console](tools/monitor/README.md): behavior trends, incidents,
model deployment and evidence review. In the installed lab, stop the existing server
and run `C:\anticheat-lab\Start Monitor.cmd`; it starts and manages the server.
