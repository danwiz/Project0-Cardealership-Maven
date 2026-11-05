package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcSignedReceiptPinRevisionFloor;
import com.revature.repository.jdbc.JdbcSignedReceiptPinRevisionFloor.MonotonicWitness;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.AuthenticatedSignedPinPublisher;
import com.revature.service.AuthenticatedSignedPinPublisher.PublishCommand;
import com.revature.service.CanonicalSignedReceiptTransport;
import com.revature.service.ImmutableSignedPinDocumentPublisher;
import com.revature.service.ImmutableSignedPinDocumentPublisher.ImmutableDocumentStore;
import com.revature.service.SignedReceiptPinIndex;
import com.revature.service.SignedReceiptPinIndex.SignedPinDocument;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;
import com.revature.service.VersionPinnedSignedReceiptTransport.Pin;

/** CI append-only maps are a contract fixture, not genuine WORM storage. */
class ImmutableSignedPinDocumentPublisherTest {
    private static final String NAMESPACE = "tenant-a";
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void sameSignedRevisionCanBeRetriedWhileDistinctRevisionsStayAddressable()
            throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument five = f.signed(5, (byte) 1);
        SignedPinDocument six = f.signed(6, (byte) 2);
        f.publisher.ensurePublished(f.proof, five);
        f.publisher.ensurePublished(f.proof, five);
        f.publisher.ensurePublished(f.proof, six);
        assertEquals(2, f.objects.size());
        assertEquals(5, f.publisher.read(f.proof, f.key, 5).revision());
        assertEquals(6, f.publisher.read(f.proof, f.key, 6).revision());
        assertThrows(SecurityException.class,
                () -> f.publisher.read(f.proof, f.key, 7));
    }

    @Test
    void unknownCommitMustReadBackExactBytesBeforeAcknowledgingRetry() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument document = f.signed(5, (byte) 1);
        AtomicInteger calls = new AtomicInteger();
        ImmutableDocumentStore lostAck = new ImmutableDocumentStore() {
            @Override public boolean putIfAbsent(String key, long revision,
                    SignedPinDocument candidate) throws Exception {
                f.store.putIfAbsent(key, revision, candidate);
                calls.incrementAndGet();
                throw new IllegalStateException("commit succeeded; acknowledgement lost");
            }
            @Override public SignedPinDocument readExact(String key, long revision)
                    throws Exception {
                return f.store.readExact(key, revision);
            }
        };
        ImmutableSignedPinDocumentPublisher reconciled =
                new ImmutableSignedPinDocumentPublisher(f.verifier, lostAck);
        reconciled.ensurePublished(f.proof, document);
        reconciled.ensurePublished(f.proof, document);
        assertEquals(2, calls.get());
        assertEquals(1, f.objects.size(), "unknown commit retry must not duplicate");

        ImmutableSignedPinDocumentPublisher unavailable =
                new ImmutableSignedPinDocumentPublisher(f.verifier,
                        new ImmutableDocumentStore() {
                            @Override public boolean putIfAbsent(String key, long revision,
                                    SignedPinDocument candidate) throws Exception {
                                throw new IllegalStateException("no storage acknowledgement");
                            }
                            @Override public SignedPinDocument readExact(String key,
                                    long revision) {
                                return null;
                            }
                        });
        assertEquals("immutable pin publication unavailable", assertThrows(
                SecurityException.class,
                () -> unavailable.ensurePublished(f.proof, f.signed(7, (byte) 3)))
                .getMessage());
    }

    @Test
    void collidedRevisionsAndInvalidProofsCannotReplaceOriginalDocument()
            throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument original = f.signed(5, (byte) 1);
        SignedPinDocument conflict = f.signed(5, (byte) 2);
        f.publisher.ensurePublished(f.proof, original);
        assertThrows(SecurityException.class,
                () -> f.publisher.ensurePublished(f.proof, conflict));
        assertEquals(original.signature().length,
                f.publisher.read(f.proof, f.key, 5).signature().length);
        assertEquals((byte) 1, f.publisher.read(f.proof, f.key, 5).pin().sha256()[0]);

        AuditCheckpoint checkpoint = new AuditCheckpoint("wrong-stream",
                new AuditSourceCursor(10, 0));
        ReopenProof wrongProof = new ReopenProof(0, 1, 1, checkpoint, checkpoint);
        assertThrows(SecurityException.class,
                () -> f.publisher.read(wrongProof, f.key, 5));
        assertThrows(SecurityException.class,
                () -> f.publisher.ensurePublished(wrongProof, original));

        byte[] wrongSignature = conflict.signature();
        wrongSignature[0] ^= 1;
        SignedPinDocument forged = new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.key, 8, NOW - 1000,
                NOW + 60000, conflict.pin(), wrongSignature);
        assertThrows(SecurityException.class,
                () -> f.publisher.ensurePublished(f.proof, forged));
        assertEquals(1, f.objects.size());
    }

    @Test
    void durableDocumentReadbackGatesAuthenticatedFloorAdvance() throws Exception {
        Fixture f = new Fixture();
        JdbcDatabase db = JdbcDatabase.inMemory("pinpub_sequence_" +
                UUID.randomUUID().toString().replace("-", ""));
        try (Connection connection = db.openConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE signed_receipt_pin_floors ("
                    + "canonical_key VARCHAR(256) PRIMARY KEY, "
                    + "minimum_revision BIGINT NOT NULL CHECK(minimum_revision > 0))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO signed_receipt_pin_floors VALUES (?, 4)")) {
                insert.setString(1, f.key);
                assertEquals(1, insert.executeUpdate());
            }
        }
        AtomicLong witnessValue = new AtomicLong(4);
        MonotonicWitness witness = new MonotonicWitness() {
            @Override public long minimumRevision(String key) { return witnessValue.get(); }
            @Override public long reserveAtLeast(String key, long next) {
                return witnessValue.updateAndGet(old -> Math.max(old, next));
            }
        };
        JdbcSignedReceiptPinRevisionFloor floor = new JdbcSignedReceiptPinRevisionFloor(
                NAMESPACE, Kind.ARCHIVE_DURABLE, db::openConnection, db::openConnection, witness);
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair authorizer = keys.generateKeyPair();
        AuthenticatedSignedPinPublisher coordinator = new AuthenticatedSignedPinPublisher(
                f.verifier, floor, f.publisher, authorizer.getPublic(),
                "publisher-issuer", f.clock);
        SignedPinDocument doc = f.signed(5, (byte) 1);
        PublishCommand command = signCommand(f.key, doc, authorizer);
        assertFalse(f.objects.containsKey(f.storageKey(f.key, 5)));
        assertThrows(SecurityException.class,
                () -> coordinator.publish(f.proof, doc, command));
        assertEquals(4, floor.minimumRevision(f.proof, f.key));
        f.publisher.ensurePublished(f.proof, doc);
        assertTrue(coordinator.publish(f.proof, doc, command));
        assertEquals(5, floor.minimumRevision(f.proof, f.key));
        assertFalse(coordinator.publish(f.proof, doc, command));
    }

    private static PublishCommand signCommand(String key, SignedPinDocument doc,
            KeyPair signer) throws Exception {
        PublishCommand unsigned = new PublishCommand("publisher-issuer",
                key, 4, doc.revision(), NOW - 1000, NOW + 20000, new byte[0]);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(AuthenticatedSignedPinPublisher.signingPayload(unsigned, doc));
        return new PublishCommand("publisher-issuer", key, 4, doc.revision(),
                NOW - 1000, NOW + 20000, signature.sign());
    }

    private static final class Fixture {
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        final ReopenProof proof;
        final String key;
        final KeyPair issuer;
        final ConcurrentHashMap<String, SignedPinDocument> objects = new ConcurrentHashMap<>();
        final ImmutableDocumentStore store = new ImmutableDocumentStore() {
            @Override public boolean putIfAbsent(String key, long revision,
                    SignedPinDocument document) {
                return objects.putIfAbsent(storageKey(key, revision), document) == null;
            }
            @Override public SignedPinDocument readExact(String key, long revision) {
                return objects.get(storageKey(key, revision));
            }
        };
        final SignedReceiptPinIndex verifier;
        final ImmutableSignedPinDocumentPublisher publisher;

        Fixture() throws Exception {
            AuditCheckpoint checkpoint = new AuditCheckpoint("ci-immutable-pin-doc",
                    new AuditSourceCursor(10, 0));
            proof = new ReopenProof(0, 1, 1, checkpoint, checkpoint);
            key = CanonicalSignedReceiptTransport.receiptKey(
                    NAMESPACE, Kind.ARCHIVE_DURABLE, proof);
            KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
            keys.initialize(2048);
            issuer = keys.generateKeyPair();
            verifier = new SignedReceiptPinIndex(NAMESPACE, Kind.ARCHIVE_DURABLE,
                    (p, k) -> null, (p, k) -> 4, issuer.getPublic(),
                    "archive-pin-issuer", clock);
            publisher = new ImmutableSignedPinDocumentPublisher(verifier, store);
        }

        String storageKey(String key, long revision) {
            return key + "#" + revision;
        }

        SignedPinDocument signed(long revision, byte marker) throws Exception {
            byte[] digest = new byte[32];
            digest[0] = marker;
            Pin pin = new Pin("v-immutable-" + revision, "etag-immutable",
                    16, digest);
            SignedPinDocument unsigned = new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                    "archive-pin-issuer", key, revision, NOW - 1000,
                    NOW + 60000, pin, new byte[0]);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(issuer.getPrivate());
            signature.update(SignedReceiptPinIndex.signingPayload(unsigned));
            return new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                    "archive-pin-issuer", key, revision, NOW - 1000,
                    NOW + 60000, pin, signature.sign());
        }
    }
}
