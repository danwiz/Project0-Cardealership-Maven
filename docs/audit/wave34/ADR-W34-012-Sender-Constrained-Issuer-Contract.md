# ADR-W34-012 — Issuer-signed, sender-constrained lease acceptance contract

**Status:** PROPOSED / UNAPPROVED. **Production release: NO-GO.**
**Immutable source parent:** Wave34.9 draft PR #42 at 0ddb87448705f3d091693e508b730b21348fbfc2, full PG16 workflow 42657540277 SUCCESS. The prior stacked PRs remain draft/unmerged.

## Scope and meaning of independence

Wave34.9 used PostgreSQL-local bearer UUID leases and authenticated LOGIN roles. A stolen bearer could be reused by the same writer before revocation; the CI DBA controlled issuer/roles and could bypass enforcement. Wave34.10 studies a **cryptographic issuer contract** with two different keys: the issuer signs lease claims; the sender separately signs a one-time challenge bound to the exact request.

Issuer and verifier exist **inside one JUnit test process**, not as separate operational services. Their private keys are freshly generated on every run with JCA P-256 ECDSA. A passing test does NOT establish independent issuer ownership, mTLS sender constraints, device-bound secrets, HSM/KMS custody, distributed replay prevention, real merchant identity, HA quorum or a production trusted authority. No persistent PostgreSQL migration or production runtime changes are involved.

## Contract v1 — review-only, not a wire standard

Lease claims are domain-separated, deterministically length-prefixed binary strings (not JWT, CBOR or JSON canonicalization); signed with SHA256withECDSA over:
- protocol marker and approved algorithm identifier;
- issuer key ID (kid), authenticated writer subject, audience, tenant;
- immutable lease JTI; SHA-256 fingerprint of the intended sender public key;
- monotonic writer epoch; issued-at and expiration in seconds.

Sender proof uses a distinct EC private key and signature over:
- protocol marker, server-issued one-time nonce, lease JTI;
- exact subject, request ID, operation, ownership ID and synthetic amount.

The verifier validates issuer key trust/revocation, signature, audience and tenant, writer identity, epoch, 30-second maximum lease life, expiry/issued-at, lease revocation, sender public-key fingerprint, nonce issuance/expiry and request field equality. It verifies the sender signature before **atomically removing** the one-time challenge. The challenges and revocation state are volatile in-memory maps: this is a single-process concurrency demonstration, not a durable distributed replay solution. Signed claims are only as authoritative as the independently verified claims source.

## Reproducible negative acceptance cases

| ID | Test method | Requirement / limitation |
| --- | --- | --- |
| W3410-01 | authenticIssuerSignatureRequiredAndTamperedLeaseClaimsRejected | Reject signed-claim tampering and forged key claiming trusted kid |
| W3410-02 | senderProofIsBoundToWriterKeyAndExactRequestFields | Reject substituted writer key, altered synthetic amount or ownership |
| W3410-03 | exactChallengeReplayAndConcurrentDoubleAuthorizationAreRejected | One concurrent exact-nonce authorization succeeds in one verifier; reuse, stale nonce denied |
| W3410-04 | expiryRevocationIssuerRotationEpochAndAudienceFailClosed | Reject expired/revoked/old-epoch/wrong-audience issuer claims; accept rotated trusted key |

Test-only P-256 keypairs are not stored in the repository, committed to GitHub or written into evidence artifacts. The four synthetic manifests record exact SHA, scenario and count, never raw keys, nonces or signed bearer tokens.

## Production design choices still open

- True **separate issuer authority** and revocation/rotation: independent workload identity, credential/KMS/HSM protection, cryptographic algorithm selection, trust bootstrap, key ID provenance, signed policy and audit.
- **Sender proof security:** sender-held nonexportable private key or mTLS channel binding, server-generated nonce, transaction context binding, short expiry and verified issuer audience; prevent challenge substitution, credential stealing, signature replay and delegated misuse.
- **Durable anti-replay:** DB/consensus-backed JTI+nonce/charge-request uniqueness and transactional consumption under failover; no local in-memory set as production evidence. Authoritative provider-charge idempotency separately required.
- **Lease lifecycle:** epoch issuance/fencing with independent quorum, old-primary hard write denial, long-running transaction policy, clock skew, partition recovery and revocation dissemination.
- **DBA migration:** non-superuser SECURITY DEFINER ownership, grants, independent issuer linkage, real schema v6 design, rollback/recovery.
- **Payments/business:** resolved legacy PAY-ID collision, provider-issued charge receipts with separately authenticated status, finance/accounting units, refunds/chargebacks/outbox, source rights, all original release gates and independent AppSec/DBA/SRE/QA/payments/legal owner approvals.

## Release disposition

The tests do not post real payments or call a provider. They do not create a production issuer, API, SQL role, database trigger, migration, license or deployment. No independent release gate closes on a green CI run. **NO-GO.**
