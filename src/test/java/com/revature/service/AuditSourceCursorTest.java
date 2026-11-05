package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuditSourceCursorTest {
    @Test
    void ordersEventsWithinCommitAndThenByCommitPosition() {
        AuditSourceCursor first = new AuditSourceCursor(42, 0);
        AuditSourceCursor second = new AuditSourceCursor(42, 1);
        AuditSourceCursor followingCommit = new AuditSourceCursor(43, 0);
        assertTrue(AuditSourceCursor.origin().compareTo(first) < 0);
        assertTrue(first.compareTo(second) < 0);
        assertTrue(second.compareTo(followingCommit) < 0);
        assertEquals(first, new AuditSourceCursor(42, 0));
        assertEquals(first.hashCode(), new AuditSourceCursor(42, 0).hashCode());
    }

    @Test
    void treatsCommitPositionsAsUnsignedSixtyFourBitValues() {
        AuditSourceCursor low = new AuditSourceCursor(Long.MAX_VALUE, 0);
        AuditSourceCursor high = new AuditSourceCursor(Long.MIN_VALUE, 0);
        assertTrue(low.compareTo(high) < 0);
        assertTrue(high.compareTo(AuditSourceCursor.origin()) > 0);
    }

    @Test
    void rejectsImpossibleOriginOrdinalAndNegativeEventOrdinal() {
        assertThrows(IllegalArgumentException.class, () -> new AuditSourceCursor(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AuditSourceCursor(42, -1));
        assertThrows(IllegalArgumentException.class, () -> new CredentialAuditEvent(
                "fixture", AuditSourceCursor.origin(), 1, "alice", "writer",
                java.time.Instant.EPOCH));
    }
}
