# anticheat monitor

A small desktop launcher for the local Spigot lab. Opens a window, starts the
server, and displays its findings and console output. The C++ detector and
installed server plugin stay unchanged.

## Run

Stop the existing server first. Put this folder here:

```
C:/anticheat-lab/anticheat/monitor/
```

Double-click `start.bat`. Use it instead of the old server launch command.
The launcher console closes; the monitor remains open while Spigot runs as a
separate process. Java 8 x64 is selected for Spigot. No CMake or plugin rebuild
is required; the compiled `anticheat-monitor.jar` is included.

`monitor.properties` controls the server directory, Java root, and heap.
`java.home=auto` searches installed Java 8 runtimes. To set it explicitly:

```
java.home=C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot
```

Use forward slashes, no quotes, and do not add `/bin`.

## Views

Alerts . only Findings whose native level is `suspicious`
Activity . all received Findings, including trace records and resets
Players . names, last recorded state, and finding counts
Server log . stdout/stderr, startup, errors, and a server-command field

Select a finding for its evidence. Copy JSON preserves the original record.
Stop server sends `stop`; closing the live window asks to save/stop first.
Force stop appears after 15 seconds and requires confirmation because it can
lose unsaved world data.

No added Reach/Autoclicker detection, fake probabilities, or automatic bans.
This view currently describes the FastBreak-only lab build.

## Logs

Works with the old raw-JSON console and the clean reporter's rotating files at
`server-1.8/plugins/FoxAntiCheat/logs/findings-0.jsonl` (and rotations 1/2).
Existing evidence bytes are skipped before launch; matching console/file
records are deduplicated. Keep `trace=true` in the existing engine.conf to see
ordinary mining Findings in Activity. With trace disabled only emitted records
can appear.

Raw console output is saved to `monitor/logs/<run>-server.log`. These files are
not auto-deleted; remove old captures when no longer needed. Existing Spigot
and plugin evidence logs are not removed or overwritten by the monitor.

Open saved log reads raw-JSON console logs or Finding JSONL files in a separate
REPLAY window. Compact console summaries alone do not contain full evidence;
open the corresponding JSONL file for structured alert replay.

The live view retains up to 2,000 alerts, 5,000 activity records, and 6,000 console
lines, with additional byte limits. Overflow/parse failures are visible. This
is a bounded lab monitor, not a lossless high-volume telemetry database.
Do not hard-kill the monitor to stop the server; use Stop server.

## Source

src/dev/fox/monitor/
  MonitorApp.java . connects log readers to the UI
  MonitorWindow.java . alert, activity, player, and console views
  MonitorModel.java . bounded display state and duplicate filtering
  Finding.java . reads the existing native Finding JSON
  EvidenceTail.java . follows rotating evidence files
  LineReader.java . frames bounded UTF-8 log lines
  ServerProcess.java . starts Spigot and carries console input/output
  MonitorConfig.java . local paths, Java runtime, and heap
  JavaRuntime.java . validates Java 8 x64 without blocking its output pipe
  Theme.java . shared black/gray UI styling

Build with a JDK 21 root (only standard Java libraries are used):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Jdk "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
```

The monitor targets Java 8. Its tests use a small child-process fixture, not
Minecraft. Live Windows/Spigot testing remains necessary.

## Startup fix

The version check now drains Java's output while Java is running, rather than
waiting for it to exit before reading. This prevents full-pipe startup timeouts.
The check has a 15-second process timeout and still requires Java 8 x64.
The Server log view reports the exact Java executable being checked.
A timed-out/interrupted probe is terminated; this does not kill a game server.

Replace the monitor JAR with the included build, then launch start.bat again.
No anticheat plugin, C++ library, engine settings, or world data is changed.
