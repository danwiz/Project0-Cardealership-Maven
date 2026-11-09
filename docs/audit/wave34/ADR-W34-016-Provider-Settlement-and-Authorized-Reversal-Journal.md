# ADR-W34-016 — Provider Settlement State, Reversal Authorization and Append-Only Journal

**Status: PROPOSED / NOT APPROVED. Production release NO-GO.**
**Immutable parent:** Wave34.13 draft PR #46 at \`bf4c4a1d6632c374750bf7949d3a5c19ca4ad3c5\`, successful GitHub Actions run #42691558723 (418/418 full tests in 119 classes and 151/151 SECURITY-tagged methods in 64 classes on each of Java 11 and 17).

## Problem and boundaries

Wave34.13 verified simulated merchant-state recovery and one local capture posting, but did not model the financial impact of settlement, partial refunds or chargebacks, whether reversal events are authentic and authorized, or how multiple events are ordered and reconciled.

Wave34.14 introduces a **separate, disposable PostgreSQL16 settlement and reversal journal**. It deliberately does **not** mutate the legacy payment plan or application \`payment_transactions\` tables. Existing schema version 5 remains the only application schema. The new tables live only in randomized \`w3414_...\` CI schemas, outside the application migrator.

The provider events are signed by a fake provider key generated in one JUnit JVM. For a reversal event, a **second test-generated signing key** supplies a matching finance-approval proof. Both keys, policies and claims are controlled entirely by the test. These are NOT independent merchant or finance authorities; no real refund, dispute, chargeback, settlement or financial authorization occurs.

## Provider event and state model (review-only)

| Incoming event | Required prior state | Illustrative local journal delta | New state |
| --- | --- | --- | --- |
| CAPTURED, sequence 1 | No charge exists | +100 synthetic minor units | CAPTURED |
| SETTLED, next sequence | CAPTURED, unsettled | 0 (settlement marker only) | SETTLED |
| REFUNDED, next sequence | SETTLED, remaining amount > 0, valid simulated finance approval | -R, R <= remaining synthetic captured units | PARTIALLY_REVERSED or REVERSED |
| CHARGEBACK, next sequence | SETTLED, remaining amount > 0, valid simulated finance approval | -C, C <= remaining synthetic captured units | PARTIALLY_REVERSED or REVERSED |
| Missing, PENDING, UNKNOWN, DECLINED, DISPUTED, untrusted or out-of-order event | Any | None | HOLD — no new journal |
| Replay identical accepted event ID and signed claims | Existing committed event | None | Return existing event result |
| Conflicting event ID, event sequence, finance approval ID or over-reversal | Any | None | HOLD or unique-constraint rollback |

The +100/-40/-60 example is an **illustrative net-position projection**, not a GAAP balance sheet, double-entry journal, approved money-unit mapping, real cash settlement or contractual receivable. It does not model merchant processor fees, taxes, reserve funds, dispute fees, partial settlement, multiple capture objects, multiple merchant accounts, or real provider correction/adjustment flows. Finance must separately approve all postings and actual accounting semantics.

## Data and transaction boundaries

- \`w3414_charge_state\` has a composite provider/merchant/charge identity, monotonic \`last_sequence\`, original synthetic capture, \`reversed_minor\`, settlement flag and current state. It enforces reversed <= captured.
- \`w3414_provider_events\` is unique on provider+merchant+event ID and on provider+merchant+charge+sequence. It stores the signed synthetic event digest and any matching test approval ID.
- \`w3414_reversal_journal\` is unique per provider event and per non-null approval ID; it carries an immutable signed delta and entry type. A test trigger blocks UPDATE/DELETE with SQLSTATE 55000. **A privileged DBA can disable/drop the trigger**, so this is not production-grade tamper resistance.
- A single PostgreSQL transaction locks charge state \`FOR UPDATE\`, checks exact replay identity/sequence and remaining amount, appends the event, updates the state and inserts the journal entry. Unique conflicts, foreign-key errors or business holds roll the transaction back; a client reconnect cannot treat a failed journal entry as posted.
- The provider and finance signatures are verified in Java test code **before** opening the transaction. A privileged SQL caller can bypass the Java verifier. No non-superuser verifier-to-DB enforcement is implemented in this wave.

## Four synthetic acceptance cases

| ID | Test method | Claim bounded by the test |
| --- | --- | --- |
| W3414-01 | settledCapturePartialRefundAndChargebackCreateBalancedAppendOnlyJournal | +100 captured, 0 settled, -40 refunded, -60 chargeback -> synthetic net zero; attempted journal update/delete denied |
| W3414-02 | missingOutOfOrderAndOverReversalEventsRemainOnFinancialHold | Missing capture, refund before settlement, sequence gap, PENDING/DISPUTED and over-reversal blocked with preserved state |
| W3414-03 | forgedEventExpiredApprovalAndReusedAuthorizationCannotPost | Provider signature/amount/currency/ownership tampering and wrong, expired or reused finance proof rejected; exact replay is idempotent |
| W3414-04 | simultaneousReplayCommitsOnceAndFailedJournalFKRollsBackState | Four concurrent duplicate refund events create one journal entry; injected FK failure rolls back event/state; valid retry succeeds |

Evidence manifests contain synthetic journal counts/net, a SHA-256 digest, exact source head and all production-trust flags set false. They do not contain private keys, customer data, real receipts or approval credentials.

## Unresolved policy and engineering gates

1. **Real provider contract:** independently authenticated merchant accounts, authoritative capture/settlement/refund/dispute objects, signature verification, webhook replay controls, polling, pagination, key rotation/revocation and signed event provenance.
2. **Finance authorization:** explicit legal entity, account ownership, balance/suspense policies, double-entry debit and credit accounts, real currency minor units and decimal precision, fees, tax, FX, partial captures, refunds and chargeback accounting, finance signoffs.
3. **Immutability and controls:** non-superuser append-only owner, independently verifiable ledger evidence/WORM custody, tamper-resistant key management, transactional outbox and separation of duties.
4. **Lifecycle and HA:** real provider ACK loss, event reorder/dedupe under partition, delayed correction, disputes and settlement cutoffs, verified backups and log LSN, stale-primary fencing and RPO/RTO.
5. **Legacy correctness:** \`PAY-999999\` → \`PAY-1000000\` lexicographic allocator defect; unkeyed \`PaymentProcessor.record\`; schema migration design and source-rights independent reviews.
6. **Governance:** historical Stage27 14-gate and Phase16 17/12 register reconciliation, PVA-01..16 real-provider acceptance still NOT RUN, and independent DBA, AppSec, QA, SRE, finance/payments, legal and owner release approvals pending.

**Decision: PROPOSED ONLY. Green CI demonstrates the stated isolated test properties, not a production settlement/reversal implementation or independent finance authorization. NO-GO.**
