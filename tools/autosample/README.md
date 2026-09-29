# Automated collection

Real keyboard/mouse input drives two independently registered Minecraft 1.8.9
processes. Only one connects at a time. The server records its received packets.
Labels are operator declarations; detector findings never label training samples.

Run the lab launchers in this order:

1. `configure_clients.cmd`: register vanilla, then Vape with only Timer 1.07.
2. `paired_examples.cmd`: alternating, matched route seeds; 40 pairs by default.
3. `timer_status.cmd`: concise database summary.

Keep the desktop awake/unlocked and clients windowed. W moves forward, left Ctrl
sprints, Space jumps. F8 releases keys and stops. Restarted clients require new
registration. The runner reconnects through the stock Minecraft 1.8.9 menu.

Completed trials contain a manifest, compressed raw events, plan and input log.
Screenshots are optional (`screenshot_seconds`, default 0). A failed or interrupted
trial is excluded. The script resumes completed matching conditions.

Every audited 180-second trial is imported into the Timer SQLite database. Short
smoke tests remain raw recordings and do not enter this baseline. `analyze_samples.cmd`
reimports existing trials; `--collection-date YYYY-MM-DD` selects a collection.

`config.json` contains server, dataset, database and Timer tool paths. The Timer
importer uses Python's standard library. Input automation needs only psutil and
Pillow. `profiles.json` contains local client identities and stays out of source.

The server loads an immutable database model at startup; restarting applies a newly
published baseline. Collection sessions are not banned. See `docs/TIMER.md` in the
anticheat repository for the score, statistical limits and enforcement contract.
