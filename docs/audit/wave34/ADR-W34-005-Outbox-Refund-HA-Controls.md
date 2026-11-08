# ADR-W34-005 — Transactional outbox, refunds/chargebacks, and high-availability release controls

**Status:** PROPOSED / NOT IMPLEMENTED. **NO-GO.** Source basis: Wave33 signed receipt/failover studies and Wave34.2 disposable atomic receipt posting. No real provider, refund, chargeback, outbox, failover router or customer data involved.

## Target event and ledger model (not existing schema)
- \`provider_payment_events\`: immutable provider ID, event ID, merchant namespace, event type, original charge/refund ID, authenticated payload digest, key ID, event sequence/occurred/received timestamp, verified status and currency unit. Separate raw sensitive material from limited retained evidence; retention/privacy decision required.
- \`ledger_entries\`: append-only debit/credit-style event with stable DB-assigned ID, immutable link to provider event and purchase/contract, amount in approved unit/currency, causal parent reference, actor/authorization evidence and posted timestamp. Avoid rewriting or deleting historical payment rows to express a refund.
- \`outbox_events\`: inserted in same SQL transaction as ledger posting. Event ID unique, payload hash, schema version, retry metadata and eligible timestamp. Publisher uses lease/claim and at-least-once delivery; consumers dedupe by immutable event ID. Do not call provider or publish Kafka/queue from inside SQL COMMIT path.
- \`provider_reconciliation_inbox\`: separately immutable receive/authenticate state so a delayed or duplicated webhook can be reviewed without double-posting. No replay of charge request on uncertainty.

## Accounting and legal review items
Refunds and chargebacks are not just negative payments. Settle full/partial refund caps, disputes, reversals, double-entry journal equivalence, FX, fees, allocation to installment/ownership, bookkeeping recognition, consumer timelines and legal obligations with the owner/payment operations. Reject event reordering that would violate an approved state machine. Never perform arbitrary \`UPDATE payment_transactions\` to erase a settled event.

## HA and safety
Wave33's test Docker kill is not production quorum fencing. Require independently selected witness/leader-epoch, hard stale-primary write denial, client routing, receiver idempotency after failover, quarantined former primary, replica rebuild and controlled failback. Measure RPO and RTO from business-approved start/end events, reconcile external provider committed charge IDs across the cutover, and define owner-approved recovery thresholds. Current tests are technology demonstrations only.

## Candidate end-to-end sequence
\`\`\`mermaid
sequenceDiagram
  participant Provider
  participant Verifier
  participant Reconciler
  participant PG as PostgreSQL
  participant Publisher
  Provider->>Verifier: signed event + merchant identity
  Verifier->>Provider: independently authenticated query of committed state
  Provider-->>Verifier: verified immutable charge/refund
  Verifier->>Reconciler: authenticated event (scoped authorization)
  Reconciler->>PG: BEGIN / claim provider event / lock ownership
  Reconciler->>PG: append ledger + receipt link + outbox
  PG-->>Reconciler: COMMIT with stable local event ID
  Reconciler-->>Provider: idempotent acknowledgement only
  Publisher->>PG: lease committed outbox event
  Publisher-->>Publisher: at-least-once delivery / monitor failures
\`\`\`

## Acceptance scenarios
1. Duplicate/simultaneous same provider event -> one ledger posting and one outbox event.
2. Lost provider ACK after COMMIT -> query provider + idempotent local reconciliation; never resubmit charge on uncertainty.
3. Stale leader attempted write after failover -> denied by actual fenced production-class router/witness, not only local Docker kill.
4. Partial/full refunds, chargeback, out-of-order webhook, replay with changed payload, cancellation and settlement correction -> append-only, correctly allocated and legally approved state transitions.
5. Outbox publisher crash before/after delivery and lease expiration -> no missed event, consumer-level dedupe (at-least-once, NOT magical exactly-once cross systems).
6. Incompatible currency units or amount rounding -> explicit reject; cannot silently reuse legacy integer semantics.

**Decision:** All operator, financial, legal, security, DB and HA approvals OPEN; no production infrastructure or schema changes authorized.