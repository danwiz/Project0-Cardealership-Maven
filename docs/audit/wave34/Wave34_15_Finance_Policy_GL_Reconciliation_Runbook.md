# Wave34.15 — Double-Entry Policy, GL and Finance Reconciliation Runbook

**Status: PROPOSED / NOT A FINANCE APPROVAL. PRODUCTION NO-GO.**
**Repository:** danwiz/Project0-Cardealership-Maven.
**Parent PR:** Wave34.14 #47 head \`eb4d1de97a940441da1d3b1a0dbb292648c9487a\`.

## Test-only operating scope

The sample three-account chart exists only in a randomly named \`w3415_...\` schema on the fixed disposable PostgreSQL16 GitHub Actions service. It is created after the unchanged version-5 schema migrator bootstraps synthetic buyer and plan rows, then destroyed after each method. Neither an operational accounting service nor actual provider, merchant, bank, finance approval, tax system or financial data is accessed.

All event and finance approvals are signed by keys generated in one test JVM. They do **not** qualify as two independent financial signers. The CI database owner is privileged enough to bypass the append-only trigger. The sample amounts and currency are educational fixtures, NOT real approved units or GAAP postings.

## Intended acceptance matrix

| Case | Procedure and authoritative test limit |
| --- | --- |
| W3415-01 | Process synthetic CAPTURED 100, SETTLED 100, REFUNDED 40, CHARGEBACK 60. Check exactly eight GL lines, DR=CR=300, bank/clearing/receivable net 0; legacy ledger and payment plan untouched |
| W3415-02 | Directly insert incomplete journal (missing credit), imbalanced entry (100 debit/99 credit), mismatched currency; SQL must fail with 23514 and roll back. Verify test-only UPDATE/DELETE denials 55000 |
| W3415-03 | Reject settlement before capture, reversal before settlement, event sequence gap, missing/invalid/expired test approval, wrong currency, amount above synthetic available bank balance and external reconciliation variance. After each HOLD, journal digest must not change |
| W3415-04 | Submit same exact approved synthetic reversal concurrently four ways; return one event/2 lines. Reject reused approval UUID for a new event (23505) and changed existing event identity; accept a separate legitimate reversal |

For each Java version run both the **426/121 full source+PostgreSQL16** roster and independently the **151/64 SECURITY-tagged** roster. The four \`target/wave34-15-evidence/*.json\` manifests must match the immutable PR SHA; must explicitly state the absence of real provider, approved finance policy, production double entry and real funds movement; and must contain synthetic counts, debit/credit totals, original ledger-row count and SHA-256 digest only. Preserve failed runs and final successful exact-commit artifacts.

## Financial control design requiring external approval

- Controller must approve actual legal entity accounts, money units, opening journal, accrual/cash basis, revenue and sales tax recognition, clearing reconciliation, customer receivable, fees, FX/rounding, refunds, chargebacks and reserves.
- Payments/treasury owner must approve exact merchant and bank settlement reconciliation and distinguish authorized/captured/settled/returned/disputed cases.
- AppSec and DBA must verify that no direct database write, elevated function owner or legacy unkeyed \`PaymentProcessor.record\` can bypass authenticated provider-event and GL authorization.
- Reconciliation owner must define daily batch totals, bank matching, exception/suspense queues, missing/duplicate/out-of-order data, preservation evidence and signed close controls.
- Owner/legal/SRE/QA must approve migration, source rights, disaster recovery and staged release gates.

## Proposed production stop criteria

A missing merchant settlement, mismatched currency or amount, nonmatching event digest, forged approval, out-of-order provider event, unbalanced debit/credit pair, stale issuer, reused approval, or unmatched bank total must be HELD for review. Never directly alter an existing immutable journal line; propose separately authorized reversal/correction entries. Never reissue an unknown external provider charge as a reconciliation shortcut.

### Release gate register

| Gate | Control | Current disposition |
| --- | --- | --- |
| GL-01 | Approved legal entity chart of accounts and policy | NOT APPROVED |
| GL-02 | Approved monetary currency/minor units and fees | NOT APPROVED |
| GL-03 | Balanced commit-time GL constraints | SYNTHETIC_PG16_ONLY |
| GL-04 | Authorized capture/settlement/refund/chargeback postings | SYNTHETIC_ONLY |
| GL-05 | Independent finance maker-checker signatures | NOT IMPLEMENTED |
| GL-06 | Bank/provider settlement matching and close | NOT RUN |
| GL-07 | Durable, non-bypassable, append-only audit custody | NOT IMPLEMENTED |
| GL-08 | Production GL/outbox and restricted issuer-to-DB integration | NOT IMPLEMENTED |
| GL-09 | Legacy PAY-ID defect and approved migration | OPEN_DEFECT |
| GL-10 | Real provider PVA-01..16 and HA/recovery tests | NOT RUN |
| GL-11 | Independent Controller/DBA/AppSec/QA/SRE/legal/owner reviews | PENDING |
| GL-12 | Original Stage27/Phase16 formal release decisions | NO-GO |

**All production release gates remain open; no merge, deployment or financial transfer is authorized.**
