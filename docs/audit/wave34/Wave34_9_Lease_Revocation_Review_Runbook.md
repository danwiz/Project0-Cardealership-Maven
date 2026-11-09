# Wave34.9 — Lease Lifecycle and Revocation Security Review Runbook

**Status: PROPOSED. Release NO-GO.**
**Parent:** Wave34.8 PR #41 SHA 68957fce394b170a820358fa2e50c98b28333b3d.
**Scope:** disposable PostgreSQL16 role/lease tests only; DO NOT apply SQL to a persistent database.

## Threat model and failure protocol

- Attack paths: forged bearer token, cross-principal token, expired token, already-revoked token, old epoch, permission escalation, disabled database trigger, bypass by privileged DBA, stolen token from same principal, network partition and stale primary. The final three are **NOT solved** by this test.
- Authority: a separately authenticated test issuer can mint/revoke one local role-bound lease; separate operator can freeze/reopen the fence; restricted writers can update only synthetic plan/ledger through the trigger.
- Revocation: when the test issuer updates revoked_at, the row-lock barrier drains an active writer first. A previously committed posting is immutable; revocation cannot retroactively un-post it. Remaining retry/capture ambiguity must be resolved by independent merchant authentication and atomic provider idempotency.
- Expiry: compare expires_at to database \`clock_timestamp()\`, never trust application wall time. A lease expiration during a long-running transaction may not revoke the already-admitted write, so production needs maximum transaction duration and lease validity policy.

## Acceptance matrix
| Case | Tests performed in synthetic CI | Expected |
| --- | --- | --- |
| W349-01 | No token, valid issue, unknown token, revoke and retry | 55000 reject unauthorized; exactly one posting |
| W349-02 | Real-clock expiry, cross-role reuse, ACL/role escalation denial | Expired/alien -> 55000; forbidden DDL/DML -> 42501 |
| W349-03 | Active writer and operator freeze, new epoch, reopen | 55P03 while draining; frozen/stale -> 55000; new lease works after reopen |

The tests record a checksum-only ledger manifest with source SHA, epoch, synthetic counts/balance and **production_authorized=false**. No bearer token or login password is emitted into the manifest. GitHub Actions must preserve failed attempts as well as latest exact-head success.

## Production reviewer decision table
| Gate | Approval requirement | Current disposition |
| --- | --- | --- |
| LEASE-01 | Separate real issuer and key/identity custody | NOT IMPLEMENTED |
| LEASE-02 | Sender-constrained, non-replayable lease proof | NOT IMPLEMENTED |
| LEASE-03 | Short-lived issue, expiry, revocation and audit | SYNTHETIC ONLY |
| LEASE-04 | Authenticated database principal bound to lease | SYNTHETIC ONLY |
| LEASE-05 | Least-privilege non-superuser DDL/security-definer ownership | NOT IMPLEMENTED |
| LEASE-06 | Multi-node quorum and old-primary fencing | NOT RUN |
| LEASE-07 | Writer drain and fenced source snapshot/LSN | SYNTHETIC ONLY |
| LEASE-08 | Cutover restore, recovery, RPO/RTO | NOT RUN |
| LEASE-09 | Merchant/provider signature and PVA-01..16 | NOT RUN |
| LEASE-10 | Currency, accounting, chargebacks and outbox | BLOCKED OWNER |
| LEASE-11 | Ledger ID fix and approved versioned migration | NOT IMPLEMENTED |
| LEASE-12 | Independent audits, source rights, Stage27 release signoffs | NO-GO |

**Stop condition:** missing/expired lease, identity mismatch, invalid epoch, source drift, revoked issuer key or unverified external charge. Preserve evidence and reject without resubmitting an unknown provider charge. The present implementation is NOT an authorized production stop circuit.

### Next milestone candidate
Wave34.10: independently verified cryptographic lease envelope and non-superuser DDL owner in an isolated environment, plus replay resistance and issuer-key revocation testing; production policies still to be approved.