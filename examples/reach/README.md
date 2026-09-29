# Reach recordings

Read [REPORT.md](REPORT.md) for the measured comparison and SQL queries.

- `catalog.json` pins original raw manifests, chunks and native traces by SHA-256.
- `raw/` contains 23 unchanged captures from two controlled OFF/ON experiments.
- `native/` contains original stationary-check verdicts, including capture guards.
- `plans/` preserves the original declared collection plans and implementation hashes.
- `engine.conf` is the configuration used for collection and native replay.
- `results.sqlite` joins the analyzed attacks to original event/packet identifiers.

The analyzer uses the same five-second guards for every displayed count. Earlier
controller summaries compared guarded request totals with full-capture native
totals; those numbers had different denominators. The original evidence is retained.

```powershell
python tools/reach/analyze.py --study examples/reach --output build/reach-study
```

After building, add `--engine build/native/anticheat_replay.exe` to verify every
native verdict. No Minecraft client, proprietary cheat binary or server JAR is
needed to inspect the evidence. These are manual clicks with scripted positions,
operator-declared module settings, one attacking account, one target and localhost.
The reused OFF control is included once. These are not independent human/network
validation samples and do not train a Reach classifier.
