# ADR-W34-003 — Independently authenticated payment-provider trust boundary

**Status:** PROPOSED / UNAPPROVED. **Release: NO-GO.** Architecture only. No real provider, credentials, keys, customer data, fund transfers, or payment endpoint.
**Scope/source:** Wave34.2 draft PR #35, immutable baseline c369e22a5dd87831286bf821bf7f59e30a9a7311; source classes ProviderPaymentIdentity, JdbcWave34PaymentReconciliationCandidate; Wave33SyntheticProviderSignature is test-only.

## Confirmed current-state limitation
Wave34.2 delegates receipt verification to a caller-provided ReceiptVerifier and pins a synthetic HMAC key in test source. The Wave33 HMAC covers request ID, transaction ID, customer, contract, amount and state; **it does not cryptographically bind** provider namespace, ownership ID, currency, merchant account, event ID, issue time, nonce, or payment-method ownership. Wave34.2's hard-coded provider/currency policy is a disposable laboratory protection, not independent payment authority. An identifier, valid hex length, or DB uniqueness constraint is not cryptographic proof.

## Decisions requiring owner, payment operations and AppSec authorization
- **Provider integration model:** independently owned verified merchant API query, signed webhook delivery, or both. Proposed default: both; webhook signals receipt, independently authenticated provider query confirms final settlement/charge state. Explicitly handle eventual consistency, delay, and provider downtime.
- **Workload identity:** provider-specific mTLS/verified server identity and scoped merchant credentials; signature trust anchored in provider-controlled key material; authenticating a local service account does not establish provider authorship. Select mTLS/OAuth/signature protocol based on actual provider contract.
- **Canonical receipt envelope v2:** provider/merchant namespace; immutable request and provider transaction ID; immutable event ID and sequence; internal customer/ownership/contract binding; amount with explicitly APPROVED minor-unit mapping and ISO 4217 currency; event type/state; event occurred/received timestamps; issued-at/expiry/nonce; algorithm and key ID; signature over every security-relevant field. Source event authenticity and merchant authorization are separate checks.
- **No trust on caller-controlled references:** independently resolve ownership, consent/payment mandate and customer account. Reject mismatched amount, currency, contract, provider merchant account, status or event state.
- **Key operations:** provider JWKS/certificate pin and rotation policy, revoked/retired key behavior, effective dates, cache freshness, signature downgrade denial, algorithm allowlist; no test HMAC or private key committed to source.
- **Event integrity:** verify raw signed payload before canonicalization, preserve digest plus scoped evidence and receipt timestamps, enforce nonce/expiry/replay policy, settle immutable ID to one local posting, and reconcile late/out-of-order/refunded/disputed events without destructive ledger edits.
- **Operations:** read-only provider-query identity, bounded retries/backoff, dead-letter queue, provider reconciliation SLA, rate limiting, availability/outage behavior, safe failure responses and incident audit trails. Never blindly resend a charge after unknown COMMIT.

## Recommended boundaries (architecture, not implementation)
\`\`\`mermaid
flowchart LR
  P[Independent provider merchant API] --> V[Verifier: authenticated merchant account, signature, timestamps, status]
  W[Authenticated signed webhook] --> V
  V --> E[Canonical immutable provider event]
  E --> R[Local reconciler: consent, ownership, contract, amount, currency]
  R --> T[(One PostgreSQL transaction)]
  T --> U[(Provider receipt uniqueness)]
  T --> L[(Append-only ledger and balance projection)]
  T --> O[(Transactional outbox and audit)]
  O --> C[Idempotent downstream consumers]
\`\`\`

## Negative acceptance
Reject forged MAC, unknown or revoked key, wrong merchant namespace, stale/expired event, mismatched currency/minor units, customer/ownership/contract mismatch, duplicate event ID with mutated body, provider request and transaction cross-binding, status regression, out-of-order refund/chargeback, partial network loss, and untrusted issuer. Verify each at an exact commit in disposable staging with an independently operated **test provider** before production approval.

## Alternatives
A. Signed webhook alone: low operational latency, but lost webhook and replay/rotation need separate reconciliation. **Not sufficient as sole authority without provider policy.**
B. Trusted merchant API query alone: clearer state confirmation but polling/event latency and outage gaps.
C. Signed webhook **plus** authenticated merchant API reconciliation: proposed default, greater integration cost, better uncertainty recovery. Owner/payment operations must ratify the provider-specific final design.

**Decision:** OPEN. No production verifier implementation authorized. Historic Stage27 14 gates not closed by this ADR.