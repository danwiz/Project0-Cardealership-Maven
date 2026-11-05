package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcRotationClaimLedger;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.CanonicalSignedReceiptTransport;
import com.revature.service.SignedEvidenceArtifactAdapter;
import com.revature.service.SignedRotationEvidenceAuthority;
import com.revature.service.SignedRotationEvidenceAuthority.Evidence;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;

/** Only local maps and generated keys; no vendor services or storage are called. */
class CanonicalSignedReceiptTransportTest {
    private static final String STREAM = "ci-cross-tenant/../../sensitive-stream";
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void locatorBindsTenantKindStreamGenerationEpochAndCursorWithoutPathInjection() {
        ReopenProof proof = proof(STREAM, 0, 1, 1, 8);
        String key = key("tenant-a", Kind.SOURCE_COMPLETE, proof);
        assertTrue(key.matches("tenant-a/source_complete/v1/[0-9a-f]{64}\\.receipt"));
        assertFalse(key.contains(".."));
        assertFalse(key.contains("sensitive-stream"));
        assertEquals(key, key("tenant-a", Kind.SOURCE_COMPLETE, proof));
        assertNotEquals(key, key("tenant-b", Kind.SOURCE_COMPLETE, proof));
        assertNotEquals(key, key("tenant-a", Kind.ARCHIVE_DURABLE, proof));
        assertNotEquals(key, key("tenant-a", Kind.SOURCE_COMPLETE,
                proof("another-stream", 0, 1, 1, 8)));
        assertNotEquals(key, key("tenant-a", Kind.SOURCE_COMPLETE,
                proof(STREAM, 0, 2, 1, 8)));
        assertNotEquals(key, key("tenant-a", Kind.SOURCE_COMPLETE,
                proof(STREAM, 0, 1, 2, 8)));
        assertNotEquals(key, key("tenant-a", Kind.SOURCE_COMPLETE,
                proof(STREAM, 0, 1, 1, 9)));
        assertThrows(IllegalArgumentException.class,
                () -> key("../tenant", Kind.SOURCE_COMPLETE, proof));
        assertThrows(IllegalArgumentException.class,
                () -> key("TENANT", Kind.SOURCE_COMPLETE, proof));
        assertThrows(SecurityException.class, () -> key("tenant-a",
                Kind.SOURCE_COMPLETE, proof(STREAM, 1, 1, 1, 8)));
    }

    @Test
    void isolatedStoresOnlyReleaseProperlyLocatedVerifiedArtifacts() throws Exception {
        ReopenProof candidate = proof(STREAM, 0, 1, 1, 10);
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair sourceKey = keys.generateKeyPair();
        KeyPair archiveKey = keys.generateKeyPair();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                "manifest".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> sourceObjects = new HashMap<>();
        Map<String, byte[]> archiveObjects = new HashMap<>();
        sourceObjects.put(key("tenant-a", Kind.SOURCE_COMPLETE, candidate),
                SignedEvidenceArtifactAdapter.encode(signed(Kind.SOURCE_COMPLETE,
                        "source-issuer", sourceKey, candidate, digest)));
        archiveObjects.put(key("tenant-a", Kind.ARCHIVE_DURABLE, candidate),
                SignedEvidenceArtifactAdapter.encode(signed(Kind.ARCHIVE_DURABLE,
                        "archive-issuer", archiveKey, candidate, digest)));
        JdbcRotationClaimLedger ledger = ledger();
        SignedRotationEvidenceAuthority authority = new SignedRotationEvidenceAuthority(
                new SignedEvidenceArtifactAdapter(Kind.SOURCE_COMPLETE,
                        new CanonicalSignedReceiptTransport("tenant-a",
                                Kind.SOURCE_COMPLETE, sourceObjects::get)),
                new SignedEvidenceArtifactAdapter(Kind.ARCHIVE_DURABLE,
                        new CanonicalSignedReceiptTransport("tenant-a",
                                Kind.ARCHIVE_DURABLE, archiveObjects::get)),
                sourceKey.getPublic(), archiveKey.getPublic(),
                "source-issuer", "archive-issuer", ledger,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        assertFalse(authority.verify(proof(STREAM, 0, 1, 2, 10)),
                "a new epoch must not fetch the old signed receipt");
        assertFalse(authority.verify(proof("other-stream", 0, 1, 1, 10)));
        assertEquals(0, ledger.load(STREAM).epoch());
        assertTrue(authority.verify(candidate));
        assertEquals(1, ledger.load(STREAM).epoch());
        assertFalse(authority.verify(candidate), "persisted epoch cannot be reclaimed");

        // A wrong store namespace fails closed even with valid bytes present
        // under the unrelated tenant's key.
        SignedEvidenceArtifactAdapter wrongTenant = new SignedEvidenceArtifactAdapter(
                Kind.SOURCE_COMPLETE, new CanonicalSignedReceiptTransport(
                        "tenant-b", Kind.SOURCE_COMPLETE, sourceObjects::get));
        assertThrows(SecurityException.class, () -> wrongTenant.load(candidate));
    }

    private static JdbcRotationClaimLedger ledger() throws Exception {
        JdbcDatabase db = JdbcDatabase.inMemory("located_claim_" +
                UUID.randomUUID().toString().replace("-", ""));
        try (Connection connection = db.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE writer_rotation_floors ("
                    + "stream_id VARCHAR(100) PRIMARY KEY, credential_generation BIGINT NOT NULL, "
                    + "rotation_epoch BIGINT NOT NULL, cursor_position BIGINT NOT NULL, "
                    + "cursor_ordinal INTEGER NOT NULL)");
            sql.execute("INSERT INTO writer_rotation_floors (stream_id, credential_generation, "
                    + "rotation_epoch, cursor_position, cursor_ordinal) VALUES ('"
                    + STREAM + "', 0, 0, 0, 0)");
        }
        return new JdbcRotationClaimLedger(db::openConnection);
    }

    private static String key(String namespace, Kind kind, ReopenProof proof) {
        return CanonicalSignedReceiptTransport.receiptKey(namespace, kind, proof);
    }

    private static ReopenProof proof(String stream, long previous,
            long next, long epoch, long cursor) {
        AuditCheckpoint checkpoint = new AuditCheckpoint(stream,
                new AuditSourceCursor(cursor, 0));
        return new ReopenProof(previous, next, epoch, checkpoint, checkpoint);
    }

    private static Evidence signed(Kind kind, String issuer, KeyPair keys,
            ReopenProof proof, byte[] digest) throws Exception {
        Evidence bare = new Evidence(kind, issuer, digest,
                NOW - 1000, NOW + 60000, new byte[0]);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update(SignedRotationEvidenceAuthority.signingPayload(proof, bare));
        return new Evidence(kind, issuer, digest,
                NOW - 1000, NOW + 60000, signature.sign());
    }
}
