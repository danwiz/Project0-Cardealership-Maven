# ADR-W34-010 — Database-Authenticated Writer Principal and Privilege Isolation

**Status: PROPOSED / INDEPENDENT REVIEW REQUIRED. Production NO-GO.**
**Parent:** Wave34.7 PR #40 at exact SHA 588dfb0661f38dc561316d5cc1358ca60e18091b. All previous PRs remain draft and unmerged.
**Scope:** Disposable PostgreSQL16 role/ACL experiment ONLY; no production principal or secret provisioning, runtime payment code change, approved migration, external merchant or real transaction.

## Change from Wave34.7
Wave34.7 used a custom session GUC, w347.writer_epoch, to tell the database which epoch a writer claimed. PostgreSQL custom GUCs are under application control, so that claim is spoofable. Wave34.8 replaces that **within a disposable laboratory** with separate authenticated PostgreSQL LOGIN roles and a test-only owner-controlled mapping from session_user to an epoch. A SECURITY DEFINER trigger checks an OPEN fence row and the principal registry, acquiring a SHARE row lock that conflicts with an operator transition.

The test fixture's database owner is a CI PostgreSQL superuser, which can drop all protections. Security definer ownership, privilege grants and the mutable role registry are **not production hardened**. PostgreSQL local role authentication is not an independently operated cross-region, quorum-backed writer-lease issuer.

## Proposed trust boundaries
- **Writer A:** PostgreSQL-authenticated LOGIN role allowed INSERT on payment_transactions, restricted UPDATE/SELECT columns on payment_plans and USAGE on its disposable schema. It has no fence/registry DML, TRIGGER ownership, CREATE, GRANT or DBA privileges. It maps to epoch 1.
- **Writer B:** Same restricted DML with a separate login, mapped to epoch 2. It cannot post before operator cutover, during FROZEN mode, or impersonate Writer A.
- **Operator:** Separate PostgreSQL-authenticated LOGIN role has SELECT/UPDATE on the single fence table. It cannot post payments, update the principal-epoch registry, drop triggers, own the schema or change migration history.
- **Disposable DBA:** Test fixture owner provisions roles, schema, registry, grants and SECURITY DEFINER trigger. It is privileged and can bypass all guardrails; production must instead establish a separate, least-privilege, auditable DDL owner with no runtime sign-in rights.

The trigger uses session_user, not CURRENT_USER (which is the function definer during SECURITY DEFINER execution) and not any client-settable GUC. The function binds its schema-qualified table references and sets search_path=pg_catalog. This mitigates selected SQL search-path spoofing but does not prove malicious-DBA safety or a real independent issuer.

## Synthetic PG16 acceptance
- **W348-01:** Writer A posts at OPEN/epoch1; writer B is denied. The operator freezes epoch2, blocking both. After operator reopen, writer A stays denied and Writer B can post. No failed attempt changes the balance.
- **W348-02:** A restricted writer cannot UPDATE fence, UPDATE principal mapping, ALTER TABLE DISABLE TRIGGER, SET ROLE to the operator or SET SESSION AUTHORIZATION. The operator cannot INSERT a payment. Setting a legacy custom GUC does not bypass authenticated session_user binding.
- **W348-03:** A current authenticated writer holds the fence row SHARE lock until its SQL transaction commits. Operator freeze with a short lock timeout fails SQLSTATE 55P03. Once the writer drains, the operator freeze commits and subsequent writer attempts are denied.

Each JUnit scenario emits an exact-head, SHA-256-bearing JSON evidence record. All tests execute inside random w348_* PostgreSQL16 CI schemas and disposable PG LOGIN roles; JUnit tears down schema and roles. Application schema remains v5. The tests use **synthetic** account/ownership records and payment amounts without an approved currency or unit.

## Residual risks and decisions (OPEN)
1. **Independent epoch issuer:** external hardware/managed identity issuer, quorum, lease issuance, expiry, revocation and non-overlapping role transitions; storage-enforced monotonic fencing token. Existing test administrator is NOT independent.
2. **Database permission hardening:** dedicated non-superuser SECURITY DEFINER owner, role/inheritance/SET ROLE restrictions, schema/object ownership, trigger privileges and function search_path; defense against stale connections, queued writes, privileged maintenance and bypass/DDL.
3. **Cross-node old-primary fencing:** quorum authority and old-primary write denial across network partitions, client routing, connection pools, replicas, promote/failback and independent RPO/RTO measurement.
4. **Financial and provider authorization:** independently verified merchant events, PVA-01..16, payment-origin signature claims, local session/consent/account ownership, approved currency/minor units, full refunds/chargebacks and append-only journal/outbox.
5. **Migration and identity fix:** production-safe versioned SQL, approved operator rollback/restore, and correction of the lexicographically sorted PAY-999999 to PAY-1000000 allocator defect. No changes to legacy PaymentProcessor.record().
6. **Governance:** independent AppSec, DBA, QA, payments/finance, SRE, owner, source-rights/legal review; Stage27 14 gates and earlier Phase16 17-gate assessment must be separately reconciled.

## No-go decision
A passing role-isolation laboratory run proves **only** that restricted PostgreSQL roles cannot perform the specified unauthorized actions in this disposable database. It is not provider-signature verification, independent distributed writer identity, production HA fencing or release signoff. **NO MERGE. NO MIGRATION. NO REAL PAYMENTS. NO-GO.**
