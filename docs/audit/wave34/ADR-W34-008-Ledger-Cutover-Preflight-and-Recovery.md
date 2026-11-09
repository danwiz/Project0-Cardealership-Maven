# ADR-W34-008 — Controlled Ledger Cutover Preflight and Recovery

**Status: PROPOSED, NOT APPROVED. Production NO-GO.**
**Exact baseline:** Wave34.5 draft PR #38 at 8f5544c6ac55015e9ff86bdd2678444e6258921d. Source evidence is PR #38 and prior Waves 34.1–34.5.
**Allowed scope:** Synthetic PostgreSQL16 fixture and review documents only. Runtime payment API, payment allocator, persistent schema v5, customer data, external provider, protected master and existing PR stack unchanged.

## Confirmed context
Wave34.4 reproduced a lexicographic payment-ID collision at the PAY-999999 to PAY-1000000 boundary. Wave34.5 established a disposable one-to-one mapping from immutable historical payment identifiers to PostgreSQL BIGINT identity values. Neither milestone establishes a production ledger, real provider trust, currency/minor-unit policy or operator migration authority. A clean mapping experiment is NOT cutover approval.

## Candidate controlled transition (all operator actions below NOT EXECUTED)

Source and rights review → owner-approved change → DBA migration/restore design → hard writer/leader fencing → immutable source snapshot and digest → approved staging-only mapping → independent source/target reconciliation → security/finance/DBA review → staged canary and monitoring → owner GO/NO-GO.

Every failed predicate sends the work to HOLD. Merely obtaining a consistent PostgreSQL read snapshot does not quiesce writers. A changed ledger after the approved baseline must invalidate readiness even if mapping coverage is complete.

## Proposed mandatory invariants
1. One-to-one mapping: every legacy payment ID maps to exactly one new identity; no orphan map, no missing or duplicate legacy key.
2. Source custody: transaction ID, customer, ownership, contract, amount, cumulative payment, resulting balance and recorded timestamp have the same immutable digest as the reviewed source snapshot.
3. Financial integrity: sums by customer/contract/ownership and correctly approved currency/precision match independent accounting results. Current application money-unit design is not approved; this invariant is NOT SATISFIED in production.
4. Writer fencing: independent, hard proof prevents post-snapshot writes and old-primary write acceptance. No synthetic local gate establishes this.
5. Identifiers: PostgreSQL BIGINT identities can skip values after rollbacks and are not legal invoice numbers, temporal order or contiguous accounting counters.
6. Customer authorization/provider trust: no merchant event can be posted without independent provider-authenticated identity, customer authorization, approved settlement status, anti-replay and atomic receipt/ledger/outbox. All PVA-01..16 real-provider tests remain NOT RUN.
7. Audit & recovery: backups/restore, operator grants, rollback/forward-fix, retention, source rights, risk acceptance and independent release approval are recorded and reviewed.

## Wave34.6 exact test scope
- W346-01: empty/partial mapping causes synthetic HOLD; full mapping plus unchanged ledger digest and plan counts passes synthetic preflight; backfill replay preserves numeric mapped keys.
- W346-02: source write after a successful snapshot invalidates that old baseline; subsequently completing mapping coverage does not make the stale baseline acceptable. Re-observation is not equivalent to actual writer fencing.
- W346-03: invalid mapping reference forces a transaction rollback and no provisional mapping remains; original source digest and plan balances persist; later valid disposable backfill can pass.

The tests reuse W34.5 DDL exclusively inside randomly named w346_ per-test disposable PostgreSQL 16 schemas, checking CI/GITHUB_ACTIONS/W34_DISPOSABLE_ONLY and pinned localhost test connection. This is a **test preflight**, not application deployment or versioned migration.

## Owner/DBA/SRE cutover decisions still OPEN
Source-rights approval and retention; approved monetary units/refund policy; real provider issuer and merchant authority; complete v6+ SQL with grants and deploy strategy; hard stale-primary fencing and app write freeze; independently calculated customer totals; rollback/restore and operator DR exercise; canary stop triggers, measured RPO/RTO; independent security, QA, DBA, finance, legal and release reviews.

**No merge, no production DDL, no live payment, no change of source disposition or Stage27 release gates. NO-GO.**
