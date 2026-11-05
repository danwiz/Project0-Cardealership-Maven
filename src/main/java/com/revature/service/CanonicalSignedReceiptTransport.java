package com.revature.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.SignedEvidenceArtifactAdapter.ArtifactTransport;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;

/**
 * Content-addressed, path-safe read-only receipt locator. No provider SDK,
 * network endpoint or signed object is trusted by this path computation:
 * SignedRotationEvidenceAuthority must still verify actual provider evidence.
 */
public final class CanonicalSignedReceiptTransport implements ArtifactTransport {
    private static final String BLOCKED = "signed receipt location unavailable";
    private static final String DOMAIN = "dealership.signed-receipt.locator.v1";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String namespace;
    private final Kind kind;
    private final ReadOnlyObjectStore store;

    public CanonicalSignedReceiptTransport(String namespace, Kind kind,
            ReadOnlyObjectStore store) {
        this.namespace = requireNamespace(namespace);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public byte[] fetch(ReopenProof proof) throws Exception {
        return store.read(receiptKey(namespace, kind, proof));
    }

    /**
     * Reproducible, non-secret object key. Raw stream IDs cannot become path
     * components; the key binds stream, generation, epoch, cursor and role.
     */
    public static String receiptKey(String namespace, Kind kind, ReopenProof proof) {
        String safeNamespace = requireNamespace(namespace);
        Objects.requireNonNull(kind, "kind");
        if (proof == null || !proof.sourceHighWater().equals(proof.archivedCheckpoint())
                || proof.nextGeneration() <= proof.previousGeneration()
                || proof.nextGeneration() <= 0 || proof.rotationEpoch() <= 0) {
            throw blocked();
        }
        String stream = proof.archivedCheckpoint().streamId();
        byte[] streamBytes = stream.getBytes(StandardCharsets.UTF_8);
        if (streamBytes.length == 0 || streamBytes.length > 512) throw blocked();
        try {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(content);
            writeString(out, DOMAIN);
            writeString(out, safeNamespace);
            writeString(out, kind.name());
            out.writeInt(streamBytes.length);
            out.write(streamBytes);
            out.writeLong(proof.previousGeneration());
            out.writeLong(proof.nextGeneration());
            out.writeLong(proof.rotationEpoch());
            out.writeLong(proof.archivedCheckpoint().sourceCursor().commitPosition());
            out.writeInt(proof.archivedCheckpoint().sourceCursor().eventOrdinal());
            out.flush();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray());
            char[] hex = new char[hash.length * 2];
            for (int index = 0; index < hash.length; index++) {
                int value = hash[index] & 0xff;
                hex[index * 2] = HEX[value >>> 4];
                hex[index * 2 + 1] = HEX[value & 15];
            }
            return safeNamespace + "/" + kind.name().toLowerCase(Locale.ROOT)
                    + "/v1/" + new String(hex) + ".receipt";
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw blocked();
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    static String requireNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("invalid artifact namespace");
        }
        return namespace;
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    @FunctionalInterface
    public interface ReadOnlyObjectStore {
        /** Bounded, authenticated GET against the configured trusted store. */
        byte[] read(String canonicalKey) throws Exception;
    }
}
