# Wave32 — External Payment Receipt Integrity, Failover and Identity/Fencing Backlog

**Baseline:** Wave31 draft PR #31 at `66126e9f7b523162fe74edbc21232bbe80736161`.  
**Mode:** Review-only draft. Disposable Docker PostgreSQL16 and synthetic test data only.  
**Release:** NO-GO. All fourteen Stage27 release gates remain formally NOT CLOSED.

## Priority B — Independent processor receipt reconciliation (implemented as test fixture)

**Problem identified in inspected source:** `JdbcPaymentProcessor.record(customerName, ownershipId, amount)` does not accept or persist a payment-processor idempotency key. It generates a new local `PAY-000001` style transaction identifier. After an external processor commits a charge but the application loses the processor acknowledgement, reissuing an independent charge/payment call is unsafe without reliable reconciliation.

Wave32 adds a **test-only**, explicitly named `Wave32IndependentPaymentFixture` and a physical PostgreSQL16 failover workflow with **three distinct disposable PostgreSQL16 containers**:

1. Dealership primary seeded with real schema v5, an accepted customer vehicle purchase and a payment plan.
2. Streaming physical standby of the dealership primary.
3. Separately administered synthetic processor database containing `processor_charges`; not part of the dealership primary/standby physical replication group.

An immutable processor request ID and provider transaction ID bind the charge, customer, contract ID and amount. The processor commits exactly one synthetic charge. The fixture **suppresses its application-level success notification** after the processor COMMIT, so the dealership has not yet posted the payment.

The dealership primary is fenced and its physical standby promoted while the independent processor remains running and retains the committed charge. A new application query uses a SELECT-only processor identity to retrieve the completed charge and validate its identity/amount/contract. An owner-controlled **test-only local receipt register** has a unique provider request key; within a **single dealership SQL transaction**, the fixture inserts the receipt binding, reduces the real existing payment-plan balance and creates the linked payment-transaction row. Retrying the same provider request key detects the stored receipt and does **not** update the plan or insert another payment.

The lab requires:
- Provider charge count exactly 1 even after dealership primary dies.
- No local payment prior to failover/reconciliation.
- After promoted-standby reconciliation: exactly one local receipt, one `PAY-000001` transaction for 1,250, plan balance 22,750 from 24,000 and amount paid 1,250.
- A second replay leaves all balances and record counts unchanged.
- The provider receipt reader cannot update the provider database.
- One accepted dealership sale, one vehicle ownership and one payment plan remain linked.

**Strict limitations:** No real payment provider, real funds, PCI data, merchant API, provider digital signature, mTLS provider identity, refunds, chargebacks, delayed processor settlement, multiple installment sequences or cross-region storage is used. The provider ACK loss is a *simulated application-level acknowledgment suppression*, not a physically intercepted TLS response. The local receipt table and posting implementation are **test-only**, not deployed migrations or wired into public payment commands. The local sample uses one fixed contract/request/transaction and cannot establish complete production idempotency.

The intended architecture is to introduce an immutable, authenticated provider event ID into an **approved** production reconciliation schema, atomic posting/outbox and provider settlement state machine. Before any deployment, DBA/AppSec/payment operations must review provider signature/key identity, duplicate event collisions, raw transaction ID allocation, external settlement checks, race/timeout handling, audit logs, refunds, payment reconciliation and rollback. Do **not** treat a local `PAY` ID as a payment-provider charge ID.

## Priority A — Independently authenticated credential issuer (still OPEN)

Wave31 proved a distinct PostgreSQL receipt-issuer SQL role and owner-controlled function for registering a validated, pre-cutover signed intent. Wave26 separately proved a disposable mTLS broker with pinned client certificate in a child JVM. **These do not yet compose into a production-verified authenticated issuance service.**

Required next: independently authenticated issuer service identity (mTLS/SPIFFE/workload identity), verified user authentication event reference, short-lived purpose-bound key and context, separately owned KMS secret, authenticated API, fail-closed key/version/nonce rotation, rate limiting and security audit. Test issuer+executor, signer+issuer, cert substitution, network/host compromise and schema-owner compromise. An SQL role alone is not a proof of authenticated service or customer.

## Priority C — Real fencing, quorum and client router (still OPEN)

Wave32 payment lab explicitly stops/fences the old primary **before** promotion, but Docker kill is not a quorum witness or production STONITH. Client router and write rejection from an unreachable/returning former primary remain untested. Plan independent witness epoch/lease, fail-closed write routes, stale-primary quarantine/read-only rejoin, client retries, WAL lag and RPO/RTO measurement. Do not claim any production failover guarantee from this lab.

## Priorities D/E — Rights and release gates

The Wave31 inherited Java11/17 regression baseline is **374 full and 145 SECURITY test methods per JDK**. Wave32 adds a standalone test-only executable, not a JUnit method. The normal inherited regression must still preserve every prior method identity.

Complete Maven build-plugin/transitive SCA, original educational source rights/NOTICE and quarantined legacy serialized-data migration. Assign named independent AppSec, DBA, QA, architect, finance/payment operations, legal/source-rights and release-owner approvals. Numerical business RPO/RTO objectives remain owner decisions, not inferred from a successful test.

**All 14 formal Stage27 release gates stay NOT CLOSED — NO-GO.**
