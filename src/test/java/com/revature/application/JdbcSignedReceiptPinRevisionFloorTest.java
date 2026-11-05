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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcSignedReceiptPinRevisionFloor;
import com.revature.repository.jdbc.JdbcSignedReceiptPinRevisionFloor.MonotonicWitness;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.CanonicalSignedReceiptTransport;
import com.revature.service.SignedReceiptPinIndex;
import com.revature.service.SignedReceiptPinIndex.SignedPinDocument;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;
import com.revature.service.VersionPinnedSignedReceiptTransport.Pin;

/** Disposable H2 + simulated witness; NOT a deployed independent trust anchor. */
class JdbcSignedReceiptPinRevisionFloorTest {
    private static final String NAMESPACE = "tenant-a";
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void restartAndPrivilegedSqlRollbackRequireIndependentWitnessRecovery() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals(4L, fixture.floor.minimumRevision(fixture.proof, fixture.key));
        assertTrue(fixture.floor.advance(fixture.proof, fixture.key, 4, 5));
        JdbcSignedReceiptPinRevisionFloor restarted = fixture.newFloor();
        assertEquals(5L, restarted.minimumRevision(fixture.proof, fixture.key));
        assertFalse(restarted.advance(fixture.proof, fixture.key, 4, 6),
                "stale publisher must not touch the witness or SQL");
        assertFalse(restarted.advance(fixture.proof, fixture.key, 5, 5));
        assertFalse(restarted.advance(fixture.proof, fixture.key, 5, 4));
        assertEquals(5L, fixture.witnessValue.get());

