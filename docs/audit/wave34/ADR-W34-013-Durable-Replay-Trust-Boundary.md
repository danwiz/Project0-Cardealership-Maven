# ADR-W34-013 — Issuer trust boundary, transactional replay reservation and ledger posting

**Status: PROPOSED / AWAITING INDEPENDENT REVIEW. Production NO-GO.**
**Stack parent:** Wave34.10 draft PR #43 exact SHA 003b4589020a1b5c762036cbca06d08ad7528959. Verified Actions run #42660193164 passed 406 full JUnit methods across 116 classes on Java 11/17 and 151 SECURITY-tagged methods across 64 classes on both.

## Precise architectural decision
The immediate W34.10 acceptance test held its challenge/revocation state in an in-memory Java map, so replay resistance did not survive a verifier restart or span multiple independent workers. W34.11 studies the stronger **database transaction boundary**: verify synthetic test-issued P-256 issuer and sender signatures in the test JVM, then reserve a nonce/request identity in PostgreSQL16 using unique constraints in the **same SQL transaction** as updating a synthetic payment plan and inserting its ledger record. Reconnecting the client preserves committed replay claims because they are in the database.

This is a compatibility/proof-of-concept experiment. **The verifier and issuer are still in one test process**, both test keys are generated locally, and the SQL principal is an elevated CI owner. No production call path forces every payment through the verifier. The database replay table alone cannot authenticate an issuer, sender, charge provider or merchant consent. This does not implement independent trust; it makes one missing production boundary explicit and testable.

## Transaction and idempotency model
A proposed write begins with an independently trusted verified issuer+sender envelope, with exact subject, tenant, audience, epoch, lease ID, challenge nonce, request ID, transaction ID, ownership and amount. The test validates the cryptographic claims before opening its SQL transaction. Within the synthetic PostgreSQL16 transaction:
1. Insert into a claim reservation table keyed by an unguessable nonce and the (tenant, request ID) pair. PostgreSQL UNIQUE constraints enforce one committed reservation for a request even across simultaneous sessions or a new database connection.
2. Update the specific payment plan using its authorized ownership ID; obtain returned cumulative payment and balance.
3. Insert the corresponding synthetic payment ledger record with those returned totals.
4. Commit **all three** together; on unique conflict, foreign-key error or other SQL failure roll back the entire SQL transaction. Do not charge a provider or send external messages.

DB-generated identity values can skip on aborted transactions; a sequence gap is not an accounting fault. A successful replay claim reservation must not be treated as a financial ledger without the associated atomic posting and correctly reconciled business money model.

## Four exact test cases
| ID | Scenario | Evidence goal | Limit |
| --- | --- | --- | --- |
| W3411-01 | One committed signed request then replay through a new JDBC connection; separate legitimate same-amount request | One claim/one local posting for replay; distinct payment accepted and cumulative plan totals consistent | Does not prove distributed cross-region durability |
| W3411-02 | Four concurrent writers of identical verified request | Exactly one commits; other attempts hit SQLSTATE 23505; one plan/ledger increment | Test connections use CI superuser; no real provider charge |
| W3411-03 | Inject invalid ownership foreign key after request claim and payment-plan update | SQLSTATE 23503 rolls back claim, plan and ledger; controlled retry can post once | Injection is synthetic, not recovery of a real payment |
| W3411-04 | Wrong issuer/sender verification key; tampered amount/tenant | Fail closed before SQL claim reservation; no phantom payment | Trust anchor is held by the test JVM, not independent identity service |

The new SQL file is **review-only executable fixture** and is never placed in an application migrator. It runs in randomized w3411_... PostgreSQL16 CI schemas after the existing schema v5 bootstrap; runtime code and persistent schema stay untouched. No merchant credentials or customer information are present.

## Required next boundary — NOT IMPLEMENTED
- **Independent issuer authority:** separate operational trust domain with approved key custody (managed keys/HSM), authenticated issuing identity, signed audience/tenant/epoch/subject/claims, revocation, expiry and key rotation. A pinned test public key is not trusted issuer onboarding.
- **Non-exportable sender proof:** real workload/device-bound credential, mTLS/channel/audience binding and signed request nonce, robust per-principal authorization. Test private keys are exportable and in one JVM.
- **Database trust integration:** restricted DML principal, controlled SECURITY DEFINER/DDL owner, verifier-to-DB attestation that cannot be bypassed by calling SQL directly, signed source trace, hardened grants, network restrictions and audit.
- **High availability:** leader quorum, stale-primary write fencing, conflict handling across failover, retention of unique constraints and dedupe during restore, plus known RPO/RTO.
- **Financial correctness:** unkeyed PaymentProcessor.record modernization, PAY-999999 to PAY-1000000 lexical collision fix, independently authenticated provider charge identity, payment replay after lost ACK, currencies/minor units, refunds/chargebacks, double-entry reconciliation and transactional outbox.
- **Release ownership:** DBA, AppSec, payments/finance, SRE, QA, legal/source rights and owner approvals; legacy Stage27 and separate Phase16 registers reconciled by independent evidence.

## Decision / release disposition
**PROPOSED ONLY. NO-GO.** A successful Java/PostgreSQL lab confirms SQL atomicity and replay uniqueness under these fixtures; it neither establishes real issuer separation nor proves production-grade payment idempotency. No merge, deployment, persistent migration, live financial change or Stage27 gate closure is authorized.
