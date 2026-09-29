# Collection boundary

`runner.py` drives real input through `windows_input.py`. `core.py` supplies seeded
plans and collection guards. The bridge plugin exposes bounded local observation
and recording controls; it never manufactures movement packets.

```text
seeded input -> real client -> server recorder -> immutable raw trial
                                             -> audit -> SQLite
SQLite legitimate reference -> native Timer assessment -> durable decision -> ban
```

Only completed, audited trials enter the database. Loss, correction, wrong client,
stale server state, death or focus loss stops the trial. Source hashes bind imports
to their manifests, plans, input metadata and raw chunks.

One route seed contributes at most one legitimate reference value. Repetitions use
the maximum score. Cheat examples evaluate the reference; they do not fit it.
The importer and C++ check share fixed window/episode rules, checked by replaying
real captures through the compiled engine. Publication never changes a running model.

The detailed detection and database contract is `docs/TIMER.md` in the repository.
