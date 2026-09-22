# Validation record

## Environment

- Windows x64, MSVC 19.51.36246, CMake/Ninja.
- Eclipse Temurin JDK 21.0.11.10; Java compilation targets release 8.
- Adapter compiled against the existing local Spigot 1.8.8 v1_8_R3 JAR.
- Native compiler warnings are errors; JNI tests use `-Xcheck:jni`.

## Reproduction

From a Visual Studio x64 development shell:

```powershell
./build.ps1 -Jdk 'C:/path/to/jdk-21' -ServerJar 'C:/path/to/spigot-1.8.8.jar'
```

The build runs native and cross-language CTest suites, compiles the complete adapter,
then runs Java session, real-Netty packet, and reporting tests before packaging.
Adapter tests compile against the current source list, avoiding stale classpath
artifacts. Build cleanup is limited to the resolved build directory and refuses
directory links.

## What the tests establish

Final local build: **7/7 CTest suites passed**, including 25 movement/research
scenarios and the schema-3 JNI suite. The adapter gates passed 9 session/JNI,
15 movement/Netty and 33 reporting checks. The 17 shipped evidence files and all
13 selected original class hashes matched the recorded capture archive.

Verify evidence independently with:

```text
python tests/verify_research.py
python tests/verify_research.py --archive path/to/received-classes.jar
```

Research files use byte-preserving Git attributes so automatic newline conversion
does not invalidate these hashes on another platform.

- Existing engine, FastBreak, Reach, AutoClicker and JNI regression suites remain gates.
- Timer: 20 Hz, 1.07/1.5/2.0 accelerated cadence, all packet variants, 200 ms coalescing,
  stale snapshots, gaps, unavailable context and corrections.
- Motion: vanilla flat movement and gravity, exaggerated acceleration, item `.2`
  versus restored input, false descending ground claims, supported ground, vertical
  overrides, collisions, omitted positions, invalid coordinates, read batches and impulses.
- Velocity: replacement versus addition, acknowledged reductions, legitimate responses,
  missing acknowledgement and attack exemptions.
- HitBoxes: stationary stable misses, valid rays and changing aim.
- KeepSprint: disabled default and conditional `.6` versus `.95` arithmetic.
- Wire: real Java/native movement findings, all new event bodies, malformed/truncated
  payload rejection and old schema-2 combat compatibility.
- Adapter: owned, duplicate and expired barriers; bounded ledger; exact original-packet
  forwarding; original-before-barrier ordering; deferred callbacks and queue accounting;
  actual four NMS Flying presence-bit layouts.

These are deterministic synthetic/model and integration tests. They are not labeled
gameplay captures, original-client parity tests, production load tests, or measurements
of detection/false-positive rates. A full plain-terrain sampler is compiled but needs
an actual server/world fixture for runtime collision-query validation.

## Remaining validation

No running server was modified or restarted for this change. No original Vape
off/on/off gameplay session was collected for these engine checks. The historical
Timer baseline remains disabled/configured 1.07/actual 1.0. The study procedure and
specific model limitations are in [VAPE_DETECTIONS.md](VAPE_DETECTIONS.md).

The low-level client transaction handler and server transaction matching were
inspected in the local client/server bytecode. EmbeddedChannel verifies our own
ordering; it cannot prove ordering/compatibility of every other plugin or custom client.
