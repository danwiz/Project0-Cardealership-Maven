package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Ported behavioral invariants from the unmerged short-chain offer regression work.
 * Runs on the long-head typed domain; this does not reconcile the 15 merge conflicts.
 */
@Tag("INTEGRATION-CANDIDATE-V22")
class OfferIntegrationV22Test {

    @Test
    void pendingRequestsPreserveStableIdsAndDoNotOversellFinalUnit() {
        Offer offers = new Offer();
        offers.registerOffer("Honda", "Civic", 2020, 12000, "yes", 1);
        offers.setpOffer("first", 0);
        offers.setpOffer("second", 0);
        assertEquals(2, offers.getPurchaseRequests().size());
        assertEquals(0, offers.getPurchaseRequests().get(0).getId());
        assertEquals(1, offers.getPurchaseRequests().get(1).getId());
        offers.setAccept(0, 12, true);
        assertEquals(0, offers.getListings().get(0).getStockQuantity());
        assertEquals(PurchaseRequestStatus.ACCEPTED, offers.getPurchaseRequests().get(0).getStatus());
        assertThrows(IllegalStateException.class, () -> offers.setAccept(1, 12, true));
        assertEquals(PurchaseRequestStatus.PENDING, offers.getPurchaseRequests().get(1).getStatus());
        assertEquals(0, offers.getListings().get(0).getStockQuantity());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void competingApprovalCallsHaveExactlyOneWinner() throws Exception {
        Offer offers = new Offer();
        offers.registerOffer("Toyota", "Corolla", 2021, 24000, "yes", 1);
        offers.setpOffer("first", 0);
        offers.setpOffer("second", 0);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> attemptApproval(offers, 0, go));
            Future<Boolean> b = pool.submit(() -> attemptApproval(offers, 1, go));
            go.countDown();
            int winners = (a.get(6, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(6, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners, "a single stocked listing permits one approval");
            assertEquals(0, offers.getListings().get(0).getStockQuantity());
            long accepted = offers.getPurchaseRequests().stream()
                    .filter(r -> r.getStatus() == PurchaseRequestStatus.ACCEPTED).count();
            assertEquals(1L, accepted);
            assertTrue(offers.getPurchaseRequests().stream()
                    .allMatch(r -> r.getStatus() == PurchaseRequestStatus.ACCEPTED
                            || r.getStatus() == PurchaseRequestStatus.PENDING));
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean attemptApproval(Offer offers, int requestId, CountDownLatch go)
            throws InterruptedException {
        go.await();
        try {
            offers.setAccept(requestId, 12, true);
            return true;
        } catch (IllegalStateException expectedOutOfStock) {
            return false;
        }
    }
}
