# ADR-W34-004 — Ledger identity, monetary semantics, and production migration sequencing

**Status:** PROPOSED, NOT APPROVED. **NO-GO.**
**Source:** JdbcPaymentProcessor.java and JdbcDatabase.java in Wave34.2 immutable head c369e22a5dd87831286bf821bf7f59e30a9a7311. Current persistent schema is v5. No production schema change is made.

## Source-observed hazards and controls
| Source behavior | Risk / uncertainty | Preferred solution for OWNER/DBA review |
| --- | --- | --- |
| \`nextGlobalSequence\` selects lexicographically latest \`payment_transactions.transaction_id\` with \`FOR UPDATE\`, then adds one | Empty-table callers cannot lock a non-existent last row; concurrent first or parallel customers can contend for same ID. Sequence may not be durable across separate writes. | Database-owned monotonic identity/sequence or UUID/ULID with explicit unique and stable external reference; synthetic concurrent first-writer and retry tests. |
| \`PAY-%06d\` with \`parseSequence\` matching exactly six digits | >999999 boundary is not handled by parser; formatting width is minimum width, not maximum. | Versioned ID contract preserving legacy identifiers and non-breaking API mapping; choose overflow strategy. |
| Balance arithmetic, cumulative paid and original obligation use Java \`int\` | Potential overflow; unit/currency not persisted in existing plan/ledger rows. | Approved signed 64-bit minor-unit or DECIMAL money design, overflow-exact checks, per-currency precision/rounding rules; **no assumed cents**. |
| Ledger's latest payment is selected by recorded_at then transaction ID | Timestamp ordering and migration/backfill may not represent canonical entry order across multiple plans; new ownership after earlier payment can alter obligation basis. | Stable ledger event sequence, append-only events, per-ownership balances, independent reconciliation and explicit projection rules. |
| Existing \`PaymentProcessor.record\` has no provider request ID | Lost external response can double-post if caller retries a local payment. | Separate verified provider-authority adapter + uniqueness + atomic receipt/ledger/outbox; do not silently change legacy API. |

These are source-derived risk hypotheses unless a corresponding failure has been reproduced. Wave32 already characterized the missing request idempotency behavior. A passing Wave34.2 synthetic PG16 test does not resolve all hazards.

## Suggested incremental migration (planning only)
M0. Source custody, rights, API/financial-policy approval; freeze exact baseline. Document database backup/restore and owner-approved RPO/RTO.
M1. Approve v6 or later revision, provider contract and money-unit schema. Staging-only additive tables with FKs, check constraints and provider uniqueness; independent SQL role/grant review.
M2. Introduce DB-owned transaction identity for **new** events while preserving historical \`PAY-000001\` IDs and external references; verify 0, first concurrent, 999999 boundary, overflow, failover and rollback.
M3. Dual-observe: compare old ledger and new append-only postings from immutable, authenticated evidence; idempotent reconciliation without dual-charging. Never automatically backfill from untrusted external text.
M4. Verify audit/outbox dedupe, currency/accounting, rollback/forward-fix, backup/restore, HA fencing and independent review in disposable PostgreSQL16.
M5. Only after explicit release governance approval: plan operator-controlled migration with DDL principal separate from application, canary and rollback/forward-fix policy. The runtime must remain **DDL-free**, as in current schema-readiness controls.

## Required migration gates
- Approved schema DDL plus up/down or forward-only rollback plan; verified backup and restore; fresh operator-only migration principal; grants deny ordinary application DDL.
- Referential and uniqueness constraints; signed event identity; changes to money accounting independently reviewed with finance/payments owner.
- Liquidity/chargeback/refund netting cannot be inferred from \`amount_paid\` without explicit business policy.
- Data integrity invariants before/after migration, independently calculated totals, no orphan receipts, payment projections/reports consistent.
- Graceful fallback when a provider committed but app lost ACK, no duplicate funds or ledger entries.
- Legacy payment command compatibility/disablement decision, no hidden behavior change.

**Decision:** OPEN. The Wave34.1 \`wave34_candidate_additive_receipt.sql\` is a test-only schema candidate, not a migration file. Do not increment \`CURRENT_SCHEMA_VERSION\` or apply to a live DB.