# Wave34.8 — Database Principal Isolation, Evidence, and Approval Runbook

**Status: PROPOSED / NOT PRODUCTION-READY. NO-GO.**
**Source:** Wave34.7 PR #40, SHA 588dfb0661f38dc561316d5cc1358ca60e18091b.
**Execution:** Three synthetic PostgreSQL16 tests; no operator authorization to change live databases, send payment requests, modify protected Git branches, or deploy.

## Role and permission architecture for review

| Principal | Allowed CI access | Explicit deny / security boundary |
| --- | --- | --- |
| Writer A | PostgreSQL LOGIN; INSERT payment_transactions; narrow payment_plans UPDATE; epoch1 | Cannot edit fence/registry, disable trigger, impersonate operator or take ownership |
| Writer B | Separate PostgreSQL LOGIN; same narrow finance DML; epoch2 | Cannot post before authorized operator transition; no elevated role membership |
| Operator | Separate PostgreSQL LOGIN; SELECT/UPDATE fence row | Cannot post payments, change principal registry or alter schema |
| Test DBA | Creates/destroys CI-only roles/schema/trigger and fixture | Has broad superuser rights: **not suitable as production role model** |
| Production issuer | None | **NOT IMPLEMENTED**; requires independent provider and leadership policy |

## Test-to-evidence matrix

| Case | Trigger | Expected SQL/evidence |
| --- | --- | --- |
| W348-01 | Authenticated writer change and committed freeze | PostgreSQL 55000 denies unauthorized/stale principal; protected plan unchanged |
| W348-02 | Permission abuse: UPDATE fence/registry, ALTER trigger, SET ROLE, SET SESSION AUTHORIZATION | 42501 denial under restricted role; operator also denied ledger writes |
| W348-03 | Live writer transaction vs operator freeze | 55P03 timeout until writer finishes; later fence succeeds and stale writer is denied |

Every test produces one JSON with exact commit SHA, fence epoch/mode, ledger digest, verified role-auth method and production flags set to false. CI must verify all three manifest names, XML evidence, full suite and security suite; exact head and artifact hashes form the GitHub review record. No secret is emitted in the manifest.

## Approval gates that remain OPEN

Identity issuer and lease lifecycle; PostgreSQL hardening by non-superuser DBA; complete financial DML coverage; real old-primary/replica isolation and client routing; trusted backup, source snapshot and WAL provenance; approved source rights; all PVA-01..16 external provider cases; money/chargeback accounting; migration/version/grants/restore; operator RPO/RTO; independent security, payments, DBA, QA, SRE and owner signoff; original Stage27 and Phase16 gate reconciliation.

**Do not promote CI fixtures or SQL roles to production.** Any future live deployment must begin with an explicit, independently authorized operator change request and a new reviewed design. This document does not authorize execution of that change.

## Subsequent milestone candidate — Wave34.9
Design an external issuer interface, hardware/managed identity-backed lease provenance, negative replay/expiry tests and a stronger non-superuser ownership model in isolated staging. Preserve failure evidence and keep the full payment pipeline on NO-GO until issuer trust, ledger ID modernization and production financial accounting are fully approved.
