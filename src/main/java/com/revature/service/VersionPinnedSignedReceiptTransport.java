package com.revature.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.SignedEvidenceArtifactAdapter.ArtifactTransport;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;

/**
 * Provider-neutral conditional object read using an independently trusted pin.
 *
 * The receipt pin must come from an authenticated, separate manifest/authority,
 * not from the same mutable object response. Object ETags are opaque concurrency
 * tokens, NOT SHA-256; the digest is computed from the returned bytes.
 */
public final class VersionPinnedSignedReceiptTransport implements ArtifactTransport {
    private static final String BLOCKED = "version-pinned receipt unavailable";
    private static final int MAX_RECEIPT_BYTES = 4096;

    private final String namespace;
    private final Kind kind;
    private final TrustedReceiptIndex pins;
    private final ConditionalObjectReader reader;

    public VersionPinnedSignedReceiptTransport(String namespace, Kind kind,
            TrustedReceiptIndex pins, ConditionalObjectReader reader) {
        this.namespace = CanonicalSignedReceiptTransport.requireNamespace(namespace);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public byte[] fetch(ReopenProof proof) {
        try {
            String key = CanonicalSignedReceiptTransport.receiptKey(namespace, kind, proof);
            Pin pin = pins.expected(proof, key);
            if (pin == null) throw blocked();
            VersionedObject object = reader.read(key, pin.versionId, pin.etag);
            if (object == null || object.bytes.length != pin.length
                    || !pin.versionId.equals(object.versionId)
                    || !pin.etag.equals(object.etag)
                    || !MessageDigest.isEqual(pin.sha256,
                            MessageDigest.getInstance("SHA-256").digest(object.bytes))) {
                throw blocked();
            }
            return object.bytes.clone();
        } catch (Exception unavailable) {
            // Neither provider diagnostics nor object names/versions are
            // returned to a caller. The signed-evidence layer also sanitizes.
            throw blocked();
        }
    }

    private static String requireToken(String token, int max) {
        if (token == null || token.trim().isEmpty()
                || token.getBytes(StandardCharsets.UTF_8).length > max
                || token.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("invalid pinned object metadata");
        }
        return token;
    }

    public static final class Pin {
        private final String versionId;
        private final String etag;
        private final int length;
        private final byte[] sha256;

        public Pin(String versionId, String etag, int length, byte[] sha256) {
            this.versionId = requireToken(versionId, 256);
            this.etag = requireToken(etag, 256);
            if (length < 1 || length > MAX_RECEIPT_BYTES
                    || sha256 == null || sha256.length != 32) {
                throw new IllegalArgumentException("invalid pinned object metadata");
            }
            this.length = length;
            this.sha256 = sha256.clone();
        }

        public String versionId() { return versionId; }
        public String etag() { return etag; }
        public int length() { return length; }
        public byte[] sha256() { return sha256.clone(); }
    }

    public static final class VersionedObject {
        private final String versionId;
        private final String etag;
        private final byte[] bytes;

        public VersionedObject(String versionId, String etag, byte[] bytes) {
            this.versionId = requireToken(versionId, 256);
            this.etag = requireToken(etag, 256);
            if (bytes == null || bytes.length == 0
                    || bytes.length > MAX_RECEIPT_BYTES) {
                throw new IllegalArgumentException("invalid received object");
            }
            this.bytes = bytes.clone();
        }

        public String versionId() { return versionId; }
        public String etag() { return etag; }
        public byte[] bytes() { return bytes.clone(); }
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    @FunctionalInterface
    public interface TrustedReceiptIndex {
        /** Independently authenticated pin for exactly this proof and key. */
        Pin expected(ReopenProof proof, String canonicalKey) throws Exception;
    }

    @FunctionalInterface
    public interface ConditionalObjectReader {
        /** Server-side version-qualified + If-Match read; never fetch latest. */
        VersionedObject read(String canonicalKey, String expectedVersion,
                String ifMatchEtag) throws Exception;
    }
}
