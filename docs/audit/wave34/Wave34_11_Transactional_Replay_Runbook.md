# Wave34.11 — Durable Replay and Issuer-to-Database Trust Review Runbook

**State: PROPOSED / REVIEW-ONLY. Production NO-GO.**
**Repository:** danwiz/Project0-Cardealership-Maven. Parent: Wave34.10 PR #43, SHA 003b4589020a1b5c762036cbca06d08ad7528959.

## Test operator sequence
Only the fixed CI PostgreSQL16 localhost instance, CI/GITHUB_ACTIONS/W34_DISPOSABLE_ONLY flags and exact source SHA may execute the rehearsal. JUnit creates one random w3411_* schema, seeds synthetic buyer/vehicle/plan records under schema v5, applies the **test-only** unique replay claim table, then destroys the schema on teardown.

The four tests must independently demonstrate:
- **W3411-01:** a repeated signed request cannot reserve a second database claim through a fresh JDBC connection; two truly separate same-amount installments can both post.
- **W3411-02:** four same-request threads compete; one atomic insert+plan+ledger transaction commits and the others fail uniqueness, with no duplicate balance decrement.
- **W3411-03:** an injected invalid FK fails the ledger insert, and the same SQL transaction rolls back the claim and the prior plan update. A fresh, valid synthetic retry can commit once.
- **W3411-04:** invalid issuer verification key, sender proof key or tampered signed claims are rejected before any SQL claim reservation.

All four output exact-commit JSON evidence with a checksum of synthetic ledger counts/state only. They do not contain private keys, signature bodies, customer data or bearer credentials. The evidence is **not** a signed external custody manifest.

## Production decision register — all unapproved

| Gate | Requirement | Current state |
| --- | --- | --- |
| REPLAY-01 | Independent HSM/KMS-backed issuer and onboarding | NOT IMPLEMENTED |
| REPLAY-02 | Sender-constrained identity and channel binding | SYNTHETIC_JCA_ONLY |
| REPLAY-03 | Durable one-database replay uniqueness | SYNTHETIC_PG16_ONLY |
| REPLAY-04 | Exact request/ownership/amount cryptographic checks | SYNTHETIC_JCA_ONLY |
| REPLAY-05 | Atomic local replay + plan + ledger posting | SYNTHETIC_PG16_ONLY |
| REPLAY-06 | Non-bypassable auth-to-DB trust path with least-privilege roles | NOT IMPLEMENTED |
| REPLAY-07 | Real provider charge idempotency, ACK recovery, PVA-01..16 | NOT RUN |
| REPLAY-08 | Multi-primary/old-primary fencing and RPO/RTO | NOT RUN |
| REPLAY-09 | Correct money accounting, refunds/chargebacks/outbox | BLOCKED_OWNER |
| REPLAY-10 | Legacy PAY-ID allocator fix and approved versioned migration | OPEN_DEFECT |
| REPLAY-11 | Independent DBA/AppSec/QA/finance/SRE/legal reviews | PENDING |
| REPLAY-12 | Stage27 and Phase16 releases disposition and GO | NO-GO |

## Stop criteria and recovery
Unknown/untrusted issuer, altered signature, mismatched tenant/ownership/amount, expired or revoked key, replayed nonce or duplicated (tenant, request ID) -> **reject** before external funds movement. If a transaction fails after claim reservation, rollback entire SQL transaction and preserve evidence. Do not blindly resend an external charge, re-use a possibly committed provider request identity or consider a SQL sequence gap a lost payment. Restore/failover must independently reconcile real provider state and source ledger before any approved replay.

No real issuer service, provider integration, DBA migration, non-superuser access boundary, backup/restore drill or production release approval has been completed. **NO-GO.**
