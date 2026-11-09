# Wave34.10 — Independent Issuer Contract and Sender Proof Security Runbook

**State: PROPOSED / review-only / NO-GO.**
**Parent:** Wave34.9 draft PR #42 at 0ddb87448705f3d091693e508b730b21348fbfc2.

## Verification artifacts

Code under src/test/java/com/revature/repository/jdbc/Wave34SenderConstrainedLeaseContractTest.java executes 4 isolated JUnit methods with Java 11-compatible JCA P-256 keypairs. Its authorization requests are named synthetic fixtures, not live payment operations. Test-generated JSON in target/wave34-10-evidence is checked against the exact PR SHA and contains no credentials, bearer strings, private keys or nonces.

Source custody CI must preserve unchanged production payment runtime, pom.xml, database migrator, all previous disposable SQL. Reproduce on Java 11 and 17; expected full test roster increases from 402/115 to **406 methods across 116 classes**, while SECURITY-tagged selection remains **151 methods across 64 classes**. Four exact named tests and four evidence manifests must be present, with no skips/failures.

## Review acceptance matrix

| Gate | Required authority/evidence | Current state |
| --- | --- | --- |
| ISSUER-01 | Independently operated issuer and controlled signing key | NOT IMPLEMENTED |
| ISSUER-02 | Cryptographic claim and key-ID verification | SYNTHETIC_JCA_ONLY |
| ISSUER-03 | Sender-bound private key / channel attestation | SYNTHETIC_JCA_ONLY |
| ISSUER-04 | Durable JTI and nonce anti-replay | IN_MEMORY_ONLY |
| ISSUER-05 | Issuer and lease revocation/rotation propagation | SYNTHETIC_JCA_ONLY |
| ISSUER-06 | External principal + database trusted role binding | NOT INTEGRATED |
| ISSUER-07 | Stale-primary/quorum fencing across network partitions | NOT RUN |
| ISSUER-08 | Real provider charge authentication, PVA-01..16 | NOT RUN |
| ISSUER-09 | Correct ledger transaction identity and idempotency | OPEN DEFECT |
| ISSUER-10 | Financial currency/unit/refunds/chargeback policy | BLOCKED OWNER |
| ISSUER-11 | Production migration, backup/restore, RPO/RTO | NOT RUN |
| ISSUER-12 | Independent owners/reviewers and Stage27 release gates | NO-GO |

## Threats and stop conditions

Treat unknown/revoked issuer kid, signature mismatch, wrong audience/subject/tenant, revoked/expired lease, wrong epoch, substituted sender key, request mismatch or replayed nonce as fail-closed. Do not treat one-process in-memory anti-replay as durable under crashes. For real integrations, re-authenticate provider state independently before posting; never retry unknown external charge blindly.

## Required next authorization steps

An AppSec/identity owner must approve the issuer, trust-key lifecycle, key custody and replay persistence architecture. DBA/SRE must approve storage-level anti-replay and quorum fencing; finance/payments must decide money policy and accounting treatment. Owner/legal must resolve source rights. A named release owner must reconcile historical Stage27 (14) and Phase16 (17) registers separately. No production change is allowed without a new explicit change authorization.

**This document does not authorize GitHub merges, provider calls, credentials, live schema changes, financial settlement or release GO.**
