# Wave34.16 — Settlement Batch Reconciliation & Finance Maker–Checker Runbook

**Status: PROPOSED / UNRATIFIED — Production NO-GO.**
**Scope:** test-only PostgreSQL16 in \`danwiz/Project0-Cardealership-Maven\`; parent PR #48 at \`f99a59c5787fb0229199d9cfa50111bf93b5dcda\`.

## Proposed end-to-end batch review sequence

1. **Acquire evidence read-only.** Obtain independently authenticated provider settlement object, final bank deposit record and GL cash/clearing movements. Preserve original source bytes, timestamps, provenance, access authority and SHA-256 checksums. Wave34.16 obtains NONE of these real sources.
2. **Normalize identities and amounts.** Match legal entity, merchant account, bank account, currency, provider charge/batch reference, batch cutoff (UTC and local), recognized fee and adjustment rules, chargebacks, returns, tax and FX. Only proceed on signed Finance-approved mappings; the test hardcodes an illustrative gross 100, fee 3, net 97.
3. **Reconcile.** Require provider gross - approved fees +/- approved adjustments = net; provider net = booked bank net = approved GL cash delta, after documented timing and suspense exceptions. A mismatch should create a HOLD/exception case, not a forced journal change.
4. **Authorize maker–checker.** One analyst prepares a canonical batch digest; an independently enrolled checker reviews raw external sources and signs the same digest. Maker cannot self-approve. The test uses separate ephemeral P-256 keys and role strings in one JVM; neither is a real human approval.
5. **Record a single controlled outcome.** Use one transaction to bind batch, three verified source records and decision audit, with uniqueness, deferred consistency checks, append-only evidence and replay identity. The test is NOT a production close service.
6. **Close and preserve.** Independently review bank reconciliation, exceptions and suspense balances; lock the period under an approved policy; preserve nonrepudiable evidence, key provenance, owner/controller signoffs, restore checks and source rights.

## CI acceptance roster

| Case | Expected bounded result |
| --- | --- |
| W3416-01 | Three simulated feeds gross 100/fee 3/net 97 align, distinct test maker/checker signatures, same batch replay returns original reference and three source rows |
| W3416-02 | Bank variance, GL variance, tampered source/currency, forged checker key, same actor or expired approval: HOLD before SQL and no phantom close |
| W3416-03 | Four concurrent identical signed batch requests commit once; a new batch cannot reuse the same bank deposit reference; distinct bank deposit succeeds |
| W3416-04 | Invalid audit FK, missing GL row and bank net mismatch fail atomically; controlled retry posts one complete synthetic review |

Exact-commit Java 11 and Java 17 **full PG16 regression target: 430 methods / 122 classes** each. Independently run the existing **151 SECURITY-tagged methods / 64 classes** per JDK. Four key-free \`target/wave34-16-evidence\` JSON manifests must carry exact SHA, count/digest/amount and all real trust/finance flags set false.

## Financial HOLD and exception policy (draft)

A missing provider batch, unbooked bank deposit, incorrect merchant or account, currency mismatch, unrecognized fee, settlement variance, reused bank reference, revoked/expired/missing reviewer authority, wrong approver identity, unexpected GL difference or failed journal custody requires **HOLD**. Create a review case with original evidence and assign Controller/treasury authority. Do not synthesize a bank deposit, reissue a payment, override a GL record, waive the balancing constraint or silently match unrelated amounts.

Real provider settlement can be many-to-many with bank deposits and GL recognition. The one-batch/one-bank/one-GL demonstration does **not** establish a production reconciliation algorithm or approved threshold for tolerances. No variance tolerances are allowed in this test. Production tolerances require a signed policy and explicit suspense accounting.

## Formal release gates — OPEN

| Gate | Requirement | State |
| --- | --- | --- |
| BATCH-01 | Independently authenticated provider settlement | NOT RUN |
| BATCH-02 | Independently authenticated booked bank deposit | NOT RUN |
| BATCH-03 | Independently sourced approved GL cash/clearing | NOT RUN |
| BATCH-04 | Currency, fees, adjustments, FX, timing and suspense policy | NOT APPROVED |
| BATCH-05 | Verified true maker–checker identity and dual control | SYNTHETIC ONLY |
| BATCH-06 | Atomic uniqueness, audit and 3-feed balance gate | SYNTHETIC PG16 ONLY |
| BATCH-07 | Production immutable custody and restricted GL write path | NOT IMPLEMENTED |
| BATCH-08 | Real settlement batch reconciliation and close process | NOT RUN |
| BATCH-09 | Legacy PAY-ID and payment API remediation | OPEN DEFECT |
| BATCH-10 | Real merchant PVA-01..16 and multi-node failover | NOT RUN |
| BATCH-11 | Controller/treasury/DBA/AppSec/QA/SRE/legal reviews | PENDING |
| BATCH-12 | Stage27 and Phase16 final release authorization | NO-GO |

**No production issuer, provider, bank, GL account, money transfer, migration, merge or release authorized.**
