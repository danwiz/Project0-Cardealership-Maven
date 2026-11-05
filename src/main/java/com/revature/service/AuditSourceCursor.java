package com.revature.service;

import java.util.Objects;

/**
 * Provider-neutral, per-event commit-order position. The first component is an
 * unsigned, source-defined commit position; the second disambiguates distinct
 * audit rows emitted within one committed transaction. Neither is an audit ID.
 *
 * The source adapter must guarantee this pair is stable across replay, restarts
 * and all pages of the stream. A raw sequence ID or transaction LSN alone does
 * NOT satisfy that guarantee.
 */
public final class AuditSourceCursor implements Comparable<AuditSourceCursor> {
    private static final AuditSourceCursor ORIGIN = new AuditSourceCursor(0, 0);

    private final long commitPosition;
    private final int eventOrdinal;

    public AuditSourceCursor(long commitPosition, int eventOrdinal) {
        if (eventOrdinal < 0 || (commitPosition == 0 && eventOrdinal != 0)) {
            throw new IllegalArgumentException("invalid audit source cursor");
        }
        this.commitPosition = commitPosition;
        this.eventOrdinal = eventOrdinal;
    }

    public static AuditSourceCursor origin() { return ORIGIN; }
    public long commitPosition() { return commitPosition; }
    public int eventOrdinal() { return eventOrdinal; }

    @Override
    public int compareTo(AuditSourceCursor other) {
        Objects.requireNonNull(other, "other");
        int position = Long.compareUnsigned(commitPosition, other.commitPosition);
        return position != 0 ? position : Integer.compare(eventOrdinal, other.eventOrdinal);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AuditSourceCursor)) return false;
        AuditSourceCursor cursor = (AuditSourceCursor) other;
        return commitPosition == cursor.commitPosition && eventOrdinal == cursor.eventOrdinal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitPosition, eventOrdinal);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(commitPosition) + ":" + eventOrdinal;
    }
}
