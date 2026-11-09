# Wave34.12 — Restricted DB Access and Local ACK Recovery Runbook

**Status: PROPOSED / NOT APPROVED. PRODUCTION NO-GO.**
**Parent PR:** Wave34.11 #44 at exact SHA \`3c77a609f3907d6ff153cea875abd76eaf4755aa\`.

## Scope, custody and operations

Run the four synthetic tests only on the fixed localhost PostgreSQL16 disposable GitHub Actions instance with \`CI=true\`, \`GITHUB_ACTIONS=true\`, \`W34_DISPOSABLE_ONLY=true\`, and verified exact-head checkout. Each JUnit method creates a fresh \`w3412_...\` schema, bootstraps existing schema v5, provisions two temporary independent database LOGIN principals, installs the disposable SQL function/table, seeds synthetic owner/contract/plan and tears down roles/schema afterward.

**Do not promote this SQL to an application migrator or production database.** The CI test superuser owns the \`SECURITY DEFINER\` function. All independent issuer, provider, finance, DBA, QA and owner approvals remain outstanding.

## Test verification matrix

| Case | Test preconditions | Required assertion |
| --- | --- | --- |
| W3412-01 | Separate issuer and executor LOGIN roles | Executor denied table DML/DDL and issuer SET ROLE; issuer denied direct ledger posting/function; controlled function posts once |
| W3412-02 | First intent committed, acknowledgement deliberately disregarded | Retry on a new connection returns the same transaction reference without another posting; same tenant/request cannot be reissued; distinct installment works |
| W3412-03 | Four executor connections submit identical UUID simultaneously | Row lock serializes; four same results, one consumed intent and one local ledger posting |
| W3412-04 | Two intents share legacy transaction ID; second attempts insert | SQLSTATE 23505, full rollback of second plan update and intent consumption; revoked/expired/unknown UUIDs denied with 55000 |

All four generate evidence in \`target/wave34-12-evidence/*.json\`, with source SHA, synthetic ledger digest, row counts, schema v5 and explicit \`production_authorized=false\`. No passwords, lease secrets, real receipts or user data are included. Exact-source gate fails if runtime payment code, version 5 migrator, earlier fixtures, or expected roster change.

## Explicit release HOLD gates

| Gate | Requirement | Status |
| --- | --- | --- |
| DB-01 | Independent verifier's issuer/merchant authority | NOT IMPLEMENTED |
| DB-02 | Production non-superuser function owner and enforceable DML boundary | NOT IMPLEMENTED |
| DB-03 | Restricted DB principals and ACL enforcement | SYNTHETIC_ONLY |
| DB-04 | Atomic local uncertain ACK recovery | SYNTHETIC_ONLY |
| DB-05 | External lost provider ACK and charge-state reconciliation | NOT RUN |
| DB-06 | Provider PVA-01–16 real authentication | NOT RUN |
| DB-07 | Signed intent, subject, tenant and request authority | NOT IMPLEMENTED |
| DB-08 | Durable distributed recovery and stale-primary fencing | NOT RUN |
| DB-09 | Ledger ID collision fix and approved migration | OPEN_DEFECT |
| DB-10 | Monetary accounting, refunds, outbox and retention | BLOCKED_OWNER |
| DB-11 | Independent DBA/AppSec/finance/QA/SRE/legal approvals | PENDING |
| DB-12 | Original Stage27 and Phase16 release decisions | NO-GO |

## Response to an uncertain payment

If the local SQL outcome is unknown, an **authorized** caller can query or retry the original request identity through a reviewed idempotent interface. Never generate an unrelated request identity merely to force posting. If the external merchant outcome is unknown, use trusted provider status/settlement APIs and independent reconciliation *before* any new charge attempt. Quarantine mismatched local/provider evidence; preserve ledger, signed receipts, logs and recovery decision. Database transactions do not roll back external payments.

**All evidence in this phase is synthetic. The production payment operation remains NO-GO.**
