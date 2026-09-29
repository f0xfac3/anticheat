# Gameplay collection lab

**Playing alone? Start with the [solo session steps](SOLO_SESSION.md).**

## What is installed

- Spigot 1.8.8 with the C++ checks and raw observation recorder.
- A separate `ac_collection_lab` flat world. Existing world folders are retained.
- `acdata` console commands for per-player labeled trials. Nothing is automatically
  labeled by a detection result, and no model is installed in the live anticheat.
- Compressed, rotating raw chunks, audit/review/export tools and an offline ML baseline.
- A 20 GiB compressed-record quota and a 2 GiB free-space floor. Metadata uses
  a small amount of additional space. No automatic deletion.

The Java adapter targets direct Minecraft 1.8.x/protocol 47; use the same pinned
1.8.9 client build for your first paired comparisons. Translators are unsupported.

## Start your first two-player session

1. Double-click `C:/anticheat-lab/demo/collection/Start-Collection.cmd`. **Use one server
   at a time.** If using a monitor, ensure it points to this lab's server directory.
2. Connect locally to `localhost:25565`. The other tester joins the host's existing
   LAN/private-network server address. `online-mode` and network binding retain the
   prior server settings. This setup does not configure a router, VPN or firewall.
3. Use the server console (or the monitor's Server log command field). Console
   commands omit `/`. In-game equivalents require `foxanticheat.capture`, normally
   an operator. You do not need to grant your friend operator powers.
4. Run `acdata arena` once. It prepares the stone lane, five-block floor markers,
   staircase and a 20-block fall platform in the collection world.
5. Run `acdata arena YOUR_NAME` and `acdata arena FRIEND_NAME` to enter the lane.
   `acdata arena YOUR_NAME fall` places you on the fall platform **before a trial**.
6. Run `acdata status`. An error other than `null` means recording is unavailable.

The server uses survival mode, allows ordinary terrain editing near spawn, and
disables vanilla flying kicks for experiments (`allow-flight=true`). That does not
give survival players the creative-flight ability. Structures/nether are disabled
for this separate lab world. The arena command sets daytime, disables mob spawning
and keeps inventory on death. Server corrections and damage are still observations.

## The first 20-minute pilot

Replace `A` and `B` with exact online Minecraft names. Replace the client token with
the actual tested client (`vanilla-1.8.9`, `vape`, `notvape`, etc.). A label is your
declaration, not something the server can verify by itself.

```text
acdata start A legit none notvape all-off walk-jump
acdata start B legit none vanilla-1.8.9 none walk-jump
```

For three minutes, both players walk, sprint, stop, turn, jump and use an item on
the lane. Then:

```text
acdata stop A
```

Enable **only Timer at 1.07** on A's actual client, confirm the menu/setting, and run:

```text
acdata start A cheat timer notvape 1.07 walk-jump
```

Repeat the same routine for three minutes. Stop A's recording, disable Timer,
confirm it is off, then start another `legit none notvape all-off walk-jump` trial
for three minutes. Stop both trials. Swap the tested actor and repeat this block.
Avoid toggling a module inside a trial; stop/start makes a new explicit phase.
The exporter trims five seconds from each end of every phase and creates complete,
non-overlapping five-second windows. It excludes windows containing recorded
resets, teleports or world/correction boundaries.

Record a video/menu screenshot or other independent evidence with a clock reference.
For the original Timer, reading the actual mapped field across off/on/off is stronger
evidence than a menu toggle. **notvape results must be labeled notvape, not Vape.**

After the pilot, type `acdata stopall`, then `stop` to finish files and save the world.
Audit the result before scaling up. Stopping the process forcibly can leave open or
quarantined trials, which the exporter rejects.

## Expand the collection in balanced blocks

Each block should contain off/on/off with the same actor, routine, equipment,
client build, terrain, opponent behavior and network conditions. Swap actors.
Also collect the cheat-capable client with **all modules off** as a negative control;
otherwise a classifier can learn client differences instead of cheat behavior.

| Module | Trials to record | Matched legitimate controls |
|---|---|---|
| Timer | 1.02, 1.07, 1.15, 1.5, 2.0 separately; walking, jumping, dueling | Same client at 1.0; identical activity; different FPS caps and real jitter |
| Reach | Default/off, 3.1, 3.2, 3.4; stationary and moving fights, different approach angles | Ordinary range, approach/retreat, sprint hits, jump hits and missed attacks |
| HitBoxes | Expansion .1, .2, .35, .6; sweep along target edges | Same angles with no expansion; moving target and fast aim changes |
| Velocity | Horizontal/vertical separately; strong reductions first, then 90/100; record chance/mode | Normal knockback, sprint attacks, jumping, collisions and landings |
| NoSlowdown | Block/eat/bow use with identical movement routine | Ordinary item slowdown, release/re-use transitions and diagonal input |
| Speed | Established modes/stages separately, flat runs and jumps | Sprint jumping, turns, speed effects and stops |
| Fly | Hover and controlled vertical/horizontal motion, separately | Ordinary jumps, falling, jump effects and ground transitions |
| NoFall | Walk off the prepared platform after settling; distinguish modes | Same fall and landing without the module; include deaths/respawns |
| KeepSprint | Off/on sprint attacks, repeated matched fights | Successful/unsuccessful sprint attacks and sprint-state transitions; conditional label |
| AutoClicker/AimAssist | Separate exploratory datasets with actual algorithm/mode recorded | Different human testers, sensitivity, FPS, click styles and targeting |

Chance-based modules being enabled do **not** make every window a violation. Review
whether you verified only enablement or the actual mechanism. Keep those categories
separate when interpreting results. ESP/Fullbright rendering may have no packet
signature and should not be presented as detectable simply because a label exists.

Keep special terrain, lag, disconnects, inventory changes, teleports, effects and
combined modules in named stress-test scenarios. Collect legitimate stress cases
as well as cheating ones. The existing physics checks only model plain terrain;
the recorder retains raw movement even when that context is unavailable. Unknown
physics fields remain missing in the export; they are not zeros or violations.

## How much data?

Begin with the pilot, then plan **six 90-minute sessions with both testers** on
different days, with varied but balanced blocks. That is 18 player-hours and about
1.30 million Flying-family observations at an ordinary 20/s, before ticks, combat
contexts and other event types. Actual counts and storage depend on play/conditions;
measure them with the audit tool. Do not generate duplicate or fake gameplay to
inflate this number.

For broader validation, recruit additional independent testers/cohorts and retain
unseen players, settings and days for evaluation. Lots of adjacent packets from the
same two people are not lots of independent examples. With two testers you can
validate the collection pipeline and study mechanisms; you cannot claim accuracy
on the general player population.

For each tester/run, keep a profile outside model features: Minecraft/client version,
artifact SHA-256, mods, sensitivity, DPI, FPS cap, approximate FPS, input device,
network route/impairment, equipment/effects, scenario and UTC start. Put the evidence
file reference and any uncertain phases in the review sheet. Do not use usernames,
client names, module settings, scenario names or detector scores as model inputs.

## Audit, review and export

Open PowerShell in `C:/anticheat-lab/demo/collection`. The prepared workspace lab already
has a Python environment. On a fresh manual installation, install Python 3.10+
and run `py -3 -m venv .venv` here before using these commands:

```powershell
.\.venv\Scripts\python.exe dataset_tool.py audit ..\datasets\raw
.\.venv\Scripts\python.exe dataset_tool.py review-template ..\datasets\raw --out ..\datasets\reviews\pilot.csv
```

Review `pilot.csv`. Keep `approved=no` for unknown or unverified trials. To approve
a trial, set `approved=yes`, a checked `verified_label` (`legit` or `cheat`), a
`verification` value and an evidence reference. Allowed verification values:

- `legit_control`: controlled all-off/ordinary client trial.
- `enabled_confirmed`: independently confirmed module enablement; weak window label.
- `mechanism_confirmed`: independently observed the predicted state/behavior change.

Do not use the anticheat's own alerts as the independent evidence. Then:

```powershell
.\.venv\Scripts\python.exe dataset_tool.py export ..\datasets\raw --reviews ..\datasets\reviews\pilot.csv --out ..\datasets\derived\pilot.jsonl
```

The exporter checks gzip integrity, frame lengths/schema, event/chunk counts,
ordinals and finite values before accepting a finalized trial. It does not overwrite
an earlier export. Exported identifiers are stable HMAC groups; keep
`datasets/raw/.group-key` private and stable. Raw files retain player/world UUIDs
and positions; share reviewed exports where possible. Chat and IP addresses are
not fields in this normalized observation format.

## Train and validate

The old joblib trainer has been replaced by the [behavior registry](../../analytics/README.md).
Recording and auditing still use the standard library. Use the desktop Validation
workspace or `analytics/lab.py` to admit captures, attach independent review evidence,
reserve validation groups, train, and publish a shadow model. Live and offline feature
arithmetic is checked against the original raw recordings. A pilot cannot enable ML bans.

## Layout and recovery

```text
C:/anticheat-lab/demo/
  collection/                 launchers, guide, tools, .venv, installation.json
    backups/<timestamp>/      prior server properties, plugin/native binaries and configs
  datasets/
    raw/run-.../
      boot.json               binary/config provenance and boot identity
      trial-.../manifest.json labels, UUID, boundaries, completeness and counts
      trial-.../events-*.acbin.gz   little-endian u32 length + original FXAC v3 event
      close.json              writer shutdown/failure status
    reviews/ derived/ models/
```

Compression uses a bounded worker queue (2,048 events, max 8 KiB/event), not disk I/O
on the server tick. Chunks rotate at 64 MiB of uncompressed data. Queue overflow or
disk failure stops raw capture and leaves affected unfinished trials quarantined;
gameplay and detection continue. `acdata status` reports the error. Restart only
after resolving the quota/disk issue. Back up completed runs before freeing space;
the recorder never deletes older trials automatically.

Restore the previous lab by stopping the server, copying the backed-up binaries,
configs and `server.properties` from the recorded backup directory, then restarting.
Its old world folders remain available. Preserve the collection world and raw data
as experiment artifacts unless you deliberately decide to remove them.
