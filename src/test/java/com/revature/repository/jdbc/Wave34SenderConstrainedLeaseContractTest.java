package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W34.10 REVIEW-ONLY CRYPTOGRAPHIC LEASE CONTRACT. NO PRODUCTION AUTHORITY.
 *
 * Java 11/17 JCA P-256 ECDSA keypairs are generated inside the test process.
 * "Issuer" and "verifier" share this process: separation is logical, NOT an
 * independently administered cryptographic issuer, HSM or managed identity.
 * Test challenges and replay dedupe are in-memory, not durable across
 * restarts, partitions or multiple database leaders.
 * No live provider, real money, application entrypoint or schema changes.
 */
@Tag("WAVE34")
class Wave34SenderConstrainedLeaseContractTest {
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final String AUDIENCE = "w34.10.disposable-ledger-acceptance";
    private static final String TENANT = "synthetic-tenant-01";
    private static final String SUBJECT = "w3410-synthetic-writer-01";

    @Test
    void authenticIssuerSignatureRequiredAndTamperedLeaseClaimsRejected() throws Exception {
        KeyPair signer = keyPair();
        KeyPair writer = keyPair();
        Issuer issuer = new Issuer("test-issuer-A", signer);
        Verifier verifier = trusted("test-issuer-A", signer.getPublic());
        Lease valid = issuer.issue(SUBJECT, AUDIENCE, TENANT, 9L, writer.getPublic(),
                NOW, NOW.plusSeconds(20));
        Request request = request("synthetic-rq-01");
        Challenge challenge = verifier.challenge(valid.jti, request, NOW);
        Proof proof = Proof.sign(valid, challenge, request, writer);
        verifier.authorize(valid, writer.getPublic(), proof, request, NOW.plusSeconds(1));
        assertEquals(1, verifier.accepted.size());

        Lease tampered = valid.withAmountIndependentSubject("another-writer");
        Challenge second = verifier.challenge(valid.jti, request("synthetic-rq-02"), NOW);
        Proof secondProof = Proof.sign(valid, second, request("synthetic-rq-02"), writer);
        assertThrows(SecurityException.class, () ->
                verifier.authorize(tampered, writer.getPublic(), secondProof,
                        request("synthetic-rq-02"), NOW.plusSeconds(2)));

        KeyPair forged = keyPair();
        Lease counterfeit = new Issuer("test-issuer-A", forged).issue(
                SUBJECT, AUDIENCE, TENANT, 9L, writer.getPublic(),
                NOW, NOW.plusSeconds(20));
        Request third = request("synthetic-rq-03");
        Challenge thirdChallenge = verifier.challenge(counterfeit.jti, third, NOW);
        Proof thirdProof = Proof.sign(counterfeit, thirdChallenge, third, writer);
        assertThrows(SecurityException.class, () ->
                verifier.authorize(counterfeit, writer.getPublic(), thirdProof, third,
                        NOW.plusSeconds(1)));
        assertEquals(1, verifier.accepted.size(),
                "neither modified claims nor a forged issuer key is accepted");
    }

    @Test
    void senderProofIsBoundToWriterKeyAndExactRequestFields() throws Exception {
        KeyPair signer = keyPair();
        KeyPair writer = keyPair();
        KeyPair attacker = keyPair();
        Lease lease = new Issuer("test-issuer-A", signer).issue(
                SUBJECT, AUDIENCE, TENANT, 9L, writer.getPublic(), NOW, NOW.plusSeconds(20));
        Verifier verifier = trusted("test-issuer-A", signer.getPublic());

        Request request = request("synthetic-rq-10");
        Challenge challenge = verifier.challenge(lease.jti, request, NOW);
        Proof attackerProof = Proof.sign(lease, challenge, request, attacker);
        assertThrows(SecurityException.class, () ->
                verifier.authorize(lease, attacker.getPublic(), attackerProof, request,
                        NOW.plusSeconds(1)));

        Proof legitimate = Proof.sign(lease, challenge, request, writer);
        Request alteredAmount = new Request(request.subject, request.requestId,
                request.action, request.ownershipId, request.amount + 1);
        assertThrows(SecurityException.class, () ->
                verifier.authorize(lease, writer.getPublic(), legitimate, alteredAmount,
                        NOW.plusSeconds(1)));

        Request alteredOwnership = new Request(request.subject, request.requestId,
                request.action, request.ownershipId + 1, request.amount);
        assertThrows(SecurityException.class, () ->
                verifier.authorize(lease, writer.getPublic(), legitimate, alteredOwnership,
                        NOW.plusSeconds(1)));

        verifier.authorize(lease, writer.getPublic(), legitimate, request,
                NOW.plusSeconds(1));
        assertEquals(1, verifier.accepted.size(),
                "invalid proof and wrong amount/ownership cannot consume the challenge");
    }

