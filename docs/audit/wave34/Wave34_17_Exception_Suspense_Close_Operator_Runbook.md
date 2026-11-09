# Wave34.17 — Exception Management, Suspense Ledger and Financial Close Review Runbook

**Status: PROPOSED / NOT AN OPERATIONAL ACCOUNTING POLICY — PRODUCTION NO-GO.**
**Repository:** danwiz/Project0-Cardealership-Maven.
**Parent:** Wave34.16 draft PR #49 exact SHA \`5107c7fdd1e3528a969498c7bf47574190ef3195\`.

## Proposed exception handling walkthrough

1. **Register:** Detect a provider–bank–GL mismatch. Preserve independent original source documents/records, legal entity, period/cutoff, merchant/deposit/GL references, input coverage and file hashes. Create an exception ID, owner, affected currency, variance, reason, evidence custody and SLA. Wave34.17 uses only a fake signed \`EX-01\` example, never external records.
2. **Hold:** Set exception OPEN and record a balanced, separately identified suspense HOLD pair in one transaction (test example DR 1390 / CR 1990 for 40 synthetic units). Do not post a fictional bank deposit or bypass the prior GL/source reconciliation gate. Both suspense accounts are **not** approved production COA mappings.
3. **Investigate:** Collect authoritative bank and provider status, adjustment/fee evidence, GL history, possible partial deposits and ownership rights. Keep every transition and source hash; define maker and checker controls, delegated financial authority, exception reason and documented resolution options.
4. **Resolve:** Obtain genuine independent maker/checker authorization for the exact variance, linked documents, amount, legal entity and finance policy. Append an inverse RELEASE pair; do not overwrite the original HOLD. In this lab, the approvals are P-256 keys generated inside a test JVM and do not constitute human consent.
5. **Pre-close review:** Verify zero open exceptions, zero outstanding suspense balance, every event debit and credit balanced, unchanged immutable originals and a current period-evidence digest. Escalate unresolved exceptions; a batch or month-end close must fail rather than mark variances resolved by assertion.
6. **Authorize close:** Finance Controller and independent checker approve the complete period evidence; a restricted production application would atomically close the accounting period with a durable audit record, an approved post-close adjustment policy and backup/restore custody. The lab only updates a random PG16 test row under elevated CI DBA ownership.

## W3417 CI acceptance matrix

| ID | Demonstration | Expected check |
| --- | --- | --- |
| W3417-01 | OPEN + HOLD → approved RELEASE → CLOSED | Premature close SQLSTATE 23514; balanced 40+40 debit and credit totals 80; zero net suspense; closed period denies new writes and reopening |
| W3417-02 | Source and financial signature integrity | Forged/tampered source, self-approval, expired reviewer, changed digest HOLD; no mutation until exact synthetic approval |
| W3417-03 | Four-way concurrent release and reused checker authorization | One RELEASE across four calls; reused approval ID fails SQLSTATE 23505; unrelated approved resolution and close succeed |
| W3417-04 | Database fail-closed constraints | Missing close approval, one-line suspense and forced resolution without reversal fail SQLSTATE 23514; no partial state |

Run the full inherited PostgreSQL16 suite on Java 11 and Java 17 independently: expected **434 JUnit methods in 123 classes**, plus **151 SECURITY-tagged methods in 64 classes** on each version. The exact-head CI source-custody checker freezes production payment runtime, schema v5 and prior Wave34 fixture SQL. Four JSON manifests must indicate test-local evidence and **production_authorized=false**; retain source digest, no real customer data, private signing keys, external bank statements or merchant secrets.

## Required case record (future proposed schema)

Case reference, period, legal entity, account and currency, source exception/variance, date opened, priority/severity, case owner, SLA, source custody/hash, provider/bank/GL references and coverage, root cause, allowed suspense policy/account, hold journal ID, approval-maker ID, approval-checker ID, signed resolution reason, release journal ID, final reconciliation result, close digest, approval timestamps, control validation, and immutable modification history. **No new production case records are created by this milestone.**

## Stop criteria and escalation

HOLD financial close if any unresolved exception, pending bank settlement, unsupported merchant source, stale feed, unmatched GL amount, missing maker/checker identity, unbalanced suspense pair, outstanding suspense balance, invalid/reforged signing claims, reused approval ID, unavailable audit record, source-rights limitation or missing Controller signature exists. Do not force-zero suspense, delete existing entries, mark unknown payments settled, reopen a closed period or adjust real GL balances to make totals match.

## Release gates (all OPEN)

| Gate | Proposed owner/control | Current state |
| --- | --- | --- |
| CLOSE-01 | Independent merchant/bank/GL evidence | NOT VERIFIED |
| CLOSE-02 | Approved legal entity, COA, suspense and currency policy | NOT APPROVED |
| CLOSE-03 | Typed exception workflow and audit SLA | SYNTHETIC ONLY |
| CLOSE-04 | Balanced suspense HOLD/RELEASE pairs | SYNTHETIC PG16 ONLY |
| CLOSE-05 | Real maker/checker approval identity and finance delegation | NOT IMPLEMENTED |
| CLOSE-06 | Period close gate enforcing zero unresolved and suspense | SYNTHETIC PG16 ONLY |
| CLOSE-07 | WORM/append-only and separate duty DB ACL | NOT IMPLEMENTED |
| CLOSE-08 | Genuine Controller close and post-close accounting protocol | NOT RUN |
| CLOSE-09 | Legacy PAY-ID/public API defects and approved migration | OPEN DEFECT |
| CLOSE-10 | PVA-01–16, production HA/backup/restore | NOT RUN |
| CLOSE-11 | Independent owner/Controller/DBA/AppSec/QA/SRE/legal reviews | PENDING |
| CLOSE-12 | Stage27 and Phase16 source release assessment | NO-GO |

**Only the synthetic PG16 fixture may be used. No real accounting period, GL record, bank deposit, provider account, production migration, software merge or payment operation is authorized.**
