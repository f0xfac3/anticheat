# anticheat

C++ detection engine. Java 8 adapter for Spigot 1.8.8. SQLite evidence store.
Timer is the worked example: recovered behavior, recorded trials, a legitimate
baseline, native comparison and auditable enforcement.

```text
packet -> Java observation -> JNI -> C++ check -> SQLite decision -> Bukkit action
raw trial -> audit -> SQLite reference -> frozen model loaded at server startup
```

## Read the code

| Path | Responsibility |
|---|---|
| `engine/src/checks/timer_baseline.cpp` | Episode scoring, empirical tail rank, decision |
| `engine/src/checks/movement_checks.cpp` | Mechanistic Timer budget and movement checks |
| `tools/timer/model.py` | Matching offline episode extractor |
| `tools/timer/timer.py` | Audited import, baseline publication, text status |
| `plugin/.../report/TimerStore.java` | Frozen reference, asynchronous SQLite, durable action gate |
| `plugin/.../AntiCheatPlugin.java` | Session checks, Bukkit ban and disconnect |
| `plugin/src/main/resources/timer-schema.sql` | Trials, windows, models and decisions |
| `tools/autosample/` | Optional Windows collection controller |

[Timer design and measured results](docs/TIMER.md) explains the equations, raw queries
and enforcement boundary. [Reverse-engineering notes](docs/VAPE_DETECTIONS.md)
link recovered methods to the existing movement/combat checks.

## Current evidence

17 real three-minute trials, nine legitimate route seeds, eight matched Timer pairs.
Legitimate episode score: **20.0 packets/s**. Declared Timer 1.07: **21.4 packets/s**.
Python and native replay agree on all 17 recordings.

[The portable example](examples/timer/README.md) includes the SQLite reference and
original packet recordings. Replay it without starting Minecraft.

The empirical tail rank is not a calibrated cheating probability. This small local
dataset cannot authorize high-confidence bans: the live policy explicitly abstains.
The baseline ban path is implemented and tested; production accuracy is not established.
An optional deterministic Timer budget rule supports a [local live ban test](docs/TIMER.md#local-live-enforcement-test).

## Build and test

Requires x64 MSVC, CMake, Ninja, JDK 11+ and a local Spigot 1.8.8 JAR.
From a Visual Studio x64 developer PowerShell:

```powershell
.\build.ps1 -Jdk 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' -ServerJar 'C:\anticheat-lab\demo\server\spigot-1.8.8.jar'
python -m unittest discover -s tools/timer -p test_timer.py
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

Each new audited 180-second automated trial enters SQLite. The raw recordings stay
immutable. Collection sessions are exempt from punishment. No HTML dashboards,
opaque serialized model objects or online training are needed.

The adapter is pinned to direct protocol-47 clients and `v1_8_R3`. Protocol
translation, human false-positive rates and broad network/terrain coverage remain
outside the measured example. See [bridge schema](bridge/README.md) for the wire format.

## Live monitor

[Desktop security console](tools/monitor/README.md): detections, response timeline,
player state and evidence inspection. In the installed lab, stop the existing server
and run `C:\anticheat-lab\Start Monitor.cmd`; it starts and manages the server.
