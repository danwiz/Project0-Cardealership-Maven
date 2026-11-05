package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.revature.service.CredentialAuditEvent;
import com.revature.service.CredentialAuditForwarder;
import com.revature.service.AuditSourceCursor;

/**
 * Test-scope diagnostic polling adapter for credential_rotation_audit.
 * WARNING: audit_id is a sequence number, NOT a commit-order cursor. A lower
 * ID can commit after a higher one has been forwarded, so this adapter is NOT
 * sufficient for lossless production forwarding without full reconciliation
 * or a reviewed commit-ordered CDC source.
 */
public final class JdbcCredentialAuditSource implements CredentialAuditForwarder.AuditSource {
    private final JdbcDatabase auditorDatabase;
    private final String streamId;

    public JdbcCredentialAuditSource(JdbcDatabase auditorDatabase, String streamId) {
        this.auditorDatabase = Objects.requireNonNull(auditorDatabase, "auditorDatabase");
        if (streamId == null || streamId.trim().isEmpty()) {
            throw new IllegalArgumentException("audit stream identity required");
        }
        this.streamId = streamId;
    }

    @Override
    public List<CredentialAuditEvent> readAfter(AuditSourceCursor lastAcknowledgedCursor,
            int limit) {
        if (lastAcknowledgedCursor == null || lastAcknowledgedCursor.eventOrdinal() != 0
                || lastAcknowledgedCursor.commitPosition() < 0
                || limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("invalid audit cursor or limit");
        }
        List<CredentialAuditEvent> events = new ArrayList<>();
        try (Connection connection = auditorDatabase.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT audit_id, account_username, database_actor, changed_at "
                        + "FROM credential_rotation_audit WHERE audit_id > ? "
                        + "ORDER BY audit_id LIMIT ?")) {
            statement.setLong(1, lastAcknowledgedCursor.commitPosition());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    OffsetDateTime timestamp = result.getObject(4, OffsetDateTime.class);
                    long auditId = result.getLong(1);
                    // CI-only: sequence ID is NOT a safe production commit cursor.
                    events.add(new CredentialAuditEvent(streamId,
                            new AuditSourceCursor(auditId, 0), auditId,
                            result.getString(2), result.getString(3),
                            Objects.requireNonNull(timestamp, "audit timestamp").toInstant()));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("audit source query failed", failure);
        }
        return events;
    }
}
