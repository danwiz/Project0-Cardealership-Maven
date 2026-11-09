# Wave34.19 — Simulated Witness, Revocation Race & Adjustment Outbox Operator Runbook

**REVIEW ONLY / PROPOSED / PRODUCTION NO-GO.** Parent Wave34.18 PR #52 immutable source \`c55812a0662fd1199b5b37be598d7cdf2bc5520b\`.

## Hypothetical production operation model

1. **Acquire independent evidence:** source original provider, bank and GL records read-only with source custody and the legal entity/period/currency. Preserve independently calculated content hashes and signing authority. No real evidence is available to this fixture.
2. **Request controlled adjustment:** require Controller-approved reason, legal-entity GL debit/credit, prior-period link, current open posting period, correction amount, currency, original close digest and unique adjustment ID.
3. **Validate approvals and revocation at decision time:** check separate maker/checker identities, delegated authority, limits and expiry through a real independent IdP. Recheck against authoritative active/revoked state under appropriate synchronization. This lab uses one PostgreSQL role table and same-JVM ephemeral keys.
4. **Atomic local outbox:** insert adjustment metadata, hash-chain link, one unique PENDING delivery message and cursor advancement in one transaction; exact retries must return identical persisted identity or fail closed. No application schema v5 modification is made in this lab.
5. **Witness externally:** send an authenticated signed checkpoint to an actual independently operated notary/WORM store and verify the independently distributed public key/certificate and receipt. The current lab does **not** do this: its witness is a key in the same JVM and a row in the same database.
6. **Dispatch and reconcile:** hand a durable adjustment to a real ledger consumer with immutable idempotency key, retriable delivery, explicit ACK, dead-letter recovery, and independently reconciled GL offset. A crash before ACK permits a second send. **Never call this exactly-once transport.**
7. **Independent audit:** recompute chain, compare with independently preserved witnessed tips, review revocations and threshold decisions, inspect failed/retried outbox messages, confirm source provenance and period restrictions, obtain separate Controller and operational owner signoffs.

## Four disposable PG16 acceptance scenarios

| ID | Procedure | Expected lab-only evidence |
| --- | --- | --- |
| W3419-01 | Insert two claims, witness hash tip, deny SQL rewrite, simulate privileged primary modification, restore | Detect mismatch against unchanged in-process witness; 2 chain entries, 1 local receipt; no independent anchor |
| W3419-02 | Start uncommitted checker revocation, attempt pre-signed claim concurrently, commit revocation, retry with active checker | SQL lock blocks stale claim, then 42501 HOLD; alternative active checker passes |
| W3419-03 | Enqueue one claim; deliver twice without ACK; then commit local ACK and repeat | Two possible deliveries, one local message and one receipt; changed ACK/DELETE rejected |
| W3419-04 | Four-way exact same approval concurrent enqueue; cross-adjustment approval UUID reuse and fresh distinct claim | 1 first adjustment/outbox link, conflict HOLD on reuse, 2 final distinct entries |

Target Java11 and Java17 complete PostgreSQL16 suite: **442/125 methods/classes per JDK**, plus independently **151/64 SECURITY-tagged** methods/classes per JDK. Four exact-head key-free manifests must explicitly record real-independent-witness=false, external-IDP=false, real-delivery=false, exactly-once-external=false, outbox-production=false, release-authorized=false.

## Operational stop/rollback conditions

Stop for mismatch with a witnessed tip, unknown source lineage, revoked signer, over-limit amount, changed replay payload, closed posting period, duplicate delivery ACK, missing outbox row, unresolved GL posting, missing independent finance approval, any protected source change, or an unapproved release gate. A successful retry may be a duplicate send; do not assume a real GL receiver is idempotent without proof.

Use separate approved compensating entries for real accounting corrections. Never edit a closed historical ledger or produce fake bank deposits/merchant settlement confirmations to balance totals.

## Release gates — all NOT APPROVED

| ID | Condition | Current status |
| --- | --- | --- |
| OUT-01 | Independently attested merchant, bank, GL source | NOT_VERIFIED |
| OUT-02 | Independently operated witness, anchored externally trusted hash tip | SIMULATED_ONLY |
| OUT-03 | Real WORM and separate custodian access | NOT_IMPLEMENTED |
| OUT-04 | Human finance maker/checker IdP and delegation | NOT_IMPLEMENTED |
| OUT-05 | Revocation-at-commit across real issuer/DB/distributed services | ONE_PG16_ONLY |
| OUT-06 | Production lawful prior-period debit/credit correction | NOT_APPROVED |
| OUT-07 | Atomic adjustment metadata and outbox | SYNTHETIC_PG16_ONLY |
| OUT-08 | End-to-end consumer idempotency, ACK, dead letter and reconciliation | NOT_RUN |
| OUT-09 | Provider PVA-01–16 and HA/recovery | NOT_RUN |
| OUT-10 | Open issue #51 and #53 stress + legacy PAY ID/unkeyed API | OPEN_DEFECT |
| OUT-11 | Independent Controller, DBA, AppSec, QA, SRE, Legal and owner signoff | PENDING |
| OUT-12 | Historical Stage27 and Phase16 formal release decision | NO-GO |

**No production financial operation, software merge, migration, independent witness, ledger delivery or payment authorization permitted.**
