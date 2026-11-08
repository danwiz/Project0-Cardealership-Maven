# Wave 34.2 — Atomic Synthetic Provider-Receipt Posting: Engineering Assessment

**Status:** DRAFT / REVIEW ONLY / NO-GO. Stacked after Wave 34.1 PR #34 at exact baseline `23ea92f173b494f51aee67c12471b353a3b83582`. No merge, deployment, production migration, external payment, live funds or real key provisioning.

## Specific software engineering increment

1. Reuse **unchanged** legacy public `PaymentProcessor.record(customer, ownershipId, amount)` for historical contract compatibility. A new **package-scoped, environment-gated** `recordWithinCandidateTransaction` calls the original private plan/ledger calculation **inside the caller's single SQL transaction** without opening a second transaction or committing.
2. New `JdbcWave34PaymentReconciliationCandidate` is isolated from business entry points and is **CI-only**. It requires `CI=true`, `GITHUB_ACTIONS=true`, `W34_DISPOSABLE_ONLY=true`, the exact local disposable database URL, and a per-test `w34_` schema. This implementation is NOT a production-grade or exposed payment API.
3. An injected **synthetic receipt verifier** rejects invalid HMAC BEFORE local SQL writes. The verifier uses the unchanged Wave 33 test-only signing class. It pins provider `synthetic` and currency `USD` because Wave 33's test-only signature does not cryptographically bind provider namespace, ownership ID, or currency. This **does not establish a real provider trust domain**.
4. PostgreSQL16 SERIALIZABLE **single transaction** inserts a provider-identity receipt, checks ownership and contract, locks the existing payment plan/ledger, writes the existing payment transaction, binds the local transaction ID and commits. Any failed validation or payment rolls back the newly claimed receipt and balance update.
5. Unique keys `(provider_code, provider_request_id)` and `(provider_code,provider_transaction_id)` remain in the **review-only additive SQL** of Wave 34.1. Duplicate request with identical digest returns the original local payment; conflicting digest is a SecurityException; alternate request with reused processor transaction ID is a fail-closed security conflict. SQL uses untargeted ON CONFLICT DO NOTHING so either unique key can win under concurrent insertion, followed by a mandatory exact-key/digest comparison; a different request sharing an existing provider transaction has no matching request row and is denied. SERIALIZABLE/deadlock retries re-run the **local DB transaction** a bounded number of times, never the external provider charge.
6. Reuses the legacy payment ID allocator `PAY-...` only inside the disposable lab. This allocator's production concurrency and sequence-overflow behavior still requires independent correction before release.

## Explicit real PostgreSQL 16 acceptance

- Two simultaneously started calls for the **same signed provider request**: one new local payment, one replay of its ID, one receipt, one payment plan and balance change from synthetic 24,000 to 22,750 after 1,250 posting.
- A later exact retry remains no-op.
- Same request with **validly signed changed amount** rejects without a balance mutation.
- Tampered signature fails before SQL writing; signed wrong customer/ownership refuses binding.
- Excessive payment amount fails and rolls back the provisional receipt, payment and plan changes.
- Two distinct requests of equal amount result in two legitimate postings only in the synthetic lab: 24,000 - 2(1,250) = 21,500. The policy for real business installment authorization remains UNAPPROVED.
- The same external provider transaction ID cannot be attached to a different request key; uniqueness violation is expected and reported without posting.
- Java 11 and 17, all 8 Wave 34.1+34.2 named methods actually executed and passed (not skipped). A full-solution Wave33 376-case regression remains separate and must be re-run for release candidate review.

## Security / operations review gaps still OPEN

- Independent externally operated provider verification (mTLS/merchant API, signed webhooks with full-field canonicalization, anti-replay, key rotation/revocation), provider-versus-app compromise boundary, real provider event lifecycle.
- Strong payment authorization, account/session/ownership actor proof, no real financial records/card data; real provider settlement or reconciliation state machine.
- Money units (legacy amount is integer and must NOT be silently treated as USD cents), approved currency/rounding, multiple plans and cross-customer allocation.
- Approved schema v6 or other migration, SQL grants, DDL-free deployment, failover recovery, outbox, audit retention and rollback runbook.
- Refunds, partial refunds, chargebacks, delayed settlement, race conditions between refund and capture and financial controls.
- Failover quorum/old-leader fencing, real client routing, operator-approved measured RPO/RTO, independent DBA/AppSec/QA/payments/legal and original academic source rights.
- Production payment API `record(...)` still has no provider idempotency key. This is a **reviewable prototype**, not closure of that finding.

**Stage27 14/14 gates:** still formally OPEN per historical Wave33 evidence. All work must remain a draft unmerged stack, isolated and NO-GO.

## Wave34.2 exact-SHA CI triage correction (2026-11-08)

The first CI run failed in Java11 and Java17: a signed wrong-customer claim hit PostgreSQL FK enforcement before the intended authorization rejection, and concurrent identical claims could select the other unique index. The candidate now verifies local ownership/contract **before any receipt insert** and performs a database-agnostic conflict decision across both unique constraints using `ON CONFLICT DO NOTHING` plus an exact immutable receipt lookup. All prior failed logs must be kept as engineering history; the final head must pass its own new jobs. This is still a synthetic CI candidate and does not close the unkeyed production API blocker.
