# Security console

Native Windows desktop monitor for the local Spigot anticheat. No HTML server or
additional libraries. Uses the engine's original findings and server response logs.
The desktop launcher requires Java 17+ for Windows DPI scaling (Java 21 recommended).
The Minecraft server continues to run separately on Java 8.

## Run

Stop the existing server normally (`stop` in its console), then run
`C:\anticheat-lab\Start Monitor.cmd`. The monitor starts the server itself.
Do not also run Start Live Test or collection scripts.

In **Server log**, send `acdata arena elleliska` after joining, with Timer off.
Move normally, then enable only Timer 1.07. Watch **Alerts** and **Responses**.
Send `pardon elleliska` in Server log to repeat. Stop server saves the world.

- **Alerts**: native suspicious findings and original JSON measurements.
- **Responses**: observed detections and confirmed server bans, linked by evidence ID.
- **Research**: saved model comparisons, grouped validation, timing sensitivity and data coverage.
- **Activity**: all native findings, including baseline assessments.
- **Players**: observed identity, session state and alert counts.
- **Server log**: console, startup policy and operator commands.

Confirmed bans are counted only from the plugin's BAN record, not inferred from a
flag or disconnect. Selecting a ban shows matching native evidence if still in the
bounded live buffer; permanent decisions remain in the server's SQLite database.
The monitor observes a fresh run, not historical ban state. A server-side pardon
remains visible in Server log. No risk percentages or packet telemetry are invented.
Run `C:\anticheat-lab\Evaluate Research.cmd` to refresh the offline evaluation,
then reopen Research. It reads the active cohort without changing enforcement.

The UI holds 2,000 response events, 2,000 alerts, 5,000 findings and 6,000 console
lines, with byte caps for full records. Dropped records and parse failures are shown.
Console captures are saved under `logs/`; raw evidence remains in the plugin logs.

## Build

`build.ps1 -Jdk <JDK root>` compiles Java 8-compatible classes and runs the parser,
response, retention, file rotation and subprocess tests. Edit `monitor.properties`
for your server location and Java 8 runtime. Source requires no external libraries.
