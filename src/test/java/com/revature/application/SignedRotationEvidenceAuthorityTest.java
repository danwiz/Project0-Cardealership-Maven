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

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcRotationClaimLedger;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.SignedRotationEvidenceAuthority;
import com.revature.service.SignedRotationEvidenceAuthority.Evidence;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;

/** Isolated synthetic signers and H2 ledger; no real CDC/immutable archive. */
class SignedRotationEvidenceAuthorityTest {
    private static final String STREAM = "ci-signed-audit";
    private static final String SOURCE = "ci-source-signer";
    private static final String ARCHIVE = "ci-archive-signer";
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void twoIndependentSignaturesMustMatchBeforeDurableClaim() throws Exception {
        Fixture fixture = fixture();
        ReopenProof candidate = proof(0, 1, 1, 12);
        SignedRotationEvidenceAuthority authority = fixture.authority(
                fixture.source(candidate), fixture.archive(candidate));
        ReconciledJdbcWriterGate gate = new ReconciledJdbcWriterGate(
                fixture.database::openConnection, generation -> generation == 1,
                authority, checkpoint(0), 0, 0);
        assertThrows(SecurityException.class, () -> gate.withConnection(c -> true));
        gate.activate(candidate);
        assertEquals(Boolean.TRUE, gate.withConnection(c -> !c.isClosed()));
        assertEquals(1, fixture.ledger.load(STREAM).generation());
        assertEquals(1, fixture.ledger.load(STREAM).epoch());
        assertEquals(checkpoint(12), fixture.ledger.load(STREAM).checkpoint());
        gate.pauseAndDrain(1);
        assertThrows(SecurityException.class, () -> gate.activate(candidate));
        assertThrows(SecurityException.class, () -> gate.withConnection(c -> true));
    }

    @Test
    void rejectsTamperingMismatchedDigestWrongSignerAndWrongKind() throws Exception {
        Fixture fixture = fixture();
        ReopenProof proof = proof(0, 1, 1, 20);
        Evidence goodSource = fixture.source(proof);
        Evidence goodArchive = fixture.archive(proof);
        assertFalse(fixture.authority(goodSource,
                fixture.signed(Kind.ARCHIVE_DURABLE, ARCHIVE, fixture.archiveKey,
                        proof, digest("different-manifest"), NOW - 1000, NOW + 60000))
                .verify(proof));
        assertFalse(fixture.authority(goodSource,
                fixture.signed(Kind.SOURCE_COMPLETE, SOURCE, fixture.sourceKey,
                        proof, digest("manifest"), NOW - 1000, NOW + 60000))
                .verify(proof));
        assertFalse(fixture.authority(goodSource,
                fixture.signed(Kind.ARCHIVE_DURABLE, ARCHIVE, fixture.sourceKey,
                        proof, digest("manifest"), NOW - 1000, NOW + 60000))
                .verify(proof));
        assertFalse(fixture.authority(goodSource,
                fixture.signed(Kind.ARCHIVE_DURABLE, "untrusted-archive",
                        fixture.archiveKey, proof, digest("manifest"),
                        NOW - 1000, NOW + 60000)).verify(proof));
        assertFalse(fixture.authority(goodSource, goodArchive)
                .verify(proof(0, 1, 1, 21)),
                "a signed watermark cannot authorize a different cursor");
        assertFalse(fixture.authority(goodSource, goodArchive)
                .verify(proof(0, 2, 1, 20)),
                "a signed generation cannot be silently changed");
        assertEquals(0, fixture.ledger.load(STREAM).epoch());
    }

    @Test
    void rejectsExpiredFutureOversizedAndUnavailableEvidence() throws Exception {
        Fixture fixture = fixture();
        ReopenProof proof = proof(0, 1, 1, 8);
        Evidence good = fixture.source(proof);
        assertFalse(fixture.authority(good,
                fixture.signed(Kind.ARCHIVE_DURABLE, ARCHIVE, fixture.archiveKey,
                        proof, digest("manifest"), NOW - 61000, NOW - 1000))
                .verify(proof));
        assertFalse(fixture.authority(
                fixture.signed(Kind.SOURCE_COMPLETE, SOURCE, fixture.sourceKey,
                        proof, digest("manifest"), NOW + 1, NOW + 1000),
                fixture.archive(proof)).verify(proof));
        assertFalse(fixture.authority(good,
                fixture.signed(Kind.ARCHIVE_DURABLE, ARCHIVE, fixture.archiveKey,
                        proof, digest("manifest"), NOW - 301000, NOW + 60000))
                .verify(proof));
        assertFalse(new SignedRotationEvidenceAuthority(
                p -> good,
                p -> { throw new IllegalStateException("secret-provider-diagnostics"); },
                fixture.sourceKey.getPublic(), fixture.archiveKey.getPublic(),
                SOURCE, ARCHIVE, fixture.ledger, fixture.clock).verify(proof));
        assertFalse(new SignedRotationEvidenceAuthority(
                p -> good, p -> null,
                fixture.sourceKey.getPublic(), fixture.archiveKey.getPublic(),
                SOURCE, ARCHIVE, fixture.ledger, fixture.clock).verify(proof));
        assertThrows(IllegalArgumentException.class,
                () -> new SignedRotationEvidenceAuthority(p -> good, p -> good,
                        fixture.sourceKey.getPublic(), fixture.sourceKey.getPublic(),
                        SOURCE, ARCHIVE, fixture.ledger, fixture.clock));
        assertEquals(0, fixture.ledger.load(STREAM).epoch());
    }

