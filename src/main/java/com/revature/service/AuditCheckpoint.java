package com.revature.service;

import java.util.Objects;

/**
 * Externally persisted identity and last durably acknowledged source cursor.
 * A stream identity must change when the source is restored or reinitialized.
 */
public final class AuditCheckpoint {
    private final String streamId;
    private final AuditSourceCursor sourceCursor;

    public AuditCheckpoint(String streamId, AuditSourceCursor sourceCursor) {
        if (streamId == null || streamId.trim().isEmpty()) {
            throw new IllegalArgumentException("checkpoint stream identity required");
        }
        this.streamId = streamId;
        this.sourceCursor = Objects.requireNonNull(sourceCursor, "sourceCursor");
    }

    public String streamId() { return streamId; }
    public AuditSourceCursor sourceCursor() { return sourceCursor; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AuditCheckpoint)) return false;
        AuditCheckpoint checkpoint = (AuditCheckpoint) other;
        return sourceCursor.equals(checkpoint.sourceCursor)
                && streamId.equals(checkpoint.streamId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, sourceCursor);
    }
}
