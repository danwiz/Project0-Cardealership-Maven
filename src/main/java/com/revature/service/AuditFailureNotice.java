package com.revature.service;

import java.util.Objects;
import java.util.UUID;

/** Metadata-only alert for a single forwarding failure; no raw exception data. */
public final class AuditFailureNotice {
    private final String noticeId;
    private final String phase;
    private final String streamId;
    private final AuditSourceCursor sourceCursor;
    private final long auditId;
    private final String digest;

    public AuditFailureNotice(String noticeId, String phase, String streamId,
            AuditSourceCursor sourceCursor, long auditId) {
        this.noticeId = UUID.fromString(Objects.requireNonNull(noticeId, "noticeId"))
                .toString();
        if (!"checkpoint-read".equals(phase) && !"source-history-unavailable".equals(phase)
                && !"source-read-or-order".equals(phase) && !"append".equals(phase)
                && !"checkpoint-write".equals(phase)) {
            throw new IllegalArgumentException("invalid audit failure phase");
        }
        if (streamId == null || streamId.trim().isEmpty()
                || !streamId.equals(streamId.trim()) || auditId < 0) {
            throw new IllegalArgumentException("invalid audit failure metadata");
        }
        this.phase = phase;
        this.streamId = streamId;
        this.sourceCursor = Objects.requireNonNull(sourceCursor, "sourceCursor");
        this.auditId = auditId;
        this.digest = AuditContentDigest.alert(this);
    }

    public String noticeId() { return noticeId; }
    public String phase() { return phase; }
    public String streamId() { return streamId; }
    public AuditSourceCursor sourceCursor() { return sourceCursor; }
    public long auditId() { return auditId; }
    public String recordKey() { return "audit-alert:" + noticeId; }
    public String contentSha256() { return digest; }
}
