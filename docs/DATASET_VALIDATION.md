# Dataset collection validation

Validated on 2026-09-22 against Spigot 1.8.8 and Java 8u504.

## Implemented

- Optional raw FXAC schema-3 recording before native detector submission, including normal observations.
- Bounded asynchronous gzip writer, chunk rotation, explicit trial boundaries, provenance, and incomplete-trial exclusion.
- Console trial declarations, independent review sheet, integrity audit, non-overlapping feature export with guard intervals and export hashes.
- Separate flat-world lane and fall platform. No automatic enforcement or model deployment.
- Offline scikit-learn baseline with participant/opponent grouping or explicit known-player run evaluation.

## Verified

- Full Windows build: 8/8 CTest targets, including capture_journal, passed.
- 9 session/JNI checks, 15 movement-adapter checks, and 33 reporting checks passed.
- 7 Python tests passed, including unapproved-trial exclusion, review conflicts, export boundaries, hashing, gzip truncation, and ordinal gaps.
- Trainer syntax compiled; training dependencies/runtime were NOT validated because Windows blocked package-download sockets.
- Actual server startup loaded the C++ engine and observation schema 3; acdata status reported error=null.
- acdata arena created the lane/platform, save-all succeeded, and stop closed the recorder successfully. The port was free after shutdown.
- Real raw-dataset audit: zero gameplay trials, zero events, zero reported problems. Test fixtures were isolated from the real raw directory.

## Remaining validation

Two humans must join and conduct real off/on/off trials. Runtime load with players, teammate connectivity, labeling accuracy, original-Vape mechanism confirmation, feature parity, and false-positive rates are not established by this setup test. There is no trained or deployed model.

The first two testers support a pilot. Generalization requires independent participant cohorts, settings and days. No detector verdict is an independent training label.