        // Simulates a privileged, out-of-band restoration of *only* the SQL
        // row to an older backup. A plain SQL CAS cannot prevent this.
        fixture.forceSql(4);
        SecurityException restored = assertThrows(SecurityException.class,
                () -> restarted.minimumRevision(fixture.proof, fixture.key));
        assertEquals("signed pin revision floor unavailable", restored.getMessage());
        assertTrue(restarted.advance(fixture.proof, fixture.key, 4, 5),
                "authorized repair may catch SQL back up to the external witness");
        assertEquals(5L, restarted.minimumRevision(fixture.proof, fixture.key));
        assertThrows(SecurityException.class,
                () -> restarted.minimumRevision(fixture.proof, "other-tenant/receipt"));
    }

    @Test
    void twoCompetingPublishersUseAtomicCasAndMonotonicWitness() throws Exception {
        Fixture fixture = new Fixture();
        JdbcSignedReceiptPinRevisionFloor second = fixture.newFloor();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> race(fixture.floor,
                    fixture.proof, fixture.key, 5, ready, release));
            Future<Boolean> later = executor.submit(() -> race(second,
                    fixture.proof, fixture.key, 6, ready, release));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            release.countDown();
            boolean a = first.get(15, TimeUnit.SECONDS);
            boolean b = later.get(15, TimeUnit.SECONDS);
            assertTrue(a != b, "exactly one CAS writer wins from revision four");

            long sqlValue = fixture.rawSql();
            long witness = fixture.witnessValue.get();
            assertTrue(sqlValue == 5 || sqlValue == 6);
            assertTrue(witness >= sqlValue && witness <= 6);
            if (sqlValue < witness) {
                assertThrows(SecurityException.class,
                        () -> fixture.floor.minimumRevision(fixture.proof, fixture.key));
                assertTrue(fixture.floor.advance(fixture.proof, fixture.key,
                        sqlValue, witness), "repair the conservatively blocked state");
            }
            assertEquals(fixture.witnessValue.get(),
                    fixture.floor.minimumRevision(fixture.proof, fixture.key));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void unknownRowsWitnessOutageAndUncertainCommitRemainBlocked() throws Exception {
        Fixture fixture = new Fixture();
        fixture.witnessValue.set(0);
        assertThrows(SecurityException.class,
                () -> fixture.floor.minimumRevision(fixture.proof, fixture.key));
        fixture.witnessValue.set(4);

        JdbcSignedReceiptPinRevisionFloor failedPublisher =
                new JdbcSignedReceiptPinRevisionFloor(NAMESPACE, Kind.ARCHIVE_DURABLE,
                        fixture.database::openConnection,
                        () -> { throw new SQLException("sensitive connection failure"); },
                        fixture.witness);
        SecurityException unavailable = assertThrows(SecurityException.class,
                () -> failedPublisher.advance(fixture.proof, fixture.key, 4, 6));
        assertEquals("signed pin revision floor unavailable", unavailable.getMessage());
        assertEquals(4L, fixture.rawSql());
        assertEquals(6L, fixture.witnessValue.get());
        assertThrows(SecurityException.class,
                () -> fixture.floor.minimumRevision(fixture.proof, fixture.key));
        assertTrue(fixture.floor.advance(fixture.proof, fixture.key, 4, 6));
        assertEquals(6L, fixture.floor.minimumRevision(fixture.proof, fixture.key));

        ReopenProof missing = proof("unknown-stream");
        String missingKey = CanonicalSignedReceiptTransport.receiptKey(
                NAMESPACE, Kind.ARCHIVE_DURABLE, missing);
        assertThrows(SecurityException.class,
                () -> fixture.floor.minimumRevision(missing, missingKey));
        assertThrows(SecurityException.class,
                () -> fixture.floor.advance(missing, missingKey, 4, 5));
    }

    @Test
    void signedPinIndexRejectsStaleDocumentAfterDurableSqlAdvance() throws Exception {
        Fixture fixture = new Fixture();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair signer = generator.generateKeyPair();
        Pin pin = new Pin("v-immutable", "etag-immutable", 16, new byte[32]);
        SignedPinDocument old = sign(fixture.key, 4, pin, signer);
        SignedPinDocument next = sign(fixture.key, 5, pin, signer);
        SignedPinDocument[] document = { old };
        SignedReceiptPinIndex index = new SignedReceiptPinIndex(NAMESPACE,
                Kind.ARCHIVE_DURABLE, (p, key) -> document[0], fixture.floor,
                signer.getPublic(), "archive-pin-issuer",
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        assertEquals("v-immutable", index.expected(fixture.proof, fixture.key).versionId());
        assertTrue(fixture.floor.advance(fixture.proof, fixture.key, 4, 5));
        assertEquals("signed receipt pin unavailable", assertThrows(
                SecurityException.class, () -> index.expected(fixture.proof,
                        fixture.key)).getMessage());
        document[0] = next;
        assertEquals("v-immutable", index.expected(fixture.proof, fixture.key).versionId());
        assertEquals(5L, fixture.floor.minimumRevision(fixture.proof, fixture.key));
    }

    private static boolean race(JdbcSignedReceiptPinRevisionFloor floor,
            ReopenProof proof, String key, long next, CountDownLatch ready,
            CountDownLatch release) throws Exception {
        ready.countDown();
        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("race timed out");
        try {
            return floor.advance(proof, key, 4, next);
        } catch (SecurityException denied) {
            return false;
        }
    }

    private static SignedPinDocument sign(String key, long revision, Pin pin,
            KeyPair signer) throws Exception {
        SignedPinDocument unsigned = new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", key, revision, NOW - 1000,
                NOW + 60000, pin, new byte[0]);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(SignedReceiptPinIndex.signingPayload(unsigned));
        return new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", key, revision, NOW - 1000,
                NOW + 60000, pin, signature.sign());
    }

    private static ReopenProof proof(String stream) {
        AuditCheckpoint checkpoint = new AuditCheckpoint(stream,
                new AuditSourceCursor(18, 1));
        return new ReopenProof(0, 1, 1, checkpoint, checkpoint);
    }

    private static final class Fixture {
        final ReopenProof proof = proof("ci-sql-pin-floor");
        final String key = CanonicalSignedReceiptTransport.receiptKey(
                NAMESPACE, Kind.ARCHIVE_DURABLE, proof);
        final JdbcDatabase database;
        final AtomicLong witnessValue = new AtomicLong(4);
        final MonotonicWitness witness = new MonotonicWitness() {
            @Override
            public long minimumRevision(String canonicalKey) {
                assertEquals(key, canonicalKey);
                return witnessValue.get();
            }

            @Override
            public long reserveAtLeast(String canonicalKey, long next) {
                assertEquals(key, canonicalKey);
                return witnessValue.updateAndGet(current -> Math.max(current, next));
            }
        };
        final JdbcSignedReceiptPinRevisionFloor floor;

        Fixture() throws Exception {
            database = JdbcDatabase.inMemory("pin_floor_" +
                    UUID.randomUUID().toString().replace("-", ""));
            try (Connection connection = database.openConnection();
                    Statement sql = connection.createStatement()) {
                sql.execute("CREATE TABLE signed_receipt_pin_floors ("
                        + "canonical_key VARCHAR(256) PRIMARY KEY, "
                        + "minimum_revision BIGINT NOT NULL CHECK (minimum_revision > 0))");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO signed_receipt_pin_floors VALUES (?, ?)")) {
                    insert.setString(1, key);
                    insert.setLong(2, 4);
                    assertEquals(1, insert.executeUpdate());
                }
            }
            floor = newFloor();
        }

        JdbcSignedReceiptPinRevisionFloor newFloor() {
            return new JdbcSignedReceiptPinRevisionFloor(NAMESPACE,
                    Kind.ARCHIVE_DURABLE, database::openConnection,
                    database::openConnection, witness);
        }

        long rawSql() throws Exception {
            try (Connection connection = database.openConnection();
                    PreparedStatement query = connection.prepareStatement(
                            "SELECT minimum_revision FROM signed_receipt_pin_floors WHERE canonical_key = ?")) {
                query.setString(1, key);
                try (java.sql.ResultSet result = query.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            }
        }

        void forceSql(long revision) throws Exception {
            try (Connection connection = database.openConnection();
                    PreparedStatement update = connection.prepareStatement(
                            "UPDATE signed_receipt_pin_floors SET minimum_revision = ? WHERE canonical_key = ?")) {
                update.setLong(1, revision);
                update.setString(2, key);
                assertEquals(1, update.executeUpdate());
            }
        }
    }
}
