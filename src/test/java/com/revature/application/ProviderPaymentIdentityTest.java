package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("WAVE34")
class ProviderPaymentIdentityTest {
    private static final String MAC = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static ProviderPaymentIdentity id(String request, String tx, int amount,
            String currency, String customer, String key) {
        return new ProviderPaymentIdentity("synthetic", request, tx, customer, 1L,
                "ACQ-000001", amount, currency, "COMMITTED", key, MAC);
    }

    @Test
    void exactProviderOperationReplaysWithSameDigest() {
        ProviderPaymentIdentity first = id("req-1", "tx-1", 1250, "USD", "buyer", "key-1");
        ProviderPaymentIdentity repeat = id("req-1", "tx-1", 1250, "USD", "buyer", "key-1");
        assertEquals(first.getDigest(), repeat.getDigest());
        assertEquals(ProviderPaymentIdentity.DuplicateDisposition.EXACT_REPLAY,
                first.compareSameRequest(repeat));
    }

    @Test
    void sameProviderKeyWithDifferentImmutableClaimsIsConflict() {
        ProviderPaymentIdentity original = id("req-1", "tx-1", 1250, "USD", "buyer", "key-1");
        assertEquals(ProviderPaymentIdentity.DuplicateDisposition.CONFLICT,
                original.compareSameRequest(id("req-1", "tx-1", 1251, "USD", "buyer", "key-1")));
        assertEquals(ProviderPaymentIdentity.DuplicateDisposition.CONFLICT,
                original.compareSameRequest(id("req-1", "tx-2", 1250, "USD", "buyer", "key-1")));
        assertEquals(ProviderPaymentIdentity.DuplicateDisposition.CONFLICT,
                original.compareSameRequest(id("req-1", "tx-1", 1250, "EUR", "buyer", "key-1")));
        assertEquals(ProviderPaymentIdentity.DuplicateDisposition.CONFLICT,
                original.compareSameRequest(id("req-1", "tx-1", 1250, "USD", "other", "key-1")));
        assertEquals(ProviderPaymentIdentity.DuplicateDisposition.CONFLICT,
                original.compareSameRequest(id("req-1", "tx-1", 1250, "USD", "buyer", "key-2")));
    }

    @Test
    void sameAmountWithDifferentRequestIsNotAReplay() {
        ProviderPaymentIdentity first = id("req-1", "tx-1", 1250, "USD", "buyer", "key-1");
        ProviderPaymentIdentity installmentTwo = id("req-2", "tx-2", 1250, "USD", "buyer", "key-1");
        assertNotEquals(first.getDigest(), installmentTwo.getDigest());
        assertThrows(IllegalArgumentException.class, () -> first.compareSameRequest(installmentTwo));
    }

    @Test
    void invalidOrUncommittedUntrustedClaimsFailClosed() {
        assertThrows(SecurityException.class,
                () -> new ProviderPaymentIdentity("synthetic", "req-1", "tx-1", "buyer",
                        1, "ACQ-000001", 1250, "USD", "PENDING", "key-1", MAC));
        assertThrows(SecurityException.class,
                () -> new ProviderPaymentIdentity("synthetic", "req-1", "tx-1", "buyer",
                        1, "ACQ-000001", 1250, "USD", "COMMITTED", "key-1", "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> id("req with spaces", "tx-1", 1250, "USD", "buyer", "key-1"));
        assertThrows(IllegalArgumentException.class,
                () -> id("req-1", "tx-1", -1, "USD", "buyer", "key-1"));
    }
}
