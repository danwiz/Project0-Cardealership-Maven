# ADR-W34-018 — Settlement Batch Integrity, Three-Source Reconciliation and Maker–Checker Controls

**Status: PROPOSED / UNSIGNED — PRODUCTION NO-GO.**
**Parent:** verified Wave34.15 draft PR #48 at \`f99a59c5787fb0229199d9cfa50111bf93b5dcda\`. GitHub Actions #42700166586 completed successfully: Java 11 and 17 each 426/426 full tests across 121 classes and independently 151/151 SECURITY-tagged tests across 64 classes.

## Scope and accounting problem

Wave34.15 built a **synthetic** three-asset-account double-entry proof with commit-time balance controls. It did not reconcile an authoritative merchant settlement file to an independently obtained bank deposit and the corresponding general-ledger cash movement. It also did not establish maker–checker separation of duties or a certified financial close.

Wave34.16 studies that boundary in a **random disposable PostgreSQL16 test schema**, outside the existing v5 application migrator. Five keypairs (provider, bank, GL, maker and checker) are independently generated **within the same JUnit process**. Each fixture is therefore only a synthetic source, *not* an independently operated provider, bank, accounting system or human approver. Nothing in this milestone executes real transfers, touches provider accounts, approves financial policy or changes production payment data.

## Source contract (three logical feeds, one test process)

Each test batch has exactly three signed canonical messages. Every message carries batch ID, feed kind, source reference, currency, gross/fee/net values and observation time.

| Feed | Source-provided synthetic values | Reconciliation requirement |
| --- | --- | --- |
| Provider settlement | Gross = 100, fees = 3, net = 97 illustrative USD minor units | gross - fees = net; provider and merchant authenticity must be independently verified in production |
| Bank deposit | Net credited = 97; gross = 0, fee = 0 in the test model | net must equal provider net; bank deposit reference must be unique |
| General ledger | Net bank cash movement = 97; gross = 0, fee = 0 | net must equal both provider and bank; this is a synthetic GL extract, not actual double-entry source custody |

A batch must not be considered reconciled merely because its internally asserted gross and net arithmetic balances; an **independent source** must corroborate it. In this experiment independent *keys* simulate logical independence, but every signer and data source are owned by a single test JVM. That limitation is deliberate and preserved in evidence flags.

## Proposed maker–checker contract

Two roles, MAKER and CHECKER, sign exact batch ID, SHA-256 review digest binding all three feed payload digests, approval UUID, role, subject and expiration. A checker must have a different test identity and key from the maker; the approval IDs must differ; neither may be expired or bound to another batch. These are **test signatures**, not actual human finance approvals, verified roles, maker–checker access control or legally delegated bank/payment authority.

The model enforces:
- Verify all five test signatures and the exact three-feed financial fields before starting SQL work.
- One PostgreSQL transaction inserts the batch header, three immutable source snapshots and one custody/audit marker.
- A \`DEFERRABLE INITIALLY DEFERRED\` PostgreSQL16 trigger confirms all three source rows, expected sums, currency, fee/net consistency and audit presence **at SQL commit** (SQLSTATE 23514 on variance).
- Unique (batch ID, feed kind) and (feed kind, source reference) prevent duplicate or cross-batch reuse of a synthetic bank deposit; approval UUID uniqueness prevents reusing the same test finance signoff for unrelated batches.
- A byte-for-byte-equivalent signed claim replay returns the original batch identifier without creating a second close. Parallel duplicate submissions serialize through the batch primary key.
- An invalid audit FK after creating the review header and source rows causes the **entire transaction** to roll back with SQLSTATE 23503.
- Test-only UPDATE/DELETE triggers reject rewrites with SQLSTATE 55000; a privileged DBA can remove those triggers. Not production WORM custody.

## Four acceptance cases

| ID | JUnit method | Demonstration only |
| --- | --- | --- |
| W3416-01 | independentlySignedSyntheticSourcesMustMatchBeforeMakerCheckerApproval | Synthetic 100 gross – 3 fees = 97 provider/bank/GL matched, two test approvers, idempotent replay, append-only denial, legacy untouched |
| W3416-02 | bankGlVarianceForgedSourceAndMissingSeparationOfDutiesMustHold | Bank/GL mismatch, source or currency tamper, forged checker key, same identity and expired checker denied before SQL |
| W3416-03 | concurrentReplayIsIdempotentAndBankReferenceCannotBeReused | Four-way identical batch replay creates one close; cross-batch reuse of bank reference hits SQL unique key and rolls back; unrelated batch succeeds |
| W3416-04 | deferredReconciliationAndAuditFkFailuresRollbackEntireBatch | Invalid audit FK and incomplete/imbalanced three-source direct SQL write roll back; valid controlled retry succeeds |

Every test records a key-free JSON manifest keyed to the exact PR SHA. Real provider/bank/GL attestation and independent finance approvals explicitly remain **false**. Existing application v5 and payment ledger are unchanged.

## Material limitations and OPEN release requirements

- Source independence: a real merchant settlement report (correct merchant ID, provider account, batch cutoff, capture IDs, fees/tax/adjustments), independent bank feed (account ownership, confirmed booked deposits, pending/returned/FX timing) and separately sourced authoritative GL data must be obtained and authenticated.
- Reconciliation arithmetic: amount scales, currency exponents, merchant reserves/rolling balances, split settlements, partial deposits, returns, fees, taxes, FX spread, batching time zones, weekend/holiday cutoffs, many-to-one and one-to-many matches, reversals and chargeback liabilities need a **Finance-approved** policy.
- Authorization: maker and checker must be separate enrolled principals with independent secret custody/IdP attestations, role assignment, limits, escalation, revocation, nonrepudiation and genuine finance delegated authority; no self-approval.
- Integrity: source file checksums, signatures, provenance, bank/merchant APIs, duplicate reference handling, durable reconciliation register, exception queue, suspense accounts, immutable real journal, transactional outbox and audit retention need independent review.
- Production correctness: PAY-999999/PAY-1000000 legacy ID allocator defect, unkeyed public \`PaymentProcessor.record\`, real provider PVA-01..16, owner/source rights, versioned migrations, restricted SQL principals, old-primary fencing and backup/restore RPO/RTO remain open.
- Governance: Stage27 14-gate register and earlier Phase16 17/12 framework are distinct historical measures and cannot be silently combined or declared complete. Controller/treasury, AppSec, DBA, QA, SRE, legal and owner approvals remain pending.

## Decision

**PROPOSED ONLY / NO-GO.** Passing a synthetic signed three-source PostgreSQL16 test confirms the local transaction and demonstration policy; it does not verify a real merchant, bank, GL, independent maker–checker signoff, approved accounting standards or production settlement reconciliation. No deployment, merge, production database change or funds operation authorized.
