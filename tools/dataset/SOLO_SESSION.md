# Solo gameplay collection

## Join and prepare

1. The collection server is running on `localhost:25565`. Join with Minecraft 1.8.9.
2. Use the dedicated server console window. Console commands have no leading slash.
3. Replace `YOUR_NAME` with your exact online name. The examples use client label
   `vape`; change it to `notvape` if that is the actual client you are testing.
4. Turn every module off. Keep the same client, equipment, sensitivity and FPS cap
   for each off/on/off comparison. Keep survival mode and remove potion effects.
5. Run:

```text
acdata arena YOUR_NAME
acdata status
```

The status must show `error=null`. Arena teleports should happen before recording.
Do not grant yourself creative flight for these movement comparisons.

## First trial block: Timer, about 10 minutes

Record a video showing module settings and transitions, with a clock reference.
The server label declares the enabled module; it does not prove a violation.

### A. All modules off — three minutes

```text
acdata start YOUR_NAME legit none vape all-off solo-walk-jump
```

Repeat this one-minute routine three times, on open flat ground beside the lane:

- 20 seconds walking back and forth, including turns and a brief stop.
- 20 seconds sprinting back and forth.
- 20 seconds sprint jumping, with a brief landing/stop between repetitions.

```text
acdata stop YOUR_NAME
```

### B. Only Timer enabled at 1.07 — three minutes

Enable Timer at 1.07 and confirm the actual setting before starting:

```text
acdata start YOUR_NAME cheat timer vape 1.07 solo-walk-jump
```

Repeat exactly the same routine, then:

```text
acdata stop YOUR_NAME
```

### C. Timer off again — three minutes

Disable Timer, confirm that all modules are off, then:

```text
acdata start YOUR_NAME legit none vape all-off solo-walk-jump
```

Repeat the routine for three minutes and stop:

```text
acdata stop YOUR_NAME
acdata status
```

Expect `active=0`, a nonzero `written` count and `error=null`. Writes are asynchronous;
allow the queue to drain. Stop the trial before changing any module or setting.
Avoid teleporting or opening settings menus inside a trial. The exporter trims five
seconds at each end and excludes windows containing reset/teleport/correction events.

## Extend the solo session

First complete another full off/on/off block with Timer at 1.15. Keep each phase
three minutes. This produces six separate trials, about 18 minutes of recordings.
Compare actual effects and review the first recordings before collecting hours.

The same off/on/off structure supports these further solo experiments:

| Module token | Routine | Positive setting metadata |
|---|---|---|
| `speed` | Flat running and sprint jumping; record each mode separately | Actual mode and setting, for example `mode=value`; never a guessed value |
| `fly` | A repeatable attempt to rise, move and hover; include normal jumps/falls in controls | Actual mode and configured speed |
| `noslowdown` | Walk while holding right click with a sword, release, repeat | Actual mode; identical item and movement in controls |
| `nofall` | Identical falls from the platform, with time on the ground before each fall | Actual NoFall mode |

Command shape for each positive trial:

```text
acdata start YOUR_NAME cheat MODULE vape MODE_AND_SETTING SCENARIO
```

Use no spaces inside a metadata field. Use the same scenario name for matched
negative trials, always with `legit none vape all-off`.

For NoSlowdown, equip a sword before recording. If needed, the console can give one:

```text
give YOUR_NAME minecraft:iron_sword 1
```

For NoFall, first use `acdata arena YOUR_NAME fall`, then start recording, wait
at least ten seconds, walk off, and allow at least ten seconds after landing before
stopping. The fall can kill a survival player; the recorded reset may cause export
windows to be excluded. Keep those raw recordings for mechanism review. Use separate
trials per fall and teleport only between trials. Do not describe a trimmed/death
window as accepted training data without checking the export.

Leave Reach, HitBoxes, player knockback/Velocity and KeepSprint combat trials until
another player is available. NPCs and stationary air-clicking are not interchangeable
with real player fights. ESP rendering has no established packet signature here.

## Finish safely

In the server console:

```text
acdata stopall
acdata status
stop
```

Wait for world saving and shutdown. Do not force-close the server during a trial.

Open PowerShell in this `collection` directory and audit:

```powershell
.\.venv\Scripts\python.exe dataset_tool.py audit ..\datasets\raw
.\.venv\Scripts\python.exe dataset_tool.py review-template ..\datasets\raw --out ..\datasets\reviews\solo-01.csv
```

Choose a new review filename for later sessions. Review every trial independently:
`approved=yes` requires a verified label, verification level and an evidence reference.
Use `legit_control` for verified all-off trials, `enabled_confirmed` for independently
verified enablement, and `mechanism_confirmed` only when you verified the actual effect.
Keep uncertain trials unapproved; detector alerts are not independent labels.

After review:

```powershell
.\.venv\Scripts\python.exe dataset_tool.py export ..\datasets\raw --reviews ..\datasets\reviews\solo-01.csv --out ..\datasets\derived\solo-01.jsonl
```

The fuller [collection guide](COLLECTION_GUIDE.md) explains the offline trainer.
One player is a collection/mechanism pilot. Restarting between sessions creates
distinct run IDs, but does not create independent players. Held-out run results
from your own gameplay do not establish accuracy on other people.
