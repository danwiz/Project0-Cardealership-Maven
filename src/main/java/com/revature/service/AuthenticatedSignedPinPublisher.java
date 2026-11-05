package com.revature.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Objects;

import com.revature.repository.jdbc.JdbcSignedReceiptPinRevisionFloor;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.SignedReceiptPinIndex.SignedPinDocument;

/**
 * Authenticated publisher command for a separately signed receipt pin.
 *
 * The publisher key authenticates a short-lived, exact CAS intent, while the
 * independent pin-issuer key authenticates the new document. Neither signing
 * identity alone authorizes a floor advance. This class does not sign, store,
 * transmit or publish the pin document or contain a private key.
 */
public final class AuthenticatedSignedPinPublisher {
    private static final String BLOCKED = "signed pin publication unavailable";
    private static final String DOMAIN = "dealership.signed-receipt.pin-publication.v1";
    private static final long MAX_LIFETIME_MILLIS = 60_000L;

    private final SignedReceiptPinIndex pinVerifier;
    private final JdbcSignedReceiptPinRevisionFloor revisionFloor;
    private final DurableDocumentReadback readback;
    private final PublicKey publisherKey;
    private final String publisherIssuer;
    private final Clock clock;

    public AuthenticatedSignedPinPublisher(SignedReceiptPinIndex pinVerifier,
            JdbcSignedReceiptPinRevisionFloor revisionFloor,
            DurableDocumentReadback readback, PublicKey publisherKey,
            String publisherIssuer, Clock clock) {
        this.pinVerifier = Objects.requireNonNull(pinVerifier, "pinVerifier");
        this.revisionFloor = Objects.requireNonNull(revisionFloor, "revisionFloor");
        this.readback = Objects.requireNonNull(readback, "readback");
        this.publisherKey = requireStrongRsa(publisherKey);
        this.publisherIssuer = requireToken(publisherIssuer, 256);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Validate both independent signatures and the exact proof-bound
     * publication intent BEFORE touching the witness or publisher SQL role.
     * A stale/replayed CAS returns false without weakening the floor.
     */
    public boolean publish(ReopenProof proof, SignedPinDocument document,
            PublishCommand command) {
        try {
            if (document == null || command == null
                    || !publisherIssuer.equals(command.issuer)
                    || !command.canonicalKey.equals(document.canonicalKey())
                    || command.nextRevision != document.revision()
                    || command.expectedRevision <= 0
                    || command.nextRevision <= command.expectedRevision) {
                throw blocked();
            }
            long now = clock.millis();
            if (command.issuedAtMillis <= 0 || command.issuedAtMillis > now
                    || command.expiresAtMillis <= now
                    || command.expiresAtMillis <= command.issuedAtMillis
                    || command.expiresAtMillis - command.issuedAtMillis
                            > MAX_LIFETIME_MILLIS) throw blocked();

            pinVerifier.verifyDocument(proof, command.canonicalKey, document);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publisherKey);
            verifier.update(signingPayload(command, document));
            if (!verifier.verify(command.signature)) throw blocked();

            // Durable visibility is a separate provider-controlled boundary:
            // never advance the floor based on a caller's acknowledgement.
            SignedPinDocument persisted = readback.read(proof,
                    command.canonicalKey, document.revision());
            if (persisted == null
                    || !MessageDigest.isEqual(
                            SignedReceiptPinIndex.signingPayload(document),
                            SignedReceiptPinIndex.signingPayload(persisted))
                    || !MessageDigest.isEqual(document.signature(),
                            persisted.signature())) throw blocked();
            return revisionFloor.advance(proof, command.canonicalKey,
                    command.expectedRevision, command.nextRevision);
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    /** Versioned transcript signs the exact document AND its issuer signature. */
    public static byte[] signingPayload(PublishCommand command,
            SignedPinDocument document) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(document, "document");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeString(out, DOMAIN);
            writeString(out, command.issuer);
            writeString(out, command.canonicalKey);
            out.writeLong(command.expectedRevision);
            out.writeLong(command.nextRevision);
            out.writeLong(command.issuedAtMillis);
            out.writeLong(command.expiresAtMillis);
            byte[] content = SignedReceiptPinIndex.signingPayload(document);
            out.writeInt(content.length);
            out.write(content);
            byte[] pinSignature = document.signature();
            out.writeInt(pinSignature.length);
            out.write(pinSignature);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory publication transcript failed");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String requireToken(String value, int maximum) {
        if (value == null || value.trim().isEmpty()
                || value.getBytes(StandardCharsets.UTF_8).length > maximum
                || value.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("invalid publication metadata");
        }
        return value;
    }

    private static PublicKey requireStrongRsa(PublicKey key) {
        if (!(Objects.requireNonNull(key, "publisherKey") instanceof RSAPublicKey)
                || ((RSAPublicKey) key).getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("RSA 2048+ publisher key required");
        }
        return key;
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    @FunctionalInterface
    public interface DurableDocumentReadback {
        /** Exact revision-qualified read; never fall back to a latest document. */
        SignedPinDocument read(ReopenProof proof, String canonicalKey,
                long expectedRevision) throws Exception;
    }

    /** Immutable caller-supplied signed authorization, not an identity claim. */
    public static final class PublishCommand {
        private final String issuer;
        private final String canonicalKey;
        private final long expectedRevision;
        private final long nextRevision;
        private final long issuedAtMillis;
        private final long expiresAtMillis;
        private final byte[] signature;

        public PublishCommand(String issuer, String canonicalKey,
                long expectedRevision, long nextRevision, long issuedAtMillis,
                long expiresAtMillis, byte[] signature) {
            this.issuer = requireToken(issuer, 256);
            this.canonicalKey = requireToken(canonicalKey, 512);
            if (expectedRevision <= 0 || nextRevision <= expectedRevision
                    || issuedAtMillis <= 0 || expiresAtMillis <= issuedAtMillis
                    || signature == null || signature.length > 1024) {
                throw new IllegalArgumentException("invalid publication metadata");
            }
            this.expectedRevision = expectedRevision;
            this.nextRevision = nextRevision;
            this.issuedAtMillis = issuedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.signature = signature.clone();
        }

        public String issuer() { return issuer; }
        public String canonicalKey() { return canonicalKey; }
        public long expectedRevision() { return expectedRevision; }
        public long nextRevision() { return nextRevision; }
        public long issuedAtMillis() { return issuedAtMillis; }
        public long expiresAtMillis() { return expiresAtMillis; }
        public byte[] signature() { return signature.clone(); }
    }
}
