# Provider Authentication Acceptance Contract — Wave34.4

**Status: PROPOSED, REVIEW-ONLY, NOT EXECUTED. Production NO-GO.**
**Source:** ADR-W34-003 (independent provider trust), ADR-W34-005 (outbox/refunds/HA), Wave33SyntheticProviderSignature and ProviderPaymentIdentity at immutable Wave34.3 head 946fdaf2362aadff3e4dc38db36a253040242913.

## Scope and trust contract
The consumer must not authenticate a payment simply because it has an ID, a COMMITTED status or a 64-character hexadecimal signature. Production verification must be provided by an independently controlled merchant/provider service and must use a provider-approved crypto protocol with revocable keys, authenticated merchant credentials and authoritative status reconciliation.

A full receipt/event identity proposed for signing and/or independently querying:
- version, algorithm and provider signing key ID, merchant account ID and provider namespace;
- immutable request ID, provider transaction ID, event ID, event type and sequence;
- payer/consent or transaction authorization reference (minimize personally identifying content);
- customer identity (as authorization-linked mapping), ownership ID and contract ID;
- *precisely specified* amount numeric representation and approved currency, including minor-unit scheme;
- provider charge/settlement/refund/dispute status, event timestamp, issued-at, expiry, unique nonce;
- signed raw message bytes / protected headers and audience/purpose binding;
- provider API endpoint identity, webhook delivery channel and verified key material origin.

Verify the raw signed representation as specified by **the actual provider**; do not invent canonicalization differences or sign only a subset of money-bearing claims. Verify key algorithm/rotation/revocation and merchant ownership independently. A provider signature proves origin/integrity for the signed claims, not user authorization. Local ownership, consent, amount, currency, policy and accounting remain separate checks.

## Negative/security and resilience acceptance matrix (NOT EXECUTED)
| ID | Case | Required behavior / evidence | Review owner |
| --- | --- | --- | --- |
| PVA-01 | Valid independently signed and settled charge | One authenticated provider event and one atomic local ledger/outbox posting; exact-SHA trace | Payments/QA |
| PVA-02 | Tampered amount, currency, merchant ID or ownership mapping | Reject before local financial write, log privacy-safe mismatch | AppSec |
| PVA-03 | Unknown, expired or revoked signing key | Deny, quarantine, retry only lookup under newly trusted key set; never downgrade | AppSec/provider |
| PVA-04 | Different signature algorithm or ambiguous canonicalization | Fail closed; no algorithm substitution or unsupported encoding | AppSec |
| PVA-05 | Replay exact event/request | Same local transaction outcome and no balance/outbox duplication | DBA/QA |
| PVA-06 | Same request ID, changed body | Security conflict with immutable evidence; no second posting | AppSec/DBA |
| PVA-07 | Same provider transaction ID, another request ID | Conflict; no synthetic business fallback | DBA |
| PVA-08 | ACK lost after provider COMMIT | Query independently authenticated provider state; don't charge twice | Payments |
| PVA-09 | Signed PENDING, CANCELED, REFUNDED or DISPUTED | Apply approved state machine; never treat as a new COMMITTED capture | Finance/Legal |
| PVA-10 | Out-of-order partial refund/chargeback after settlement | Append-only balanced adjustments, correct capped totals, audit/outbox | Finance/Legal |
| PVA-11 | Provider unavailable or invalid TLS identity | Fail closed, durable retry queue and incident threshold, no automatic charge replay | SRE |
| PVA-12 | Wrong customer or ownership despite valid signature | Reject as unauthorized even when provider reports COMMITTED | AppSec |
| PVA-13 | Old primary isolated, standby promoted | Stale writes denied by router/quorum, original receipt deduped, provider state reconciled | DBA/SRE |
| PVA-14 | Known key compromised / key rotation | Verified revocation and bounded recovery, no acceptance via cached invalid key | AppSec |
| PVA-15 | Permission escalation/forged receipt verifier | Trust anchor outside application runtime and restricted principal with negative tests | AppSec |
| PVA-16 | Production rollback/restore | Ledger/outbox/inbox consistent after restore with owner-approved RPO/RTO | DBA/SRE |

## Test prerequisites (unmet)
Identified real merchant/payment provider, signed example payloads authorized for testing, provider identity and signing algorithm documentation, merchant API scopes, controlled independent test signer, approved currencies/minor units, payment domain state machine, data-retention/legal policy, original source-rights clearance, test environment whose resources are demonstrably non-production, independent reviewers and production-grade fencing. Synthetic HMAC from Wave33 is **not** independent provider-authentication acceptance. No provider has been selected, configured, authenticated or queried.

## Go/no-go
All PVA cases are **NOT RUN** for a real provider. Release NO-GO; Stage27 gates remain formally unreconciled and open. This is a contract for future implementation, not a passing integration suite.