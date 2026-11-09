# ADR-W34-014 — Restricted SQL Intent Application & Uncertain Local Acknowledgement

**Status: PROPOSED — PRODUCTION NO-GO.**
**Parent:** Wave34.11 draft PR #44 at \`3c77a609f3907d6ff153cea875abd76eaf4755aa\`; exact-head Java11/17 PostgreSQL16 workflow #42677073698 passed (410 full/117 classes and 151 SECURITY/64 classes per JDK).

## Purpose and architecture

Wave34.11 proved in disposable PostgreSQL16 that a request replay claim, plan update and ledger record could commit or roll back atomically. However, its test JVM directly opened a privileged SQL connection after locally generating both issuer and sender keys, so bypassing the verifier through raw SQL remained possible.

Wave34.12 creates an *isolated proof-of-concept* SQL-boundary experiment: a **test-only** intent table and schema-qualified \`SECURITY DEFINER\` function in a randomized \`w3412_...\` PostgreSQL16 schema. The issuer and executor connect with **different PostgreSQL-authenticated LOGIN roles**, and a privileged CI DBA owns the schema and controlled function.

The issuer role can insert narrowly defined authorization rows and revoke them, but cannot directly update the financial plan/ledger or execute the posting function. The executor role can execute that function only; it cannot create authorization rows, alter payment tables, assume the issuer role, disable triggers, or access privileged schema objects. The function checks \`session_user\` and enforces intent ownership, TTL, revocation, payable balance and same-intent consumption before posting the synthetic ledger entry and updating the plan.

**Trust limit:** The issuer role is a disposable database role, NOT a separately trusted cryptographic verifier or real merchant. It can mint arbitrary synthetic authorizations; neither a real provider-issued receipt nor a cryptographically signed third-party decision is checked inside PostgreSQL. The \`SECURITY DEFINER\` owner is the CI PostgreSQL superuser and **can bypass every control**. This is not proof of production non-bypassability. No production role, live database, real charge, API, schema migration or independent issuer is modified.

## Same-intent local ACK recovery: precise semantics

After one successful local transaction commits, the test simulates losing its returned acknowledgement. Calling the controlled function again with the **same authorization UUID** on another JDBC connection returns the original \`PAY-...\` reference and performs **no second** balance/ledger mutation. Simultaneous retries serialize through \`SELECT ... FOR UPDATE\` on the authorized intent row, and all callers obtain the same existing result after one commit.

This tests **LOCAL DATABASE ACK LOSS ONLY**. It does not test the external provider's capture, authorization, settlement, event ordering, lost webhook or uncertain charge state. A real unknown provider outcome must first be reconciled against the independently authenticated provider. Do not blindly issue a second external charge.

## Four acceptance cases

| ID | Named test | Evidence and limits |
| --- | --- | --- |
| W3412-01 | executorCanOnlyUseControlledFunctionAndIssuerCannotPostDirectly | Restricted LOGIN ACL denials; allowed function posts one synthetic payment |
| W3412-02 | lostLocalAcknowledgementRetryReturnsSameResultWithoutDuplicatePosting | New JDBC connection returns original result; duplicate tenant/request identity denied; distinct payment allowed |
| W3412-03 | simultaneousRetryOfSameIntentIsSerializedAndOnlyPostedOnce | Four concurrent calls return identical result; only one ledger/plan mutation |
| W3412-04 | duplicateLedgerIdRollsBackPlanAndIntentAndRevokedExpiredRequestsFail | Existing ledger ID conflict rolls back transaction; expired, revoked and unknown intents rejected |

The intent table has a tenant/request unique constraint. The payment_transactions table already has a unique legacy transaction ID. The lab deliberately allows two **different** authorized intents to reference the same legacy ID to exercise rollback after an attempted plan increment. A new entitlement does not establish ownership of the underlying provider charge.

## Remaining architectural controls / decisions

1. Independently authenticated cryptographic verifier service and issuer signing authority, policy/audience/tenant binding, managed key custody, revocation and trusted principal provisioning.
2. Controlled non-superuser SQL function ownership with no DDL bypass, complete table/write-surface inventory, connection-pool identity, privilege grants and CI/production secret separation.
3. Unified provider receipt/intent/ledger/outbox transaction, external idempotency and unknown provider capture reconciliation. The unkeyed legacy public \`PaymentProcessor.record\` still exists.
4. Fix legacy \`PAY-999999\` to \`PAY-1000000\` lexicographic allocation collision; choose database-generated identity via separately approved migration and independently reconciled source data.
5. Financial governance: approved minor monetary units, currency and rounding, refunds/chargebacks, original contract/ownership rights, account settlement and auditable retention.
6. Production HA: independent quorum, stale-primary fencing, durable request and ledger recovery, tested backup/restore and independently measured RPO/RTO.
7. Independent owner, DBA, AppSec, QA, finance/payments, SRE and legal/source-rights signoff; reconcile original Stage27 14 gates separately from earlier Phase16 17/12 framework.

**Final decision: PROPOSED ONLY. The fixtures do not establish independent issuer trust, provider payment recovery, or production authorization. NO-GO.**
