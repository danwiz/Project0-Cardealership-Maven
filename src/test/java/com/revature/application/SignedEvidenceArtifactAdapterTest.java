package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcRotationClaimLedger;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.SignedEvidenceArtifactAdapter;
import com.revature.service.SignedRotationEvidenceAuthority;
import com.revature.service.SignedRotationEvidenceAuthority.Evidence;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;

/** Two independent CI files stand in for external receipt transport, not WORM. */
class SignedEvidenceArtifactAdapterTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String STREAM = "ci-file-archive";

    @Test
    void separateStoredSourceAndArchiveArtifactsAuthorizeOnlyOneDurableClaim(
            @TempDir Path root) throws Exception {
        KeyPair[] keys = keys();
        ReopenProof proof = proof();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                "signed-manifest".getBytes(StandardCharsets.UTF_8));
        Path sourceDir = Files.createDirectory(root.resolve("source-authority"));
        Path archiveDir = Files.createDirectory(root.resolve("archive-authority"));
        Path source = sourceDir.resolve("receipt.bin");
        Path archive = archiveDir.resolve("receipt.bin");
        byte[] sourceBytes = SignedEvidenceArtifactAdapter.encode(signed(
                Kind.SOURCE_COMPLETE, "source-issuer", keys[0], proof, digest));
        byte[] archiveBytes = SignedEvidenceArtifactAdapter.encode(signed(
                Kind.ARCHIVE_DURABLE, "archive-issuer", keys[1], proof, digest));
        Files.write(source, sourceBytes);
        Files.write(archive, archiveBytes);
        JdbcRotationClaimLedger claims = claims();
        SignedRotationEvidenceAuthority verifier = new SignedRotationEvidenceAuthority(
                new SignedEvidenceArtifactAdapter(Kind.SOURCE_COMPLETE,
                        p -> Files.readAllBytes(source)),
                new SignedEvidenceArtifactAdapter(Kind.ARCHIVE_DURABLE,
                        p -> Files.readAllBytes(archive)),
                keys[0].getPublic(), keys[1].getPublic(),
                "source-issuer", "archive-issuer", claims,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        // A separately stored, but corrupted, archive does not consume epoch.
        byte[] tampered = archiveBytes.clone();
        tampered[tampered.length - 1] ^= 1;
        Files.write(archive, tampered);
        assertFalse(verifier.verify(proof));
        assertEquals(0, claims.load(STREAM).epoch());
        Files.write(archive, archiveBytes);
        assertTrue(verifier.verify(proof));
        assertEquals(1, claims.load(STREAM).epoch());
        assertFalse(verifier.verify(proof), "replayed valid files must not reclaim epoch");
    }

    @Test
    void corruptedTruncatedSwappedOrOversizedArtifactsFailClosed(@TempDir Path root)
            throws Exception {
        byte[] digest = new byte[32];
        Evidence receipt = new Evidence(Kind.SOURCE_COMPLETE, "source-issuer", digest,
                NOW - 100, NOW + 1000, new byte[] {1, 2, 3});
        byte[] encoded = SignedEvidenceArtifactAdapter.encode(receipt);
        assertEquals(Kind.SOURCE_COMPLETE, SignedEvidenceArtifactAdapter.decode(encoded).kind());
        assertThrows(SecurityException.class,
                () -> SignedEvidenceArtifactAdapter.decode(Arrays.copyOf(encoded, 3)));
        assertThrows(SecurityException.class,
                () -> SignedEvidenceArtifactAdapter.decode(new byte[5000]));
        assertThrows(SecurityException.class,
                () -> SignedEvidenceArtifactAdapter.decode(Arrays.copyOf(encoded,
                        encoded.length + 1)));
        byte[] invalidMagic = encoded.clone();
        invalidMagic[0] ^= 1;
        assertThrows(SecurityException.class,
                () -> SignedEvidenceArtifactAdapter.decode(invalidMagic));
        byte[] invalidUtf8 = encoded.clone();
        int issuerStart = 4 + 4 + Kind.SOURCE_COMPLETE.name().length() + 4;
        invalidUtf8[issuerStart] = (byte) 0xC3;
        invalidUtf8[issuerStart + 1] = (byte) 0x28;
        assertThrows(SecurityException.class,
                () -> SignedEvidenceArtifactAdapter.decode(invalidUtf8));
        SignedEvidenceArtifactAdapter archive = new SignedEvidenceArtifactAdapter(
                Kind.ARCHIVE_DURABLE, p -> encoded);
        assertThrows(SecurityException.class, () -> archive.load(proof()));
        Path absent = root.resolve("no-such-archive.bin");
        SignedEvidenceArtifactAdapter missing = new SignedEvidenceArtifactAdapter(
                Kind.ARCHIVE_DURABLE, p -> Files.readAllBytes(absent));
        assertEquals("signed evidence artifact unavailable", assertThrows(
                SecurityException.class, () -> missing.load(proof())).getMessage());
    }

    private static JdbcRotationClaimLedger claims() throws Exception {
        JdbcDatabase database = JdbcDatabase.inMemory("artifact_claim_" +
                UUID.randomUUID().toString().replace("-", ""));
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE writer_rotation_floors ("
                    + "stream_id VARCHAR(100) PRIMARY KEY, credential_generation BIGINT NOT NULL, "
                    + "rotation_epoch BIGINT NOT NULL, cursor_position BIGINT NOT NULL, "
                    + "cursor_ordinal INTEGER NOT NULL)");
            statement.execute("INSERT INTO writer_rotation_floors VALUES ('" + STREAM
                    + "', 0, 0, 0, 0)");
        }
        return new JdbcRotationClaimLedger(database::openConnection);
    }

    private static KeyPair[] keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new KeyPair[] {generator.generateKeyPair(), generator.generateKeyPair()};
    }

    private static ReopenProof proof() {
        AuditCheckpoint checkpoint = new AuditCheckpoint(STREAM,
                new AuditSourceCursor(8, 0));
        return new ReopenProof(0, 1, 1, checkpoint, checkpoint);
    }

    private static Evidence signed(Kind kind, String issuer, KeyPair keys,
            ReopenProof proof, byte[] digest) throws Exception {
        Evidence bare = new Evidence(kind, issuer, digest,
                NOW - 1000, NOW + 60000, new byte[0]);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(SignedRotationEvidenceAuthority.signingPayload(proof, bare));
        return new Evidence(kind, issuer, digest,
                NOW - 1000, NOW + 60000, signer.sign());
    }
}
