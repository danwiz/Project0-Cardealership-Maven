# Wave 31 — Separate Receipt Issuer and Four-Policy Positive WAL Evidence

**Source baseline:** Wave30 draft PR #30, exact commit `9fc24c1bc4e56b7950a93ec7947f38b327b07657`.  
**Review boundary:** New draft branch only. No release, merge, deployment, production SQL, customer records, operational credentials, source-rights decision or cloud archive write.  
**Release:** NO-GO; 14 Stage27 gates remain formally NOT CLOSED.

## 1. Independent receipt issuer SQL identity, not production issuer authentication

Wave30 required a pre-cutover, owner-registered receipt before a VERIFY_ONLY signing key could authorize a credential change. That receipt was created directly by the schema owner in the JUnit setup. Wave31 introduces a **separate unprivileged PostgreSQL receipt-issuer identity** and a narrow owner-controlled `SECURITY DEFINER` registration function.

The issuer SQL login has function EXECUTE only. It cannot SELECT/INSERT/UPDATE/DELETE receipts, read account credential hashes or read signing-key bytes; the existing credential mutation SQL executor cannot call the issuance function. The owner-controlled registration function validates:

- Fixed issuer and customer-password-rotation purpose, CUSTOMER role and persisted session revision/hash.
- The independently owner-controlled signing key is currently SIGNING_ACTIVE, with a usable version and validity window.
- Exact HMAC of domain-separated signed fields, a fresh UUID nonce, and bounded issued/expiry epochs.
- Atomic nonce uniqueness under the protected owner table.
- An actual database-clock registration timestamp, serialized with key state change through a key-row share lock.

After the key becomes VERIFY_ONLY, the credential-mutation function still requires the exact matching, **pre-cutover** registered receipt; a newly fabricated HMAC with a backdated user-supplied issuance timestamp does not suffice. The extended real PostgreSQL16 JUnit case must check that the issuer can register a legitimate pre-cutover intent once, cannot duplicate it, cannot register after cutoff and cannot perform raw SQL or read private key material. Existing wrong-password, key expiry, revocation, nonce replay and CUSTOMER-only checks remain in the same 374 full / 145 SECURITY identity baseline.

**Important limitation:** The issuer database login is a **distinct database principal**, not a deployed mTLS-authenticated service and not cryptographic proof of an independent user authentication event. If the issuer identity and active signer key are both compromised before cutover, the function may accept a valid malicious intent. A compromised schema owner or receipt registry is also outside this test's defenses. Production still requires reviewed service identities, KMS/vault ownership, key versioning/rotation, audit rate controls, signed canonicalization, incident revocation and independent AppSec/DBA approval. No release gate closes merely because this test passes.

## 2. Actual acknowledged dealership COMMIT with connected physical standby

Wave28–30 tested bounded outage/loss scenarios: asynchronous WAL loss, required remote_write/remote_apply/remote-flush standby disconnect blocking, and idempotent retry. Wave31 provides a separate **positive connected-standby matrix**: `async`, `remote_write`, synchronous `on` and `remote_apply`.

Each isolated PostgreSQL16 matrix job:
1. Creates a disposable primary and physical standby, loads schema v5 and seeds an actual pending synthetic vehicle purchase.
2. Configures the requested WAL policy and waits for streaming, plus `sync_state='sync'` when a synchronous standby is required.
3. Runs an actual Java dealership purchase through the production service implementation on the old primary. A **successful client-visible COMMIT response** is required, not a deliberately dropped acknowledgment.
4. Samples `pg_current_wal_lsn`, the primary's `pg_stat_replication.write_lsn/flush_lsn/replay_lsn`, and physical standby receive/replay LSN values. These readings occur **after** client acknowledgment and should not be misdescribed as atomic WAL positions at the precise COMMIT timestamp.
5. Explicitly waits until the standby independently reports the accepted purchase, exactly one ownership and plan, and stock decremented once.
6. Fences/stops the old primary **before** promoting the already-caught-up standby. A fresh application replays the same request and proves idempotency: one contract/ownership/payment plan and no duplicate inventory decrement.

**Important limitation:** The async case proves survival only after the lab explicitly waits for the standby to catch up. It does **not** eliminate Wave28's async loss scenario. Positive connected standby evidence does not measure numeric production RPO/RTO, HA witness/quorum behavior, old-primary split brain, load, external payment settlement, or client routing failback. `remote_write` acknowledgment need not imply remote fsync or SQL replay; `on` means remote flush; `remote_apply` means replay acknowledged. See PostgreSQL16 WAL settings.

## 3. Owner decisions and acceptance

The prior owner RPO/RTO worksheet remains **NOT DECIDED** for monetary contracts, refunds, installment posting, credential revocation, inventory, security audit and derived reports. The project owner must approve measurable objectives and authoritative payment-ledger reconciliation before choosing production settings.

Exact-SHA Java11/17 JUnit verification must preserve the 374 full and 145 SECURITY method identities. Four separate physical PostgreSQL jobs must independently PASS with exact-SHA evidence. Record original zipped artifacts and SHA256 chain of custody, and keep all prior failed CI attempts in diagnosis logs.

Broader supply-chain advisory coverage for Maven build plugins, original imported educational code rights, historical serialized-data migration, proper operational HA fencing and named independent AppSec/DBA/QA/architect/source-rights/operations/release-owner reviews remain unfinished.

**Release: NO-GO. All 14 Stage27 gates formally NOT CLOSED.**