    @Test
    @Timeout(25)
    void exactChallengeReplayAndConcurrentDoubleAuthorizationAreRejected()
            throws Exception {
        KeyPair signer = keyPair();
        KeyPair writer = keyPair();
        Lease lease = new Issuer("test-issuer-A", signer).issue(
                SUBJECT, AUDIENCE, TENANT, 9L, writer.getPublic(), NOW, NOW.plusSeconds(20));
        Verifier verifier = trusted("test-issuer-A", signer.getPublic());
        Request request = request("synthetic-rq-20");
        Challenge challenge = verifier.challenge(lease.jti, request, NOW);
        Proof proof = Proof.sign(lease, challenge, request, writer);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch ready = new CountDownLatch(6);
            CountDownLatch fire = new CountDownLatch(1);
            java.util.List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Callable<Boolean> worker = () -> {
                    ready.countDown();
                    if (!fire.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("replay test rendezvous failed");
                    try {
                        verifier.authorize(lease, writer.getPublic(), proof, request,
                                NOW.plusSeconds(1));
                        return true;
                    } catch (SecurityException denied) {
                        return false;
                    }
                };
                futures.add(pool.submit(worker));
            }
            assertTrue(ready.await(8, TimeUnit.SECONDS));
            fire.countDown();
            int successful = 0;
            for (Future<Boolean> result : futures)
                if (result.get(10, TimeUnit.SECONDS)) successful++;
            assertEquals(1, successful,
                    "atomic challenge consumption is at-most-once in this one process");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, verifier.accepted.size());
        assertThrows(SecurityException.class, () ->
                verifier.authorize(lease, writer.getPublic(), proof, request,
                        NOW.plusSeconds(2)));

