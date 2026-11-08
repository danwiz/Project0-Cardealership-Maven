# ADR-W34-001 — External provider payment identity (review candidate)

Status: PROPOSED / OWNER AND INDEPENDENT REVIEW REQUIRED; RELEASE NO-GO.
Scope: Wave 34.1. Synthetic-only; no live payment provider, money movement, production migrations, user-facing payment commands, deployment or master edits.

Baseline: PR #33 at 7793e5adc28b7415a9dbb0a2d8797ed686f3105c, review branch audit/wave33-provider-signed-receipts-concurrency-20261107.

## Source finding
The current PaymentProcessor.record(customer, ownershipId, amount) lacks immutable external provider identity. Two same-amount calls create two local payments. This is valid for two real installments but incorrect for a retry of one already-committed provider charge with a lost ACK. Wave33's signed reconciliation model lives only in disposable test fixtures.

## Proposed boundary
1. Provider proof is verified independently, before local SQL, by an approved merchant/provider adapter. The provider owns the signing key; user-provided IDs, local HMAC test keys and a self-reported COMMITTED status do not constitute trust.
2. An authenticated receipt binds provider namespace, immutable provider request ID, provider transaction ID, local customer, ownership, contract, amount, currency, status, key ID and signature.
3. The idempotency key is (provider, provider_request_id); (provider, provider_transaction_id) is separately unique. Identical authenticated replay returns its previous local transaction, without updating balances. Same key with different canonical claims is a hard conflict and must produce a security/audit record. Distinct external requests with the same amount are legitimate only after authorization.
4. Under one SERIALIZABLE PostgreSQL transaction: claim unique provider identity, lock and verify the approved ownership/plan, update the plan, append immutable payment transaction and auditable receipt/outbox, bind local transaction, COMMIT. Retries apply to the full local transaction only, never to the external charge.
5. Currency/minor-unit mapping is NOT approved: existing int amount fields are not silently assumed to represent cents. No production posting code or migration is authorized here.

## Deliberately narrow Wave34.1 deliverables
- Immutable, strict Java identity and collision comparison contract in main source, with no payment-posting capability.
- A REVIEW-ONLY candidate additive SQL table in docs/audit/wave34/; not wired to schema_history or JdbcDatabase.migrate().
- A real PostgreSQL16 CI-only acceptance test for uniqueness, simultaneous duplicate submissions and conflicting claims. The test is not proof of application payment reconciliation.
- A draft stacked PR targeting Wave33. No merges or deployments.

## Formal decisions still open
Provider API and real verifier/KMS; source and business rights; credential and principal boundaries; migration v6 and schema grants; legal PCI/privacy handling; money units/multicurrency; provider receipt lifecycle and refunds/chargebacks; old PAY-ID allocator race; client routing/fencing; owner-approved RPO/RTO; PostgreSQL production concurrency/atomic plan+ledger integration; independent AppSec, DBA, QA and payments review.

Security baseline: Stage27 14/14 release gates remain formally open in Wave33 documentation. Phase16's 17/12 gate counts are a different historical register, not directly comparable. GO is NOT authorized.

## Acceptance
Java 11/17 contract tests and PostgreSQL16 CI must run at exact PR head SHA, show non-skipped results, and publish artifacts. Until a separately reviewed Wave34.2 application reconciliation transaction is implemented, the external payment idempotency defect is OPEN.