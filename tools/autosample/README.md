# Automated collection

## Human versus AutoClicker pilot

The macro study uses the same two-account fixture as Reach. The attacker is the
Vape client and the target stays still on Lunar. Keeping the client, account,
target and geometry fixed makes input generation the intended difference.

For each matched pair:

1. Disable every Vape gameplay module. Run `macro_human.cmd`, confirm the
   condition, focus the attacker and click naturally at roughly 4-7 CPS for 100
   seconds.
2. Enable only AutoClicker at a fixed 8 CPS. Run `macro_vape.cmd`, confirm the
   condition, focus the attacker and hold the normal attack button for 100
   seconds.

Repeat until five pairs are complete, then run `macro_analyze.cmd`. Each capture
must produce three uninterrupted 30-second windows with at least 100 attack
requests per window. Raw observations, declared settings, admitted sample IDs and
per-window features are saved under `datasets/macro/<pair>/`. The report compares
attack rate, interval variation and repeated intervals, then fits the existing
interpretable logistic model with whole matched routes held out.

This is a development pilot. It checks that the capture, feature and model paths
work and that the declared AutoClicker produces a measurable effect. It cannot
support enforcement until separate people, days, networks and script families
satisfy `analytics/recipes/attack_macro.json`.

## Stationary Reach fixture

`reach_off.cmd` then `reach_on.cmd` keep two distinct accounts connected together.
Local `automation/reach-profiles.json` declares `attacker` and `target` names.
The bridge prepares survival players at 3.0, 3.4, 3.8 and 4.2 block **center**
distances. Each phase records 45 seconds; manually click with empty hands and stay
still. All gameplay modules are off for the control. For the intervention use
only Reach, fixed at 3.5, without movement/sprint activation gates. Conditions are
operator declarations, not machine-verified cheat settings.

Only these two players are damage/velocity protected while a leased fixture is
active. Original positions are restored on release or heartbeat failure. Movement,
changed player state, missing clicks, a bad near-distance control or an observation
loss excludes the affected run. Capture shutdown remains automatic. This fixture
measures attack **requests**, not damage or successful combat.

`datasets/reach/<pair>/COMPARISON.txt` compares observed OFF/ON counts. JSON keeps
source hashes, exact raw packet ranges, measured eye/box distance, eligibility and
native replay findings. Audited phases enter the analytics registry as unreviewed
development samples with manual clicks and scripted positioning declared separately.
Training and deployment are separate commands. The native stationary Reach check runs unchanged.
Moving combat and independent human/network validation are separate experiments.

For smaller Reach settings, use `reach_edge_off.cmd` first. It samples center
distances 3.4, 3.5, 3.6, 3.7 and 3.8: raw eye/box distances near the vanilla
boundary. It prints the new pair folder. Set both Reach endpoints to the requested
fixed value, chance to 100%, then run `reach_edge_on.cmd 3.3 <pair-folder>`.
Several ON settings can share the same OFF control when the measurement contract,
accounts, engine, configuration and fixture plugin match. This reuse does not create
additional independent controls. The ON artifact filename includes the declared
setting. Retain trials that produce no out-of-bound requests; they describe the
tested boundary even when the check has no reason to flag.

The [portable Reach study](../../examples/reach/REPORT.md) includes the completed
recordings and exact SQL queries. Reproduce it with `tools/reach/analyze.py`; further
collection is optional. A finer grid is supported through `reach_off.cmd --distances
3.4,3.45,3.5,3.55,3.6,3.65`. ON always inherits its control's exact grid.

## Alternating solo Timer trials

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
