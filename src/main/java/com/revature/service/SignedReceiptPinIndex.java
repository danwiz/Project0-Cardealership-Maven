package com.revature.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Objects;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;
import com.revature.service.VersionPinnedSignedReceiptTransport.Pin;
import com.revature.service.VersionPinnedSignedReceiptTransport.TrustedReceiptIndex;

/**
 * Independent signed-pin authority for version-qualified receipt reads.
 *
 * The document fetcher and trusted monotonic revision floor are separate
 * injected boundaries. Neither the signed object nor its object-store response
 * is allowed to supply the trusted minimum revision or public signing key.
 */
public final class SignedReceiptPinIndex implements TrustedReceiptIndex {
    private static final String DOMAIN = "dealership.signed-receipt.pin.v1";
    private static final String BLOCKED = "signed receipt pin unavailable";
    private static final long MAX_LIFETIME_MILLIS = 300_000L;

    private final String namespace;
    private final Kind kind;
    private final DocumentFetcher documents;
    private final TrustedRevisionFloor revisions;
    private final PublicKey pinnedKey;
    private final String expectedIssuer;
    private final Clock clock;

    public SignedReceiptPinIndex(String namespace, Kind kind, DocumentFetcher documents,
            TrustedRevisionFloor revisions, PublicKey pinnedKey,
            String expectedIssuer, Clock clock) {
        this.namespace = CanonicalSignedReceiptTransport.requireNamespace(namespace);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.pinnedKey = requireStrongRsa(pinnedKey);
        this.expectedIssuer = requireToken(expectedIssuer, 256);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Pin expected(ReopenProof proof, String canonicalKey) {
        try {
            String requiredKey = CanonicalSignedReceiptTransport.receiptKey(namespace,
                    kind, proof);
            if (!requiredKey.equals(canonicalKey)) throw blocked();
            SignedPinDocument document = documents.fetch(proof, requiredKey);
            verifyDocument(proof, requiredKey, document);
            // The floor is independently authenticated and monotonic. A
            // missing/unknown floor (<=0) is a hard denial, never zero-default.
            long minimum = revisions.minimumRevision(proof, requiredKey);
            if (minimum <= 0 || document.revision < minimum) throw blocked();
            return document.pin;
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    /**
     * Authenticate a candidate pin document before publication, without
     * reading the old revision floor. Publication separately requires an
     * authorized publisher and a guarded monotonic floor CAS.
     */
    public void verifyDocument(ReopenProof proof, String canonicalKey,
            SignedPinDocument document) {
        try {
            String requiredKey = CanonicalSignedReceiptTransport.receiptKey(namespace,
                    kind, proof);
            if (!requiredKey.equals(canonicalKey)) throw blocked();
            if (document == null || document.kind != kind
                    || !expectedIssuer.equals(document.issuer)
                    || !requiredKey.equals(document.canonicalKey)
                    || document.revision <= 0) throw blocked();

            long now = clock.millis();
            if (document.issuedAtMillis <= 0 || document.issuedAtMillis > now
                    || document.expiresAtMillis <= now
                    || document.expiresAtMillis <= document.issuedAtMillis
                    || document.expiresAtMillis - document.issuedAtMillis
                            > MAX_LIFETIME_MILLIS) throw blocked();

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(pinnedKey);
            verifier.update(signingPayload(document));
            if (!verifier.verify(document.signature)) throw blocked();
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    /** Canonical signed transcript; the key already binds the exact proof. */
    public static byte[] signingPayload(SignedPinDocument document) {
        Objects.requireNonNull(document, "document");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeString(out, DOMAIN);
            writeString(out, document.kind.name());
            writeString(out, document.issuer);
            writeString(out, document.canonicalKey);
            out.writeLong(document.revision);
            out.writeLong(document.issuedAtMillis);
            out.writeLong(document.expiresAtMillis);
            writeString(out, document.pin.versionId());
            writeString(out, document.pin.etag());
            out.writeInt(document.pin.length());
            byte[] sha256 = document.pin.sha256();
            out.writeInt(sha256.length);
            out.write(sha256);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory pin transcript failed");
        }
    }

    private static void writeString(DataOutputStream out, String string) throws IOException {
        byte[] encoded = string.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static String requireToken(String token, int maxBytes) {
        if (token == null || token.trim().isEmpty()
                || token.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || token.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("invalid signed pin field");
        }
        return token;
    }

    private static PublicKey requireStrongRsa(PublicKey key) {
        if (!(Objects.requireNonNull(key, "pinnedKey") instanceof RSAPublicKey)
                || ((RSAPublicKey) key).getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("RSA 2048+ pin trust anchor required");
        }
        return key;
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    /** Immutable signed manifest record, independent of the receipt object. */
    public static final class SignedPinDocument {
        private final Kind kind;
        private final String issuer;
        private final String canonicalKey;
        private final long revision;
        private final long issuedAtMillis;
        private final long expiresAtMillis;
        private final Pin pin;
        private final byte[] signature;

        public SignedPinDocument(Kind kind, String issuer, String canonicalKey,
                long revision, long issuedAtMillis, long expiresAtMillis,
                Pin pin, byte[] signature) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.issuer = requireToken(issuer, 256);
            this.canonicalKey = requireToken(canonicalKey, 512);
            if (revision <= 0 || issuedAtMillis <= 0 || expiresAtMillis <= issuedAtMillis) {
                throw new IllegalArgumentException("invalid signed pin bounds");
            }
            this.revision = revision;
            this.issuedAtMillis = issuedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.pin = Objects.requireNonNull(pin, "pin");
            if (signature == null || signature.length > 1024) {
                throw new IllegalArgumentException("invalid signed pin signature");
            }
            this.signature = signature.clone();
        }

        public Kind kind() { return kind; }
        public String issuer() { return issuer; }
        public String canonicalKey() { return canonicalKey; }
        public long revision() { return revision; }
        public long issuedAtMillis() { return issuedAtMillis; }
        public long expiresAtMillis() { return expiresAtMillis; }
        public Pin pin() { return pin; }
        public byte[] signature() { return signature.clone(); }
    }

    @FunctionalInterface
    public interface DocumentFetcher {
        /** Fetch signed document by independently derived proof-bound key. */
        SignedPinDocument fetch(ReopenProof proof, String canonicalKey) throws Exception;
    }

    @FunctionalInterface
    public interface TrustedRevisionFloor {
        /** Authenticated monotonic floor; missing/unknown must fail closed. */
        long minimumRevision(ReopenProof proof, String canonicalKey) throws Exception;
    }
}
