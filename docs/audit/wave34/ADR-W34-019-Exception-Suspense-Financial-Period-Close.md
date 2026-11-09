# ADR-W34-019 — Exception Lifecycle, Balanced Suspense and Financial Period Close

**Status: PROPOSED / NOT RATIFIED — PRODUCTION NO-GO.**
**Parent:** Wave34.16 draft PR #49 at exact SHA \`5107c7fdd1e3528a969498c7bf47574190ef3195\`; successful exact-commit GitHub Actions #42703462410: Java 11/17 each 430/430 full JUnit methods in 122 classes and 151/151 independently selected SECURITY methods in 64 classes. All existing PRs remain draft/unmerged.

## Problem
Wave34.16 showed that three test-generated provider, bank and GL feeds could be reconciled to the same amount (100 gross – 3 fees = 97 net) before a simulated maker/checker review. It did not describe the entire lifecycle of a **failed reconciliation**, its reversible suspense position, independent exception resolution, or how a financial period should reject closure when evidence or an amount remains unresolved.

Wave34.17 models the accounting control boundary in **test-only, random PostgreSQL16 schemas** (\`w3417_...\`) after the unchanged application schema v5. A test-only exception record, paired suspense journal, resolution review, financial period and signed close review are transactionally linked. Neither an actual GL nor payment table is changed.

## State and settlement control model

- **Period:** \`OPEN → CLOSED\` only in the test. Once closed, a trigger denies reopening and blocks new exceptions or suspense events. It does NOT represent regulatory book-close authority, production role security or a right to change the finance period.
- **Exception:** \`OPEN → RESOLVED\` after authenticated *synthetic* variance evidence and exact maker/checker resolution review. A provider/bank/GL discrepancy may never be silently recoded into a successful settlement or made to disappear merely to close the period.
- **Suspense HOLD:** append event and two illustrative balanced journal lines (DR 1390 Synthetic suspense receivable; CR 1990 Synthetic suspense offset) for the positive variance. The event and both lines commit or roll back together; one signed source reference may not be duplicated.
- **Suspense RELEASE:** following a synthetic approved resolution, append the inverse two lines (DR 1990 / CR 1390) for the exact amount. Existing event/line records are never edited. There is at most one HOLD and one RELEASE per exception; event type/ID uniqueness and approval uniqueness prevent double-posting.
- **Period CLOSE:** only with a matching exact-balance snapshot digest, two distinct maker and checker test approvals, zero unresolved exceptions and **zero outstanding suspense**. PostgreSQL \`DEFERRABLE INITIALLY DEFERRED\` close and posting triggers reject incomplete/unbalanced sources at COMMIT, preventing a partial close.
- **Historical correction policy:** For production, any reopened accounting period, post-close correction or prior-period adjustment requires a separately authorized accounting/legal policy, retained prior-period evidence and explicit reason codes; this test *does not* implement reopening.

## Test-only ledger illustration

| Operation | Debit | Credit | Amount (synthetic minor units) | Suspense outstanding |
| --- | --- | --- | ---: | ---: |
| Open exception (HOLD) | 1390 Synthetic suspense receivable | 1990 Synthetic suspense offset | 40 | +40 |
| Approved resolution (RELEASE) | 1990 Synthetic suspense offset | 1390 Synthetic suspense receivable | 40 | 0 |

The two journal events produce total test debits 80 and total test credits 80. They represent **only** a demonstration of equal opposite book entries, not approved GAAP accounting, a validated source valuation, a provision for a receivable, merchant settlement, legal entity accounting, cash movement, or a genuine financial exception decision.

The bank/provider source evidence and the maker and checker P-256 signing keys are all created **inside one JUnit process**. These are three differently keyed test claims, *not* independently operated external systems or finance actors. The CI PostgreSQL owner has elevated privileges and could disable the test trigger. No production WORM, immutability or independently verifiable authority is established.

## Database protections (disposable PG16)

1. Unique \`exception_id\` and \`source_ref\`; unique suspense \`event_id\` and per-exception \`kind\`; independent approval UUID uniqueness for resolution and close reviews.
2. Each suspense event has two equal, positive, same-currency posting lines with explicit permitted debit/credit account placement. A deferred constraint rejects incomplete or unbalanced pairs (SQLSTATE 23514).
3. Row locks on the period and exception serialize valid replays; a concurrently submitted identical maker/checker resolution returns the original exception ID with only one RELEASE.
4. A deferred period-close gate rejects unresolved exceptions, unmatched HOLD/RELEASE amounts and absent/mismatched close-review evidence with SQLSTATE 23514, even when a privileged test script tries to mark an exception RESOLVED without release.
5. Test triggers reject UPDATE/DELETE on suspense events/lines and close reviews (SQLSTATE 55000); the closed-period trigger rejects further exception events. Privileged DDL and superuser privileges still bypass these protections.
6. No changes to \`src/main\`, schema v5, the real payment processor, prior synthetic SQL files, protected \`master\` or any external financial/merchant systems.

## Four acceptance cases

| Case | Named Java method | Required bounded evidence |
| --- | --- | --- |
| W3417-01 | exceptionHoldRequiresBalancedReleaseAndDualReviewBeforePeriodClose | Open exception creates positive suspense; premature close rejected; exact maker/checker RELEASE makes suspense zero; close succeeds, subsequent writes/reopen denied |
| W3417-02 | forgedSourceExpiredReviewerSelfApprovalAndChangedClaimsRemainOnHold | Tampered variance, forged source, self-approval, expired checker or wrong digest cannot release suspense; valid review works |
| W3417-03 | concurrentIdenticalReleasePostsOnceAndApprovalCannotCrossExceptions | Four concurrent same-review calls append one RELEASE, reused approval across another exception denied and rolled back, fresh review resolves and close succeeds |
| W3417-04 | deferredCloseAndUnbalancedSuspenseInjectionRollbackWithoutPartialRecord | Direct close without review, one-legged suspense and fake RESOLVED without release fail at SQL COMMIT; controlled release + close works |

Expected test roster: **434 methods / 123 classes** on both Java 11 and Java 17, plus **151/64 SECURITY-tagged** regression independently on each. Four synthetic manifests contain exact source head, exception status, suspense balance, debit and credit totals, legacy payment row count 0, SHA-256 digest, and **false flags** for independent source, finance approval and production period close.

## Remaining release blockers
- Trusted external provider/bank/GL sources, custody of raw evidence, statement coverage, ledger cutoff and currency/FX/tax/fee/suspense policy; provider PVA-01–16 tests are **NOT RUN**.
- Human maker/checker delegation, distinct IdP identities, multi-person authorization, approval thresholds, revocation, legal entity ownership, audit retention, and Finance Controller approval are **NOT IMPLEMENTED**.
- Production double-entry GL/exception queue, immutable append-only storage, locked periods, legal post-close adjustment protocol, outbox, backup/restore, quorum fencing and measured RPO/RTO are absent.
- Legacy payment ID allocator \`PAY-999999\` versus \`PAY-1000000\` defect and unkeyed \`PaymentProcessor.record\` public API remain unremediated.
- Source rights, architect/DBA/AppSec/Finance/QA/SRE/legal/owner signoffs and historical Stage27 14-gate vs Phase16 17/12 registers remain separate and unresolved.

**Decision: PROPOSED REHEARSAL ONLY / PRODUCTION NO-GO.** No merge, deployment, real payment, settlement close, period close, new production DB migration or finance approval is authorized by this milestone.
