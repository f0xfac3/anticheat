# Data-driven anticheat: evidence and gaps

Target: [Hypixel Data-Driven Anticheat Developer](https://hypixel.net/jobs/#listing-56),
reviewed September 29, 2026. This is a coverage assessment, not a claim of qualification.
The employer says initial reviewers will not compile or run submitted examples.
Start with [Timer's ML results](../examples/timer/research/REPORT.md) and
[Reach's measured comparison](../examples/reach/REPORT.md), then show the console
and one original packet-to-verdict link in a short video.

| Role area | Reviewable project evidence | Remaining gap |
|---|---|---|
| Gameplay/network analysis | Original packets, schema audit, source hashes, SQLite; 17 Timer and 23 Reach recordings replayed | One attacker/day per study and localhost; no large-network scale claim |
| Statistical outlier analysis | Grouped splits, held-out legitimate calibration, paired measurements, uncertainty bounds | Too little independent legitimate exposure for a deployment false-positive claim |
| Machine learning | Logistic regression, Isolation Forest comparison, cadence ablation, batching sensitivity, rejected-model results | Fresh human/day/network test set and calibration; model scores are not cheating probabilities |
| Bots and macros | Attack interval CV, entropy and repeat features with explicit minimum coverage checks | Current recordings contain insufficient attack data and no human-versus-automation labels |
| Risk reports and tools | Concise reports, exact JSON/SQL results, desktop Overview/Incidents/Detections/Validation and evidence IDs | Risk trend validation across days and populations |
| Cleaning and interpretation | Reject corrupt/incomplete/inconsistent captures; bounded extraction; allowlisted features | Independently reviewed labels and realistic nuisance conditions |
| Cheat behavior knowledge | Recovered Timer field write and Reach selection geometry -> OFF/ON recordings -> tested predicates | Moving-combat Reach is not reconstructed; an apparent legitimate Timer-budget flag remains unresolved |

## Demonstration order

1. Show Timer's matched 20.0/21.4 packets/s measurements and the logistic model.
2. Show its batching failure, the cadence ablation and the simpler comparator.
   Explain why a perfect local confusion matrix did not authorize bans.
3. Show Reach OFF versus 3.2 at centers 3.5/3.6: 0/0 requests versus 66/68.
   Follow one SQLite packet row to its raw capture and native geometry verdict.
4. Show console evidence and shadow status. Explain why uncertain movement causes
   the stationary Reach check to abstain and why promotion requires fresh evidence.

The installed lab and shipped defaults are report-only. The earlier successful
local ban demonstrates response plumbing. The [Timer incident](TIMER.md#local-live-enforcement-test)
prevents a claim that its deterministic enforcement rule has been validated.

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

Moving Reach also needs evidence about target updates sent to each attacker and
client rendering uncertainty. Stationary captures cannot validate that model.

Do not add fake samples, relabel automated vanilla as human, or present a 17/17
development result as network-wide accuracy. These are remaining experiments that
software alone cannot supply.
