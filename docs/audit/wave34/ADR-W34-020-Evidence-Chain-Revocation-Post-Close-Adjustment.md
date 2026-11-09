# ADR-W34-020 — Exception Evidence Chain, Approval Revocation and Prior-Period Adjustment

**Status: PROPOSED / NOT RATIFIED — PRODUCTION NO-GO.**
**Baseline:** Wave34.17 draft PR #50 at immutable source \`ab834bf4c4eac651450df3306a8b09bba30fc5bb\`. GitHub Actions #42711256970 SUCCESS: each of Java11 and Java17 434/434 full JUnit methods across 123 classes, plus 151/151 separate SECURITY methods across 64 classes. Prior synthetic batch race tracked separately in [issue #51](https://github.com/danwiz/Project0-Cardealership-Maven/issues/51) pending prolonged stress validation.

## Problem / architectural decision

The last wave implemented a disposable exception OPEN → RESOLVED and period OPEN → CLOSED rehearsal with suspense HOLD/RELEASE pairs. Its operational records were neither independently notarized nor protected by a genuine delegated finance approval service. A production exception/audit system must preserve original evidence and reasons, reject revoked approvers at decision time, enforce limits, and record authorized corrections to a new period rather than silently rewrite a previously closed period.

This wave introduces a **separate disposable PostgreSQL16 model** in random scoped \`w3418_...\` schemas after existing v5 migration. The model defines periods, test finance principals, an adjustment metadata register, append-only evidence chain, and an atomically advanced chain cursor. It does NOT create any real accounting entry, accounting policy, reconciliation system, genuine financial authorization, merchant API, production migration or funds movement.

## Proposed object model / relationships

- \`w3418_periods\`: historical \`2026-10\` CLOSED with an unchanged synthetic SHA-256 close digest; \`2026-11\` initially OPEN. Trigger denies reopening/editing a closed period in this lab.
- \`w3418_principals\`: maker and two possible checker identities, each with role, \`active\` status, amount limit in artificial units, optional revocation time. SQL trigger rejects restoration of a revoked principal; test administrator can still bypass it.
- \`w3418_adjustments\`: one unique adjustment ID, source reference and digest, previous and posting periods, fixed reason code, artificial positive amount, test currency, distinct maker/checker IDs and unique approval UUIDs, evidence-chain hash. Inserting requires source period CLOSED, posting period OPEN, and both principals active, correctly assigned and within limits. Two principals are not sufficient to establish genuine independent finance delegation: both are test fixtures.
- \`w3418_evidence_chain\`: monotonically numbered rows referencing each adjustment, its canonical claim digest, previous hash and current event hash; INSERT only under test-managed chain cursor lock; UPDATE/DELETE blocked by a disposable trigger.
- \`w3418_chain_cursor\`: single-lock serial sequence and hash anchor. Insertion and cursor advance share the same PostgreSQL transaction. Exact same request+test approval returns the original adjustment ID; a distinct adjustment reusing an approval UUID is rejected, not silently accepted.
- Test-only Java P-256 signatures bind role, test identity, adjustment identifier, full request digest, approval UUID and simulated expiration. PostgreSQL checks active role, approved limit, and both periods **again inside the transaction**. The Java signing keys are not external independent authorities, and the CI database owner can bypass Java checks and SQL guards.

## Tamper evidence and source-of-truth limitations

For each accepted synthetic adjustment:
\`claimDigest = SHA256(canonical(all adjustment fields))\`;
\`eventHash = SHA256(canonical(previousHash, claimDigest))\`.

The test replays the ordered chain, checks continuity and verifies the cursor tip against the stored adjustment fields. This detects naive changes to archived rows where an independent verifier retains a trusted old tip. **But this lab has no independently anchored tip, externally timestamped/notarized root, key custody separation, signed cross-system audit trail, cryptographic financial policy or WORM enforcement.** A privileged CI DBA can rewrite both chain and cursor or drop triggers. This is deliberately not described as certified immutability or production evidence custody.

Approver revocation applies to *new* adjustment decisions and is enforced under SQL row locks. The lab refuses approval amounts above each test principal's cap and rejects expired, forged, mismatched or self-issued approvals. Independent approver identity, delegation, authorization limits, real-time revocation/SCIM, key rotation, separation of duties and threshold approvals remain unimplemented in production.

An adjustment referencing the historical period may be accepted **only into a currently open new period**, with distinct approvals and a documented reason. It never edits the original closed-period digest or its original transactions. The actual general-ledger correction should use an independently approved debit/credit journal and statutory prior-period adjustment policy. Here it is *metadata only*, not an actual journal transfer.

## Synthetic acceptance contracts

| ID | Named test method | Required lab evidence |
| --- | --- | --- |
| W3418-01 | sequentialEvidenceHashChainVerifiesAndDisallowsHistoricalRewrite | Two committed adjustments make a verifiable chain; update/delete and closed-period reopening denied; original close digest unchanged |
| W3418-02 | revokedOverLimitExpiredOrForgedApprovalsCannotPostAdjustment | Over-limit, forged, expired, self-approved and revoked checks fail closed; a distinct active test checker can authorize |
| W3418-03 | postCloseCorrectionUsesOnlyOpenCurrentPeriodAndPreservesOldClose | Current-period metadata references historical closed period; attempted backdated posting and posting after new period closure held |
| W3418-04 | concurrentExactReplayPostsOnceAndCrossAdjustmentApprovalReuseFailsClosed | Four concurrent exact submissions produce one adjustment/chain link; a different adjustment cannot reuse approval IDs; distinct authorized adjustment succeeds |

Each test emits a **key-free** exact-source JSON manifest: chain length and tip, original close intact, schema version 5, zero legacy payment entries and explicit false flags for independent custody, real approver identity, production revocation, real prior-period financial adjustment and production release. Source control and CI cannot elevate these flags.

Target acceptance roster: **438 full JUnit methods / 124 classes** on each of Java 11 and 17; inherited **151/64 SECURITY** independently on both. Strict source custody only allows new test/fixture/docs/CI; it freezes prior W34.17 repair and all runtime payment/migration sources.

## Prohibited production inference / unresolved gates

- No independent provider/bank/GL sources, real signed credentials, secure approvals, legitimate source/merchant/bank data, independent record retention, WORM/signed time anchors, or financial Controller/legal authorization.
- No finance-approved legal entity account mapping, real currency/precision, debit/credit GL correction, fees/tax/FX/reversal handling, accounting-period close and prior-period adjustment approval.
- No new production schema, restricted non-superuser service actor, durable event store/outbox, quorum fencing, disaster recovery RPO/RTO, or external provider PVA-01–16 acceptance.
- Existing unkeyed \`PaymentProcessor.record\` and lexicographic legacy \`PAY-999999\` / \`PAY-1000000\` allocator defect remain unresolved.
- Historic Stage27 14 gates/0 formally closed and earlier Phase16 17 gates/12 blockers are separately recorded, not silently reconciled or approved; all owner/Finance/DBA/AppSec/QA/SRE/legal release signatures pending.

**Decision:** PROPOSED test-only ledger governance. **Production NO-GO; leave stacked PR draft, no merges, deployment, real funds or financial close.**

## Initial CI failure and inherited Wave34.15 synthetic GL replay repair

The initial Wave34.18 review SHA \`5b0a33807a166e99195c2cac86ad8b0d3a909efb\` was tested by GitHub Actions [#42714152238](https://github.com/danwiz/Project0-Cardealership-Maven/actions/runs/42714152238). Source custody **passed**; Java 17 and 11 each failed the **W3418-03** assertion because the test reused an already committed adjustment ID with a different period, and the application-level **changed replay identity HOLD** correctly raised \`SecurityException\` before SQL. W3418-03 is corrected to verify changed replay denial *separately* from a **new** backdated adjustment reaching the SQL closed-period gate (SQLSTATE 42501).

The Java 11 run separately exposed an **inherited Wave34.15 synthetic GL concurrency error**, SQLSTATE \`23505\` on unique \`w3415_gl_events_provider_event_id_key\` in \`Wave34PostgresqlBalancedGlReconciliationTest.concurrentReplayCommitsOnlyOneBalancedEventAndApprovalCannotBeReused\`. A targeted test-only remediation is tracked in [issue #53](https://github.com/danwiz/Project0-Cardealership-Maven/issues/53). The prior fixture uses \`ON CONFLICT (event_id) DO NOTHING\` despite multiple unique constraints also reused by exact concurrent replay. Wave34.18 expands from seven to **eight** review-only changed files solely to repair the **inherited W34.15 test** without modifying prior accepted draft PR #48:
- Use \`ON CONFLICT DO NOTHING\` for the synthetic GL event insert, then **require exact same event ID readback and matching sequence, event kind, minor amount, currency, canonical digest and approval UUID**. A collision on another event's provider-event ID/sequence/approval UUID is not silently accepted.
- The existing negative test for a different event reusing the same approval UUID now requires an explicit \`SecurityException\` with unchanged event and journal totals; previously it expected raw SQLSTATE 23505.
- Source custody explicitly allows this one inherited JUnit file and statically enforces the conflict/readback guard, while freezing the original W34.15 disposable SQL, Wave34.16 fix, Wave34.17 fixture, production v5 runtime, and protected \`master\`.

Both original CI failures are retained as audit evidence. **A corrected passing run, if observed, establishes only synthetic fixtures, not a real provider/GL concurrency or payment idempotency fix.** Independent stress verification for both issue #51 and issue #53 remains an OPEN reviewer action.
