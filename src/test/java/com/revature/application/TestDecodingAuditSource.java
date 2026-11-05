package com.revature.application;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.revature.service.AuditSourceCursor;
import com.revature.service.CredentialAuditEvent;
import com.revature.service.CredentialAuditForwarder;

/**
 * CI-ONLY parser for PostgreSQL's human-readable test_decoding output.
 * It is deliberately under test sources: text parsing, synthetic timestamps,
 * and peek-only replay are NOT a production-grade CDC/retention implementation.
 */
final class TestDecodingAuditSource implements CredentialAuditForwarder.AuditSource {
    private static final Pattern AUDIT_ID = Pattern.compile("audit_id\\[bigint\\]:(\\d+)");
    private static final Pattern ACCOUNT = Pattern.compile("account_username\\[text\\]:'([^']+)'");
    private static final Pattern ACTOR = Pattern.compile("database_actor\\[text\\]:'([^']+)'");

    private final Connection connection;
    private final String slot;
    private final String schema;
    private final String streamId;

    TestDecodingAuditSource(Connection connection, String slot, String schema, String streamId) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.slot = Objects.requireNonNull(slot, "slot");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.streamId = Objects.requireNonNull(streamId, "streamId");
    }

    @Override
    public List<CredentialAuditEvent> readAfter(AuditSourceCursor after, int limit) {
        Objects.requireNonNull(after, "after");
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("invalid test batch");
        return allCommittedEvents().stream()
                .filter(event -> event.sourceCursor().compareTo(after) > 0)
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<CredentialAuditEvent> allCommittedEvents() {
        List<CredentialAuditEvent> events = new ArrayList<>();
        List<PendingAudit> transaction = new ArrayList<>();
        boolean open = false;

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lsn::text, data FROM pg_logical_slot_peek_changes(" 
                + "?::name, NULL, NULL, 'include-xids', '0')")) {
            statement.setString(1, slot);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String lsn = result.getString(1);
                    String data = result.getString(2);
                    if (data.startsWith("BEGIN")) {
                        if (open) throw new SecurityException("nested audit CDC transaction");
                        transaction.clear();
                        open = true;
                    } else if (data.startsWith("table " + schema
                            + ".credential_rotation_audit: INSERT:")) {
                        if (!open) throw new SecurityException("CDC row outside transaction");
                        transaction.add(new PendingAudit(
                                extract(AUDIT_ID, data), extract(ACCOUNT, data),
                                extract(ACTOR, data)));
                    } else if (data.startsWith("COMMIT")) {
                        if (!open) throw new SecurityException("CDC commit without begin");
                        long position = parseLsn(lsn);
                        for (int ordinal = 0; ordinal < transaction.size(); ordinal++) {
                            PendingAudit row = transaction.get(ordinal);
                            events.add(new CredentialAuditEvent(streamId,
                                    new AuditSourceCursor(position, ordinal), row.auditId,
                                    row.account, row.actor, Instant.EPOCH));
                        }
                        transaction.clear();
                        open = false;
                    }
                }
            }
        } catch (SQLException failure) {
            if ("42704".equals(failure.getSQLState())) {
                throw new CredentialAuditForwarder.AuditHistoryUnavailableException();
            }
            throw new IllegalStateException("test logical decoding unavailable", failure);
        }
        if (open) throw new SecurityException("incomplete CDC transaction frame");
        return events;
    }

    private static long parseLsn(String lsn) {
        String[] halves = lsn.split("/", -1);
        if (halves.length != 2) throw new SecurityException("invalid CDC LSN");
        long high = Long.parseUnsignedLong(halves[0], 16);
        long low = Long.parseUnsignedLong(halves[1], 16);
        if (high > 0xffffffffL || low > 0xffffffffL) {
            throw new SecurityException("invalid CDC LSN component");
        }
        return (high << 32) | low;
    }

    private static String extract(Pattern pattern, String data) {
        Matcher matcher = pattern.matcher(data);
        if (!matcher.find()) throw new SecurityException("incomplete fixture CDC metadata");
        return matcher.group(1);
    }

    private static final class PendingAudit {
        private final long auditId;
        private final String account;
        private final String actor;

        private PendingAudit(String auditId, String account, String actor) {
            this.auditId = Long.parseLong(auditId);
            this.account = account;
            this.actor = actor;
        }
    }
}
