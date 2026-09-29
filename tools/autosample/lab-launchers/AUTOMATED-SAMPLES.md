# Timer lab

1. Open vanilla and Vape clients. Enable only Timer 1.07 in Vape.
2. Run `configure_clients.cmd` once per client after opening new processes.
3. Run `paired_examples.cmd`. Default: 40 paired three-minute trials.
4. Run `timer_status.cmd` to inspect the database baseline.

Each successful 180-second trial is audited and imported into SQLite automatically.
The runner starts the collection server as needed. Keep the desktop awake; F8 stops.
Use windowed clients, W forward, left Ctrl sprint and Space jump.

`analyze_samples.cmd` reimports existing recordings. To select the initial collection:

```powershell
.\analyze_samples.cmd --collection-date 2026-09-28
```

Database: `demo/server/plugins/FoxAntiCheat/anticheat.sqlite`.
Raw evidence: `datasets/raw`; input plans/logs: `datasets/automation/sessions`.
Screenshots are off by default. Old recordings and reports remain preserved.

The server loads a frozen Timer baseline at startup. Restart after importing a new
baseline. Collection sessions are exempt from bans. The current nine-seed baseline
cannot meet the configured evidence cutoff, so assessments abstain from banning.

[Timer design](demo/source/anticheat/docs/TIMER.md) · [Source](demo/source/anticheat/README.md)
