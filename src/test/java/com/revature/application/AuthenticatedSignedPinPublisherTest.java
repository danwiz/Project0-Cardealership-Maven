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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
import com.revature.service.SignedReceiptPinIndex;
import com.revature.service.SignedReceiptPinIndex.SignedPinDocument;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;
import com.revature.service.VersionPinnedSignedReceiptTransport.Pin;

/** Synthetic keys and H2 principals; no actual publisher identity is issued. */
class AuthenticatedSignedPinPublisherTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String NAMESPACE = "tenant-a";

    @Test
    void independentPinAndPublisherSignaturesAdvanceOnceAndRejectReplay() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument signed = f.document(5);
        PublishCommand command = f.command(signed, 4, f.publisherKey);
        f.storedDocument = signed;
        assertTrue(f.publisher.publish(f.proof, signed, command));
        assertEquals(5, f.floor.minimumRevision(f.proof, f.key));
        assertEquals(5, f.witnessValue.get());
        assertFalse(f.publisher.publish(f.proof, signed, command),
                "replayed valid authorization must not claim the same CAS twice");
        assertEquals(5, f.rawSql());
    }

    @Test
    void unauthorizedOrTamperedPublicationsCannotTouchSqlOrWitness() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument signed = f.document(5);
        PublishCommand legitimate = f.command(signed, 4, f.publisherKey);

        f.assertDenied(signed, f.command(signed, 4, f.rogueKey));
        f.assertDenied(signed, new PublishCommand("rogue-publisher", f.key,
                4, 5, NOW - 1000, NOW + 20000, legitimate.signature()));
        f.assertDenied(signed, new PublishCommand("publisher-issuer", "wrong/key",
                4, 5, NOW - 1000, NOW + 20000, legitimate.signature()));

        byte[] changedPinDigest = signed.pin().sha256();
        changedPinDigest[0] ^= 1;
        SignedPinDocument tamperedPin = new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.key, 5, NOW - 1000, NOW + 60000,
                new Pin("v1", "etag1", 16, changedPinDigest), signed.signature());
        f.assertDenied(tamperedPin, f.command(tamperedPin, 4, f.publisherKey));

        PublishCommand expired = f.commandWithTimes(signed, 4, f.publisherKey,
                NOW - 61000, NOW - 1);
        f.assertDenied(signed, expired);

        f.storedDocument = signed;
        PublishCommand staleExpected = f.command(signed, 3, f.publisherKey);
        assertFalse(f.publisher.publish(f.proof, signed, staleExpected));
        assertEquals(4, f.rawSql());
        assertEquals(4, f.witnessValue.get());
        f.storedDocument = signed;
        assertTrue(f.publisher.publish(f.proof, signed, legitimate));
    }

    @Test
    void databasePrincipalsSeparateReaderAndPublisherPrivileges() throws Exception {
        Fixture f = new Fixture();
        try (Connection reader = f.readerDb.openConnection()) {
            try (PreparedStatement query = reader.prepareStatement(
                    "SELECT minimum_revision FROM signed_receipt_pin_floors WHERE canonical_key = ?")) {
                query.setString(1, f.key);
                try (java.sql.ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(4, rows.getLong(1));
                }
            }
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement update = reader.prepareStatement(
                        "UPDATE signed_receipt_pin_floors SET minimum_revision = 6")) {
                    update.executeUpdate();
                }
            });
            assertThrows(SQLException.class, () -> {
                try (Statement ddl = reader.createStatement()) {
                    ddl.execute("DROP TABLE signed_receipt_pin_floors");
                }
            });
        }
        try (Connection publisher = f.publisherDb.openConnection()) {
            assertThrows(SQLException.class, () -> {
                try (Statement delete = publisher.createStatement()) {
                    delete.executeUpdate("DELETE FROM signed_receipt_pin_floors");
                }
            });
            assertThrows(SQLException.class, () -> {
                try (Statement insert = publisher.createStatement()) {
                    insert.executeUpdate("INSERT INTO signed_receipt_pin_floors "
                            + "VALUES ('untrusted-row', 99)");
                }
            });
            assertThrows(SQLException.class, () -> {
                try (Statement ddl = publisher.createStatement()) {
                    ddl.execute("DROP TABLE signed_receipt_pin_floors");
                }
            });
        }
        assertEquals(4, f.floor.minimumRevision(f.proof, f.key));
        SignedPinDocument doc = f.document(5);
        f.storedDocument = doc;
        assertTrue(f.publisher.publish(f.proof, doc,
                f.command(doc, 4, f.publisherKey)));
    }

    @Test
    void failedPublisherCommitRequiresSameAuthenticatedRepairCommand() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument signed = f.document(5);
        PublishCommand command = f.command(signed, 4, f.publisherKey);
        f.storedDocument = signed;
        JdbcSignedReceiptPinRevisionFloor failedFloor =
                new JdbcSignedReceiptPinRevisionFloor(NAMESPACE, Kind.ARCHIVE_DURABLE,
                        f.readerDb::openConnection,
                        () -> { throw new SQLException("private publisher SQL diagnostics"); },
                        f.witness);
        AuthenticatedSignedPinPublisher failedPublisher =
                new AuthenticatedSignedPinPublisher(f.pinVerifier, failedFloor,
                        (p, k, revision) -> f.storedDocument,
                        f.publisherKey.getPublic(), "publisher-issuer", f.clock);
        SecurityException error = assertThrows(SecurityException.class,
                () -> failedPublisher.publish(f.proof, signed, command));
        assertEquals("signed pin publication unavailable", error.getMessage());
        assertEquals(4, f.rawSql());
        assertEquals(5, f.witnessValue.get());
        assertThrows(SecurityException.class,
                () -> f.floor.minimumRevision(f.proof, f.key));

        f.assertDenied(signed, f.command(signed, 4, f.rogueKey), false);
        assertTrue(f.publisher.publish(f.proof, signed, command),
                "legitimate, still-valid publisher authorization may repair SQL");
        assertEquals(5, f.floor.minimumRevision(f.proof, f.key));
    }

    @Test
    void publicationRequiresIndependentDurableReadbackBeforeWitnessReservation()
            throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument signed = f.document(5);
        PublishCommand command = f.command(signed, 4, f.publisherKey);
        f.assertDenied(signed, command);

        f.storedDocument = f.document(6);
        f.assertDenied(signed, command);

        AuthenticatedSignedPinPublisher unavailable = new AuthenticatedSignedPinPublisher(
                f.pinVerifier, f.floor, (p, k, revision) -> {
                    throw new IllegalStateException("private archive diagnostics");
                }, f.publisherKey.getPublic(), "publisher-issuer", f.clock);
        assertEquals("signed pin publication unavailable", assertThrows(
                SecurityException.class,
                () -> unavailable.publish(f.proof, signed, command)).getMessage());
        assertEquals(4, f.witnessValue.get());
        assertEquals(4, f.rawSql());

        f.storedDocument = signed;
        assertTrue(f.publisher.publish(f.proof, signed, command));
        assertEquals(5, f.floor.minimumRevision(f.proof, f.key));
    }

    private static final class Fixture {
        final String dbName = "signed_pin_pub_" + UUID.randomUUID().toString().replace("-", "");
        final String url = "jdbc:h2:mem:" + dbName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        final JdbcDatabase ownerDb = JdbcDatabase.inMemory(dbName);
        final JdbcDatabase readerDb = new JdbcDatabase(url,
                "pin_floor_reader", "ci-only-reader");
        final JdbcDatabase publisherDb = new JdbcDatabase(url,
                "pin_floor_publisher", "ci-only-publisher");
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        final ReopenProof proof;
        final String key;
        final AtomicLong witnessValue = new AtomicLong(4);
        final MonotonicWitness witness = new MonotonicWitness() {
            @Override public long minimumRevision(String canonicalKey) {
                assertEquals(key, canonicalKey);
                return witnessValue.get();
            }
            @Override public long reserveAtLeast(String canonicalKey, long next) {
                assertEquals(key, canonicalKey);
                return witnessValue.updateAndGet(current -> Math.max(current, next));
            }
        };
        final KeyPair pinIssuerKey;
        final KeyPair publisherKey;
        final KeyPair rogueKey;
        final JdbcSignedReceiptPinRevisionFloor floor;
        final SignedReceiptPinIndex pinVerifier;
        final AuthenticatedSignedPinPublisher publisher;
        SignedPinDocument storedDocument;

        Fixture() throws Exception {
            AuditCheckpoint checkpoint = new AuditCheckpoint("ci-auth-pin-publisher",
                    new AuditSourceCursor(33, 0));
            proof = new ReopenProof(0, 1, 1, checkpoint, checkpoint);
            key = CanonicalSignedReceiptTransport.receiptKey(
                    NAMESPACE, Kind.ARCHIVE_DURABLE, proof);
            try (Connection connection = ownerDb.openConnection();
                    Statement sql = connection.createStatement()) {
                sql.execute("CREATE TABLE signed_receipt_pin_floors ("
                        + "canonical_key VARCHAR(256) PRIMARY KEY, "
                        + "minimum_revision BIGINT NOT NULL CHECK (minimum_revision > 0))");
                try (PreparedStatement add = connection.prepareStatement(
                        "INSERT INTO signed_receipt_pin_floors VALUES (?, 4)")) {
                    add.setString(1, key);
                    assertEquals(1, add.executeUpdate());
                }
                sql.execute("CREATE USER pin_floor_reader PASSWORD 'ci-only-reader'");
                sql.execute("CREATE USER pin_floor_publisher PASSWORD 'ci-only-publisher'");
                sql.execute("GRANT SELECT ON signed_receipt_pin_floors TO pin_floor_reader");
                sql.execute("GRANT SELECT, UPDATE ON signed_receipt_pin_floors "
                        + "TO pin_floor_publisher");
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            pinIssuerKey = generator.generateKeyPair();
            publisherKey = generator.generateKeyPair();
            rogueKey = generator.generateKeyPair();
            floor = new JdbcSignedReceiptPinRevisionFloor(NAMESPACE,
                    Kind.ARCHIVE_DURABLE, readerDb::openConnection,
                    publisherDb::openConnection, witness);
            pinVerifier = new SignedReceiptPinIndex(NAMESPACE,
                    Kind.ARCHIVE_DURABLE, (p, k) -> null, floor,
                    pinIssuerKey.getPublic(), "archive-pin-issuer", clock);
            publisher = new AuthenticatedSignedPinPublisher(pinVerifier, floor,
                    (p, k, revision) -> storedDocument,
                    publisherKey.getPublic(), "publisher-issuer", clock);
        }

        SignedPinDocument document(long revision) throws Exception {
            Pin pin = new Pin("v1", "etag1", 16, new byte[32]);
            SignedPinDocument unsigned = new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                    "archive-pin-issuer", key, revision, NOW - 1000,
                    NOW + 60000, pin, new byte[0]);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pinIssuerKey.getPrivate());
            signature.update(SignedReceiptPinIndex.signingPayload(unsigned));
            return new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                    "archive-pin-issuer", key, revision, NOW - 1000,
                    NOW + 60000, pin, signature.sign());
        }

        PublishCommand command(SignedPinDocument doc, long expected, KeyPair signer)
                throws Exception {
            return commandWithTimes(doc, expected, signer, NOW - 1000, NOW + 20000);
        }

        PublishCommand commandWithTimes(SignedPinDocument doc, long expected,
                KeyPair signer, long issued, long expires) throws Exception {
            PublishCommand unsigned = new PublishCommand("publisher-issuer", key,
                    expected, doc.revision(), issued, expires, new byte[0]);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update(AuthenticatedSignedPinPublisher.signingPayload(unsigned, doc));
            return new PublishCommand("publisher-issuer", key,
                    expected, doc.revision(), issued, expires, signature.sign());
        }

        void assertDenied(SignedPinDocument doc, PublishCommand command)
                throws Exception {
            assertDenied(doc, command, true);
        }

        void assertDenied(SignedPinDocument doc, PublishCommand command,
                boolean expectCleanFloor) throws Exception {
            assertEquals("signed pin publication unavailable", assertThrows(
                    SecurityException.class,
                    () -> publisher.publish(proof, doc, command)).getMessage());
            if (expectCleanFloor) {
                assertEquals(4, rawSql());
                assertEquals(4, witnessValue.get());
            }
        }

        long rawSql() throws Exception {
            try (Connection connection = ownerDb.openConnection();
                    PreparedStatement query = connection.prepareStatement(
                            "SELECT minimum_revision FROM signed_receipt_pin_floors WHERE canonical_key = ?")) {
                query.setString(1, key);
                try (java.sql.ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getLong(1);
                }
            }
        }
    }
}
