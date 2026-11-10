# Wave 34.21-B — PostgreSQL16 Four-Worker Replay Stress (Review Only)

**Status: PROPOSED, DRAFT, UNMERGED, PRODUCTION NO-GO.**
**Immutable baseline:** Wave 34.19 PR #54 head \`d4e784cc69e9497f6b7b56bce27134b2ef027a28\`.
**Scope:** additive test harness, CI workflow, custody gate and this runbook only.
Do not merge, promote, authorize finance operations, change payment runtime,
modify schema v5 or alter any inherited W34.15–19 fixture/source.

## Risk basis and source custody
- [Issue #51](https://github.com/danwiz/Project0-Cardealership-Maven/issues/51): PostgreSQL SQLSTATE 23505 on the unique maker-approval ID when the original fixture restricted ON CONFLICT to batch_id; corrected later to target all uniqueness constraints with exact stored-identity readback. Original failed CI run #42709465166 must remain available as audit history.
- [Issue #53](https://github.com/danwiz/Project0-Cardealership-Maven/issues/53): SQLSTATE 23505 on the unique provider_event_id during four-worker GL event replay; corrected fixture handles all unique constraints and exact readback. Original failed CI run #42714152238 remains evidence.
- Both corrections remain **test-only**, not proof of production payment correctness or independent finance authority. Both issues remain **OPEN** pending independent review and sufficient stress.

## Test plan and exact workload
The **real inherited JUnit methods** are invoked without changes on disposable PostgreSQL16:
- Issue #51: \`Wave34PostgresqlSettlementBatchApprovalTest.concurrentReplayIsIdempotentAndBankReferenceCannotBeReused()\`.
- Issue #53: \`Wave34PostgresqlBalancedGlReconciliationTest.concurrentReplayCommitsOnlyOneBalancedEventAndApprovalCannotBeReused()\`.

Matrix: JDK11 and JDK17, two issue cases, four shards of 250 repetitions = **4,000 attempted four-worker case invocations** across the full successful matrix; no test count is claimed until 16 GitHub jobs and manifests are verified. Every case itself tests four simultaneous identical requests plus negative cross-business-key reuse, valid distinct requests, and durable row counts.

Each shard writes 250 append-only JSONL observations with issue, JDK, shard, index, outer seed, PG16 version, immutable head SHA, elapsed duration, result and SQLSTATE/constraint on exception. The gate checks the complete contiguous indices and exact-head SHA. Shard failures are never replaced by successful later runs or edited away. Preserve failed artifacts.

**Limit:** seeded pacing only changes outer invocation timing. The fixture still creates its own UUIDs and uses an internal four-worker latch. PostgreSQL interleavings are nondeterministic; these cases are **repeatable load**, not deterministic schedule replay. A future instrumented fault hook is required for exact interleaving reproduction.

## Workflow execution and CI boundary
1. Architecture/source-custody job asserts the immutable base and EXACT four added files, compares inherited fixture and schema blob SHAs, and writes evidence that production authorization is false.
2. Baseline JDK11/17 PG16 full \`mvn clean verify\` must preserve 442 passing JUnit methods / 125 classes; independently run 151 SECURITY methods / 64 classes. The stress harness is selected explicitly via \`-Dtest=Wave3421PostgresqlReplayStressHarness\`, not included in the normal full suite roster.
3. Stress jobs run only after baseline passes. Each 250-attempt shard executes in a separate disposable PostgreSQL16 service, and the gate requires every row to be PASS.
4. Matrix complete = 16 successful stress jobs and source/full/security exact-head artifacts, with SHA and GitHub Actions workflow URL. CI result is a QA rehearsal, **NOT** a finance release gate.
5. If repository Actions require administrator approval or the PR workflow does not trigger, mark all CI results NOT RUN and use permitted manual/test infrastructure; never claim success.

## Outstanding failures / approval gates
This harness does **not** implement an independent witness, external consumer, KMS/WORM, HA/failover, bank/GL provider acceptance, Controller approvals, PVA-01..16 or production payment behavior. Stage27 and Phase16 historical gates remain separate. All 12 Wave34.19 release gates and independent Owner/Controller/DBA/AppSec/QA/SRE/Legal approvals remain unapproved. **Production release: NO-GO.**

## Evidence checklist
- Exact PR head + parent, four-path diff, immutable hashes and branch provenance.
- Java11 and Java17 baseline Surefire reports and independently selected SECURITY rosters.
- Sixteen complete shard JSONL records with success/failed status (or a precise NOT RUN record).
- Original issues #51/#53 CI failures, full review history, and new QA/AppSec signoff.
- Signed approval is separately required; a green action or PR mergeability is not approval.
