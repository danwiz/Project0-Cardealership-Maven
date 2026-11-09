# ADR-W34-021 — Witness Checkpoints, Revocation-At-Insert and Controlled Adjustment Outbox

**Status: PROPOSED / NOT RATIFIED / PRODUCTION NO-GO.**
**Parent:** Wave34.18 draft PR #52 exact SHA \`c55812a0662fd1199b5b37be598d7cdf2bc5520b\`, GitHub Actions #42715248420 SUCCESS. Java11 and Java17 each passed 438/438 full JUnit methods across 124 classes and 151/151 independently selected SECURITY methods across 64 classes; eight review-only files. Protected \`master\` remains separate. Inherited synthetic replay issues #51 and #53 are still open pending stress testing.

## Decision and why

Wave34.18 proved a local SHA-256 hash chain, synthetic approval limits/revocation, and current-period metadata that referenced but did not rewrite an earlier closed accounting period. It did **not** establish independent evidence custody, witness-controlled signing keys, adversarial revocation timing or reliable delivery to an accounting system.

This incremental Wave34.19 design creates **only a random disposable PostgreSQL16 test schema** (\`w3419_...\`) after unmodified application v5 migration. It adds a test witness receipt table, a transaction-locked principal check, a local adjustment + chain + outbox atomic commit, and a separately acknowledged delivery receipt. It **does not** call an external witness service, real bank/merchant/GL, or deploy an outbox/finance system.

## Five components and ownership boundaries

| Test component | Implemented boundary | Not established |
| --- | --- | --- |
| Historical/current periods | Closed historical vs open current in a synthetic adjustment | Actual finance period or statutory correction |
| Maker/checker test principals | SQL locks/active/role/limit check during INSERT | Independent identity provider, lawful finance authority or actual human consent |
| Hash chain + cursor | Serial append of claim digest/hash with transactionally advanced local cursor | External WORM, third-party timestamp or unalterable independent anchor |
| Witness receipt | Synthetic P-256 signature on (sequence, tip) in same DB, key held by same JVM | An independently operated witness, independent public key distribution, custody separation |
| Transactional adjustment outbox | Atomic adjustment/chain/PENDING message; one local ACK receipt per message | Exactly-once external delivery, durable broker, independent receiver or production GL transfer |

The phrase **witness** in this lab refers only to a simulation. Both private signing keys and the witness key originate inside one Java test JVM; the receipt and source chain share one database and one privileged CI DBA. The test owner can disable a chain trigger, modify local chain data, and could also modify the witness receipt. This proof is **not independently anchored evidence**.

## Evidence envelope and tamper detection

A canonical length-delimited request includes adjustment/source identifiers, source digest, historical/posting periods, synthetic USD amount and a test domain label. Each maker and checker signs (role, identity, request digest, approval UUID, expiry) with a **test-local** P-256 key. The accepted chain digest binds the request digest and both approval UUIDs:

\`claimDigest = SHA256(canonical("W34.19|CLAIM|v1", requestDigest, makerApprovalId, checkerApprovalId))\`

\`eventHash = SHA256(canonical("W34.19|LINK|v1", previousHash, claimDigest))\`

The witness signature binds the latest chain sequence and tip to a distinct domain string. A test-only privileged mutation disables the synthetic chain UPDATE trigger and changes the first stored hash; an unmodified local receipt and chain validator detect the discrepancy. The test restores the synthetic row for final key-free evidence. **A coordinated DBA modification of both chain and receipt is not prevented**; an external witness/KMS/WORM design and independently stored root must be implemented and assessed before release.

## Revocation-at-decision and amount limits

The \`BEFORE INSERT\` trigger obtains PostgreSQL \`FOR UPDATE\` locks on historic/current periods and both principal rows, and checks active status, role, max amount and period states. A test opens a transaction that revokes the checker while retaining the row lock. A second connection with a previously signed request blocks while the revocation is uncommitted. After the revoker commits, the SQL insert must fail with SQLSTATE 42501. This is a **one-node locking demonstration**, not an external identity revocation guarantee and not proof of approval revocation propagation across regions, queues, or already executed transfers.

Authenticating the P-256 signatures occurs in test Java before attempting the SQL insert. Database triggers **do not** authenticate a real signing credential or validate cryptographic signatures. A compromised privileged SQL actor can bypass the layer. Exact committed adjustment replay is read-only/idempotent; revoked future approval does not retroactively cancel an earlier completed adjustment.

## Controlled adjustment outbox / delivery semantics

The primary transaction creates exactly one adjustment metadata row, its chain link, **one PENDING outbox message** and cursor increment. A deterministic message ID and digest are read back on exact replay. A conflict on another adjustment's source reference or approval UUID fails closed; it never creates a phantom successful record.

A delivery worker simulation reads a pending message. If it crashes **before ACK**, a second attempt may return the same message. A test-local receipt transaction atomically records an ACK reference and changes that local message state to ACKED. A retry of the same ACK is idempotent; changed ACK/reference is rejected. **This guarantees neither exactly-once external transport nor exactly-once accounting effects.** The production design requires downstream idempotency keys, durable receipt/retry records, a broker recovery protocol, dead-letter queue, poison payload handling, backoff, monotonic causal cutoffs and reconciled provider/GL acknowledgments.

## Test acceptance cases

| Case | Named method | Bounded result |
| --- | --- | --- |
| W3419-01 | signedLabWitnessCheckpointDetectsTamperedPrimaryWithoutIndependentCustody | Two test-chain events and a signed local witness; privilege-bypassed primary tamper detected, trigger restored and witness verifies |
| W3419-02 | revocationCommittedWhileStaleApprovalWaitsMustDenySqlInsert | Fake stale signed approval blocks on checker row lock until revocation commits; SQL denies 42501; active alternate checker and within-limit amount can create a test adjustment |
| W3419-03 | crashBeforeAckPermitsRedeliveryButOneLocalReceiptAndOutbox | Multiple delivery attempts before ACK; no extra adjustment/outbox; one local ACK receipt; changed ACK and DELETE rejected |
| W3419-04 | concurrentExactEnqueueIsIdempotentAndApprovalReuseAcrossIdsHeld | Four simultaneous identical approvals enqueue once; different adjustment using same approval IDs held without changing chain/outbox; valid new adjustment succeeds |

Every method emits a **key-free** exact-head JSON evidence manifest with counts, chain tip, schema v5, no legacy payment entries, and explicit false flags for real witness, finance identity provider, provider delivery, external exactly-once semantics, production outbox and release authority.

Expected exact-commit CI matrix: **442 full JUnit methods across 125 classes per Java11 and Java17**, plus **151 SECURITY-tagged methods across 64 classes on each**, all against disposable PostgreSQL16. The source gate prohibits edits to production main Java, schema migration, previous Wave34 fixtures and inherited Wave34.15–18 test repairs. No new production migration.

## Decisions deferred / NO-GO

- Evidence service operator, independent witness public keys and KMS/HSM controls, trusted timestamp/notary/replication strategy, tamper monitoring, retention and immutable WORM anchors are NOT IMPLEMENTED.
- Human approval/IdP revocation stream, threshold rules, decision-time authority, token provenance and independent Controller consent are NOT IMPLEMENTED.
- Financial legal-entity COA, real prior-period debit/credit journal, source reconciliation, authorization and accounting correction rules remain NOT APPROVED.
- External merchant PVA-01..16, real delivery consumer and idempotency, reliable FIFO/ordering, outbox backoff, dead-letter handling, HA fencing, backup/restore and measured recovery targets are NOT VERIFIED.
- Legacy public unkeyed \`PaymentProcessor.record\` and \`PAY-999999\` vs \`PAY-1000000\` allocator flaw remain unresolved. Issues #51 and #53 need sustained randomized concurrency/independent review.
- Stage27 14-gate/0 formally closed and Phase16 17-gate/12 blocker registers are distinct. Formal DBA/AppSec/QA/Controller/Legal/Owner signoffs are pending.

**Review-only synthetic evidence — PRODUCTION NO-GO.** Keep draft, unmerged; no deployment, real provider/bank communication, financial transfer or period close.
