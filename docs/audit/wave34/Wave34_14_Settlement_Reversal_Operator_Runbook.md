# Wave34.14 — Settlement, Refund, Dispute and Reversal Ledger Review Runbook

**Status: PROPOSED / UNSIGNED. Production NO-GO.**
**Project:** danwiz/Project0-Cardealership-Maven; parent PR #46 at \`bf4c4a1d6632c374750bf7949d3a5c19ca4ad3c5\`.

## Incident/reconciliation decision protocol

Read the source-independent provider record using the original merchant/charge/event reference, verify provider and merchant authority, complete financial identity, authoritative status, event ordering and current charge balance **before** any production financial change. This wave does none of those external actions: the issuer and finance signer are both in-process Java test keypairs.

- **CAPTURED:** Create an approved capture journal only if the real provider verifies source and ownership, approved account/units and one unique event. Test example +100 synthetic units.
- **SETTLED:** A separate financial state transition after capture. Do not count settlement as another capture. Test example zero-delta settlement marker.
- **REFUNDED:** Requires matching merchant-confirmed refund event, independent approval, remaining refundable balance and correct account policy. Test example -40 units, not a real cash refund.
- **DISPUTED:** HOLD for case investigation. A dispute notice alone does not justify inventing a debit; fees/provisions require finance authority and independently verified event semantics.
- **CHARGEBACK:** Only on authoritative outcome and independently approved reversal policy. Test example -60 units, closing a synthetic 100-unit capture already partially refunded by 40.
- **Missing, outdated, reordered or conflicted:** HOLD; preserve raw evidence, never silently resequence or resubmit the original external charge.

## Execution rules for disposable test

GitHub Actions exact-commit CI and random \`w3414_...\` PostgreSQL16 schema only. Four named W3414 JUnit tests run after schema v5 bootstrap; all temporary journal/event/charge tables are dropped on teardown. Expected entire roster: **422 JUnit methods across 120 classes** on Java 11 and 17; inherited **151 SECURITY methods across 64 classes** independently on each JDK. Test event signatures and finance approvals use separate ephemeral P-256 keys within a single JVM. Neither key is a real trust root.

| Test | Required result |
| --- | --- |
| W3414-01 | Event state CAPTURED -> SETTLED -> PARTIALLY_REVERSED -> REVERSED; +100/0/-40/-60 synthetic net 0; UPDATE/DELETE denied by a disposable journal trigger |
| W3414-02 | Event gap, reversal before settlement, unmodeled status and amount greater than remaining cap all HOLD with no unauthorized journal |
| W3414-03 | Invalid provider or finance signature, currency/ownership/amount tamper, expired finance test approval and reused approval ID all denied; exact replay idempotent |
| W3414-04 | Four concurrent duplicate refund events append once; failed journal FK rolls back state and event; controlled same-event retry succeeds |

The \`target/wave34-14-evidence\` JSON contains only exact source SHA, synthetic counts, synthetic net, checksum, schema version and explicit nonproduction flags. \`payment_transactions\` remains zero rows, and the existing payment plan remains unchanged in each test. Production schema migrations, real money, any provider network or release GO are forbidden.

## Release gate assignments (all OPEN)

| Gate | Required evidence | Present |
| --- | --- | --- |
| SETTLE-01 | Independent actual provider signing and merchant API | NO |
| SETTLE-02 | Authorized production financial chart of accounts | NO |
| SETTLE-03 | Currency, precision, refunds and chargeback policy | NO |
| SETTLE-04 | Signed independent finance approval/dual control | NO |
| SETTLE-05 | Provider state transitions from genuine data | NO |
| SETTLE-06 | Durable event sequencing, idempotency and HA | SYNTHETIC ONLY |
| SETTLE-07 | Append-only independent journal custody/privileges | SYNTHETIC ONLY |
| SETTLE-08 | Transactional outbox and GL settlement reconciliation | NO |
| SETTLE-09 | Legacy PAY-ID fix and approved migration | NO |
| SETTLE-10 | External provider PVA-01..16 and disaster recovery | NO |
| SETTLE-11 | Independent financial, AppSec, DBA, QA, SRE reviews | PENDING |
| SETTLE-12 | Stage27 and Phase16 formal release disposition | NO-GO |

**An event-processing test cannot establish a financial accounting policy, merchant consent, real settlement, refund or legal release authority. Preserve previous draft PRs; no merge or deploy.**
