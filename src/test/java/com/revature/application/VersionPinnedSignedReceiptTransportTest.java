package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import com.revature.service.VersionPinnedSignedReceiptTransport;
import com.revature.service.VersionPinnedSignedReceiptTransport.Pin;
import com.revature.service.VersionPinnedSignedReceiptTransport.VersionedObject;

/** Local, independently pinned versioned maps; never calls cloud storage. */
class VersionPinnedSignedReceiptTransportTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String STREAM = "ci-pinned-readback";

    @Test
    void exactVersionAndConditionalEtagsFromSeparateAuthoritiesClaimOnce() throws Exception {
        Fixture rig = new Fixture();
        assertTrue(rig.verifier.verify(rig.proof));
        assertEquals(1, rig.claims.load(STREAM).epoch());
        assertEquals(2, rig.readRequests.size());
        assertTrue(rig.readRequests.contains(rig.sourceKey + ":v-source:etag-source"));
        assertTrue(rig.readRequests.contains(rig.archiveKey + ":v-archive:etag-archive"));
        assertFalse(rig.verifier.verify(rig.proof), "the durable epoch cannot be reused");
    }

    @Test
    void staleVersionWrongEtagLengthDigestAndMissingPinCannotClaimEpoch() throws Exception {
        Fixture rig = new Fixture();
        VersionedObject original = rig.archiveObjects.get(rig.archiveKey);
        Pin originalPin = rig.archivePins.get(rig.archiveKey);

        // Simulates a provider returning the new latest object despite the
        // caller explicitly requesting the independently pinned old version.
        rig.archiveObjects.put(rig.archiveKey,
                new VersionedObject("v-latest", "etag-latest", original.bytes()));
        rig.assertDenied();

        rig.archiveObjects.put(rig.archiveKey,
                new VersionedObject("v-archive", "stale-etag", original.bytes()));
        rig.assertDenied();

        byte[] changed = original.bytes();
        changed[changed.length - 1] ^= 1;
        rig.archiveObjects.put(rig.archiveKey,
                new VersionedObject("v-archive", "etag-archive", changed));
        rig.assertDenied();

        rig.archiveObjects.put(rig.archiveKey, original);
        rig.archivePins.put(rig.archiveKey,
                new Pin("v-archive", "etag-archive", originalPin.length() - 1,
                        originalPin.sha256()));
        rig.assertDenied();

        byte[] wrongDigest = originalPin.sha256();
        wrongDigest[0] ^= 1;
        rig.archivePins.put(rig.archiveKey,
                new Pin("v-archive", "etag-archive", originalPin.length(), wrongDigest));
        rig.assertDenied();

        rig.archivePins.remove(rig.archiveKey);
        rig.assertDenied();
        rig.archivePins.put(rig.archiveKey, originalPin);
        assertTrue(rig.verifier.verify(rig.proof), "valid original read-back may now claim");
    }

    @Test
    void providerOutageBadMetadataAndUntrustedPathsFailClosed() throws Exception {
        Fixture rig = new Fixture();
        VersionPinnedSignedReceiptTransport denied = new VersionPinnedSignedReceiptTransport(
                "tenant-a", Kind.ARCHIVE_DURABLE, (proof, key) -> {
                    throw new IllegalStateException("sensitive provider diagnostics");
                }, (key, version, etag) -> {
                    throw new AssertionError("read must not be attempted without a pin");
                });
        assertEquals("version-pinned receipt unavailable", assertThrows(
                SecurityException.class, () -> denied.fetch(rig.proof)).getMessage());
        rig.assertDeniedForMissingReceipt();

        byte[] digest = new byte[32];
        assertThrows(IllegalArgumentException.class,
                () -> new Pin("", "etag", 10, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new Pin("version", "\n", 10, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new Pin("version", "etag", 4097, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new Pin("version", "etag", 10, new byte[31]));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedObject("version", "etag", new byte[4097]));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionPinnedSignedReceiptTransport("../tenant",
                        Kind.ARCHIVE_DURABLE, (proof, key) -> null,
                        (key, version, etag) -> null));
        assertEquals(0, rig.claims.load(STREAM).epoch());
    }

    private static final class Fixture {
        final ReopenProof proof;
        final String sourceKey;
        final String archiveKey;
        final Map<String, Pin> sourcePins = new HashMap<>();
        final Map<String, Pin> archivePins = new HashMap<>();
        final Map<String, VersionedObject> sourceObjects = new HashMap<>();
        final Map<String, VersionedObject> archiveObjects = new HashMap<>();
        final List<String> readRequests = new ArrayList<>();
        final JdbcRotationClaimLedger claims;
        final SignedRotationEvidenceAuthority verifier;

        Fixture() throws Exception {
            AuditCheckpoint checkpoint = new AuditCheckpoint(STREAM, new AuditSourceCursor(8, 0));
            proof = new ReopenProof(0, 1, 1, checkpoint, checkpoint);
            sourceKey = CanonicalSignedReceiptTransport.receiptKey(
                    "tenant-a", Kind.SOURCE_COMPLETE, proof);
            archiveKey = CanonicalSignedReceiptTransport.receiptKey(
                    "tenant-a", Kind.ARCHIVE_DURABLE, proof);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair sourceSigner = generator.generateKeyPair();
            KeyPair archiveSigner = generator.generateKeyPair();
            byte[] manifest = sha256("manifest".getBytes(StandardCharsets.UTF_8));
            add(sourceKey, "v-source", "etag-source", SignedEvidenceArtifactAdapter.encode(
                    signed(Kind.SOURCE_COMPLETE, "source-issuer", sourceSigner, proof, manifest)),
                    sourcePins, sourceObjects);
            add(archiveKey, "v-archive", "etag-archive", SignedEvidenceArtifactAdapter.encode(
                    signed(Kind.ARCHIVE_DURABLE, "archive-issuer", archiveSigner, proof, manifest)),
                    archivePins, archiveObjects);
            JdbcDatabase database = JdbcDatabase.inMemory("pinned_claim_" +
                    UUID.randomUUID().toString().replace("-", ""));
            try (Connection connection = database.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE writer_rotation_floors ("
                        + "stream_id VARCHAR(100) PRIMARY KEY, credential_generation BIGINT NOT NULL, "
                        + "rotation_epoch BIGINT NOT NULL, cursor_position BIGINT NOT NULL, "
                        + "cursor_ordinal INTEGER NOT NULL)");
                statement.execute("INSERT INTO writer_rotation_floors VALUES ('"
                        + STREAM + "', 0, 0, 0, 0)");
            }
            claims = new JdbcRotationClaimLedger(database::openConnection);
            verifier = new SignedRotationEvidenceAuthority(
                    new SignedEvidenceArtifactAdapter(Kind.SOURCE_COMPLETE,
                            new VersionPinnedSignedReceiptTransport("tenant-a",
                                    Kind.SOURCE_COMPLETE, (candidate, key) -> sourcePins.get(key),
                                    (key, version, etag) -> read(key, version, etag, sourceObjects))),
                    new SignedEvidenceArtifactAdapter(Kind.ARCHIVE_DURABLE,
                            new VersionPinnedSignedReceiptTransport("tenant-a",
                                    Kind.ARCHIVE_DURABLE, (candidate, key) -> archivePins.get(key),
                                    (key, version, etag) -> read(key, version, etag, archiveObjects))),
                    sourceSigner.getPublic(), archiveSigner.getPublic(),
                    "source-issuer", "archive-issuer", claims,
                    Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        }

        VersionedObject read(String key, String version, String etag,
                Map<String, VersionedObject> objects) {
            readRequests.add(key + ":" + version + ":" + etag);
            return objects.get(key);
        }

        void assertDenied() throws Exception {
            assertFalse(verifier.verify(proof));
            assertEquals(0, claims.load(STREAM).epoch());
        }

        void assertDeniedForMissingReceipt() throws Exception {
            archivePins.remove(archiveKey);
            assertDenied();
        }
    }

    private static void add(String key, String version, String etag, byte[] bytes,
            Map<String, Pin> pins, Map<String, VersionedObject> objects) throws Exception {
        pins.put(key, new Pin(version, etag, bytes.length, sha256(bytes)));
        objects.put(key, new VersionedObject(version, etag, bytes));
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static Evidence signed(Kind kind, String issuer, KeyPair signer,
            ReopenProof proof, byte[] manifest) throws Exception {
        Evidence unsigned = new Evidence(kind, issuer, manifest,
                NOW - 1000, NOW + 60000, new byte[0]);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(SignedRotationEvidenceAuthority.signingPayload(proof, unsigned));
        return new Evidence(kind, issuer, manifest,
                NOW - 1000, NOW + 60000, signature.sign());
    }
}
