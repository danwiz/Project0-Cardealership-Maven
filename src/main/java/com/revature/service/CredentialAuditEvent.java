package com.revature.service;

import java.time.Instant;
import java.util.Objects;

/** Minimal event metadata; never contains a password or encoded credential. */
public final class CredentialAuditEvent {
    private final String streamId;
    private final AuditSourceCursor sourceCursor;
    private final long auditId;
    private final String accountUsername;
    private final String databaseActor;
    private final Instant changedAt;

    public CredentialAuditEvent(String streamId, AuditSourceCursor sourceCursor, long auditId,
            String accountUsername, String databaseActor, Instant changedAt) {
        this.streamId = required(streamId, "streamId");
        this.sourceCursor = Objects.requireNonNull(sourceCursor, "sourceCursor");
        if (sourceCursor.compareTo(AuditSourceCursor.origin()) <= 0) {
            throw new IllegalArgumentException("event source cursor must follow stream origin");
        }
        if (auditId <= 0) throw new IllegalArgumentException("audit ID must be positive");
        this.auditId = auditId;
        this.accountUsername = required(accountUsername, "accountUsername");
        this.databaseActor = required(databaseActor, "databaseActor");
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
    }

    public String streamId() { return streamId; }
    /** Monotonic commit-order cursor assigned by the trusted source adapter. */
    public AuditSourceCursor sourceCursor() { return sourceCursor; }
    public long auditId() { return auditId; }
    public String accountUsername() { return accountUsername; }
    public String databaseActor() { return databaseActor; }
    public Instant changedAt() { return changedAt; }

    /** Stable idempotency key within one configured source/database stream. */
    public String eventKey() { return streamId + ":" + auditId; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException("invalid audit " + name);
        }
        return value;
    }
}
