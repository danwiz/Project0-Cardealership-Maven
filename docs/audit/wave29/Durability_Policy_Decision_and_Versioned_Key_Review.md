# Wave29 — Durable Commit Policy Decision and Versioned Credential-Key Lifecycle

**Status:** PROPOSED / DECISION REQUIRED — no production database, security key or branch modifications.

**Source:** Wave28 PR #28 commit `c0f802e4010d066f8a5256b7df5646f2760e7487`. Wave28 independently demonstrated an old-primary COMMIT absent from a lagging asynchronous standby, contrasted with a `remote_apply` COMMIT blocked by loss of its synchronous replica. Wave29 proposes the decisions below, but **does not invent owner approval or a business SLA**.

## A. Durability policy decision

The owner must identify tolerable loss and outage *before* selecting a PostgreSQL replication setting. Suggested default for regulated or revenue-bearing transactions is **no acknowledged vehicle-payment or contract changes may be silently lost**, with operator-reviewed trade-offs and external payment-ledger reconciliation. This is a proposed risk objective, not an approved promise of zero RPO.

| Workload | Initial risk class (proposal) | Required owner decision |
| --- | --- | --- |
| Accepted vehicle purchase + contract | Critical | Maximum acknowledged purchase loss, inventory consistency and client response behavior |
| Payment posting, installment, refund | Critical | External payment processor source of truth, exactly-once idempotency, max acceptable loss |
| Customer credential and session revocation | Critical | Staleness tolerance and whether unsafe primary can authenticate after failover |
| Inventory and listing edits | High | Acceptable lost/replayed changes, oversell prevention |
| Audit and security events | High | Immutable external sink and replay reconciliation, retention |
| Reporting, derived dashboards | Moderate | Rebuildability and staleness tolerance |

### PostgreSQL 16 policy distinctions

- **Asynchronous streaming**: `synchronous_standby_names` empty. Local `synchronous_commit=on` means local WAL flush; a transaction may already have committed on old primary and still be absent on a disconnected standby. Wave28 **actually observed this case** on synthetic dealership data.
- **Synchronous `remote_write`**: with a required synchronous standby, COMMIT waits for acknowledgment that standby has received WAL and written it to its operating system, but not necessarily that WAL has been durably flushed or applied. Stronger than asynchronous transport acknowledgment, weaker than `remote_apply`.
- **Synchronous `on`**: waits for the required standby's WAL to be durably flushed, not necessarily applied/visible to a read-only standby query.
- **Synchronous `remote_apply`**: waits until required standby reports that WAL has been applied. Wave28 observed `SyncRep` blocking during disconnection. This can increase confirmation latency and impact write availability.

None of these settings alone solves application idempotency, external payment settlement, fencing/quorum, cluster failback, business RTO, or invalid client routing. Production role changes require independent DBA/operations assessment.

### Decision options (not pre-selected)

- **Option A — asynchronous with reconciliation:** best write availability, permits committed unreplicated transactions to be lost after forced promotion; not recommended for accepted monetary contracts without explicit owner acceptance and external durable ledger.
- **Option B — synchronous `remote_write`:** wait for standby OS-write acknowledgement, reduced availability if replica unavailable, remaining durability exposure to standby OS/power loss.
- **Option C — synchronous `on`:** standby WAL flush acknowledgment, typically appropriate if durable remote log record is the objective; application still must handle unknown replies and replay safely.
- **Option D — synchronous `remote_apply`:** standby replay confirmation, potentially highest latency/availability impact; useful if read consistency after acknowledged commit is a hard requirement.

**Owner decision record:** REQUIRED, NOT SUBMITTED. Capture numeric RPO/RTO objectives (and measuring events), acceptable exceptions, operational quorum/fence policy, and authoritative financial ledger source. Do not deploy a setting solely because its CI fixture passes.

## B. Wave29 versioned credential key execution

An opt-in `JdbcVersionedSignedCustomerCredentialWriter` and test-only PG16 `rotate_customer_credential_v29` function add:

1. Key IDs and owner-only key state: `SIGNING_ACTIVE`, `VERIFY_ONLY`, `REVOKED`; `not_before`, `not_after`, `signing_disabled_epoch`.
2. HMAC domain separation (`W29v1`, exact issuer and password-rotation purpose), tightly constrained ASCII fields and fixed canonical field positions.
3. A maximum 120-second DB-enforced validity window (the private signer produces 90 seconds), server-clock skew check and deny-by-default unknown/revoked key handling.
4. Verify-only rollover: pre-cutover signed intents may complete in a bounded window, while newly issued intents for the retired key are denied.
5. Emergency key revocation: rejects even an unexpired, correctly signed request.
6. Persisted account role/hash/session-generation CAS and one-use nonce consumed in the same transaction.
7. Separate PostgreSQL SQL executor principal with owner-function EXECUTE only; no direct account hash UPDATE, key SELECT, nonce SELECT or administrator authority.

**Security scope:** the SQL function is a disposable PG16 fixture, not a versioned production migration. The private signer is an opt-in Java class, not a deployed/approved key-management system. Its signing secret and SQL EXECUTE capability must not be co-compromised; HMAC cannot solve that combined threat. The implementation still lacks key custody independent of database owner, client authentication policy, signature revocation after signer compromise, production issuer registry, audit/rate limiting and owner-approved key rotation.

### Explicit test limitations

- Fixed ASCII canonicalization is reviewable for this fixture, but production must define structured canonical encoding, Unicode and account-name policy.
- `VERIFY_ONLY` cutover is enforced through the issue timestamp and key state, not an independently attested signing service event: if signer key is compromised, a malicious actor could backdate within the allowed TTL. Require signed issuance audit, short windows, and independent service controls.
- A compromised signing key **and** SQL-executor identity can still authorize arbitrary changes. This remains critical.
- Nonces are stored indefinitely in the test schema; production retention and cleanup cannot delete active replay protections.
- The new code does not modify `DealershipCompositionRoot`; release approval and customer-service integration remain separate.

## C. Release controls

The expected tests are 374 full JUnit methods and 145 SECURITY methods on Java11/17, retaining every Wave28 method identity, plus separate PostgreSQL16 `remote_write` physical-replication evidence. Preserve exact Git SHA, original Surefire XML, SHA-256 artifact custody, failure traces and formal gate statuses.

All fourteen Stage27 gates remain **NOT CLOSED**, pending independently submitted DBA, AppSec, QA, architecture, source-rights and release-owner reviews.

**Release disposition: NO-GO.**
