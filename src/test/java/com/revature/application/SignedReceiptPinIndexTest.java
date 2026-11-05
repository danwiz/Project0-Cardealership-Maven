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
import com.revature.service.SignedReceiptPinIndex;
import com.revature.service.SignedReceiptPinIndex.SignedPinDocument;
import com.revature.service.SignedRotationEvidenceAuthority;
import com.revature.service.SignedRotationEvidenceAuthority.Evidence;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;
import com.revature.service.VersionPinnedSignedReceiptTransport;
import com.revature.service.VersionPinnedSignedReceiptTransport.Pin;
import com.revature.service.VersionPinnedSignedReceiptTransport.VersionedObject;

/** Four independent CI signers, two signed-pin maps, two versioned object maps. */
class SignedReceiptPinIndexTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String STREAM = "ci-signed-pin-index";
    private static final String NAMESPACE = "tenant-a";

    @Test
    void independentSignedPinAuthoritiesAllowExactlyOneDurableEpochClaim() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.verifier.verify(fixture.proof));
        assertEquals(1, fixture.claims.load(STREAM).epoch());
        assertEquals(1, fixture.archiveReads);
        assertFalse(fixture.verifier.verify(fixture.proof),
                "verified evidence still cannot reclaim a durable epoch");
        assertEquals(4L, fixture.floors.get(fixture.archiveKey).longValue());
    }

    @Test
    void alteredKindIssuerKeyPinAndSignatureNeverReachArchiveReader() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument original = f.documents.get(f.archiveKey);
        byte[] wrongDigest = original.pin().sha256();
        wrongDigest[0] ^= 1;
        Pin changedPin = new Pin(original.pin().versionId(), original.pin().etag(),
                original.pin().length(), wrongDigest);
        f.documents.put(f.archiveKey, new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.archiveKey, 4, NOW - 1000,
                NOW + 60000, changedPin, original.signature()));
        f.assertDeniedBeforeArchiveRead();

        f.documents.put(f.archiveKey, new SignedPinDocument(Kind.SOURCE_COMPLETE,
                "archive-pin-issuer", f.archiveKey, 4, NOW - 1000,
                NOW + 60000, original.pin(), original.signature()));
        f.assertDeniedBeforeArchiveRead();

        f.documents.put(f.archiveKey, new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "source-pin-issuer", f.archiveKey, 4, NOW - 1000,
                NOW + 60000, original.pin(), original.signature()));
        f.assertDeniedBeforeArchiveRead();

        f.documents.put(f.archiveKey, new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.sourceKey, 4, NOW - 1000,
                NOW + 60000, original.pin(), original.signature()));
        f.assertDeniedBeforeArchiveRead();

        byte[] alteredSignature = original.signature();
        alteredSignature[0] ^= 1;
        f.documents.put(f.archiveKey, new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.archiveKey, 4, NOW - 1000,
                NOW + 60000, original.pin(), alteredSignature));
        f.assertDeniedBeforeArchiveRead();

        f.documents.put(f.archiveKey, original);
        assertTrue(f.verifier.verify(f.proof), "untampered signed pins recover safely");
    }

    @Test
    void authenticatedRevisionFloorExpiryAndOutagesFailClosed() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument original = f.documents.get(f.archiveKey);
        f.floors.put(f.archiveKey, 5L);
        f.assertDeniedBeforeArchiveRead();

        f.floors.remove(f.archiveKey);
        f.assertDeniedBeforeArchiveRead();
        f.floors.put(f.archiveKey, 4L);

        SignedPinDocument older = f.signedPin(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.archiveKey, 3L, NOW - 1000,
                NOW + 60000, original.pin(), f.archivePinSigner);
        f.documents.put(f.archiveKey, older);
        f.assertDeniedBeforeArchiveRead();

        SignedPinDocument expired = f.signedPin(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.archiveKey, 4L, NOW - 3000,
                NOW - 1, original.pin(), f.archivePinSigner);
        f.documents.put(f.archiveKey, expired);
        f.assertDeniedBeforeArchiveRead();

        SignedPinDocument tooLong = f.signedPin(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.archiveKey, 4L, NOW - 1000,
                NOW + 300001, original.pin(), f.archivePinSigner);
        f.documents.put(f.archiveKey, tooLong);
        f.assertDeniedBeforeArchiveRead();

        SignedReceiptPinIndex unavailable = new SignedReceiptPinIndex(NAMESPACE,
                Kind.ARCHIVE_DURABLE, (proof, key) -> {
                    throw new IllegalStateException("private provider error");
                }, (proof, key) -> 4, f.archivePinSigner.getPublic(),
                "archive-pin-issuer", f.clock);
        assertEquals("signed receipt pin unavailable", assertThrows(
                SecurityException.class, () -> unavailable.expected(f.proof,
                        f.archiveKey)).getMessage());
        assertEquals("signed receipt pin unavailable", assertThrows(
                SecurityException.class, () -> f.archivePinIndex.expected(
                        f.proof, f.sourceKey)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new SignedReceiptPinIndex("../invalid", Kind.ARCHIVE_DURABLE,
                        (proof, key) -> null, (proof, key) -> 1,
                        f.archivePinSigner.getPublic(), "archive-pin-issuer", f.clock));

        f.documents.put(f.archiveKey, original);
        assertTrue(f.verifier.verify(f.proof));
    }

    private static final class Fixture {
        final ReopenProof proof;
        final String sourceKey;
        final String archiveKey;
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        final Map<String, SignedPinDocument> documents = new HashMap<>();
        final Map<String, Long> floors = new HashMap<>();
        final Map<String, VersionedObject> sourceObjects = new HashMap<>();
        final Map<String, VersionedObject> archiveObjects = new HashMap<>();
        final KeyPair archivePinSigner;
        final SignedReceiptPinIndex archivePinIndex;
        final JdbcRotationClaimLedger claims;
        final SignedRotationEvidenceAuthority verifier;
        int archiveReads;

        Fixture() throws Exception {
            AuditCheckpoint checkpoint = new AuditCheckpoint(STREAM,
                    new AuditSourceCursor(28, 2));
            proof = new ReopenProof(0, 1, 1, checkpoint, checkpoint);
            sourceKey = CanonicalSignedReceiptTransport.receiptKey(
                    NAMESPACE, Kind.SOURCE_COMPLETE, proof);
            archiveKey = CanonicalSignedReceiptTransport.receiptKey(
                    NAMESPACE, Kind.ARCHIVE_DURABLE, proof);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair sourceEvidenceSigner = generator.generateKeyPair();
            KeyPair archiveEvidenceSigner = generator.generateKeyPair();
            KeyPair sourcePinSigner = generator.generateKeyPair();
            archivePinSigner = generator.generateKeyPair();
            byte[] digest = sha256("manifest".getBytes(StandardCharsets.UTF_8));
            addReceipt(Kind.SOURCE_COMPLETE, sourceKey, "v-source", "etag-source",
                    "source-issuer", sourceEvidenceSigner, sourceObjects, digest);
            addReceipt(Kind.ARCHIVE_DURABLE, archiveKey, "v-archive", "etag-archive",
                    "archive-issuer", archiveEvidenceSigner, archiveObjects, digest);
            floors.put(sourceKey, 2L);
            floors.put(archiveKey, 4L);
            documents.put(sourceKey, signedPin(Kind.SOURCE_COMPLETE,
                    "source-pin-issuer", sourceKey, 2L, NOW - 1000,
                    NOW + 60000, pinOf(sourceObjects.get(sourceKey)), sourcePinSigner));
            documents.put(archiveKey, signedPin(Kind.ARCHIVE_DURABLE,
                    "archive-pin-issuer", archiveKey, 4L, NOW - 1000,
                    NOW + 60000, pinOf(archiveObjects.get(archiveKey)), archivePinSigner));
            SignedReceiptPinIndex sourcePinIndex = new SignedReceiptPinIndex(NAMESPACE,
                    Kind.SOURCE_COMPLETE, (candidate, key) -> documents.get(key),
                    (candidate, key) -> floors.getOrDefault(key, 0L),
                    sourcePinSigner.getPublic(), "source-pin-issuer", clock);
            archivePinIndex = new SignedReceiptPinIndex(NAMESPACE,
                    Kind.ARCHIVE_DURABLE, (candidate, key) -> documents.get(key),
                    (candidate, key) -> floors.getOrDefault(key, 0L),
                    archivePinSigner.getPublic(), "archive-pin-issuer", clock);
            JdbcDatabase database = JdbcDatabase.inMemory("signed_pin_claim_" +
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
                            new VersionPinnedSignedReceiptTransport(NAMESPACE,
                                    Kind.SOURCE_COMPLETE, sourcePinIndex,
                                    (key, version, etag) -> sourceObjects.get(key))),
                    new SignedEvidenceArtifactAdapter(Kind.ARCHIVE_DURABLE,
                            new VersionPinnedSignedReceiptTransport(NAMESPACE,
                                    Kind.ARCHIVE_DURABLE, archivePinIndex,
                                    (key, version, etag) -> {
                                        archiveReads++;
                                        return archiveObjects.get(key);
                                    })),
                    sourceEvidenceSigner.getPublic(), archiveEvidenceSigner.getPublic(),
                    "source-issuer", "archive-issuer", claims, clock);
        }

        void assertDeniedBeforeArchiveRead() throws Exception {
            assertFalse(verifier.verify(proof));
            assertEquals(0, archiveReads);
            assertEquals(0, claims.load(STREAM).epoch());
        }

        void addReceipt(Kind kind, String key, String version, String etag,
                String issuer, KeyPair signer, Map<String, VersionedObject> store,
                byte[] digest) throws Exception {
            Evidence bare = new Evidence(kind, issuer, digest,
                    NOW - 1000, NOW + 60000, new byte[0]);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update(SignedRotationEvidenceAuthority.signingPayload(proof, bare));
            byte[] bytes = SignedEvidenceArtifactAdapter.encode(new Evidence(kind, issuer,
                    digest, NOW - 1000, NOW + 60000, signature.sign()));
            store.put(key, new VersionedObject(version, etag, bytes));
        }

        SignedPinDocument signedPin(Kind kind, String issuer, String key,
                long revision, long issued, long expiry, Pin pin, KeyPair signer)
                throws Exception {
            SignedPinDocument unsigned = new SignedPinDocument(kind, issuer,
                    key, revision, issued, expiry, pin, new byte[0]);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update(SignedReceiptPinIndex.signingPayload(unsigned));
            return new SignedPinDocument(kind, issuer, key, revision,
                    issued, expiry, pin, signature.sign());
        }
    }

    private static Pin pinOf(VersionedObject object) throws Exception {
        return new Pin(object.versionId(), object.etag(), object.bytes().length,
                sha256(object.bytes()));
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
