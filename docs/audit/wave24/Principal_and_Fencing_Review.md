# Wave24 — Isolated DB Principals & Partition-Fencing Review

**Mode:** audit candidate; no production migration or merge.  
**Parent:** Wave23 `4af09718996fcab0353e00086b3fa86982f035a7`.  
**Branch:** `audit/wave24-private-auth-pg16-fencing-20261107`.

## PostgreSQL database roles (isolated CI only)

| Principal | Granted | Must be denied |
| --- | --- | --- |
| Schema owner | Test fixture migrations/DDL | Any public API use |
| Public registrar | schema USAGE; schema_history SELECT; EXECUTE on approved customer-only functions | accounts SELECT/INSERT/UPDATE/DELETE; DDL |
| Private authenticator | SELECT of username, role, encoded credential and session revision in the private service | Generic account writes and public network access |
| Credential writer | SELECT selected account columns; UPDATE(encoded_credential) only | UPDATE(role), DELETE, INSERT, DDL and public secret access |

The credential-only SQL principal could still change a password hash via raw SQL if its credentials were stolen; this is **not** a complete database-enforced customer-plaintext proof. The application-layer writer revalidates current plaintext and session generation in one transaction; final production design must secure its process, credentials, deployment policy and RPC surface. Avoid treating the Java interface as a security boundary across untrusted processes.

The public registrar cannot authenticate users by itself and intentionally never reads account hashes. A separate private verifier handles login and returns only bounded authentication outcomes to callers. An actual deployment needs rate limits, identity look-up controls, audit, credential renewal/rotation, recovery policy and independent DBA/AppSec approval.

## Disposable partition/fencing acceptance

1. Start actual PostgreSQL16 primary and physical standby; migrate real dealership schema v5 and approve a synthetic vehicle sale.
2. Wait for the standby to see committed state. Confirm physical streaming replication.
3. Sever the primary's **replication network** while its process remains running. Prove `pg_is_in_recovery() = false` and the old primary can execute a disposable temporary write. **Do not promote yet.**
4. Explicitly kill the container-level old primary and confirm its process is stopped. Only then promote the standby.
5. Reconnect the actual Java dealership application, verify one ownership and one payment plan, and replay approval idempotently.
6. Preserve timestamped WAL positions, distinct source SHA and an auditable proof; fail closed on skipped stages.

This is a **controlled Docker fencing rehearsal**, not a production fencing/quorum controller or simultaneous in-flight COMMIT test. It intentionally does not leave two primary nodes writable after promotion and does not demonstrate failback or measured RPO/RTO.

## Formal release gates

All fourteen Stage27 gates remain **NOT CLOSED**, regardless of green test subchecks. Independent exact-SHA AppSec, DBA, QA, architect, source license owner and release owner approvals are required. Do not touch persistent databases, GitHub protected master, releases or external cloud archives.
