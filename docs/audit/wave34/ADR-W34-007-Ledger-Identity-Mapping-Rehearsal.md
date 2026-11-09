# ADR-W34-007 — Ledger Identity Mapping and Controlled Migration Rehearsal

**Decision:** PROPOSED / NOT APPROVED. **Release:** NO-GO.
**Evidence baseline:** Wave34.4 draft PR #37 at SHA 087031e73b30817b58f423b72eafa28c0c3b8ce2.
**Change boundary:** Synthetic PostgreSQL16 fixture, architecture and release evidence only. No runtime code, public payment API, JdbcDatabase.CURRENT_SCHEMA_VERSION, persistent database, secrets, merchant integration, protected branch or production data changed.

## Existing defect: confirmed from Wave34.4

JdbcPaymentProcessor.nextGlobalSequence reads the lexicographically greatest transaction_id, rather than a numeric database identity. On the historical six-digit edge, PAY-999999 sorts after PAY-1000000, so a subsequent call can derive and attempt to insert PAY-1000000 again. The earlier test reproduced a SQLSTATE 23505 uniqueness failure with rollback. Separately, parseSequence accepts exactly six digits, and SELECT ... FOR UPDATE cannot lock a nonexistent first row.

This rehearsal **does not fix** that runtime defect. Its purpose is to test a candidate migration boundary without modifying existing financial records or authorizing payment behavior.

## Candidate schema alternative for review

Introduce an **additive one-to-one mapping** in a future approved migration: ledger_entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY and legacy_transaction_id VARCHAR(40) UNIQUE NOT NULL REFERENCES payment_transactions(transaction_id). Historical payment records retain their exact PAY-... references; new numeric identities are allocated by PostgreSQL rather than by sorting formatted strings.

Unlike the commented Wave34.3 future-schema illustration, Wave34.5 includes an **executable DDL fixture strictly limited to disposable per-test w345_* schemas**. It is never imported into production migrations, does not increment schema v5, and is not an approved migration script.

### Alternative assessment

| Design | Concurrency | Compatibility | Major trade-off |
| --- | --- | --- | --- |
| PostgreSQL BIGINT identity mapping | Database-enforced uniqueness and independent allocation | Preserves original PAY-... strings | Adds indirection; sequence gaps expected |
| New append-only ledger with DB-owned primary key | Stronger long-term accounting/event foundation | Requires explicit compatibility service | Larger migration and financial review |
| Opaque UUID/ULID ledger ID | Distributed identifier option | Existing consumers must migrate | Format, ordering and entropy policies |
| Continue MAX(PAY-...) + 1 | Unsafe around first row and seven-digit boundary | No migration | **Do not select for production** |

The mapping is a compatibility stepping stone, **not** an independent accounting journal, double-entry ledger, monetary-policy decision or final provider-payment design.

## Rehearsal acceptance and exact interpretation

| ID | Disposable test case | Expected evidence | Does not prove |
| --- | --- | --- | --- |
| W345-01 | Backfill PAY-999999 and PAY-1000000 to distinct BIGINT IDs | Legacy IDs and plan balances unchanged; mapping unique; schema v5 persists | Safe backfill for customer production data |
| W345-02 | Concurrent first mapping: 4 workers × 8 existing legacy IDs | 32 separate identities, no duplicate, no extra payment; backfill replay no-op | Production throughput/HA performance |
| W345-03 | Insert mapping then invalid FK in one transaction | Provisional mapping rolled back; new sequence value may skip | Full operator recovery on production schema |

### Source custody and exclusions

Every CI run must verify the exact PR head and unchanged runtime source blobs, including payment processor, database migrator, provider candidate, schema DDL and application entrypoints. CI may execute the small test-fixture DDL only after checking localhost PostgreSQL16, fixed test account, CI, GITHUB_ACTIONS, W34_DISPOSABLE_ONLY, and randomized w345_ schema.

No production migration, data backfill, release approval, real payment verification, schema version bump, live deployment or financial settlement is permitted.

## Operator migration design — decisions pending

1. **Currency/unit accounting:** legacy integer amounts may represent an unknown business unit; require approved money semantics and independent reconciliation before any real migration.
2. **DBA:** approved schema revision, owner-only DDL migration and least-privilege DML grants; rollback/forward-fix, database backup/restore validation, write freeze and change window, plan for identity bounds/sequence cache and replication.
3. **Compatibility:** read/lookup of historical six/seven-digit PAY- references, conflict detection, legacy API semantics, notification/event consumers and retention.
4. **Backfill:** deterministic source snapshot and audit manifest; count/hash reconciliation, orphan check, reference invariants, two-sided totals, rollback and quarantine on mismatch; no external receipt guessed from legacy rows.
5. **Financial safeguards:** independent provider-authentication contract PVA-01–16 still NOT RUN; transaction and outbox atomicity/refunds/chargebacks still proposed, no hidden dual charging.
6. **Cutover:** only staged after owner, AppSec, DBA, finance/payments, QA and source-rights/legal approval with RPO/RTO targets.

**Wave34.5 success means the migration hypothesis is rehearsed in disposable PostgreSQL, not that a production financial migration is correct. Stage27's historical 14 gates remain formally unreconciled/open and release stays NO-GO.**
