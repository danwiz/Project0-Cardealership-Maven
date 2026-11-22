# Wave 34.24 — Independent Trust Assurance & Release Gate Register

**22 November 2026 · REVIEW ONLY · DRAFT · UNMERGED · PRODUCTION NO-GO.**

Baseline: PR #57 at `cfd7eb0ce528ef505407c148c67eb23bdc37d7d4`, with successful Wave34.23 CI #42823902997. Wave34.24 adds only its adversarial disposable probe, custody/evidence validator, PR-only workflow, this register, and the QA/AppSec dossier. No production Java, historical SQL fixtures, payments, migrations, or protected master changes.

## Conclusion: separate processes are NOT INDEPENDENT

W34.23 placed local witness and receiver services in different OS processes and SQLite stores, yet a single privileged CI operator controls all stores, the signing key and the test administration token. Eight negative controls describe why the actual trust gates are still **BLOCKED**:

| ID | Observed vulnerability in isolated disposable lab | Independent acceptance proof required |
| --- | --- | --- |
| W3424-01 | Same OS identity reads witness private key | Privileged primary operator cannot read/export/use key under distinct authorization |
| W3424-02 | Anonymous loopback user obtains witness attestation | Mutual workload authentication and least-privilege signing policy |
| W3424-03 | Same operator forges and overwrites valid witness receipt | Independent non-exportable signer and externally immutable checkpoints |
| W3424-04 | Primary operator directly changes receiver effects | Separate identity, administrator and database custody; external reconciliation |
| W3424-05 | Hash-only ACK calculated for message never delivered | Receiver-signed ACK with canonical message and durable-effect digest and independent key |
| W3424-06 | Unauthenticated HTTP receiver delivery commits effect | TLS/mTLS or equivalent verified principal, replay and scope policy |
| W3424-07 | Runner's shared test-admin token revokes signer | Separate key custodian with audited revocation and dual control |
| W3424-08 | Historical P-256 signature still cryptographically verifies after revocation | Independent trust-policy check at receipt acceptance, revocation/time semantics |

An automated test may **PASS as a probe** only when it demonstrates an expected weakness. Such a green test explicitly means the *operational gate remains BLOCKED*, never that custody is approved.

## Required independent acceptance gates: ALL NOT RUN

| Gate | Falsifiable acceptance test | Owner/reviewer | Disposition |
| --- | --- | --- | --- |
| WIT-01 | Privileged primary operator cannot read witness private key or mutate checkpoint store, even with primary administrative compromise | Independent Witness Operator, AppSec | NOT RUN / BLOCKED |
| WIT-02 | HSM/KMS non-exportable signing key and separate authorized key rotation | Key Custodian | NOT RUN / BLOCKED |
| WIT-03 | Immutable external signed checkpoint verifies monotonic sequence and detects primary DBA edit | Witness Operator, Auditor | NOT RUN / BLOCKED |
| WIT-04 | Real key revocation enforced by relying party at decision time | Key Custodian, SRE | NOT RUN / BLOCKED |
| WIT-05 | Unauthorized signing / spoofed issuer / anchor substitution denied and independently logged | AppSec | NOT RUN / BLOCKED |
| RCV-01 | Primary operator cannot write independently controlled receiver effect store | Receiver Operator, DBA | NOT RUN / BLOCKED |
| RCV-02 | Signed receiver ACK covers message ID, request digest, effect digest, commit time, nonce/sequence, key ID/version | Receiver Operator, AppSec | NOT RUN / BLOCKED |
| RCV-03 | Durable inbox and precisely one committed real target effect under retry/lost ACK | QA, Controller, DBA | NOT RUN / BLOCKED |
| RCV-04 | Poison/DLQ, lease fencing, split-brain, HA failover and RTO/RPO acceptance | SRE, QA | NOT RUN / BLOCKED |
| RCV-05 | Independent bank/provider/GL reconciliation and PVA-01..16 negative mismatch rejection | Provider, Controller | NOT RUN / BLOCKED |
| GOV-01 | Independent attributed QA and AppSec assessment of issue #51 and #53 remediation | Independent reviewers | NOT RUN / BLOCKED |
| GOV-02 | Owner/Controller/DBA/AppSec/QA/SRE/Legal approvals, plus historical Stage27/Phase16/W34.19 gates | Named authorities | NOT RUN / BLOCKED |

Acceptable architectural options may involve separate admin domains/accounts and externally managed KMS/HSM plus immutable records, or genuine third-party independent attestation and receiver services. Vendor, budget and operating-organization decisions are **not made**; local containers/VMs/processes in one administrator domain are **not** independence.

**W3422-09 independently administered witness — NOT RUN. W3422-10 independently controlled external receiver — NOT RUN.** Synthetic issues #51 and #53 remain OPEN. No signed reviewer outcomes or financial release approvals are claimed. **PRODUCTION NO-GO.**
