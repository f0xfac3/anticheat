# Recovered Vape operations -> server observations

The engine now has executable checks for Timer, Reach, HitBoxes, Velocity,
NoSlowdown, NoFall, Speed, Fly, and an opt-in KeepSprint experiment. They emit
research findings. The separate [Timer baseline policy](TIMER.md) now connects
audited recordings to a conditional ban path. Other checks remain report-only.

There are three different claims to test:

1. **Recovered operation:** a particular recovered class writes a field, changes
   an input, changes targeting geometry, or scales an impulse.
2. **Conditional detection:** under stated game and observation assumptions, that
   operation can produce an observation outside the ordinary client model.
3. **Live reliability:** recorded original-client off/on/off trials and legitimate
   sessions establish actual coverage and false-positive behavior.

This change supplies source evidence and reproducible synthetic tests for the first
two layers. [Timer's local recorded example](TIMER.md) and the
[23-recording Reach study](../examples/reach/REPORT.md) add limited live evidence;
broader human/network validation remains outstanding. Neither a decompiler listing nor a
passing synthetic test proves that every setting/mode is caught, that a player uses
Vape, or that the check is safe to punish on. Another client or a server-side plugin
can produce the same observable contradiction.

## evidence

The original captured class archive has SHA-256
`0bba297387dc4d1dfad51553771af31ad8b7ff3019419db9329502596279854e`.
The retained research records describe 3,290 recovered classes, 7,956 substituted
constant calls, and 2,042 resolved member calls. The decoder comparison checked
4,813 populated cache values and found zero mismatches **among those comparisons**.
This does not validate every decompiled branch.

[manifest.json](research/vape/manifest.json) records each original class entry hash,
the previous business-excerpt hash, and the shipped excerpt hash. Shipped excerpts
only have blank lines removed and line endings normalized. They preserve original
class names and decompiler errors; they are reference text, excluded from compilation.
The original binary and full dump are not distributed here.

The strength of the source evidence differs by module: Timer includes independent
live field-resolution evidence; the other entries principally retain decoded
business-method evidence from the earlier analysis. Their synthetic tests exercise
the stated operation and observation model, rather than executing the original
obfuscated classes. Unresolved wrappers and decompiler control-flow artifacts still
need instruction-level checking for any additional branch claimed in a demonstration.

The Timer [field record](research/vape/field-evidence.json) independently connects
the recovered accessors to `Minecraft.field_71428_T` and
`Timer.field_74278_d`. The [live baseline](research/vape/baseline.json) recorded
**disabled, configured 1.07, actual timerSpeed 1.0**. It is explicitly not an enabled
trial. The notvape reconstruction is a useful test stimulus, but its replacements
for hooks, clocks and version wrappers cannot establish original Vape parity.

## Implemented coverage

| Module / source | Direct operation | Observable consequence and check | Boundary |
|---|---|---|---|
| Timer: [yG](research/vape/recovered/Timer-yG.txt), [Nb](research/vape/recovered/Timer-Nb.txt) | Writes configured float to the client timer; disable writes 1 | Sustained excess Flying-family packet tick budget; `timer.budget.v1` | Ordinary eligible walking update path, bounded arrival jitter; no trustworthy client clock |
| Reach: [y6](research/vape/recovered/Reach-y6.txt), [OR](research/vape/recovered/Reach-OR.txt), [Vs](research/vape/recovered/Reach-Vs.txt) | Samples an increased selection range and uses ray/box intersections | Repeated attack requests outside a conservative stationary eye/box distance; `reach.stationary.v1` | Existing check retained; server snapshots, not reconstructed client view |
| HitBoxes: [yY](research/vape/recovered/HitBoxes-yY.txt), [OR](research/vape/recovered/Reach-OR.txt) | Adds selection-box expansion | Stable aim repeatedly misses ordinary expanded target geometry; `hitboxes.stable_ray.v1` | Stationary low-ping lab model; not a general moving-target ray tracer |
| Velocity: [yZ](research/vape/recovered/Velocity-yZ.txt) | Scales incoming velocity/explosion effects horizontally/vertically, with gates and delay branches | No modeled response to an acknowledged outgoing impulse survives; `velocity.response.v1` | Isolated collision-free impulse; settings close to vanilla may fit the envelope |
| NoSlowdown: [yl](research/vape/recovered/NoSlowdown-yl.txt) | Restores input magnitude from `.2` to `1` in the shown item-use branch | Horizontal acceleration exceeds a continuous item-use envelope; `noslow.item_input.v1` | Flat ordinary ground and sampled item use |
| NoFall: [ys](research/vape/recovered/NoFall-ys.txt) | Normal branch writes outgoing ground true while descending after a fall-distance gate | Repeated unsupported descending ground claims; `nofall.ground_claim.v1` | Does not assert the server accepted the claim or prevented damage |
| Speed: [yq](research/vape/recovered/Speed-yq.txt), [z5](research/vape/recovered/Speed-z5.txt) | Staged horizontal speed/jump overrides | Displacement needs more horizontal input than the model permits; `speed.plain.v1` | Flat ground or continuous clear air; not complete bhop mode coverage |
| Fly: [yp](research/vape/recovered/Fly-yp.txt) | Direct horizontal/vertical movement control | Airborne vertical steps contradict gravity/drag; `fly.airborne.v1` | Multiple reported positions in collision-free air |
| KeepSprint: [y7](research/vape/recovered/KeepSprint-y7.txt) | Recognizes the `.6` attack slowdown, divides it out, applies retention (default `.95`) | Conditional post-attack retention envelope; `keepsprint.attack_retention.v1` | **Disabled by default:** client attack-success branch is not acknowledged |

## observation

The adapter is pinned to direct protocol 47 clients and Spigot **1.8.8 v1_8_R3**
(internal model 10808). Its protocol label is configured; it cannot discover a
translator's real client version. The client bytecode used to audit transaction
handling is the local Forge/MCP 1.8.9 build used in the reverse-engineering work.
Other server/client combinations need a separate audit.

`MovementObservations` registers all four exact Flying-family classes. It copies
coordinates, rotations, presence flags and the ground bit on the Netty thread.
`MovementSampler` obtains world state on the server thread, queued before ordinary
processing of that incoming movement packet. Omitted coordinates are not interpreted
as zero positions. Timer counts all variants; physics checks require reported positions.

The world model currently accepts only air, stone, grass, dirt, cobblestone, wood
planks and bedrock. It bounds coordinate steps, loaded chunks, query volume and
nearby entity/collision checks. Liquids, webs, ice, slime, ladders, stairs/slabs,
pistons, vehicles, sleeping, creative/flight, dead players and unknown terrain are
outside the model. This restriction deliberately leaves coverage gaps.

Ground support is measured from collision boxes, independently of packet `onGround`.
The source and destination body boxes are combined for the swept-clearance test.
Ordinary-ground slipperiness is `.6`; the movement attribute and jump potion are
sampled from the server. These are current server facts, not acknowledged client
state. There is no complete prediction of arbitrary plugins or client-side block history.

Join, respawn, world changes, outgoing position corrections, outgoing chunk/block
updates, queue loss and server stalls invalidate or warm the movement model.
The adapter uses two seconds of grace after lifecycle/world/stall boundaries;
Timer then has its own warmup. Any outgoing block update can reset the model even
if far away, so busy worlds can reduce coverage. Physics checks also use two seconds
of grace after impulses. Observation queue age must be at most 100 ms by default.
Physics evidence requires successive packets within 150 ms and independent read
batches; a Netty read batch is not assumed to equal a client tick.

## 1. Timer: derive a time budget from the actual field write

Recovered `yG.f(rB)` contains:

```java
uO.o().S(((Double)this.a.z()).floatValue());
```

`yG.P()` calls `S(1.0f)`. `Nb.S(float)` uses the multiplier field on the legacy
branch and a different milliseconds-per-tick transformation on later versions.
The live reflected fields establish which legacy field we are discussing.

For the ordinary legacy walking path, one client update produces one Flying-family
packet, including idle/look-only updates. A multiplier `s` can therefore increase
the long-term cadence from approximately 20 to `20*s` updates per real second.
That is the causal connection. The server cannot read `timerSpeed` from a packet.

[TimerCheck](../engine/src/checks/movement_checks.cpp) uses monotonic receive time:

```text
balance = min(5000, max(-credit_ms, balance - elapsed_ms) + 50)
```

Every eligible movement packet costs 50 ms; elapsed real time repays that cost.
Default credit is 250 ms and the required positive lead is 250 ms. A finding also
requires at least 40 packets, two seconds of observation, eight consecutive
over-limit packets, and 500 ms continuously above the limit. Gaps of 750 ms or more
reset the window; unusable context and corrections also reset it. The initial
warmup is 1000 ms. Slow clients cannot bank unlimited credit.

At steady `s=1.07`, the ideal excess budget accumulates at about 70 ms/second.
That predicts a sustained-window response, not an instant 21-packet threshold.
Lag recovery can create bursts, so a short burst alone is not evidence of the field
write. Long or adversarial network buffering can still exceed this timing model.
Timer slowing (`s<1`) is not detected by this excess-budget check.

## 2. Reach and HitBoxes: range and geometry are different observations

Recovered Reach uses `3.0` as an ordinary fallback and a configured sampled range
when its gates pass. HitBoxes changes the geometry used by entity selection; merely
searching for a string named Reach would not prove either operation.

The existing [ReachCheck](../engine/src/checks/reach.cpp) computes the minimum distance
from an envelope of recent eye positions to an envelope of target bounds. It pads
the raw box by `.13125` (`.1` ordinary picking expansion plus `1/32` encoding margin)
and compares against `3.0 + .05`. It requires 750 ms of stable history, at most `.03`
motion, bounded frame/queue age and three qualifying attacks from separate batches.
The shipped Reach ping limit is 150 ms; HitBoxes uses 75 ms. These are heuristics
for selecting lab cases, not latency compensation proofs.

[HitboxCheck](../engine/src/checks/hitboxes.cpp) requires stable position and aim
before and for at least 100 ms after the attack. Waiting allows the following
rotation update to invalidate a stale-aim comparison. It performs a ray/AABB test
with range 3.05 and generous padding:

```text
padding = .13125 + 3.05 * sin(3 degrees) + .03
```

Any plausible hit in the retained frames clears the sample streak. Only three
stable misses produce a finding. Out-of-range targets are left to Reach.
Small HitBoxes expansion may remain inside this uncertainty padding. Neither check
has historical outgoing entity interpolation/acknowledgements, and neither proves
which target geometry the client actually rendered. Findings retain
`client_view=NOT_RECONSTRUCTED` and describe attack **requests**, not accepted hits.

## 3. Velocity: use an observed impulse and a processing barrier

Recovered `yZ` divides sampled horizontal/vertical settings by 100 and changes
motion or incoming packet effects. Its helper `r()` includes randomization and
clamping; chance, Kite and delay branches also exist. **A fixed ratio such as .90
is not a reliable fingerprint of this implementation.**

The adapter copies actual outgoing local-player entity-velocity values (integer
components divided by 8000), and explosion additions. It sends an owned rejected
window-0 transaction immediately afterward. The ordinary 1.8.9 client handles both
packets on its main thread and replies to a rejected transaction using its window
and action number. Thus a matching reply supplies a processing-order marker under
that client behavior. It does not supply trusted elapsed time, guarantee movement
occurred, or make a malicious acknowledgement truthful.

The ledger matches exact IDs, expires entries after two seconds, rejects duplicate
acks, holds at most 32 entries, and resets on observation boundaries. Outgoing
transaction collisions invalidate evidence. Other plugins that use transaction
probes must coordinate IDs; this adapter is not a global transaction-ID allocator.
Ordinary unmatched responses pass through to Spigot, whose inspected handler only
unlocks a matching pending transaction. The marker does not modify gameplay packets.

[VelocityCheck](../engine/src/checks/velocity.cpp) distinguishes **replacement**
velocity from **additive** explosion motion. It considers every application slot
until acknowledgement and two further movement slots. Candidate trajectories allow
unknown horizontal input, gravity/drag, and a ground-jump alternative including the
sprint jump impulse. Each observed displacement eliminates incompatible candidates.
Three isolated acknowledged impulses with no surviving response produce a finding.

Attacks reset this check because legitimate sprint attacks can reduce local X/Z.
Landing, collision, missing positions, stale samples, overlapping impulses and
corrections cancel the pending comparison. Unacknowledged impulses cannot alert.
A fully suppressed impulse can evade this check if position omission makes the
comparison unavailable. Mild reductions, chance failures and delayed modes may fit
the allowed envelope or be skipped. Those are explicit coverage limitations.

## 4. NoSlowdown and Speed: compare required acceleration

In `yl.z(W9)`, the recovered branch checks for input components with magnitude
`.2` and restores them to `+1` or `-1`. On flat ground, remove the carried momentum
from two successive displacements:

```text
drag = .91f * slipperiness
required_input = length(delta_horizontal - previous_delta_horizontal * drag)
ordinary_max = movement_attribute * 1.3 * .16277136 / drag^3
item_max = ordinary_max * sqrt(2) * .2
```

The extra sprint factor and diagonal allowance are conservative; ordinary `.98`
input damping is omitted from the upper bound. NoSlow requires continuous sampled
item use and flat support. Restoring full input can exceed `item_max + .015` even
when the total speed is otherwise ordinary. Releasing use, changing attributes or
unsupported context cannot establish a NoSlow sample.

`z5` includes `2.149`, `.66` stage decay and `lastDistance / 159`. These explain why
an override can exceed legitimate input/drag; the detector does **not** match those
constants in packet traffic. Speed tests a general input envelope, with airborne
input bounded at `.026`. Three contradictory samples within ten seconds produce
a finding. Jump takeoff/landing transitions are excluded rather than treated as
continuous airborne or flat-ground samples. The synthetic multiplier fixture is
a stress case inspired by the recovered stage, not a faithful full Bhop simulation.

## 5. Fly and NoFall: distinguish motion from ground claims

The Fly source overrides movement vectors. Between continuous clear-air steps,
the ordinary vertical recurrence is approximately:

```text
next_dy = (previous_dy - .08) * .98f
```

The Fly check requires three discrepancies larger than `.015`. It excludes ground
transitions and impulse grace; it does not label every ascent or zero-height step
as flight. Tiny-motion clipping, protocol omission and special movement states
must remain within the model's tolerances or exclusions.

The shown NoFall Normal branch sets `onGround=true` while descending once its
fall-distance gate exceeds `2.224`. The server check does not use that exact number
as a signature. It requires repeated ground claims while both current and previous
steps are descending by more than `.03` with no source/destination support. This
tests the contradiction caused by the write. It makes no assertion about damage,
and it does not fully model the separate AntiCheat packet-pair mode.

## 6. KeepSprint: an explicit conditional experiment

Recovered `y7.F(WL)` compares motion against stored motion times `.6`, then replaces
it with `(motion / .6) * retainFactor`. The default retention shown is `.95`.
That is stronger evidence than observing a sprint flag alone.

The opt-in check tests a flat-ground post-attack envelope with `.6` retained
momentum, stable sprint state/attribute, no item use, a player target and low ping.
Its finding includes `client_attack_branch=ASSUMED_FROM_SPRINT_PLAYER_ATTACK;NOT_ACKNOWLEDGED`.
An attack packet alone cannot establish the exact client-local damage/knockback
branch or its order relative to movement. Server sprint/attribute transitions can
also make this narrow comparison unavailable. For these reasons
`keepsprint.enabled=false` is the shipped default. The test proves the arithmetic
under the hypothesis, not reliable original-client KeepSprint detection.

## Modules with no justified standalone detector yet

The following modules were part of the recovered/reconstructed inventory. They are
accounted for here rather than receiving a fabricated definitive signature.

| Modules | Evidence/observation limit and useful next work |
|---|---|
| AutoClicker, Triggerbot, Killaura | Existing `autoclicker.cadence.v1` is a low-variance **attack-request heuristic**. Recovered randomized/gated schedules and manual input can overlap. Killaura range/geometry abuse can independently reach the range checks. No client attribution or calibrated probability. |
| AimAssist, AntiFireball | Rotations and attacks are visible, but the recovered control/input systems do not imply a unique human-impossible rotation distribution. Need labeled original-client traces and quantization/target-history work. |
| WTap, JumpReset, HitSelect, BlockHit | Automate actions that ordinary clients can also perform. Timing correlations are hypotheses. Jump alternatives must be allowed when testing Velocity. |
| Sprint, Parkour, SafeWalk | Ordinary sprint/jump/edge behavior is possible manually. No impossible packet signature established. |
| InvWalk | The server cannot reliably infer every local GUI state from these packets. Movement while a guessed menu is open is insufficient. |
| Blink, Freecam | Silence, buffering or a camera-only change need not violate an observable invariant. Burst arrival alone also occurs with network stalls. Interactions during an impossible position state could justify a separate future check. |
| ESP, ItemESP, StorageESP, Tracers, NameTags, Fullbright | Client rendering changes can leave the same packet stream. A direct packet-only detector is not established. Reduce unnecessary information sent to clients as a separate server design measure. |
| AntiDebuff | Renderer-only suppression has no guaranteed server observation; movement effects need their own effect model. |
| FastPlace, Scaffold, AutoTool, AutoHeal | Need placement/inventory/use sequencing and world validation that this adapter does not yet collect. Shared timing automation is insufficient proof. |
| BackTrack, FakeLag, KnockbackDelay, SilentAura, HitFlick, MLG | The earlier reconstruction did not establish full behavior/parity. No invented detector is included. |
| Later-version combat modules | Their mechanics are outside this 1.8 adapter. |

FastBreak remains the pre-existing mining check. It is not relabeled as a newly
proven Vape module. Step, Criticals, LongJump and AntiVoid implementations were not
established in this recovered inventory.

## Reproduce, then establish live coverage

Build using [the repository instructions](../README.md). The native model cases are
in [movement_tests.cpp](../tests/movement_tests.cpp); existing Reach controls remain
in [combat_tests.cpp](../tests/combat_tests.cpp). JNI tests use the actual Java
serializer/native decoder, including schema-2 compatibility and every movement
payload truncation. [MovementPacketTest](../tests/MovementPacketTest.java) exercises
the real Netty forwarding handler and barrier ownership. See
[VALIDATION.md](VALIDATION.md) for the recorded build result and remaining gaps.

For a defensible original-client demonstration:

1. Record client/server hashes, protocol, configs, network conditions, enabled
   plugins and the relevant recovered class hashes. Use an isolated, static plain
   arena. Start with ordinary clients and all cheat modules disabled.
2. Capture copied observations with their **receive timestamps**, sampling times,
   packet presence bits, world/context flags, read batches, outgoing impulses and
   acknowledgement tokens. The current JSON finding archive is not a complete raw
   observation recorder; instrument `ObservationSink` for a bounded recording or
   use the custom packet layer's recorder. Replayer records are `u32 length + event`
   in [the bridge format](../bridge/README.md).
3. For Timer, read the actual mapped field, record off, enable at 1.07 and a stronger
   setting, then disable and confirm restoration. Compare field state, update
   cadence, budget growth and recovery. Do not call the existing baseline a toggle trial.
4. Test one other module at a time: measured target geometry for Reach/HitBoxes;
   known outgoing impulses and original response for Velocity; fixed use duration
   for NoSlow; recorded support and descent for NoFall; trajectories for Speed/Fly.
   Preserve chance failures and skipped comparisons, not just successful alerts.
5. Repeat with jitter, coalescing, loss/stalls, potion transitions, attacks during
   knockback, jumps/landings, block updates, teleports, lifecycle changes and supported
   ordinary controls. Unsupported cases should lose eligibility, not become evidence.
6. Replay the capture and attach the exact finding events and surrounding windows.
   Report per-setting eligibility and outcomes, including misses and legitimate
   findings. Only then consider enforcement thresholds or a server correction policy.

The basic server patch supplied here is therefore: collect the necessary primitive
observations, invalidate uncertain contexts, evaluate the relevant physical/time
invariant, and emit evidence for repeated contradictions. Actual punishment policy
needs the final live validation step.
