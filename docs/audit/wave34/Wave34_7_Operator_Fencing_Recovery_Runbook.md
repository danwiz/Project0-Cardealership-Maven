# Wave34.7 — Operator Fencing, Evidence and Recovery Decision Runbook

**Status: PROPOSED / UNAPPROVED; release NO-GO.**
**Parent:** Wave34.6 PR #39, SHA e2b55f30836d99fe81b03254a0b42e533f8e5fa5.
No production DDL, payments, customer data, cloud migration, protected branch modifications or issuer credentials are authorized by this document.

## Acceptance decision matrix (proposed future production gates)

| Gate | Requirement | Current evidence and state |
| --- | --- | --- |
| FENCE-01 | Authenticated non-spoofable epoch issuer and write principal | NOT IMPLEMENTED; CI GUC is spoofable |
| FENCE-02 | Storage-layer write denial across every finance table and stale leader | SYNTHETIC single-DB triggers only |
| FENCE-03 | Transaction drain and fence barrier | SYNTHETIC PG16 SHARE/UPDATE row-lock test |
| FENCE-04 | Quorum/old-primary isolation, client routing and verified failback | NOT RUN |
| FENCE-05 | Quiesced-source SHA-256 + LSN/timeline and signed evidence | SYNTHETIC local hash only |
| FENCE-06 | Complete source/target reference and independently audited accounting reconciliation | SYNTHETIC mapping only |
| FENCE-07 | Currency/minor units, refunds/chargebacks and outbox authorization | BLOCKED owner/payments |
| FENCE-08 | Independent real provider / merchant verification PVA-01..16 | NOT RUN |
| FENCE-09 | Privileged DBA migration, restore and forward-fix rehearsals | NOT RUN |
| FENCE-10 | Security, QA, legal, provenance and source-rights approvals | PENDING |
| FENCE-11 | Measured owner-approved RPO/RTO, operational monitoring and canary stop triggers | NOT RUN |
| FENCE-12 | Full Stage27 and Phase16 release gate reconciliation and owner GO signature | NO-GO |

## Proposed transition process, not an executed operation

1. Authenticate approver/operator and custody the exact source SHA, migration artifact, database backup and restore evidence.
2. Stop admission of new external charges and verify provider settlement state *independently*; do not retry an unknown provider charge as if uncommitted.
3. Issue a trusted monotonic epoch to approved writer identities and revoke earlier leases. Confirm stale-primary isolation, network routing and old-session eviction.
4. Establish a durable writer-drain barrier, prove no earlier writer remains active, then take the signed source snapshot with WAL LSN, timeline and business-unit reconciliation.
5. Rehearse approved staging migration. Compare source/target identities, amounts, account/contract ownership, immutable records and external provider references against the same fenced snapshot.
6. If any source drift, mapping hole, orphan, reused provider transaction ID or stale writer is observed: HOLD. Preserve evidence, quarantine routes, use owner/DBA-approved restore or forward-fix. Never reseed identities to mask gaps.
7. Only with independent production-level reviews and explicit owner release authorization may an operator open a new epoch for a staged canary. Observe RPO/RTO, account balances, refunds, notification outbox and negative authorization tests.
8. Record final GO/NO-GO with named signoffs; this runbook does not supply one.

## Wave34.7 disposable evidence manifest
Three JUnit methods exercise freeze/reopen, drain barrier, and aborted operator freeze. Each writes an exact-head JSON test record to target/wave34-7-evidence, containing synthetic ledger SHA-256, epoch, mode, count, synthetic paid/balance, and explicit production_authorized=false. CI uploads these records along with JUnit and source-custody evidence. No test row is a real customer/payment record, and no test key or provider credential is present.

**A green GitHub Actions run verifies the laboratory scenario only. It cannot authorize real writer fencing, migration or deployment. Production NO-GO.**