    @Test
    void distinctValidatedReplicasCompetingForSignedEpochHaveOneWinner()
            throws Exception {
        Fixture fixture = fixture();
        ReopenProof candidate = proof(0, 1, 1, 4);
        Evidence source = fixture.source(candidate);
        Evidence archive = fixture.archive(candidate);
        SignedRotationEvidenceAuthority first = fixture.authority(source, archive);
        SignedRotationEvidenceAuthority second = fixture.authority(source, archive);
        ReconciledJdbcWriterGate gateA = new ReconciledJdbcWriterGate(
                fixture.database::openConnection, generation -> true, first,
                checkpoint(0), 0, 0);
        ReconciledJdbcWriterGate gateB = new ReconciledJdbcWriterGate(
                fixture.database::openConnection, generation -> true, second,
                checkpoint(0), 0, 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = workers.submit(() -> activate(gateA, candidate, ready, release));
            Future<Boolean> b = workers.submit(() -> activate(gateB, candidate, ready, release));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(a.get(10, TimeUnit.SECONDS) != b.get(10, TimeUnit.SECONDS));
            assertEquals(1, fixture.ledger.load(STREAM).epoch());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static boolean activate(ReconciledJdbcWriterGate gate, ReopenProof proof,
            CountDownLatch ready, CountDownLatch release) throws Exception {
        ready.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race timed out");
        try {
            gate.activate(proof);
            return true;
        } catch (SecurityException denied) {
            return false;
        }
    }

    private static Fixture fixture() throws Exception {
        JdbcDatabase database = JdbcDatabase.inMemory("signed_evidence_" +
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
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new Fixture(database, generator.generateKeyPair(), generator.generateKeyPair());
    }

    private static byte[] digest(String manifest) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(
                manifest.getBytes(StandardCharsets.UTF_8));
    }

    private static AuditCheckpoint checkpoint(long cursor) {
        return new AuditCheckpoint(STREAM, new AuditSourceCursor(cursor, 0));
    }

    private static ReopenProof proof(long previous, long next, long epoch, long cursor) {
        return new ReopenProof(previous, next, epoch,
                checkpoint(cursor), checkpoint(cursor));
    }

    private static final class Fixture {
        private final JdbcDatabase database;
        private final KeyPair sourceKey;
        private final KeyPair archiveKey;
        private final JdbcRotationClaimLedger ledger;
        private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

        private Fixture(JdbcDatabase database, KeyPair sourceKey, KeyPair archiveKey) {
            this.database = database;
            this.sourceKey = sourceKey;
            this.archiveKey = archiveKey;
            this.ledger = new JdbcRotationClaimLedger(database::openConnection);
        }

        private Evidence source(ReopenProof proof) throws Exception {
            return signed(Kind.SOURCE_COMPLETE, SOURCE, sourceKey, proof,
                    digest("manifest"), NOW - 1000, NOW + 60000);
        }

        private Evidence archive(ReopenProof proof) throws Exception {
            return signed(Kind.ARCHIVE_DURABLE, ARCHIVE, archiveKey, proof,
                    digest("manifest"), NOW - 1000, NOW + 60000);
        }

        private Evidence signed(Kind kind, String issuer, KeyPair key,
                ReopenProof proof, byte[] manifestDigest, long issued, long expiry)
                throws Exception {
            Evidence unsigned = new Evidence(kind, issuer, manifestDigest,
                    issued, expiry, new byte[0]);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key.getPrivate());
            signer.update(SignedRotationEvidenceAuthority.signingPayload(proof, unsigned));
            return new Evidence(kind, issuer, manifestDigest, issued, expiry, signer.sign());
        }

        private SignedRotationEvidenceAuthority authority(Evidence source, Evidence archive) {
            return new SignedRotationEvidenceAuthority(p -> source, p -> archive,
                    sourceKey.getPublic(), archiveKey.getPublic(),
                    SOURCE, ARCHIVE, ledger, clock);
        }
    }
}
