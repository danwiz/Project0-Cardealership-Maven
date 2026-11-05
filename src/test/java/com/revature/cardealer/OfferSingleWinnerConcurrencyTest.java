package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Process-local compatibility facade protection; not a JDBC transaction test. */
@Tag("SECURITY")
class OfferSingleWinnerConcurrencyTest {

    @Test
    void mutationEndpointsShareTheOfferMonitor() throws Exception {
        for (Method method : new Method[] {
                Offer.class.getMethod("setpOffer", String.class, int.class),
                Offer.class.getMethod("registerOffer", String.class, String.class,
                        int.class, int.class, String.class, int.class),
                Offer.class.getMethod("setAccept", int.class, int.class, boolean.class),
                Offer.class.getMethod("rejectAllOffers"),
                Offer.class.getMethod("removeOffer", int.class),
                Offer.class.getMethod("getPurchaseRequests"),
                Offer.class.getMethod("getListings") }) {
            assertTrue(Modifier.isSynchronized(method.getModifiers()), method.getName());
        }
    }

    @Test
    void competingApprovalsCannotAcceptMoreThanAvailableInventory() throws Exception {
        Offer offer = new Offer();
        offer.registerOffer("Honda", "Accord", 2020, 12000, "yes", 1);
        offer.setpOffer("buyer-one", 0);
        offer.setpOffer("buyer-two", 0);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> approveOnce(offer, 0, start));
            Future<Boolean> second = executor.submit(() -> approveOnce(offer, 1, start));
            start.countDown();

            boolean firstAccepted = first.get(20, TimeUnit.SECONDS);
            boolean secondAccepted = second.get(20, TimeUnit.SECONDS);
            assertTrue(firstAccepted != secondAccepted, "exactly one buyer must win the last vehicle");
            assertEquals(0, offer.getListings().get(0).getStockQuantity());

            List<PurchaseRequest> requests = offer.getPurchaseRequests();
            long accepted = requests.stream()
                    .filter(request -> request.getStatus() == PurchaseRequestStatus.ACCEPTED)
                    .count();
            long pending = requests.stream()
                    .filter(request -> request.getStatus() == PurchaseRequestStatus.PENDING)
                    .count();
            assertEquals(1, accepted);
            assertEquals(1, pending);
            assertThrows(IllegalStateException.class,
                    () -> offer.setAccept(firstAccepted ? 1 : 0, 12, true));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static boolean approveOnce(Offer offer, int requestId, CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrency start timed out");
            }
            offer.setAccept(requestId, 12, true);
            return true;
        } catch (IllegalStateException soldOut) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
