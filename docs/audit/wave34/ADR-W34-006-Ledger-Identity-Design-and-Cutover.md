# ADR-W34-006 — Durable ledger identity migration and controlled cutover

**Decision state: PROPOSED / AWAITING OWNER, DBA, PAYMENTS AND RELEASE REVIEW. Release: NO-GO.**
**Baseline**: Wave34.3 PR #36, SHA 946fdaf2362aadff3e4dc38db36a253040242913. The original public PaymentProcessor.record() remains unchanged. Existing schema v5 and protected branches must remain untouched. This ADR is not an executable migration.

## Exact source-derived observations
- JdbcPaymentProcessor.nextGlobalSequence uses SELECT transaction_id FROM payment_transactions ORDER BY transaction_id DESC LIMIT 1 FOR UPDATE. If no row exists, no existing transaction ID row can be locked; simultaneous first writers may both derive PAY-000001. The outcome of actual writes can involve SQL serialization/uniqueness errors and must not be mistaken for an authenticated provider-charge retry.
- String.format("PAY-%06d", nextSequence) has **minimum** six-digit width, whereas parseSequence requires PAY- followed by **exactly** six digits. After creation of PAY-1000000, the subsequent allocator call cannot parse the latest ID.
- The current ledger uses Java int for payment amount and cumulative balance. It does not independently encode a currency and approved minor-unit policy.
- The Wave34.2 candidate uses a guarded, temporary test-only receipt scheme. It does not introduce production ledger IDs, a real merchant API or a migration.

## Wave34.4 characterization (expected behavior of unmodified code)
| Test | Technique | Expected observation | Interpretation |
| --- | --- | --- | --- |
| Six-to-seven digit boundary | H2 schema v5 fixture seeds PAY-999999; actual JdbcPaymentProcessor.record called twice | First generates PAY-1000000; second throws IllegalArgumentException; rollback retains prior payment/balance | CONFIRMED BUG if executable JUnit passes. No product fix delivered |
| Concurrent first-entry read | Real PG16, two SERIALIZABLE connections issue exact legacy latest-ID SELECT FOR UPDATE on an empty ledger | Both see no existing row | CONFIRMED ABSENCE OF LOCKED ID ROW, **not** proof both can commit a duplicate charge |
| Sequence alternative | Disposable PG16 sequence, 4 workers * 8 nextval calls, no payment records | 32 distinct IDs, no payment transaction | DESIGN FEASIBILITY ONLY; gaps from rollbacks are normal and allowed |

## Proposed technical decision (not approved)
Adopt a **database-owned BIGINT GENERATED ALWAYS AS IDENTITY or sequence** for a *new* ledger entry key and immutable provider transaction reference. Do not reuse the legacy display string as global allocation authority. Preserve existing PAY-000001 etc as immutable historical display identifiers. Prefer a new versioned public reference if external clients depend on six-digit formatting; **no renumbering of historical rows**. Keep a unique immutable provider-origin key and provider transaction key, ensure all side-effects commit in the same SQL transaction and retry only local SERIALIZABLE work. Reconciliation must never blindly re-issue an external charge.

Candidate alternatives:
- **PG BIGINT IDENTITY / sequence (preferred technical prototype)**: durable uniqueness under concurrency, no need to lock latest row; not contiguous, values may be skipped after abort/failover and should never be interpreted as a business counter.
- **UUID/ULID**: distributed collision-resistant identifier, lexicographic properties vary by format, suitable for public opaque IDs with additional validation and privacy considerations; requires data-model/consumer compatibility study.
- **Legacy formatted PAY-###### allocator (do not extend)**: incompatible six-digit parse boundary and first-writer gap, requires redesign.

## Forward-only migration phases — DESIGN ONLY
D0. Source ownership/rights, finance unit-policy, PCI/privacy, provider contract and independent AppSec review.
D1. DBA design: identity column/sequence, constraints, non-DDL runtime principal, legacy nullable mapping, outage budget, backups, grants, migration/rollback/forward-fix.
D2. Disposable migration rehearsal: empty and non-empty ledgers, overlapping transactions, 999999+ format edge, imported legacy IDs, link integrity and exact monetary reconciliation.
D3. Two-way audit comparison: before/after counts, amounts and provably signed provider event references; no user-visible dual charging and no mutable rewriting of settled financial history.
D4. Staging cutover: issue *new* opaque references from DB identity, preserve old references for reads. Define response behavior for old APIs and invalid legacy IDs, idempotent outbox and rollback point.
D5. Only after owner/release policy signed and all Stage27 gates verified: authorized operator migration, quarantine on unreconciled mismatch, monitored canary and recovery exercise.

## Non-negotiable acceptance / failure signals
No duplicate local postings for one independently authenticated provider event; no false deduplication of separate legitimate installments; no stale-primary writes after failover; no ignored currency mismatch; no production app DDL; no silent monetary overflow; no lost rollback evidence; no disclosure of original coursework data; independent reviewer approvals retained as source-linked proof. GO status remains **NO-GO** until approvals and production-class validation exist.

**No migration v6 and no payment ID fix is implemented by Wave34.4.**