        Request newRequest = request("synthetic-rq-21");
        Challenge fresh = verifier.challenge(lease.jti, newRequest, NOW);
        Proof wrongNonce = Proof.sign(lease, challenge, newRequest, writer);
        assertThrows(SecurityException.class, () ->
                verifier.authorize(lease, writer.getPublic(), wrongNonce, newRequest,
                        NOW.plusSeconds(1)));
        verifier.authorize(lease, writer.getPublic(),
                Proof.sign(lease, fresh, newRequest, writer),
                newRequest, NOW.plusSeconds(1));
        assertEquals(2, verifier.accepted.size());
    }

    @Test
    void expiryRevocationIssuerRotationEpochAndAudienceFailClosed() throws Exception {
        KeyPair signerA = keyPair();
        KeyPair signerB = keyPair();
        KeyPair writer = keyPair();
        Issuer a = new Issuer("test-issuer-A", signerA);
        Issuer b = new Issuer("test-issuer-B", signerB);
        Verifier v = trusted("test-issuer-A", signerA.getPublic());
        Lease old = a.issue(SUBJECT, AUDIENCE, TENANT, 9L, writer.getPublic(),
                NOW, NOW.plusSeconds(15));
        Request request = request("synthetic-rq-30");
        Challenge ch = v.challenge(old.jti, request, NOW);
        Proof proof = Proof.sign(old, ch, request, writer);
        assertThrows(SecurityException.class, () ->
                v.authorize(old, writer.getPublic(), proof, request, NOW.plusSeconds(16)));

        v.revokedLeases.add(old.jti);
        assertThrows(SecurityException.class, () ->
                v.authorize(old, writer.getPublic(), proof, request, NOW.plusSeconds(1)));
        v.revokedLeases.remove(old.jti);

        v.epoch = 10L;
        assertThrows(SecurityException.class, () ->
                v.authorize(old, writer.getPublic(), proof, request, NOW.plusSeconds(1)));
        v.epoch = 9L;

        Lease wrongAudience = a.issue(SUBJECT, "unrelated-service", TENANT,
                9L, writer.getPublic(), NOW, NOW.plusSeconds(15));
        Request reqWrongAudience = request("synthetic-rq-31");
        Challenge chWrongAudience = v.challenge(wrongAudience.jti, reqWrongAudience, NOW);
        Proof pWrongAudience = Proof.sign(wrongAudience, chWrongAudience,
                reqWrongAudience, writer);
        assertThrows(SecurityException.class, () ->
                v.authorize(wrongAudience, writer.getPublic(), pWrongAudience,
                        reqWrongAudience, NOW.plusSeconds(1)));

        v.trust.put("test-issuer-B", signerB.getPublic());
        v.revokedIssuers.add("test-issuer-A");
        assertThrows(SecurityException.class, () ->
                v.authorize(old, writer.getPublic(), proof, request, NOW.plusSeconds(1)));

        Lease rotated = b.issue(SUBJECT, AUDIENCE, TENANT, 9L, writer.getPublic(),
                NOW, NOW.plusSeconds(15));
        Request newRequest = request("synthetic-rq-32");
        Challenge newChallenge = v.challenge(rotated.jti, newRequest, NOW);
        v.authorize(rotated, writer.getPublic(),
                Proof.sign(rotated, newChallenge, newRequest, writer),
                newRequest, NOW.plusSeconds(1));
        assertEquals(1, v.accepted.size());
    }

    private static Request request(String requestId) {
        return new Request(SUBJECT, requestId, "TEST_ONLY_RECONCILE",
                1042L, 1250L);
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String fingerprint(PublicKey key) throws GeneralSecurityException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    }

    private static byte[] wire(String... claims) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(claims.length);
                for (String claim : claims) {
                    byte[] bytes = claim.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
            return buffer.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] sign(java.security.PrivateKey key, byte[] message)
            throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(message);
        return signature.sign();
    }

    private static boolean validSignature(PublicKey key, byte[] message, byte[] signed)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(message);
        return verifier.verify(signed);
    }

    private static void required(boolean condition, String detail) {
        if (!condition) throw new SecurityException(detail);
    }

    private static final class Request {
        final String subject, requestId, action;
        final long ownershipId, amount;
        Request(String subject, String requestId, String action,
                long ownershipId, long amount) {
            this.subject = subject;
            this.requestId = requestId;
            this.action = action;
            this.ownershipId = ownershipId;
            this.amount = amount;
        }
    }

    private static final class Lease {
        final String kid, subject, audience, tenant, jti, senderFingerprint;
        final long epoch;
        final Instant issued, expires;
        final byte[] signature;
        Lease(String kid, String subject, String audience, String tenant,
                String jti, String senderFingerprint, long epoch,
                Instant issued, Instant expires, byte[] signature) {
            this.kid=kid; this.subject=subject; this.audience=audience;
            this.tenant=tenant; this.jti=jti; this.senderFingerprint=senderFingerprint;
            this.epoch=epoch; this.issued=issued; this.expires=expires;
            this.signature=Arrays.copyOf(signature,signature.length);
        }
        byte[] claims() {
            return wire("W34.10|LEASE|v1","ECDSA-P256-SHA256",kid,subject,audience,
                    tenant,jti,senderFingerprint,Long.toString(epoch),
                    Long.toString(issued.getEpochSecond()),
                    Long.toString(expires.getEpochSecond()));
        }
        Lease withAmountIndependentSubject(String newSubject) {
            return new Lease(kid,newSubject,audience,tenant,jti,senderFingerprint,
                    epoch,issued,expires,signature);
        }
    }

    private static final class Issuer {
        final String kid;
        final KeyPair credentials;
        Issuer(String kid, KeyPair credentials) {
            this.kid=kid; this.credentials=credentials;
        }
        Lease issue(String subject, String audience, String tenant,
                long epoch, PublicKey sender, Instant issued, Instant expires)
                throws GeneralSecurityException {
            Lease unsigned=new Lease(kid,subject,audience,tenant,
                    UUID.randomUUID().toString(),fingerprint(sender),
                    epoch,issued,expires,new byte[0]);
            return new Lease(kid,subject,audience,tenant,unsigned.jti,
                    unsigned.senderFingerprint,epoch,issued,expires,
                    sign(credentials.getPrivate(),unsigned.claims()));
        }
    }

    private static final class Challenge {
        final String nonce, jti, subject, requestId;
        final Instant expires;
        Challenge(String nonce, String jti, String subject,
                String requestId, Instant expires) {
            this.nonce=nonce; this.jti=jti; this.subject=subject;
            this.requestId=requestId; this.expires=expires;
        }
    }

    private static final class Proof {
        final String nonce,jti,subject,requestId,action;
        final long ownershipId,amount;
        final byte[] signature;
        Proof(String nonce, String jti, Request r, byte[] signature) {
            this.nonce=nonce;this.jti=jti;this.subject=r.subject;
            this.requestId=r.requestId;this.action=r.action;
            this.ownershipId=r.ownershipId;this.amount=r.amount;
            this.signature=Arrays.copyOf(signature,signature.length);
        }
        byte[] claims() {
            return wire("W34.10|PROOF|v1",nonce,jti,subject,requestId,action,
                    Long.toString(ownershipId),Long.toString(amount));
        }
        static Proof sign(Lease lease, Challenge challenge, Request request,
                KeyPair sender) throws GeneralSecurityException {
            Proof unsigned=new Proof(challenge.nonce,lease.jti,request,new byte[0]);
            return new Proof(challenge.nonce,lease.jti,request,
                    Wave34SenderConstrainedLeaseContractTest.sign(
                            sender.getPrivate(),unsigned.claims()));
        }
    }

    private static final class Verifier {
        final Map<String,PublicKey> trust=new ConcurrentHashMap<>();
        final Set<String> revokedIssuers=ConcurrentHashMap.newKeySet();
        final Set<String> revokedLeases=ConcurrentHashMap.newKeySet();
        final Map<String,Challenge> pending=new ConcurrentHashMap<>();
        final Set<String> accepted=ConcurrentHashMap.newKeySet();
        volatile long epoch=9L;

        Challenge challenge(String jti, Request request, Instant now) {
            Challenge c=new Challenge(UUID.randomUUID().toString(),jti,
                    request.subject,request.requestId,now.plusSeconds(8));
            pending.put(c.nonce,c);
            return c;
        }

        void authorize(Lease lease, PublicKey sender, Proof proof,
                Request expected, Instant now) throws GeneralSecurityException {
            PublicKey issuerKey=trust.get(lease.kid);
            required(issuerKey!=null && !revokedIssuers.contains(lease.kid),
                    "issuer trust unavailable");
            required(validSignature(issuerKey,lease.claims(),lease.signature),
                    "issuer signature invalid");
            required(lease.audience.equals(AUDIENCE) && lease.tenant.equals(TENANT)
                    && lease.subject.equals(expected.subject) && lease.epoch==epoch,
                    "audience, tenant, subject or epoch mismatch");
            required(!revokedLeases.contains(lease.jti)
                    && !now.isBefore(lease.issued) && now.isBefore(lease.expires)
                    && lease.expires.isAfter(lease.issued)
                    && lease.expires.getEpochSecond()-lease.issued.getEpochSecond()<=30,
                    "lease revoked, expired or invalid");
            required(lease.senderFingerprint.equals(fingerprint(sender)),
                    "sender public key not bound to lease");
            Challenge challenge=pending.get(proof.nonce);
            required(challenge!=null && now.isBefore(challenge.expires)
                    && challenge.jti.equals(lease.jti)
                    && challenge.subject.equals(expected.subject)
                    && challenge.requestId.equals(expected.requestId)
                    && proof.jti.equals(lease.jti)
                    && proof.subject.equals(expected.subject)
                    && proof.requestId.equals(expected.requestId)
                    && proof.action.equals(expected.action)
                    && proof.amount==expected.amount
                    && proof.ownershipId==expected.ownershipId,
                    "unknown/stale nonce or altered request");
            required(validSignature(sender,proof.claims(),proof.signature),
                    "sender proof invalid");
            required(pending.remove(proof.nonce,challenge),
                    "replayed nonce was already consumed");
            accepted.add(lease.jti+"|"+expected.requestId);
        }
    }

    private static Verifier trusted(String kid, PublicKey authority) {
        Verifier result=new Verifier();
        result.trust.put(kid,authority);
        return result;
    }
}
