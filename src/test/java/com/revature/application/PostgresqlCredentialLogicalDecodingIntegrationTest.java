package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.CredentialAuditEvent;
import com.revature.service.CredentialAuditForwarder;

/**
 * CI-only PostgreSQL 16 test_decoding proof, not a production CDC adapter.
 * Uses a unique ledger schema and logical slot in the disposable CI database.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlCredentialLogicalDecodingIntegrationTest {

    @Test
    void decodingUsesCommitOrderAndDetectsUnrecoverableSlotLoss() throws Exception {
        // Never create a replication slot against an arbitrary developer database.
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI")));
        String url = System.getenv("DEALERSHIP_PG_TEST_URL");
        String owner = System.getenv("DEALERSHIP_PG_TEST_USER");
        String secret = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        assertEquals("jdbc:postgresql://localhost:5432/dealership_ci", url);
        assertEquals("dealership_ci_owner", owner);
        // Avoid printing an unexpected secret in assertion output.
        assertTrue("ci-only-postgres-fixture".equals(secret),
                "logical decoding test requires the disposable CI credential");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "cdc_" + suffix;
        String slot = "cdc_slot_" + suffix;
        String ledger = schema + ".credential_rotation_audit";
        boolean schemaCreated = false;
        boolean slotCreated = false;

        try (Connection ownerConnection = DriverManager.getConnection(url, owner, secret)) {
            try {
                try (Statement statement = ownerConnection.createStatement();
                        ResultSet result = statement.executeQuery("SHOW wal_level")) {
                    assertTrue(result.next());
                    assertEquals("logical", result.getString(1));
                }
                try (Statement statement = ownerConnection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                    schemaCreated = true;
                    statement.execute("CREATE TABLE " + ledger + " ("
                            + "audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                            + "account_username TEXT NOT NULL, "
                            + "database_actor TEXT NOT NULL DEFAULT current_user, "
                            + "changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                }
                try (PreparedStatement statement = ownerConnection.prepareStatement(
                        "SELECT slot_name FROM pg_create_logical_replication_slot(?::name, "
                        + "'test_decoding')")) {
                    statement.setString(1, slot);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(slot, result.getString(1));
                    }
                    slotCreated = true;
                }

                long lateId;
                long earlyOneId;
                long earlyTwoId;
                try (Connection late = DriverManager.getConnection(url, owner, secret);
                        Connection early = DriverManager.getConnection(url, owner, secret)) {
                    late.setAutoCommit(false);
                    early.setAutoCommit(false);

                    // The earlier sequence value is not yet committed.
                    lateId = insertAudit(late, ledger, "late-commit");
                    earlyOneId = insertAudit(early, ledger, "early-one");
                    earlyTwoId = insertAudit(early, ledger, "early-two");
                    assertTrue(lateId < earlyOneId && earlyOneId < earlyTwoId);
                    early.commit();

                    List<String> first = decodedLedgerRows(ownerConnection, slot, schema, false);
                    assertEquals(2, first.size());
                    assertTrue(first.get(0).contains("'early-one'"));
                    assertTrue(first.get(1).contains("'early-two'"));
                    // A consumer that fails before acknowledgement can peek again.
                    assertEquals(first, decodedLedgerRows(ownerConnection, slot, schema, false));

                    late.commit();
                }

                List<String> committed = decodedLedgerRows(ownerConnection, slot, schema, false);
                assertEquals(3, committed.size());
                assertTrue(committed.get(0).contains("'early-one'"));
                assertTrue(committed.get(1).contains("'early-two'"));
                assertTrue(committed.get(2).contains("'late-commit'"));
                assertEquals(committed,
                        decodedLedgerRows(ownerConnection, slot, schema, false));

                // Wire real test_decoding transaction frames into the
                // provider-neutral forwarder using a test-only text parser.
                String streamId = "disposable-cdc-" + suffix;
                TestDecodingAuditSource source = new TestDecodingAuditSource(
                        ownerConnection, slot, schema, streamId);
                List<CredentialAuditEvent> decoded = source.readAfter(
                        AuditSourceCursor.origin(), 10);
                assertEquals(3, decoded.size());
                assertEquals(earlyOneId, decoded.get(0).auditId());
                assertEquals(earlyTwoId, decoded.get(1).auditId());
                assertEquals(lateId, decoded.get(2).auditId());
                assertEquals(decoded.get(0).sourceCursor().commitPosition(),
                        decoded.get(1).sourceCursor().commitPosition());
                assertEquals(0, decoded.get(0).sourceCursor().eventOrdinal());
                assertEquals(1, decoded.get(1).sourceCursor().eventOrdinal());
                assertTrue(decoded.get(1).sourceCursor().compareTo(
                        decoded.get(2).sourceCursor()) < 0);
                assertEquals(decoded.stream().map(CredentialAuditEvent::sourceCursor)
                                .collect(Collectors.toList()),
                        source.readAfter(AuditSourceCursor.origin(), 10).stream()
                                .map(CredentialAuditEvent::sourceCursor)
                                .collect(Collectors.toList()));
                // Checkpoint after the first row of a committed transaction;
                // the next page must resume with ordinal 1 at the SAME LSN.
                assertEquals(List.of(decoded.get(0).sourceCursor()),
                        source.readAfter(AuditSourceCursor.origin(), 1).stream()
                                .map(CredentialAuditEvent::sourceCursor)
                                .collect(Collectors.toList()));
                assertEquals(List.of(decoded.get(1).sourceCursor()),
                        source.readAfter(decoded.get(0).sourceCursor(), 1).stream()
                                .map(CredentialAuditEvent::sourceCursor)
                                .collect(Collectors.toList()));

                AtomicReference<AuditCheckpoint> acknowledged = new AtomicReference<>(
                        new AuditCheckpoint(streamId, AuditSourceCursor.origin()));
                AtomicBoolean failSecondCheckpointOnce = new AtomicBoolean(true);
                CredentialAuditForwarder.CheckpointStore checkpoint =
                        new CredentialAuditForwarder.CheckpointStore() {
                    @Override public AuditCheckpoint load() { return acknowledged.get(); }
                    @Override public boolean advanceIfCurrent(
                            AuditCheckpoint expected, AuditSourceCursor next) {
                        if (next.equals(decoded.get(1).sourceCursor())
                                && failSecondCheckpointOnce.getAndSet(false)) {
                            // Crash after the sink's durable acknowledgement.
                            throw new IllegalStateException("fixture checkpoint interrupted");
                        }
                        AuditCheckpoint current = acknowledged.get();
                        return current != null && current.equals(expected)
                                && next.compareTo(expected.sourceCursor()) > 0
                                && acknowledged.compareAndSet(current,
                                        new AuditCheckpoint(streamId, next));
                    }
                };
                Map<String, CredentialAuditEvent> archive = new LinkedHashMap<>();
                List<String> alerts = new ArrayList<>();
                AtomicInteger secondEventAppendAttempts = new AtomicInteger();
                CredentialAuditForwarder.AppendOnlySink sink = event -> {
                    if (event.auditId() == earlyTwoId) secondEventAppendAttempts.incrementAndGet();
                    CredentialAuditEvent prior = archive.putIfAbsent(event.eventKey(), event);
                    if (prior != null) {
                        // A replay is safe only when the source presents the
                        // same immutable event; conflicting keys must fail.
                        if (!prior.sourceCursor().equals(event.sourceCursor())
                                || prior.auditId() != event.auditId()
                                || !prior.accountUsername().equals(event.accountUsername())
                                || !prior.databaseActor().equals(event.databaseActor())
                                || !prior.changedAt().equals(event.changedAt())) {
                            throw new SecurityException("archive event key collision");
                        }
                    }
                };
                CredentialAuditForwarder.AlertSink notifications =
                        (phase, stream, cursor, id) -> alerts.add(phase + ":" + cursor);
                CredentialAuditForwarder forwarder = new CredentialAuditForwarder(
                        streamId, source, sink, checkpoint, notifications, 1);
                assertEquals(1, forwarder.forwardNextBatch());
                assertEquals(decoded.get(0).sourceCursor(),
                        acknowledged.get().sourceCursor());
                assertEquals(1, archive.size());
                assertThrows(IllegalStateException.class, forwarder::forwardNextBatch);
                assertEquals(decoded.get(0).sourceCursor(),
                        acknowledged.get().sourceCursor());
                assertEquals(2, archive.size()); // Append succeeded before the crash.
                assertEquals(List.of("checkpoint-write:" + decoded.get(1).sourceCursor()), alerts);

                // A restarted forwarder retries ordinal 1 and the external
                // archive deduplicates the exact event, then resumes later LSNs.
                CredentialAuditForwarder restarted = new CredentialAuditForwarder(
                        streamId, source, sink, checkpoint, notifications, 1);
                assertEquals(1, restarted.forwardNextBatch());
                assertEquals(decoded.get(1).sourceCursor(),
                        acknowledged.get().sourceCursor());
                assertEquals(2, secondEventAppendAttempts.get());
                assertEquals(2, archive.size());
                assertEquals(1, restarted.forwardNextBatch());
                assertEquals(decoded.get(2).sourceCursor(),
                        acknowledged.get().sourceCursor());
                assertEquals(0, restarted.forwardNextBatch());
                assertEquals(3, archive.size());
                assertEquals(List.of("checkpoint-write:" + decoded.get(1).sourceCursor()), alerts);

                assertEquals(committed,
                        decodedLedgerRows(ownerConnection, slot, schema, true));
                assertTrue(decodedLedgerRows(ownerConnection, slot, schema, false).isEmpty());

                dropSlot(ownerConnection, slot);
                slotCreated = false;
                assertThrows(CredentialAuditForwarder.AuditHistoryUnavailableException.class,
                        () -> decodedLedgerRows(ownerConnection, slot, schema, false));
                assertThrows(CredentialAuditForwarder.AuditHistoryUnavailableException.class,
                        restarted::forwardNextBatch);
                assertEquals(List.of("checkpoint-write:" + decoded.get(1).sourceCursor(),
                        "source-history-unavailable:" + decoded.get(2).sourceCursor()), alerts);
            } finally {
                try {
                    if (slotCreated) dropSlot(ownerConnection, slot);
                } finally {
                    if (schemaCreated) {
                        try (Statement statement = ownerConnection.createStatement()) {
                            statement.execute("DROP SCHEMA " + schema + " CASCADE");
                        }
                    }
                }
            }
        }
    }

    private static long insertAudit(Connection connection, String ledger, String username)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + ledger + " (account_username) VALUES (?) RETURNING audit_id")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static List<String> decodedLedgerRows(Connection connection, String slot,
            String schema, boolean consume) throws SQLException {
        String function = consume ? "pg_logical_slot_get_changes"
                : "pg_logical_slot_peek_changes";
        List<String> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT data FROM " + function + "(?::name, NULL, NULL, 'include-xids', '0')")) {
            statement.setString(1, slot);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String row = result.getString(1);
                    if (row.startsWith("table " + schema + ".credential_rotation_audit: INSERT:")) {
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException failure) {
            // An absent slot means the previous cursor's history is no longer
            // available. Do not quietly interpret this as no new audit events.
            if ("42704".equals(failure.getSQLState())) {
                throw new CredentialAuditForwarder.AuditHistoryUnavailableException();
            }
            throw failure;
        }
        return rows;
    }

    private static void dropSlot(Connection connection, String slot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_drop_replication_slot(?::name)")) {
            statement.setString(1, slot);
            statement.execute();
        }
    }
}
