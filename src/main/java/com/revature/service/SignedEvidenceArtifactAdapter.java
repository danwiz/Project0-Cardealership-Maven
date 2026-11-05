package com.revature.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.SignedRotationEvidenceAuthority.Evidence;
import com.revature.service.SignedRotationEvidenceAuthority.EvidenceProvider;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;

/**
 * Provider-neutral, bounded receipt-envelope adapter. Production transports
 * must authenticate the *independently administered* source/archive endpoints,
 * cap responses before allocation, and return the exact signed bytes. Neither
 * this framing nor its transport makes a provider assertion truthful.
 */
public final class SignedEvidenceArtifactAdapter implements EvidenceProvider {
    private static final String BLOCKED = "signed evidence artifact unavailable";
    private static final int MAGIC = 0x44524531; // DRE1
    private static final int MAX_ENVELOPE = 4096;
    private final Kind kind;
    private final ArtifactTransport transport;

    public SignedEvidenceArtifactAdapter(Kind kind, ArtifactTransport transport) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Evidence load(ReopenProof proof) {
        Objects.requireNonNull(proof, "proof");
        try {
            byte[] encoded = transport.fetch(proof);
            Evidence evidence = decode(encoded);
            if (evidence.kind() != kind) throw blocked();
            return evidence;
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    /**
     * Bounded format for independently signed fixture artifacts. The existing
     * signingPayload protocol remains the signature's canonical transcript.
     */
    public static byte[] encode(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.signature().length == 0) throw blocked();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            writeString(out, evidence.kind().name());
            writeString(out, evidence.issuer());
            writeBytes(out, evidence.manifestDigest());
            out.writeLong(evidence.issuedAtMillis());
            out.writeLong(evidence.expiresAtMillis());
            writeBytes(out, evidence.signature());
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENVELOPE) throw blocked();
            return encoded;
        } catch (IOException impossible) {
            throw blocked();
        }
    }

    public static Evidence decode(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENVELOPE) {
            throw blocked();
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) throw blocked();
            Kind kind = Kind.valueOf(readString(in, 32));
            String issuer = readString(in, 256);
            byte[] digest = readBytes(in, 32, 32);
            long issued = in.readLong();
            long expires = in.readLong();
            byte[] signature = readBytes(in, 1, 1024);
            if (in.available() != 0) throw blocked();
            return new Evidence(kind, issuer, digest, issued, expires, signature);
        } catch (IOException | RuntimeException malformed) {
            throw blocked();
        }
    }

    private static byte[] readBytes(DataInputStream in, int min, int max)
            throws IOException {
        int length = in.readInt();
        if (length < min || length > max) throw blocked();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        byte[] raw = readBytes(in, 1, max);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException invalid) {
            throw blocked();
        }
    }

    private static void writeString(DataOutputStream out, String value)
            throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream out, byte[] data)
            throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    @FunctionalInterface
    public interface ArtifactTransport {
        /** Authenticated, already size-bounded provider response. */
        byte[] fetch(ReopenProof proof) throws Exception;
    }
}
