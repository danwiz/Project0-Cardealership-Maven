# Wave34.6 — Operator Cutover Decision Runbook (Review Draft)

**Status:** PROPOSED. All approvals pending. **Production NO-GO.**
**Parent:** Wave34.5 draft PR #38, exact SHA 8f5544c6ac55015e9ff86bdd2678444e6258921d.
**No source-file dispositions, persistent schema, payment-network transactions or live customer data touched.**

## Role and decision boundaries

The release owner controls GO/NO-GO; DBA owns operator migration, SQL privileges and restore; finance/payments approves monetary units and ledger reconciliation; AppSec verifies independently operated provider identity, signature and actor authorization; QA validates exact-commit source evidence; SRE verifies routing/fencing/RPO/RTO; owner/legal resolves source rights and retention. None of these approvals are implied by CI success.

## Proposed cutover gates

| Gate | Scope | Required evidence | Current status |
| --- | --- | --- | --- |
| CUT-01 | Source identity and rights | parent SHA, chain of custody, approved code ownership | Partial GitHub evidence |
| CUT-02 | Financial definitions | units, currency, rounding, refunds, accounting | BLOCKED |
| CUT-03 | Real-provider identity | issuer controls, signed claims, PVA-01..16 | NOT RUN |
| CUT-04 | Versioned migration | DBA-reviewed SQL, grants, backup/restore and rollback | NOT RUN |
| CUT-05 | Ledger completeness | source/target counts, unique mapping, digests and balance reconciliation | SYNTHETIC ONLY |
| CUT-06 | Writer quiescence | fenced old primary, client routing and no post-snapshot writes | NOT IMPLEMENTED |
| CUT-07 | Legacy reference compatibility | six/seven-digit identifiers, API/consumer compatibility | SYNTHETIC ONLY |
| CUT-08 | Recovery | failover, ACK loss, rollback, sequence gaps | PARTIAL SYNTHETIC |
| CUT-09 | Supply-chain and testing | SCA/license/secrets plus exact-SHA full QA | PARTIAL |
| CUT-10 | Independent signoffs | owner, AppSec, DBA, payments, QA, legal | PENDING |
| CUT-11 | Operator canary | deployment scope, stop triggers, observability, RPO/RTO | NOT RUN |
| CUT-12 | Release | Stage27 and historical Phase16 gate dispositions | NO-GO |

## Proposed stop and recovery procedure (not executed)
1. Obtain authorized source snapshot, recorded baseline digest, signed decisions, backup checksum and tested restore evidence before migration.
2. Verify writer fencing and capture a final coherent source snapshot; if any transaction, contract, amount, currency mapping or balance differs, immediately HOLD, quarantine partial target and investigate under operator custody.
3. If a mapping insert fails, roll back its transaction. A PostgreSQL identity gap is expected and MUST NOT be reused or reseeded to hide missing numbers. Do not delete immutable financial entries or retry provider charges automatically.
4. If the canary fails after any authorized cutover, stop new writes, isolate routing, preserve the original dataset and payment-provider evidence, execute independently approved restore or forward-fix and reconcile all external charge states before resumption.
5. Every incident and rollback requires an exact-SHA record, change ticket, independently calculated before/after totals, measured RPO/RTO and named owner approval.

## Wave34.6 synthetic preflight interpretation
- PASS: source digest+balance and mapping cardinality/uniqueness agree at one read snapshot.
- HOLD: mapping incomplete, orphaned, stale source observation or schema mismatch.
- NOT TESTED: independent provider authentication, live financial balances, real writer fencing, production migration and operator restore.

**The runbook is a decision artifact, not an instruction or authorization to run SQL against persistent customer systems. Stage27 release remains NO-GO.**
