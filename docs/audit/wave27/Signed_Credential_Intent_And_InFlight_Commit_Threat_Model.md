# Wave27 — Compromised SQL Writer Containment and Pre-COMMIT Promotion

**Read-only/review mode:** Derived from PR #26 at `3ffd066148eb3f4386e5d2798dc73e11d22cf0fb`, with no production schema, roles, key distribution, service composition or protected-master changes. All Stage27 release gates remain **NOT CLOSED**.

## 1. Proposed signed-intent customer credential authority

Previous Wave26 tests proved mutual TLS and separate-JVM entry to a private credential broker. However, the database login possessed direct `UPDATE(encoded_credential)`; stealing that login bypasses Java-side current-password verification.

Wave27 introduces `JdbcSignedCustomerCredentialWriter` with a separately injected 256-bit HMAC key and a test-only PostgreSQL16 `SECURITY DEFINER` function that requires a correctly signed change intent. The **SQL login has EXECUTE permission on the function only**, not accounts read/write or secret-table read. The schema-owner-owned function validates HMAC-SHA256, a one-use UUID nonce, CUSTOMER role, existing hash and persisted session-generation compare-and-swap in one SQL transaction. The existing session-revision trigger rotates the generation on successful UPDATE.

The signing service verifies current plaintext PBKDF2 with the private reader before authorizing the intent. The owner-only SQL key and private signer key exist independently of the unprivileged SQL login. The injected key must be held only by an independently authenticated, hardened service. A stolen **SQL login alone** cannot forge the MAC. The proposed design does **not** protect against compromise of both signing key and login, schema-owner compromise, service process compromise, weak rate limits, TLS identity compromise or a production implementation error.

The disposable PG16 regression must prove:
- Identity writer cannot SELECT hashes, signing key, nonce table; cannot directly UPDATE/INSERT/DELETE accounts or change roles
- Fake or missing HMAC is denied with no mutation
- Genuine broker verifies current plaintext and updates one CUSTOMER, rotating its stored revision
- A stale session, wrong plaintext or ADMINISTRATOR role is denied
- Nonce reuse with a NEW valid HMAC and new revision is still denied
- Function owner, REVOKE PUBLIC and SELECT/EXECUTE grants are issued atomically in disposable CI

**Caution:** The test fixture installs `pgcrypto` in disposable PG16 and stores an owner-only test signing key. PostgreSQL `hmac` and `SECURITY DEFINER` are supported in PostgreSQL16, but the implementation needs independent DBA review of search_path, function ownership, grants, extension schema, key custody/rotation, nonce retention, logging and transaction behavior. This SQL fixture is **NOT a production migration**.

## 2. Physical failover while a real COMMIT is in flight

Wave23 demonstrated backend COMMIT reached primary, but reply was dropped before client. Wave26 proved the stale primary could be rebuilt as read-only replica. Wave27 adds a **different, deterministic pre-backend-COMMIT cutover interleaving**:

1. Seed a pending real dealership vehicle purchase on physical PG16 primary and replicate it.
2. Start actual Java purchase approval on primary, with a local test-only TCP proxy withholding the real outbound PostgreSQL `COMMIT` wire frame **before delivery to backend**.
3. While JDBC client remains blocked waiting for COMMIT outcome, independently prove standby still has the PENDING request, stock=1 and zero ownership/payment plans.
4. Fence/stop old primary, promote physical standby, then release original client; it must classify result as uncertain.
5. Reconnect to new leader, confirm original request is still pending, retry, and prove exactly one accepted sale, one ownership and payment plan, stock decremented once. Verify another retry is idempotent.

**Exact limitation:** This is a pre-COMMIT message interruption **during promotion**. Since COMMIT is withheld from the old server, it cannot establish the separate race where the server has accepted COMMIT and its WAL is still propagating while promotion happens. The next HA test must cover acknowledged/non-acknowledged durable WAL under replication lag, production fencing/quorum, RPO/RTO and real client routing. Do not conflate these cases.

All Docker containers, DB roles, accounts, keys and purchase records in these tests are synthetic. No production database/network must be pointed at this lab.

## 3. Quality and governance

Preserve all 372 Wave26 full JUnit identities and 143 SECURITY identities on each JDK. Expect **373 full and 144 SECURITY** methods, with exactly one added PG16 signed-intent test and no skip. Preserve source SHAs, raw JUnit XML and artifact checksums. The new cutover workflow is a separate exact-SHA diagnostic, not a substitute for the full suite.

Complete source rights/license and build-plugin SCA, historical serialized data conversion and independent DBA/AppSec/QA/architect/source-owner/release-owner reviews before promotion. Never close any formal Stage27 gate merely because a scoped CI subcheck passed.

**Release: NO-GO.**
