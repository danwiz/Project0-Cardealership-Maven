# Wave33 — Synthetic Signed Processor Receipts, Concurrent Posting, and Remaining HA/Identity Boundaries

**Basis:** Wave32 PR #32, exact source SHA `8b2c51e5d1ab88d3ab5b9ca2172e735ba17c5ea1`.  
**Change control:** Review-only isolated draft branch targeting Stage28 integration. No protected master, merge, release, deployment, production database, live payment processor, real monetary transactions, keys or cloud archive write.  
**Release:** NO-GO. All 14 Stage27 gates remain FORMALLY NOT CLOSED.

## A. Source-verified payment architecture gap

The existing `JdbcPaymentProcessor.record(customerName, ownershipId, amount)` does **not** accept or persist a provider's immutable idempotency key. Wave32's separate JUnit test verified that two local same-amount calls generate two different `PAY-...` rows and reduce the plan twice. The behavior can be legitimate for two different installments; it is unsafe as an automatic retry of **one** external processor charge when the acknowledgment was lost. This production API remains **unchanged** in Wave33.

Wave32's actual isolated PG16 three-database lab demonstrated a separate processor charge persisted through dealership primary failure and was posted once on promoted standby using a unique synthetic receipt request ID. It did not authenticate the independent processor's receipt cryptographically, and only tested sequential replay.

## B. Wave33 scoped implementation and acceptance

The new `Wave33SyntheticProviderSignature` class exists **only in test source**, with an explicitly synthetic fixed HMAC key. It constructs unambiguous, length-prefixed UTF-8 canonical bytes including a purpose domain, key ID, processor request ID, processor transaction ID, customer, contract, integer amount and status. Verification enforces an expected synthetic key ID, validates fields, and compares HMAC-SHA256 using `MessageDigest.isEqual`.

The new `@SECURITY` JUnit test exercises correct HMAC and rejects: changed customer, processor request ID, processor transaction ID, contract, amount, status, unknown key ID, tampered signature and malformed identifiers. The full CI guard requires this named test to run, pass and not be skipped.

A second isolated **real PostgreSQL16 physical primary+standby + independent third processor** workflow clones the Wave32 reconciliation model. The synthetic processor's committed charge now records its test-only signature and signing key ID. The SELECT-only reader fetches the receipt, Java verifies HMAC **before dealership SQL writes**, and the local unique receipt register stores provider ID, canonical evidence digest, key ID and MAC with the local payment ID.

After old dealership primary fencing and caught-up standby promotion, TWO concurrent JDBC calls are released together against the same provider request key. The database unique constraint plus one transaction for receipt, plan update and ledger append must yield **exactly one new local posting** and one duplicate/no-op; a further sequential replay also must be a no-op. Final obligations must remain one synthetic processor charge of 1,250, one local payment, one ownership, one plan, remaining balance 22,750 from 24,000.

The lab checks HMAC tampering by mutating the amount and by supplying an unknown key ID before any local posting. Its tests are synthetic and may not substitute for a real provider-signed webhook and authenticated merchant API.

**Limits:** There are no external funds, merchant payment network, provider signing certificate, KMS/HSM custody, key rotation/revocation, provider event lifecycle, webhook replay window, customer consent, PCI verification, chargeback/refund, actual response-packet interception or multiple-ownership accounting. This test-only HMAC signer and its shared CI key do NOT prove cryptographic independence of the provider from the dealership application. A real processor must own its key and deliver authenticated events through an independently verified channel. The ledger schema and posting transaction are **test fixtures**, not approved production migrations or payment features.

## C. Refunds and chargebacks — explicit unimplemented design

Refunds must not be modeled as deleting a historical payment row. Propose an append-only `provider_refund_events` relation keyed by immutable provider refund ID, linking the original charge, captured/refunded amount, currency, settlement state and reason. Require authenticated provider event signatures, explicit limits on total refunded, idempotent negative adjustment or accounting credit events, reconciled local plan/ledger state and owner-approved chargeback semantics. Verify simultaneous charge, partial refund, full refund, duplicate webhook and out-of-order events in real PG16 tests before production.

No refund implementation or production refund API is included in this PR. Financial settlement rules and consumer/legal handling are owner decisions.

## D. Credential issuer and physical HA boundaries — still open

Wave31 proved a separate SQL receipt-issuer principal, owner-controlled signing-intent registration and physical PG16 connected standby commits. Wave26 separately tested a disposable mTLS broker with a pinned client certificate. Neither combination is a **deployed, independently authenticated credential-issuance service**. Next review must design workload identity, mTLS/SPIFFE or equivalent, user authentication event proof, independent key custody and issuer-signing authority, audit, rate limits, cert rotation and combined-secret compromise.

Wave33's Docker fencing only kills the old primary before promotion. It is not quorum arbitration/STONITH; no multi-node witness, automated client router, stale-leader quarantine, production failback or measured RPO/RTO is tested. The previous physical HA labs remain technical evidence, not production acceptance. Proposed next acceptance: old-primary network partition with independent leader epoch, deny stale writes through router, rejoin quarantined old-primary as read-only until rebuild, measure failover latency from agreed start/end events, and reconcile external provider charge state across the cutover.

## E. Formal evidence and release controls

Java 11 and 17 must each retain **all Wave32 375 full and 146 SECURITY method identities**, with one new security method, making the expected total **376 full and 147 SECURITY**. Verify original Surefire XML and exact-SHA artifact custody. The real three-PG16 signed-provider/concurrency workflow must separately PASS on the **same SHA**, preserve machine-readable proof and exact source integrity.

Do not close any gate based on a synthetic test alone. Require named independent AppSec, DBA, QA, payments/finance operations, architect, legal/source rights and release-owner approvals, approved RPO/RTO and provider-authentication architecture, complete build/plugin SCA and original academic source-rights evidence.

**Formal Stage27 status: 0/14 closed. Release NO-GO.**
