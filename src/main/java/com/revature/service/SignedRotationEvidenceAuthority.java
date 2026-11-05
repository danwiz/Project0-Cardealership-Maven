package com.revature.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;

import com.revature.repository.jdbc.JdbcRotationClaimLedger;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReconciliationAuthority;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;

/**
 * Provider-neutral evidence verification boundary, not a source or archive.
 *
 * Checks two distinct, pinned RSA signing identities and identical committed
 * manifest digests before a durable rotation claim. Providers must independently
 * attest real source coverage and external immutable archive durability;
 * possessing a valid signature alone cannot establish those underlying facts.
 */
public final class SignedRotationEvidenceAuthority implements ReconciliationAuthority {
    public enum Kind { SOURCE_COMPLETE, ARCHIVE_DURABLE }

    private static final String PROTOCOL = "dealership.writer-reopen.evidence.v1";
    private static final long MAX_LIFETIME_MILLIS = 300_000L;
    private static final int DIGEST_BYTES = 32;

    private final EvidenceProvider source;
    private final EvidenceProvider archive;
    private final PublicKey sourceKey;
    private final PublicKey archiveKey;
    private final String sourceIssuer;
    private final String archiveIssuer;
    private final JdbcRotationClaimLedger claims;
    private final Clock clock;

    public SignedRotationEvidenceAuthority(EvidenceProvider source,
            EvidenceProvider archive, PublicKey sourceKey, PublicKey archiveKey,
            String sourceIssuer, String archiveIssuer,
            JdbcRotationClaimLedger claims, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.sourceKey = requireStrongRsa(sourceKey);
        this.archiveKey = requireStrongRsa(archiveKey);
        if (MessageDigest.isEqual(this.sourceKey.getEncoded(),
                this.archiveKey.getEncoded())) {
            throw new IllegalArgumentException("source and archive must have distinct keys");
        }
        this.sourceIssuer = requireIssuer(sourceIssuer);
        this.archiveIssuer = requireIssuer(archiveIssuer);
        if (this.sourceIssuer.equals(this.archiveIssuer)) {
            throw new IllegalArgumentException("source and archive must have distinct issuers");
        }
        this.claims = Objects.requireNonNull(claims, "claims");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Fail closed on unverifiable evidence; only then attempt the atomic shared
     * epoch claim. A claimed epoch is never silently undone if the subsequent
     * local credential attestation fails; recovery needs a fresh ceremony.
     */
    @Override
    public boolean verify(ReopenProof proof) {
        if (proof == null || !proof.sourceHighWater().equals(proof.archivedCheckpoint())) {
            return false;
        }
        try {
            Evidence sourceReceipt = source.load(proof);
            Evidence archiveReceipt = archive.load(proof);
            if (!verifyReceipt(proof, sourceReceipt, Kind.SOURCE_COMPLETE,
                    sourceIssuer, sourceKey)
                    || !verifyReceipt(proof, archiveReceipt, Kind.ARCHIVE_DURABLE,
                            archiveIssuer, archiveKey)
                    || !MessageDigest.isEqual(sourceReceipt.manifestDigest,
                            archiveReceipt.manifestDigest)) {
                return false;
            }
            return claims.claim(proof);
        } catch (Exception unavailable) {
            // No provider diagnostics, signer details, or secret material in a
            // caller-visible denial. The owned writer gate also sanitizes.
            return false;
        }
    }

    private boolean verifyReceipt(ReopenProof proof, Evidence evidence,
            Kind requiredKind, String expectedIssuer, PublicKey key)
            throws GeneralSecurityException {
        if (evidence == null || evidence.kind != requiredKind
                || !expectedIssuer.equals(evidence.issuer)
                || evidence.manifestDigest.length != DIGEST_BYTES
                || evidence.signature.length == 0) {
            return false;
        }
        long now = clock.millis();
        if (evidence.issuedAtMillis <= 0 || evidence.issuedAtMillis > now
                || evidence.expiresAtMillis <= now
                || evidence.expiresAtMillis <= evidence.issuedAtMillis
                || evidence.expiresAtMillis - evidence.issuedAtMillis > MAX_LIFETIME_MILLIS) {
            return false;
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(signingPayload(proof, evidence));
        return verifier.verify(evidence.signature);
    }

    /** Versioned, length-delimited wire transcript; signatures omit signature bytes. */
    public static byte[] signingPayload(ReopenProof proof, Evidence evidence) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(evidence, "evidence");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeString(out, PROTOCOL);
            writeString(out, evidence.kind.name());
            writeString(out, evidence.issuer);
            writeString(out, proof.archivedCheckpoint().streamId());
            out.writeLong(proof.previousGeneration());
            out.writeLong(proof.nextGeneration());
            out.writeLong(proof.rotationEpoch());
            out.writeLong(proof.archivedCheckpoint().sourceCursor().commitPosition());
            out.writeInt(proof.archivedCheckpoint().sourceCursor().eventOrdinal());
            out.writeInt(evidence.manifestDigest.length);
            out.write(evidence.manifestDigest);
            out.writeLong(evidence.issuedAtMillis);
            out.writeLong(evidence.expiresAtMillis);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory evidence encoding failed");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static String requireIssuer(String id) {
        if (id == null || id.trim().isEmpty()
                || id.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("independent issuer identity required");
        }
        return id;
    }

    private static PublicKey requireStrongRsa(PublicKey key) {
        if (!(Objects.requireNonNull(key, "key") instanceof RSAPublicKey)
                || ((RSAPublicKey) key).getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("RSA 2048+ trust anchor required");
        }
        return key;
    }

    @FunctionalInterface
    public interface EvidenceProvider {
        /** Fetch from a separately administered authenticated authority. */
        Evidence load(ReopenProof proof) throws Exception;
    }

    /** Immutable, signed statement; no private key or raw credential material. */
    public static final class Evidence {
        private final Kind kind;
        private final String issuer;
        private final byte[] manifestDigest;
        private final long issuedAtMillis;
        private final long expiresAtMillis;
        private final byte[] signature;

        public Evidence(Kind kind, String issuer, byte[] manifestDigest,
                long issuedAtMillis, long expiresAtMillis, byte[] signature) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.issuer = requireIssuer(issuer);
            Objects.requireNonNull(manifestDigest, "manifestDigest");
            Objects.requireNonNull(signature, "signature");
            if (manifestDigest.length != DIGEST_BYTES || signature.length > 1024) {
                throw new IllegalArgumentException("unsupported evidence size");
            }
            this.manifestDigest = Arrays.copyOf(
                    manifestDigest, manifestDigest.length);
            this.issuedAtMillis = issuedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.signature = Arrays.copyOf(signature, signature.length);
        }

        public Kind kind() { return kind; }
        public String issuer() { return issuer; }
        public byte[] manifestDigest() { return manifestDigest.clone(); }
        public long issuedAtMillis() { return issuedAtMillis; }
        public long expiresAtMillis() { return expiresAtMillis; }
        public byte[] signature() { return signature.clone(); }
    }
}
