# ADR-W34-009 — Writer Epoch, Cutover Barrier and Recovery Evidence

**Status: PROPOSED / NOT RATIFIED. Production release NO-GO.**
**Source baseline:** Wave34.6 draft PR #39 at e2b55f30836d99fe81b03254a0b42e533f8e5fa5. Historical Stage27 14 gates and separate Phase16 17-gate register remain unreconciled.

## Problem statement
Wave34.6 verified a synthetic source-to-target consistency preflight but could not prove that a new ledger write would not commit immediately after the recorded snapshot. A production migration cannot rely on a digest alone. Before using an approved mapping, the writer generation must be fenced, in-flight transactions drained, and the source/target counts, sums, references and digest checked again under the same authorized writer boundary.

## Test-only design — explicitly NOT a production permission system
The disposable PostgreSQL16 fixture defines an OPEN/FROZEN singleton row with a monotonically incremented epoch. Row-level triggers on the payment ledger and plan capture a SHARE lock on the fence row; an operator UPDATE to FROZEN must wait for earlier writers to commit or roll back. The trigger requires a transaction-local session epoch to match the current OPEN epoch, rejecting frozen/stale writers with SQLSTATE 55000.

This **only enforces cooperative simulated writers inside a random CI test schema**. Client-controlled PostgreSQL custom GUCs are spoofable, and the fixture's owner DB role can bypass or remove triggers and modify the fence. It is not proof of independent principal identity, secure lease issuance, quorum, leader fencing, old-primary isolation, cross-shard routing, failover integrity or production cutover. The design applies no DDL outside disposable per-test w347_* PostgreSQL16 schemas, leaves runtime schema v5 unchanged and never accesses a real provider.

## Test architecture
1. **W347-01 — Freeze and reopen:** one synthetic payment is committed with epoch 1. After operator freeze to epoch 2, both stale-epoch and same-epoch FROZEN writes are rejected without plan/ledger changes. A simulated reopen at epoch 3 allows only epoch 3 writes.
2. **W347-02 — Drain barrier:** an in-flight synthetic writer holds a SHARE lock on the epoch row. A freeze UPDATE with a bounded lock timeout must fail SQLSTATE 55P03 until the earlier writer transaction completes. Once that writer commits, the operator freeze succeeds and subsequent epoch-1 write is rejected. This demonstrates serialization in one database, not distributed quorum fencing.
3. **W347-03 — Aborted freeze recovery:** an operator attempts a freeze within a transaction and then encounters an invalid state CHECK violation. Rollback restores OPEN epoch 1 without changing the original ledger digest. A later committed freeze rejects a stale write.

Tests generate synthetic per-case JSON evidence with exact checked-out commit SHA, fence epoch/mode, schema v5, ledger counts, synthetic plan balance and a SHA-256 digest of a canonicalized test ledger. This is a **reproducible local evidence manifest prototype**; it does not constitute cryptographic signing, a trusted remote timestamp or a chain-of-custody service.

## Candidate production architecture requiring independent design
- **Trusted epoch control:** independent, authenticated lease issuer that prevents application writers from self-declaring any epoch, with non-overlapping validity periods, revocation, expiry and monitored renewal.
- **Database enforcement:** least-privilege SQL principals, ownership separation, controlled migration/DDL role, fence stored within the correct consistency domain, and an auditable implementation covering all financial write surfaces. Protect against writes that bypass the same database.
- **Distributed HA:** quorum-backed leadership, routing that quarantines the previous primary and rejects its writes, transaction drain, asynchronous replica lag verification and failback controls. Node kill or container restart alone is insufficient.
- **Evidence custody:** a signed source snapshot/LSN, WAL timeline, source and target ledger manifests, exact commit SHA, schema version, independently reconciled balances per owner/contract/currency, migration principal, issuer key provenance and operator approvals.
- **Recovery:** on unexpected unlock, source mutation, provider acknowledgement loss, duplicate external receipt or audit discrepancy: stop the transition, quarantine stale routes, query the provider under independent trust, avoid resubmitting charges, and use a DBA-approved restore or forward-fix.
- **Release governance:** approved monetary units, owner/business authorization, real provider PVA-01..16, accounting/refunds/chargebacks, migration SQL and rollback rehearsals, independent DBA/AppSec/QA/finance/legal/SRE reviews, measured owner-approved RPO/RTO, and disposition of every original Stage27/Phase16 release gate.

## Design alternatives and limitations
- **Database row-lock + epoch:** useful for *one PostgreSQL authority*, but requires secured principals and complete financial write coverage; not automatically a multi-primary fence.
- **External coordination/lease + database lease token:** supports distributed leadership if tokens and fencing are enforced at the storage boundary; must survive partitions, clock skew, stale leases and coordinator outages.
- **Read-only switch or client-side write freeze alone:** simpler but can be bypassed by old writers, stale sessions, unaccounted jobs or side channels. Not sufficient independently.

**Decision:** PROPOSED. The test-only trigger and GUC are intentionally not promotable. No schema v6, migration, source disposition, actual paid transaction or production release is authorized. NO-GO.
