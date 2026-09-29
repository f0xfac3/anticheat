# Security console

A native Swing desktop console. Java 21 is recommended for Windows DPI rendering;
the managed Spigot process runs on Java 8.

Run `C:\anticheat-lab\Start Monitor.cmd` in the prepared lab. It starts the server.
Do not start a second server or a collection controller at the same time.

| Workspace | Purpose |
|---|---|
| Overview | Measured cadence, model-margin trends, observed sessions and confirmed responses |
| Incidents | Native findings, durable model decisions, exact evidence and action outcomes |
| Detections | Register recipes, compare held-out measurements, train, inspect gates, deploy or roll back |
| Validation | Declare conditions, reserve validation sessions, admit raw captures, review evidence |

**Server controls**, at the top right, contains the console and command field.
After joining, `acdata arena <name>` prepares the lab area. Native Timer budget
enforcement retains its own policy in `enforcement.properties`. A shadow model
does not ban. `pardon <name>` allows a banned test account to reconnect.

The charts use persisted 30-second windows from the last hour; gaps break lines.
A model margin is not a probability. Unseen feature ranges produce explicit
abstention records. Empty charts mean there are no eligible windows yet.
Confirmed responses are read from durable SQLite actions, not inferred from a
disconnect. A historical ban remains an event even after a pardon.

The registry is polled read-only off the UI thread. Both UI and terminal workflows
call the same `analytics/lab.py` commands. Deployment files are verified against
registry hashes at server startup. Changes require a server restart.

## Configuration

Set `server.directory`, `analytics.framework`, `analytics.python`, and
`analytics.raw` in `monitor.properties`. Paths resolve relative to that file.
The Python environment needs `analytics/requirements.txt`. The registry lives
under the server's `plugins/FoxAntiCheat/analytics.sqlite`.

Build with `build.ps1 -Jdk <JDK root>`. Parser, response, retention, file rotation,
subprocess and runtime tests run during the build. `MonitorUiTest` additionally
renders each workspace against saved JSONL evidence without starting a server.
Native checks and ML inference are implemented outside the desktop.
