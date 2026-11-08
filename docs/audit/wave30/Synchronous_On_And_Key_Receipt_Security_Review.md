# Wave30 — Synchronous Remote WAL Flush and Pre-Cutover Signing Receipt

**Status:** DRAFT / NO-GO. Derived from exact Wave29 SHA `c61f497ecadf7d51dc2334329cf117cb8b24081a`.
**Mode:** Synthetic disposable PostgreSQL16; no production DDL, keys, transactions, deployment, protected-master updates, or release.

## 1. Physical PostgreSQL16 COMMIT durability with synchronous_commit=on

The new isolated physical primary/standby experiment sets **`synchronous_standby_names='*'` and `synchronous_commit='on'`**. Under PostgreSQL16, an acknowledged synchronous COMMIT requires standby WAL flush, but does not require the standby to have replayed SQL. Unlike an asynchronous primary's local WAL flush, synchronous on requires a remote flush confirmation from a recognized synchronous physical replica.

The test uses the actual dealership v5 schema and a synthetic PENDING purchase. After verifying `pg_stat_replication.sync_state='sync'`, the standby replication network is severed and the established WAL sender terminated. The primary stays reachable through its published JDBC port. A real Java dealership purchase is started. The lab requires `pg_stat_activity.wait_event='SyncRep'` and no early successful client response. It fences the old primary before reconnecting/promoting the standby and requires the original client's COMMIT outcome to be UNCERTAIN, followed by a durable PENDING readback and one idempotent business approval: exactly one accepted purchase, one vehicle ownership, one payment plan, zero remaining stock.

This case complements:
- Wave28 **asynchronous**: old primary locally committed, lagging promoted standby lacked sale
- Wave29 **remote_write**: PostgreSQL SyncRep wait without standby OS-write confirmation
- Wave28 **remote_apply**: PostgreSQL SyncRep wait without standby WAL apply confirmation
- Wave30 **synchronous on**: PostgreSQL SyncRep wait without required standby durable WAL flush

All are bounded lab fault schedules; the tests do not independently certify production quorum/fencing, failback, client router, measured RPO/RTO or external payment settlement. The owner has not approved any numeric RPO or RTO.

## 2. Wave30 security finding — VERIFY_ONLY HMAC backdating risk

Wave29's VERIFY_ONLY cutover compared the **signed issue epoch** to `signing_disabled_epoch`. A retired secret that is stolen could sign a *new* request with a falsified earlier issue time. A MAC verifies that the signer possesses the secret, not that its issue timestamp is true.

Wave30 strengthens the disposable owner-controlled PostgreSQL16 fixture by adding `w30_issued_intents`, a **separately owner-registered, immutable-identity receipt** for any intent that will be honored after signing is disabled. Each registration records nonce, key ID, customer, issue epoch, HMAC and database registration time. The SQL executor has **no SELECT or INSERT grant** on this register.

The owner creates a signed receipt while the signing key is still active, then changes that key to VERIFY_ONLY. The owner SQL function now refuses a VERIFY_ONLY intent unless the exact matching receipt exists **and its database registration time precedes the signing-disabled cutoff**. The existing HMAC, short TTL, purpose/issuer, nonce replay, role, credential and session generation checks remain mandatory. The actual PostgreSQL test proves an unregistered *backdated request with a valid MAC* is denied; a registered pre-cutover request is accepted once; new post-cutover issuance, expired/future/unknown/revoked keys, direct SQL writes and forged SQL receipt INSERT are denied.

**This is a controlled SQL receipt prototype, not a deployed issuance service**. The fixture's trusted schema owner manually registers the genuine receipt in the JUnit setup. A production system needs an independent, authenticated issuance registration service, key storage and audit, key rotation/revocation runbooks, correct immutable clocks and owner-approved migration. A compromised owner/issuance registry can forge receipts. Combined signer + SQL executor compromise while a key is active is still not intrinsically prevented. Key/role compromise, rate limiting, persistent audit and legal requirements all remain open.

No new JUnit method is added; this deliberately strengthens the existing Wave29 named test. The expected Java11 and Java17 regression baseline remains **374 full / 145 security methods**, with all method identities unchanged.

## 3. Evidence and release governance

Required to make any technical PASS statement: original Surefire XML from both JDKs, fail-closed Wave29 key test checker, synchronous-on PostgreSQL16 physical evidence JSON, source SHA and artifact SHA-256 custody. An initial CI test failure is not a security bypass verdict unless the relevant negative test actually reaches its SQL target.

Business RPO/RTO choice, production signer/key custody, owner-reviewed SQL migrations, actual quorum fencing, full build-plugin software-composition/notice/legal review, imported code rights, and human independent AppSec/DBA/QA/architecture/source-owner/release-owner signoffs are still outstanding.

All fourteen Stage27 release gates are formally **NOT CLOSED**. **NO-GO.**
