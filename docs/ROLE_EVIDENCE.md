# Data-driven anticheat: evidence and gaps

Target: [Hypixel Data-Driven Anticheat Developer](https://hypixel.net/jobs/#listing-56),
reviewed September 29, 2026. This is a coverage assessment, not a claim of qualification.
The employer says initial reviewers will not compile or run submitted examples.
Start with the [recorded research results](../examples/timer/research/REPORT.md),
then show the live monitor and linked native evidence in a short video.

| Role area | Reviewable project evidence | Remaining gap |
|---|---|---|
| Gameplay/network analysis | Original packets, schema audit, source hashes, per-trial features, SQLite and native replay | Only one player/day and a small local corpus; no large-network scale claim |
| Statistical outlier analysis | Grouped splits, held-out legitimate calibration, paired measurements, uncertainty bounds | Too little independent legitimate exposure for a deployment false-positive claim |
| Machine learning | Logistic regression, Isolation Forest comparison, cadence ablation, batching sensitivity, rejected-model results | Fresh human/day/network test set and calibration; model scores are not cheating probabilities |
| Bots and macros | Attack interval CV, entropy and repeat features with explicit minimum coverage checks | Current recordings contain insufficient attack data and no human-versus-automation labels |
| Risk reports and tools | Research report, exact JSON predictions, live desktop Alerts/Responses/Players and evidence IDs | Risk trend validation across days and populations |
| Cleaning and interpretation | Reject corrupt/incomplete/inconsistent captures; bounded extraction; allowlisted features | Independently reviewed labels and realistic nuisance conditions |
| Cheat behavior knowledge | Recovered Timer field write -> expected excess cadence -> packet measurements -> deterministic live ban | More module mechanisms and validation; one local ban is not a data-driven model evaluation |

## What to say in the demonstration

"I traced Timer's effect to the client's tick rate, then measured it from received
movement packets. I kept matched routes together when splitting the data so the
same route could not enter training and testing. I compared a simple cadence rule
with learned models, tested sensitivity to batched arrivals, and kept the failed
models in the report. The live ban uses the validated code path of the deterministic
budget rule; the research models remain experimental. The recorded data is enough
to show the workflow, but not enough to claim production false-positive rates."

## Evidence needed next

1. Same-client control: Vape loaded with Timer off, then Timer on; retain identical
   route/settings. This tests whether the model learned a client difference.
2. Fresh validation: keep entire players and recording days out of development.
   Record legitimate play across network delay/batching, server load, collisions,
   corrections and idle periods. Fix acceptance targets before inspecting results.
3. Bot/macro study: separately label human input versus scripted input, regardless
   of Timer state. Include skilled human repetitive actions and variable-rate scripts.
   Keep each person and script family in one split; never use current scripted vanilla
   trials as human negatives. Report coverage and failures before considering action.
4. Shadow operation: record decisions without punishment, review false positives,
   and measure latency, queue loss and eligible player-hours. Preserve model version,
   features and evidence for each decision. Use an explicit promotion/rollback policy.
5. Scale evidence: replay a representative larger corpus and measure throughput,
   memory and failure behavior. Repeating the same recording measures load capacity,
   not new statistical evidence.

Do not add fake samples, relabel automated vanilla as human, or present a 17/17
development result as network-wide accuracy. These are remaining experiments that
software alone cannot supply.
