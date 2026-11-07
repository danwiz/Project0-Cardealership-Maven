# Wave 26 — Private mTLS Credential Broker and Fenced PostgreSQL Rejoin

Baseline: Wave25 draft PR #25 at `a746b37a54b3eafcd3d23eab1c2522b1f038f480`.
Candidate is a review-only draft. No production deployment, SQL migration, certificate issuance or protected-master modification.

## Authenticated internal transport — disposable evidence

The test-only `Wave26MtlsCredentialServiceProcess` is a **separate child JVM** bound only to 127.0.0.1, using TLS 1.2 and requiring a client certificate. The actual signed client certificate is pinned by SHA-256 in the service configuration. A second rogue certificate signed by the **same test CA** must fail the application-level pin. Missing client certificates must not permit a credential mutation.

The TLS broker's process environment, not its client transport, receives separate PostgreSQL verifier and credential-writer login credentials. Only the private broker instance constructs `PrivateCustomerCredentialChangeService`. The external request includes synthetic username, current plaintext and replacement plaintext only under mTLS; no stored hash, session revision, writer object or database credential is returned. The internal Java writer independently rechecks current plaintext, role, persisted hash and session revision under PostgreSQL row lock before updating the credential.

The named JUnit integration test runs on actual disposable PostgreSQL16 with a private reader DB principal that is denied UPDATE and a separately granted credential-only writer. It proves valid rotation, old-password rejection, stale revision rotation, wrong plaintext rejection, no-cert rejection and **same-CA rogue client denial**.

**Not production-approved:** This is a small blocking TCP fixture, not hardened HTTP/gRPC, service-mesh or RPC. It has no production secrets, service identity lifecycle, CRL/OCSP enforcement, certificate renewal, authorization policy store, throttling, request tracing, HA, data isolation or deployment manifests. Crucially, the credential-writer SQL login can still issue raw `UPDATE(encoded_credential)` if stolen. Private Java checking does not prevent that. Production permission/isolation design and independent security review remain open.

## Fenced former-primary rebuild into a read-only replica

Wave25 proved a stale old primary could be restarted **while disconnected** and remain locally writable. Wave26's successor lab extends that sequence:
1. Start actual dealership PostgreSQL16 primary and physical standby, migrate schema v5 and approve a synthetic vehicle sale.
2. Partition replication while old primary remains writable, then kill/fence it **before** promoting the standby.
3. Promote caught-up standby and reconcile one durable dealership ownership/payment plan without duplicates.
4. Restart old primary only **without network**, prove stale local writeability, and stop it again.
5. With old primary stopped and isolated, **destroy and fully replace its stale data directory** from a new `pg_basebackup -R` taken from the promoted leader. This destructive action is permitted solely against disposable, generated CI Docker volumes.
6. Create the replacement replica's standby signal and replication credential file.
7. Only after complete reinitialization, connect the old container to the isolated CI Docker network and restart it. Verify `pg_is_in_recovery()=true`, one streaming replica on the promoted leader, and actual dealership sale/ownership/plan preserved.

**Not a production failback certification:** This is physical replica reinitialization, not `pg_rewind`, orchestrator/quorum fencing, a production client-router policy, in-flight COMMIT while promoting, synchronous durability guarantee, failback to old leadership, or measured RTO/RPO. The old data is deliberately destroyed, so no production use is authorized.

## Acceptance and release

- Java11 and Java17: expected 372 full methods / 102 classes, SECURITY 143 methods / 58 classes; all 371 and 142 Wave25 method identities must remain present.
- Named real PostgreSQL mTLS test cannot be skipped and must pass on each JDK.
- Physical PostgreSQL16 rebuild/rejoin evidence must identify exact PR SHA and prove the former primary is read-only after rejoin.
- Preserve original CI artifact hashes, separate technical subchecks from human signoffs and keep all fourteen Stage27 release gates **NOT CLOSED**.

All production, owner, secrets, source-rights and legal permissions require separate approval. **Release remains NO-GO.**
