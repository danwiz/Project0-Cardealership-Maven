# Wave34.13 — Provider Status, Financial HOLD and Reconciliation Review Runbook

**Status: PROPOSED / UNSIGNED. Production NO-GO.**
**Repository:** danwiz/Project0-Cardealership-Maven; **parent:** Wave34.12 PR #45 at SHA \`96cb1efb992d4ef40d4ef3eb2ab0bed5706fcf52\`.

## Operating question
When a merchant call times out or its acknowledgement is lost, what external provider evidence must be obtained *before* the financial ledger may be updated, what changes should be held, and which duplicate or reversal events require independent investigation?

The only data tested in this milestone comes from an **in-process fake provider** that signs synthetic snapshots using a newly generated Java P-256 keypair. It is not a live payment service; no real captures, refunds or settlements are performed. This runbook is a proposed acceptance protocol, not an authorization to execute a financial operation.

## Decision matrix

| Verified state / condition | Synthetic decision | Future authorized response |
| --- | --- | --- |
| Missing or unreachable provider state | HOLD: no ledger | Query independently trusted provider status; investigate |
| PENDING / UNKNOWN | HOLD: no ledger | Poll with bounds, surface manual review; no blind re-charge |
| DECLINED | HOLD: no ledger | Confirm final charge state and customer action |
| CAPTURED, exact signed identities, units and ownership | One atomic local posting | Independently reconcile merchant charge, accounting and local intent before posting |
| CAPTURED, exact already-posted receipt | Return original local ID | Idempotent local acknowledgement recovery |
| Reused provider transaction under different request | HOLD: conflicting identity | Manual fraud/replay/settlement investigation |
| Reused request under different provider transaction | HOLD: conflicting identity | Reconcile authoritative provider and local records |
| REFUNDED / DISPUTED | HOLD new payment | Independently approved reversal/dispute workflow; no invented journal |
| Invalid/forged signature or wrong merchant, currency, owner or amount | HOLD: reject | AppSec/security event and independent provider verification |
| Local ledger ID collision | Roll back entire local transaction | Correct allocator and reconcile; never silently reseed or bypass |

## Exact acceptance tests and expected proof
- **W3413-01:** A PENDING/no-response original request cannot mutate the ledger; later fake CAPTURED status is verified via read-only lookup, posted once and returned on replay. Zero recovery capture commands.
- **W3413-02:** PENDING, UNKNOWN, DECLINED, REFUNDED and DISPUTED snapshots never create local financial postings.
- **W3413-03:** A real P-256 test signature is checked on every canonical fake provider field; forged issuer, altered amount/currency/owner/merchant/status and mismatched signed request all fail before SQL.
- **W3413-04:** Concurrent same-receipt posting, provider transaction collision, request ID collision and legacy PAY transaction collision preserve atomicity; one distinct same-amount capture is accepted.

## Evidence and controls
GitHub Actions must verify the exact source head, preserve unchanged payment runtime/schema v5/inherited disposable fixtures, execute real PostgreSQL16 JUnit on Java 11 and 17 and perform the inherited SECURITY selection. Expected new roster: **418 full JUnit methods across 119 test classes**, and **151 SECURITY-tagged methods across 64 classes**, on each Java version. Four JSON scenario records in \`target/wave34-13-evidence\` contain exact commit SHA, synthetic ledger digest/counts, schema v5 and explicit \`real_provider_contacted=false\`, \`production_authorized=false\`. They contain no real provider credentials, customer data or signing private keys.

## Production release gate owners
**All remain OPEN.** Independent provider AppSec/issuer trust, real merchant capture API/settlement reconciliation and PVA-01..16; approved currency/minor units/refund/dispute accounting; ownership/consent and financial authorization; hardened non-superuser SQL trust, real provider idempotency and outbox; numeric payment-ID allocator; quorum stale-primary fencing; restore/RPO/RTO and controlled canary; legal/source rights; signed owner and independent DBA/AppSec/payments/QA/SRE signoffs. The historical Stage27 and Phase16 gate registers must be reconciled independently.

**A successful test establishes only the fake-provider recovery behavior. Production release is NO-GO.**
