# Wave34.18 — Evidence Chain, Revocation and Post-Close Adjustment Review Runbook

**Status: PROPOSED / UNSIGNED — PRODUCTION NO-GO.**
**Parent:** Wave34.17 draft PR #50 exact SHA \`ab834bf4c4eac651450df3306a8b09bba30fc5bb\`. This is a disposable PostgreSQL16 proof only.

## Proposed exception evidence custody workflow

1. **Intake and preserve:** acquire read-only original merchant, bank and GL records with independent source identity, timestamps, currency, legal entity, account, coverage, original signatures and byte-level hashes. The lab has none; its fake source references and digests are generated inside Java tests.
2. **Build canonical envelope:** include original exception/case, closed period ID/close digest, new open adjustment period, correction reason, original evidence hash, amount/unit/currency, affected ledger accounts, risk/classification, identity/authority and estimated cash/financial effect. Do not silently replace original source artifacts.
3. **Authenticate maker/checker:** require independent real IdP/finance delegations with separate principals, bounded monetary authority, expiry, credential revocation and audit custody; at decision time recheck revocation under a transactionally consistent authorized service principal. The test has role strings and ephemeral keys in one JVM, and SQL rows editable by CI owner.
4. **Review adjustment:** require an approved GL debit/credit correction, source-to-claim reconciliation, responsible accounting period, Controller signoff and formal exception action. Never rewrite already posted immutable entries or reopen a historical period solely to make totals match.
5. **Commit append-only evidence:** under an approved restricted service, serialize a canonical digest chain and bind the current period adjustment, immutable prior-period pointer and signed approvals in one transaction. Retain witnessed hash tips, external timestamps/notarized checkpoints and independent stores; the lab has only a local hash chain/cursor.
6. **Verify and close:** independently re-read all source hashes, chain continuity, period closing digests and control identities, then route mismatch, revoked authority, expired signer, duplicate approval, over-limit amount or late backdated adjustment to **HOLD** for investigation.

## Test-only expected outcomes

| Test | Acceptance condition |
| --- | --- |
| W3418-01 | Two metadata adjustments yield chain length two with SHA-256 continuation; historic period stays CLOSED and its digest unchanged, direct journal rewrite rejected |
| W3418-02 | Fake actor exceeds 50 synthetic units, has revoked status, an expired/forged role signature or self-approval: hold; different active synthetic checker succeeds |
| W3418-03 | Correction referencing \`2026-10\` must post into OPEN \`2026-11\`, not modify \`2026-10\`; after synthetic closure of November, subsequent write held |
| W3418-04 | Four-way idempotent replay produces one adjustment and one chain link; reused maker/checker approval IDs on a different adjustment are rejected with unchanged chain; unrelated new adjustment succeeds |

The full actual PostgreSQL16 test roster is expected to be **438 methods/124 classes each** on Java11 and Java17. Separate existing SECURITY selection is **151/64 each**. Retain exact PR SHA, original failed run attempts, artifact hashes and four key-free JSON evidence manifests.

## Required production governance controls

Real maker/checker IdP enrollment and independent approvals; Controller-signed authority limits by currency/legal entity; documented revocation and approval expiration semantics; external time/checkpoint service; custody and retention in WORM/multi-party stores; independent maker/checker separation; approved prior-period journal accounting and source rights; period locking, correction windows, reversible suspense and audit retention; multi-region replay/HA; signed release gate owners.

## Release gates — all unapproved

| Gate | Requirement | Status |
| --- | --- | --- |
| EVID-01 | Independent original provider/bank/GL evidence | NOT_VERIFIED |
| EVID-02 | Externally anchored and independently witnessed chain tip | NOT_IMPLEMENTED |
| EVID-03 | Production-grade append-only/WORM custody and access segregation | NOT_IMPLEMENTED |
| EVID-04 | Genuine real IdP maker/checker roles and threshold limits | NOT_IMPLEMENTED |
| EVID-05 | Revocation and replay policy enforced by restricted service | SYNTHETIC_PG16_ONLY |
| EVID-06 | Approved current-vs-closed-period posting constraints | SYNTHETIC_PG16_ONLY |
| EVID-07 | Controller approved double-entry prior-period correction GL policy | NOT_APPROVED |
| EVID-08 | Provider PVA-01–16 plus HA/RPO/RTO and external restoration | NOT_RUN |
| EVID-09 | Legacy PAY-ID allocator and public payment API remediation | OPEN_DEFECT |
| EVID-10 | Repeated stress verification of issue #51 inherited replay test | OPEN_ISSUE |
| EVID-11 | Independent Owner/Controller/DBA/AppSec/QA/SRE/legal signoffs | PENDING |
| EVID-12 | Formal separate Stage27 and Phase16 release disposition | NO-GO |

**No real accounting adjustment, issuer authorization, production database migration, payment, financial close, PR merge or deployment is permitted.**
