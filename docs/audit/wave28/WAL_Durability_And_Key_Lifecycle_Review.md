# Wave 28 — PostgreSQL16 WAL Durability, Commit Policy, and Signed-Key Lifecycle Review

**Baseline:** Wave27 PR #27 at `329f20c3c1ac57320ae34b6615f097b18d6950dd`.  
**Mode:** Disposable PostgreSQL16 + synthetic dealership only; review-only draft PR.  
**Forbidden:** Production database, protected/default branch, deployment, secrets, tags, cloud archives.  
**Release:** NO-GO; fourteen Stage27 gates formally NOT CLOSED.

## 1. Reproducible WAL-durability experiments

The tests exercise actual Java dealership schema v5, with a real physical PostgreSQL16 standby. They must never be described as production acceptance.

### ASYNC — primary durable COMMIT absent on the isolated standby

- Start physical streaming, seed one PENDING request; verify it has replayed.
- Require empty synchronous_standby_names and default synchronous_commit=on (local flush).
- Disconnect the primary's network from standby without stopping its local PostgreSQL server.
- Forward an actual dealership approval COMMIT to primary through the existing Wave23 wire proxy; the backend returns ReadyForQuery while the client reply is suppressed. Primary direct connection confirms ownership/payment plan and stock change.
- Check the disconnected standby still has PENDING request, stock=1, no ownership or payment plan, and WAL LSN trails primary.
- Fence old primary BEFORE promoting the lagging standby; the promoted database has lost the locally committed business transaction (by deliberate isolation). Do not claim RPO=0.
- Reconcile the same persistent request ID on promoted standby; approve only after confirming PENDING state, then idempotently repeat. Expect one accepted sale, one ownership, one plan, stock=0.
- Record both LSNs, but LSN distance alone does NOT measure amount or age of lost business transactions.

### SYNC remote_apply — transaction waits for acknowledged application on standby

- Start physical streaming, seed the same PENDING dealership request.
- Configure the **disposable** primary with synchronous_standby_names='*' and synchronous_commit='remote_apply'; verify pg_stat_replication.sync_state='sync' before fault.
- Break replication while the old primary remains locally writable.
- Start real Java dealership approval and require pg_stat_activity.wait_event='SyncRep', proving COMMIT waits for synchronous standby acknowledgment.
- Verify standby still has PENDING state, stock=1, no ownership or payment plan, and the client has NOT reported success.
- Fence old primary and promote standby. The original client must report UNKNOWN. A new client reads persistent state and retries the same request safely.
- This verifies one concrete remote_apply outage case, NOT HA service availability, production latency/RPO/RTO or reliable client routing.

**Crucial durability distinction:** With asynchronous physical replication, a transaction locally committed on primary may be absent on a disconnected standby. With remote_apply, the primary is not allowed to acknowledge the COMMIT until the synchronous standby has durably applied it. PostgreSQL documentation: https://www.postgresql.org/docs/16/runtime-config-wal.html.

## 2. Credential authority: do not claim production signed-key closure

Wave27's test-only signed-intent function reduces SQL-login-only credential-hash compromise. It does not protect against compromise of signer+executor together and lacks trusted key rotation/expiry. Next security work:

- Distinct versioned key IDs, active/not-before/not-after/revoked attributes; single canonical signed payload with server-recognized purpose, requester/customer subject, key ID, account revision, nonce and expiry.
- Strong owner-managed key storage with no PUBLIC or SQL executor SELECT, secure rotation procedure, independent issuer controls and fault-tested revocation.
- Server-side maximum TTL and clock-skew acceptance; reject future-dated or expired intent; bound nonce retention and prevent pre-issued token replay, plus stable error responses.
- Avoid plaintext secrets in logs, environment dumps, stack traces, database events and artifacts. Review scoped key delivery to signer process, certificate lifecycle, rate limiting, insider collusion and multi-process authority.
- Independent SQL owner/DBA validates SECURITY DEFINER, fully qualified SQL, pgcrypto extension ownership, pg_temp search path and transaction grants. Test fixture is NOT a production migration.
- Formal ADR to choose key custody, rotation, expiry and deployment design; no auto-grant and no business release.

## 3. Distinct unresolved cases

- Client sees successful COMMIT, replica not caught up, and primary fails: ASYNC case measures potential data-loss class, not an acceptable business contract by itself.
- Client holds an unacknowledged synchronous COMMIT while standby is down: SYNC case demonstrates availability trade-off, not a universal RPO guarantee.
- WAL received but not fsynced vs received/fsynced but not replayed; synchronous_commit modes on/remote_write/remote_apply must be separately compared.
- Two writable leaders during partition, production fencing/quorum, client router and old-primary safe failback remain untested.
- Real user-facing payment/installment/refund/account privilege changes through cutover, RTO/RPO and legal/source rights require separate independent acceptance.

All fourteen Stage27 release gates stay formally NOT CLOSED, regardless of these technical subtests.
