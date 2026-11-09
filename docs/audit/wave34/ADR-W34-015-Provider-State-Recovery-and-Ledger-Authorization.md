# ADR-W34-015 — Independent Provider-State Recovery and Financial Posting Authorization

**Status: PROPOSED / NOT RATIFIED. Production NO-GO.**
**Parent:** Wave34.12 draft PR #45, exact SHA \`96cb1efb992d4ef40d4ef3eb2ab0bed5706fcf52\`; real PG16 CI workflow #42681003121 PASS (414 full JUnit methods / 118 classes and 151 SECURITY-tagged methods / 64 classes for Java11 and Java17).

## Problem
Wave34.12 proved that a lost **local database acknowledgement** can be resolved by a retry of the same restricted SQL intent without producing another local payment. It did **not** establish whether an external provider has authorized, captured, settled, refunded or disputed a payment following an unavailable or delayed response. A production system may not infer capture from an attempted request, successful API transport or a prior unsigned status update.

Wave34.13 introduces a **synthetic provider read-model** and a database-backed local capture claim. The fake provider returns an ECDSA P-256-signed snapshot of request identity, provider and merchant identity, customer, ownership ID, synthetic amount, currency, status and observation time. The test consumer validates the snapshot against pinned **test-only** public key and exact local expectations. Only status \`CAPTURED\` authorizes a synthetic local ledger posting.

**Vital distinction:** Both signer and verifier run in the same JUnit process. There is NO independent external merchant account, live provider status API, genuine provider signing key, real settlement, approved financial policy or production SQL trust boundary. This work must not be described as real provider acceptance or reconciliation.

## Proposed recovery sequence (laboratory only)

1. Receive an uncertain request result. Do **not** blindly create a second provider charge or assume the payment failed.
2. Read current provider state by **the original client request ID**, using a provider identity and merchant configuration independently authenticated in a future production design. The current lab uses only an in-memory fake.
3. Reject missing state, PENDING, UNKNOWN, DECLINED, REFUNDED or DISPUTED as a new successful payment. Queue a manual/operator reconciliation decision for refunded/disputed transactions; never invent a reversal ledger entry.
4. Verify issuer public-key provenance (future production prerequisite), full signature, provider/merchant/request/transaction identity, customer/ownership, currency, synthetic amount, time window and CAPTURED state.
5. In a single disposable PG16 transaction, insert a provider capture record with UNIQUE(provider,merchant,transaction) and UNIQUE(provider,merchant,request), increment the synthetic payment plan, insert its associated local ledger entry and bind the local transaction ID. Any failed ledger constraint or business validation rolls the whole operation back.
6. On exact replay, compare the original immutable signed-claim digest and all financial/ownership fields and return the original local reference. Do not duplicate the ledger mutation. On a conflicting provider transaction ID or request ID, HOLD and preserve evidence rather than guessing which charge is correct.

No external capture, settlement, refund, dispute, webhook or REST call is made by Wave34.13; it characterizes the **consumer-side decision** only.

## Test cases

| ID | Named test | Expected evidence |
| --- | --- | --- |
| W3413-01 | missingCaptureStatusIsHeldUntilFreshSignedProviderQueryConfirmsCapture | Missing/PENDING held; fake captured status (simulated lost ACK) permits one posting and repeat returns original result; no recovery capture calls |
| W3413-02 | pendingUnknownDeclinedRefundedOrDisputedStatusNeverPostsAsNewPayment | Five signed non-CAPTURED states cannot create new local postings; balance unchanged |
| W3413-03 | forgedOrMismatchedProviderClaimsCannotAuthorizeLedgerPosting | Tampered amount/currency/owner/merchant/status, forged signer, and valid but mismatched request rejected before SQL |
| W3413-04 | concurrentCaptureReplayAndConflictingProviderIdsFailClosedWithAtomicRollback | Four concurrent recoveries post once; reused provider transaction with new request, reused request with new transaction HOLD; duplicate local PAY ID rolls back; truly distinct capture succeeds |

The JUnit test seeds only synthetic buyer/vehicle/plan rows inside random \`w3413_...\` PostgreSQL16 schemas after the unchanged application schema v5 bootstrap. The DDL is a separate \`DISPOSABLE\` fixture file and is never added to the runtime migrator.

## Financial model and accounting caveats

- Test \`amount_minor=1\`, \`currency=USD\` and the existing integer legacy plan amounts are **synthetic fixtures**, not an approved mapping from real provider minor units into application financial units. Currency, tax, fees, rounding and precision require independent Finance approval.
- Successful provider capture is not necessarily final settlement; merchant payment processing, refunds, disputes and chargebacks require an event-sourced/double-entry or equally audited reversals and settlement ledger design. This test deliberately does not generate refund or chargeback entries.
- The existing public \`PaymentProcessor.record\` and legacy lexicographic PAY ID generator are unchanged. A separate controlled request boundary and numeric/identity allocator modernization remain mandatory.
- Independently approved provider verification must authenticate the provider account, signing authority, key rotation/revocation, merchant binding, charge/capture object and freshness via official provider APIs. Merely receiving an ECDSA signature from a test key does not prove merchant trust.
- Real provider capture may be unavailable, pending, reversed, duplicated, delayed, or acknowledged after local failure. The read-only status-recovery routine must be idempotent and fail closed with manual investigation where authoritative truth cannot be established.
- Full production readiness requires durable request/receipt custody, authorization-to-DB enforcement, non-superuser DBA ownership, client routing and old-primary fencing, approved versioned DDL, backup/restore and measured RPO/RTO.

## Release decision
All original Stage27 (14-gate) and earlier Phase16 (17/12 reported) release assessments remain separately unreconciled, provider PVA-01..16 **NOT RUN**, and independent AppSec/DBA/finance/payments/SRE/QA/legal/owner signoffs pending. Existing draft PRs remain unmerged. **NO-GO: no production issuer, provider connection, real payment, schema migration, deployment or merge authorized.**
