# JNI/event boundary (v3)

`NativeBridge.nCreate(config)` creates an engine and returns a checked numeric ID.
`nSubmit(handle, directByteBuffer, length)` synchronously consumes one normalized
event and returns ASCII JSON report strings (or null for none).
`nDestroy(handle)` destroys that engine and all per-session checks.

The buffer belongs to Java. Native code decodes and copies values during the call;
it retains no pointer into the Java buffer, no JNI references, and no JNIEnv.
All calls use the creating thread. Registry access is also mutex-protected.
The Java wrapper guards closed handles and thread misuse. Native entry points
validate independently and translate C++ exceptions into Java exceptions.
Native faults such as access violations are NOT recoverable through this mechanism.

This is our own transport representation of normalized events, not raw Minecraft
packets. There is no packed struct cast: the reader checks each field explicitly.
All scalars are little endian. Text is `u16 length + ASCII bytes`, maximum 1024
bytes. Events are capped at 8192 bytes. Unknown schemas/types, trailing bytes,
truncation, invalid booleans/enums, and non-ASCII identifiers are rejected.
This restricted text encoding is for UUIDs/game identifiers, not player chat.

## Common header (48 bytes)

```
u32 magic = 0x43415846 (bytes "FXAC")
u16 schema = 3
u16 type
u64 session_id
u64 normalized_event_ordinal
u64 observed_nanoseconds_since_adapter_start
u64 epoch_milliseconds
u64 server_tick
```

Ordinal is incremented at delivery on the main thread; packet sequence is recorded
at the decoded Netty observation point. They are deliberately distinct. Tick time
is a snapshot, not an assertion that wall-clock intervals equal a number of ticks.

## Bodies

```
1 SessionStart: text uuid, u32 client_protocol, u32 server_model
2 SessionEnd:   empty
3 Reset:        text reason
4 Tick:         empty
5 Dig:          u64 packet_sequence, u64 read_batch, u64 sampled_ns,
                u8 action (0=start, 1=abort, 2=finish), u8 face (0..5),
                i32 x, i32 y, i32 z, MiningContext
6 Context:      i32 x, i32 y, i32 z, MiningContext
7 Attack:       u64 packet_sequence, u64 read_batch, u64 sampled_ns, CombatContext
8 CombatContext: u64 sampled_ns, CombatContext
9 Swing:        u64 packet_sequence, u64 read_batch
10 Teleport:    text cause, text from_world, text to_world, Vector3 from, Vector3 to
11 Movement:    u64 packet_sequence, u64 read_batch, u64 sampled_ns,
                Vector3 position, f64 yaw, f64 pitch,
                u8 has_position, u8 has_look, u8 packet_on_ground, MovementContext
12 Impulse:     u64 token, Vector3 velocity, u8 additive
13 ImpulseAck:  u64 token
14 Correction:  empty (also invalidates world-snapshot assumptions after block/chunk updates)

Vector3:
    f64 x, f64 y, f64 z

CombatContext:
    text world_uuid, text target_uuid, text target_kind, text unavailable_reason,
    i32 target_id, Vector3 eye, Vector3 target_minimum, Vector3 target_maximum,
    i32 ping_ms, u8 available,
    f64 yaw, f64 pitch, u8 rotation_available, u8 attacker_sprinting, u8 target_player

MovementContext:
    text world_uuid, text unavailable_reason,
    u8 available, u8 source_supported, u8 destination_supported, u8 clear_path,
    u8 flat_ground, u8 using_item, u8 sprinting,
    f64 movement_speed, f64 friction, f64 jump_velocity

MiningContext:
    text world_uuid, text state_key, text block, text tool,
    text unavailable_reason, f64 damage_per_tick, u8 available
```

The server-model value 10808 is an internal adapter label, not a protocol number.
Protocol 47 is the configured direct 1.8.x baseline; this adapter does not discover
clients hidden behind a translator or distinguish 1.8.x patches sharing a protocol.

When adding telemetry, update the typed event and both schema ends together and
add a cross-language smoke test. The public Check interface and generic dispatcher
do not change. Batched transfers or an out-of-process transport can be added later;
this starter makes one synchronous call per normalized observation.

The decoder accepts schema 1 (types 1-6), schema 2 (types 1-10), and schema 3.
Schema-2 CombatContext ends after `available`; its missing rotation/sprint/target
flags default to false. Schema 3 appends that 19-byte suffix and adds types 11-14.
Deploy the plugin JAR and native library together; older native libraries reject v3.

Support/clearance flags come from server collision queries. They are separate from
the packet ground bit. Missing movement positions are not inferred by the C++
physics checks. Impulses contain copied on-wire values, not a Bukkit intention.
An acknowledgement is an owned transaction-processing marker with the trust and
timing limits described in `docs/VAPE_DETECTIONS.md`.

Attack and Swing are different observations. Neither is a recording of physical
mouse input. CombatContext contains unexpanded server bounds, not a historical
client view. Teleport invalidates queued pre-teleport callbacks without inventing
observation loss; each check decides which state depends on continuous position.

The standalone replayer reads records as `u32 event_length + event_bytes`.
`tests/combat_cases.py` generates valid synthetic scenarios in this format.
