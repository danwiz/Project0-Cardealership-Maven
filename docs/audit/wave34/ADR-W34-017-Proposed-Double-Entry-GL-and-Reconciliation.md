# ADR-W34-017 — Proposed Double-Entry GL, Monetary Policy and Reconciliation

**State: PROPOSED, UNSIGNED, NOT GAAP-RATIFIED. Production NO-GO.**
**Parent source:** Wave34.14 draft PR #47 at \`eb4d1de97a940441da1d3b1a0dbb292648c9487a\`; exact-head Actions #42696469679 PASS: Java 11 and 17 each 422/422 full tests across 120 classes, plus 151/151 SECURITY-tagged across 64 classes.

## Problem and boundary

Wave34.14 demonstrated synthetic capture, settlement, refund and chargeback transitions with single signed amount deltas (+100/0/-40/-60). **A one-column delta journal is not double-entry accounting.** The finance team must approve a chart of accounts, debit/credit recognition events, money units, settlement authority, tax/fees, reconciliation, reversals, cash controls and GL interfaces before any production financial change.

Wave34.15 therefore uses **three illustrative asset accounts, 2 rows per synthetic event, and a deferred PostgreSQL16 balance constraint** in a new random \`w3415_...\` test schema. It does not change existing payment transaction or payment-plan tables, schema v5 migrator or any persistent database. Both simulated provider signer and finance signer are generated in one test JVM. The CI database owner can bypass all triggers: the lab does not establish financial authorization, audited accounting policy, independent custody or production immutability.

## Proposed chart of accounts — test illustration only

| Test COA code | Account | Nature | Example debit | Example credit |
| --- | --- | --- | --- | --- |
| 1000 | Bank cash | Asset | Received settlement | Paid refund/chargeback |
| 1100 | Processor clearing | Asset | Captured charge | Settled transfer |
| 1200 | Customer receivable | Asset | Refund/chargeback restores receivable | Captured payment reduces receivable |

This model is a **net-change illustration against a hypothetical prior receivable**. It does not create/validate an original customer receivable, recognize revenue, represent payment authorization/capture/settlement as necessarily final, or account for taxes, exchange rates, fees, disputed holds, deferred revenue or chargeback reserve. An independent Controller/Finance owner must select and ratify real accounts and accounting treatment; no user or financial-policy approval is inferred.

## Four sample postings and reconciliation

Amounts below are **synthetic integer minor units in one test currency** and are NOT a conversion from legacy \`int amount\`.

| Event | Debit | Credit | Synthetic amount |
| --- | --- | --- | ---: |
| CAPTURED | 1100 Processor clearing | 1200 Customer receivable | 100 |
| SETTLED | 1000 Bank cash | 1100 Processor clearing | 100 |
| REFUNDED | 1200 Customer receivable | 1000 Bank cash | 40 |
| CHARGEBACK | 1200 Customer receivable | 1000 Bank cash | 60 |

Total debit postings = 300, total credit postings = 300. Illustrative net movements after all four events: cash 0, clearing 0, receivable 0. This net zero is **not evidence that real accounts were reconciled**, nor proof of merchant settlement or business profitability.

## SQL control design

- \`w3415_gl_accounts\` restricts synthetic accounts to three USD asset codes.
- \`w3415_gl_events\` enforces unique event ID, provider event ID, monotonic sequence, supported event kind, positive amount, test currency and separate finance approval ID for a reversal.
- \`w3415_gl_lines\` requires exactly two posting lines (one debit, one credit in the lab), account references, nonzero amount and same currency.
- A PostgreSQL \`DEFERRABLE INITIALLY DEFERRED\` constraint trigger rejects incomplete/unbalanced/currency-mismatched entries **at SQL COMMIT** with SQLSTATE 23514. All event/line writes form one transaction. Deleting or rewriting posted synthetic lines/events is blocked with SQLSTATE 55000 by disposable test triggers, but privileged DDL can bypass them.
- Same signed event replay returns the original event ID after matching event type, amount, sequence, digest and test approval identity. Concurrent same-event requests serialize via event uniqueness; a new event cannot reuse a finance approval UUID. These safeguards are local to one disposable PostgreSQL16 instance; real distributed HA/cross-region dedupe is untested.
- Proposed simulated financial HOLD policy: no settlement without capture; no refund/chargeback without settlement; no reversals in excess of synthetic available bank position; no invalid signer, expired/wrong approval, unsupported currency or reconciliation variance. These constraints are **not** an approved operating financial-control policy.
- No source code from the production payment processor or schema migrator is modified. Existing \`PAY-...\` allocator, public unkeyed API, original ledger and plan remain unchanged.

## Acceptance criteria with exact synthetic evidence

| ID | Test method | Expected scope-limited result |
| --- | --- | --- |
| W3415-01 | capturedSettledRefundedChargedBackProducesBalancedThreeAccountGL | Four events, eight GL lines, 300=300 debit/credit, illustrative net movements and v5 legacy unchanged |
| W3415-02 | deferredBalanceGateRejectsIncompleteUnbalancedAndCurrencyWrongEntries | Incomplete, unbalanced and currency-mismatched journal mutations fail at SQL constraint boundary; UPDATE/DELETE denied |
| W3415-03 | unapprovedOutOfOrderAndReconciliationVarianceRemainOnHold | Settlement before capture, refund before settlement, sequence gap, invalid approval, over-reversal and external position variance HOLD |
| W3415-04 | concurrentReplayCommitsOnlyOneBalancedEventAndApprovalCannotBeReused | Four concurrent identical reversal submissions produce one balanced event; approval UUID reuse conflicts; new valid event accepted |

All four tests produce key-free SHA-256 evidence and assert no legacy payment postings. CI should verify 426 full JUnit methods / 121 classes and 151 SECURITY-tagged / 64 classes on each JDK, without using a synthetic pass as a production authorization.

## Required production financial architecture — NOT IMPLEMENTED

1. Finance and legal entity chart of accounts: AR/AP/cash/merchant clearing/fees/tax/refunds/reserves/FX/revenue, opening balances and supporting contracts/rights.
2. Deterministic money: currency and ISO 4217 exponent handling, integer minor units vs decimal ledger representation, rounding, multi-currency and FX rates, merchant fees and chargeback liabilities.
3. Independent actual-provider transaction truth: capture vs settlement vs reversal; signed merchant events, status polling, out-of-order webhook retention, dispute rules, settlement batches, bank-deposit matching and authorization provenance.
4. Double-entry production integration: approved GL account mapping, an independently attested event/approval envelope, restricted write principal, append-only audit/WORM custody, commit-time balance validation and transactional outbox; no bypass by raw legacy payment APIs.
5. Controls and operations: maker-checker dual approval thresholds, reconciler roles, exception queues, immutable evidence chain, segregation of duties, reversal/correction entries (no historical overwrite), daily balances and close process.
6. Correct legacy \`PAY-999999\` to \`PAY-1000000\` allocator, approved versioned migration, source-rights/author attribution review, backup/restore, stale-primary fencing and separately measured RPO/RTO.
7. Named independent signoffs: finance Controller/payments owner, business owner, DBA, AppSec, QA, SRE and legal; real provider PVA-01..16 acceptance and Stage27 14-gate vs Phase16 17/12 evidence registers remain separate and unresolved.

**Decision: PROPOSED ONLY. A balanced synthetic two-line GL does not close any production release gate. NO-GO.**
