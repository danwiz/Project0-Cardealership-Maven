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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
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

/** Exact disposable GitHub Actions PostgreSQL guard; never runs on production. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlSignedPinPublicationIntegrationTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String NAMESPACE = "tenant-a";

    @Test
    void separateSqlRolesAndDurableReadbackGatePublisherAdvance() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String owner = System.getenv("DEALERSHIP_PG_TEST_USER");
        String password = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(owner)
                        && "ci-only-postgres-fixture".equals(password),
                "Only the exact disposable CI PostgreSQL may run this rehearsal");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "pinpub_" + suffix;
        String readerRole = "pin_reader_" + suffix;
        String publisherRole = "pin_writer_" + suffix;
        String fixturePassword = "ci-only-pin-publication-fixture";
        AuditCheckpoint checkpoint = new AuditCheckpoint("ci-postgres-pin-archive",
                new AuditSourceCursor(50, 1));
        ReopenProof proof = new ReopenProof(0, 1, 1, checkpoint, checkpoint);
        String key = CanonicalSignedReceiptTransport.receiptKey(
                NAMESPACE, Kind.ARCHIVE_DURABLE, proof);

        JdbcDatabase admin = new JdbcDatabase(baseUrl, owner, password);
        try (Connection connection = admin.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE ROLE " + readerRole + " LOGIN PASSWORD '" + fixturePassword + "'");
            sql.execute("CREATE ROLE " + publisherRole + " LOGIN PASSWORD '" + fixturePassword + "'");
            sql.execute("CREATE TABLE " + schema + ".signed_receipt_pin_floors ("
                    + "canonical_key VARCHAR(256) PRIMARY KEY, "
                    + "minimum_revision BIGINT NOT NULL CHECK (minimum_revision > 0))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + schema + ".signed_receipt_pin_floors VALUES (?, 4)")) {
                insert.setString(1, key);
                assertEquals(1, insert.executeUpdate());
            }
            sql.execute("CREATE FUNCTION " + schema + ".enforce_pin_floor() "
                    + "RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.canonical_key <> OLD.canonical_key "
                    + "OR NEW.minimum_revision <= OLD.minimum_revision THEN "
                    + "RAISE EXCEPTION 'pin revision regression'; END IF; "
                    + "RETURN NEW; END $$");
            sql.execute("CREATE TRIGGER signed_pin_monotonic BEFORE UPDATE ON "
                    + schema + ".signed_receipt_pin_floors FOR EACH ROW "
                    + "EXECUTE FUNCTION " + schema + ".enforce_pin_floor()");
            sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + readerRole);
            sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + publisherRole);
            sql.execute("GRANT SELECT ON " + schema + ".signed_receipt_pin_floors TO " + readerRole);
            sql.execute("GRANT SELECT ON " + schema + ".signed_receipt_pin_floors TO " + publisherRole);
            sql.execute("GRANT UPDATE (minimum_revision) ON " + schema
                    + ".signed_receipt_pin_floors TO " + publisherRole);
        }

        String scopedUrl = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase readerDb = new JdbcDatabase(scopedUrl, readerRole, fixturePassword);
        JdbcDatabase publisherDb = new JdbcDatabase(scopedUrl, publisherRole, fixturePassword);
        try (Connection reader = readerDb.openConnection()) {
            assertThrows(SQLException.class, () -> {
                try (Statement sql = reader.createStatement()) {
                    sql.executeUpdate("UPDATE signed_receipt_pin_floors "
                            + "SET minimum_revision = 5");
                }
            });
        }
        try (Connection publisherSql = publisherDb.openConnection()) {
            assertThrows(SQLException.class, () -> {
                try (Statement sql = publisherSql.createStatement()) {
                    sql.executeUpdate("UPDATE signed_receipt_pin_floors "
                            + "SET canonical_key = 'swapped'");
                }
            });
            assertThrows(SQLException.class, () -> {
                try (Statement sql = publisherSql.createStatement()) {
                    sql.executeUpdate("DELETE FROM signed_receipt_pin_floors");
                }
            });
            assertThrows(SQLException.class, () -> {
                try (Statement sql = publisherSql.createStatement()) {
                    sql.executeUpdate("UPDATE signed_receipt_pin_floors "
                            + "SET minimum_revision = 4");
                }
            });
            assertThrows(SQLException.class, () -> {
                try (Statement sql = publisherSql.createStatement()) {
                    sql.execute("ALTER TABLE signed_receipt_pin_floors DISABLE TRIGGER "
                            + "signed_pin_monotonic");
                }
            });
        }

        AtomicLong witnessed = new AtomicLong(4);
        MonotonicWitness witness = new MonotonicWitness() {
            @Override public long minimumRevision(String canonicalKey) {
                assertEquals(key, canonicalKey);
                return witnessed.get();
            }
            @Override public long reserveAtLeast(String canonicalKey, long revision) {
                assertEquals(key, canonicalKey);
                return witnessed.updateAndGet(old -> Math.max(old, revision));
            }
        };
        JdbcSignedReceiptPinRevisionFloor floor = new JdbcSignedReceiptPinRevisionFloor(
                NAMESPACE, Kind.ARCHIVE_DURABLE, readerDb::openConnection,
                publisherDb::openConnection, witness);
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair issuerKey = keys.generateKeyPair();
        KeyPair authorizerKey = keys.generateKeyPair();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        SignedReceiptPinIndex index = new SignedReceiptPinIndex(NAMESPACE,
                Kind.ARCHIVE_DURABLE, (p, k) -> null, floor,
                issuerKey.getPublic(), "archive-pin-issuer", clock);
        AtomicReference<SignedPinDocument> durable = new AtomicReference<>();
        AuthenticatedSignedPinPublisher coordinator = new AuthenticatedSignedPinPublisher(
                index, floor, (p, k, revision) -> durable.get(), authorizerKey.getPublic(),
                "publisher-issuer", clock);

        SignedPinDocument document = signedPin(key, issuerKey);
        PublishCommand command = signedCommand(key, document, authorizerKey);
        assertThrows(SecurityException.class, () -> coordinator.publish(proof, document, command),
                "new floor must wait for an independently readable pin document");
        assertEquals(4, floor.minimumRevision(proof, key));
        assertEquals(4, witnessed.get());
        durable.set(document);
        assertTrue(coordinator.publish(proof, document, command));
        assertEquals(5, floor.minimumRevision(proof, key));
        assertEquals(5, witnessed.get());
        assertFalse(coordinator.publish(proof, document, command));
        assertEquals(5, floor.minimumRevision(proof, key));
    }

    private static SignedPinDocument signedPin(String key, KeyPair issuer) throws Exception {
        Pin pin = new Pin("v-ci-postgres", "etag-ci-postgres", 16, new byte[32]);
        SignedPinDocument unsigned = new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", key, 5, NOW - 1000,
                NOW + 60000, pin, new byte[0]);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(issuer.getPrivate());
        signer.update(SignedReceiptPinIndex.signingPayload(unsigned));
        return new SignedPinDocument(Kind.ARCHIVE_DURABLE, "archive-pin-issuer",
                key, 5, NOW - 1000, NOW + 60000, pin, signer.sign());
    }

    private static PublishCommand signedCommand(String key, SignedPinDocument doc,
            KeyPair authorizer) throws Exception {
        PublishCommand unsigned = new PublishCommand("publisher-issuer",
                key, 4, 5, NOW - 1000, NOW + 20000, new byte[0]);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(authorizer.getPrivate());
        signer.update(AuthenticatedSignedPinPublisher.signingPayload(unsigned, doc));
        return new PublishCommand("publisher-issuer",
                key, 4, 5, NOW - 1000, NOW + 20000, signer.sign());
    }
}
