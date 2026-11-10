# Wave 34.23 — Separate-Process Witness & Receiver Trust-Boundary Rehearsal

**Scope:** disposable process-level contract test only, **NOT INDEPENDENT** operational custody; **DRAFT / UNMERGED / UNDEPLOYED / PRODUCTION NO-GO**.

**Source parent:** Wave 34.22 PR #56 at \`c6f3fea85a1a3c138f49ccccdfd8a81e7b200ecd\`, verified Java11+17 450/450 full and 151/151 SECURITY tests and eight selected SQL barrier scenarios. Historical evidence and all original defects are preserved. This W34.23 milestone adds four files only: a Python integration test/sidecar server, exact-SHA evidence gate, isolated workflow, and this review record. No production Java changes, schema v5 changes, historical SQL/test rewrites, financial provider access, branch merge or deployment.

## Implemented in the isolated test lab

The test runner launches **two separate Python service processes** on random loopback ports, one with a witness database and P-256 private key, and another with a receiver database and inbox/effects ledger. The primary outbox is a *third SQLite database* in the orchestrating test process. All stores are disposable, generated under a temporary directory, and deleted after execution.

- Witness public API (test only): \`POST /attest\`, \`GET /receipt?claim_id=...\`, \`GET /trust\`, \`POST /revoke\` (test-only admin token). Receipts sign domain-separated canonical \`W3423|v1\` claim fields via **OpenSSL ECDSA P-256 SHA-256**. The verifier checks signatures against the published public key. A changed claim is rejected. Key revocation marks the signer untrusted; cryptographic validity of a historical signature alone does not override revocation.
- Receiver public API (test only): \`POST /deliver\`, \`GET /status?message_id=...\`. A single SQLite transaction inserts a dedupe-keyed inbox record and one synthetic integer-valued effect. Duplicate identical requests return the same receipt, changed business identity is rejected. Simulated failures occur before transaction commit (503) or after commit before HTTP response (dropped connection).
- Primary outbox supports \`PENDING -> CLAIMED -> ACKED\` via lease expiry and increasing generation; an out-of-date worker cannot ACK. After an assumed lease expiration a successor redelivers the same message and can ACK only with the exact expected synthetic receiver receipt.

**Limitations:** This is not a production-ready broker, outbox worker or security service. Its ACK is a deterministic digest **not a receiver signature**; the test-only admin token is not an identity provider; lease time is caller-controlled; no TLS/mTLS, authentication, HSM, WORM, external provider, ledger or real accounting authority is present. The test runner has OS privileges over all child processes and underlying files; therefore **independent administration and key custody are NOT established**. No true adversarial administrator separation, external regulatory attestation, or exactly-once transport guarantee is claimed.

## Test case register

| Case | Execution contract | Expected result |
| --- | --- | --- |
| W3423-01 | Witness signs claim receipt with P-256 private key | Public verification succeeds |
| W3423-02 | Primary digest modified while witness store unchanged | Signature fails on modified receipt; original recoverable |
| W3423-03 | Forged signature substituted | Verification rejects |
| W3423-04 | Identical vs changed duplicate witness claim | Exact receipt idempotency vs 409 conflict |
| W3423-05 | Receiver pre-commit injected failure | 503, no inbox/effect, clean retry |
| W3423-06 | Receiver initial message | One committed synthetic receipt |
| W3423-07 | Identical redelivery | Same receipt; no duplicate effect |
| W3423-08 | Changed digest with identical message ID | 409, no changed business effect |
| W3423-09 | Receiver commits, HTTP response lost; successor claims after expiry | Durable result preserved; redelivery and ACK succeed |
| W3423-10 | Stale worker/invalid receipt attempts ACK | Rejected |
| W3423-11 | Receiver process restarted after effect commit | Original inbox/effect durable; identical redelivery returns prior receipt |
| W3423-12 | Unauthorized then authorized test-only signer revocation | Unauthorized 403; revoked signer holds new signing, prior signature still cryptographically valid but untrusted |

**Acceptance rule:** Twelve exact-head JSON evidence entries, separate-process server execution with a SQLite store for each role, test-only source custody, and no silent conversion of operational NOT RUN gates into PASS. The 450/126 full JUnit and 151/64 SECURITY rosters on Java 11 and 17 are **unchanged** and should be rerun to guard inheritance.

## Outstanding true W3422-09 and W3422-10 release criteria

**W3422-09 — independently operated witness: NOT RUN.** Requires independent administrative identity/domain, real HSM/KMS-managed keys, key rotation/revocation at decision time, external immutable anchoring, witness-service availability/recovery SLOs, independent signatory and audited access controls, and an adversarial privileged-primary operator test. Separate local processes and stores do not meet this criterion.

**W3422-10 — independently controlled receiver and external business effects: NOT RUN.** Requires a distinct deployed service/security principal, persistent inbox + effect transaction with real target provider, authentic receiver-signed receipts, production-grade mTLS/identity, real queue/worker retries, poison/dead-letter policy and crash/HA failover, real PVA-01..16 acceptance and financial Controller approval. This lab only models a local process and synthetic integer effects.

## Release restrictions

No evidence here authorizes a merge to protected \`master\`, release, production credentials, payment processing, general-ledger posting, real money or provider contact. Synthetic issue [#51](https://github.com/danwiz/Project0-Cardealership-Maven/issues/51) and [#53](https://github.com/danwiz/Project0-Cardealership-Maven/issues/53) remain open for independent QA/AppSec review. The 12 Wave34.19 gates and earlier Stage27/Phase16 gates remain unapproved, along with Owner/Controller/DBA/AppSec/QA/SRE/Legal signoff.

**PRODUCTION NO-GO.** Success in this wave establishes only a constrained process-isolation proof of contract.
