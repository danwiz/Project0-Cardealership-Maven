package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.CanonicalSignedReceiptTransport;
import com.revature.service.ImmutableSignedPinDocumentPublisher;
import com.revature.service.ImmutableSignedPinDocumentPublisher.ImmutableDocumentStore;
import com.revature.service.SignedReceiptPinIndex;
import com.revature.service.SignedReceiptPinIndex.SignedPinDocument;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;
import com.revature.service.VersionPinnedSignedReceiptTransport.Pin;

/**
 * Provider-neutral failure-injection acceptance cases. The concurrent map is
 * deliberately NOT a durable, independently administered WORM implementation.
 */
class ImmutableSignedPinDocumentAdapterAcceptanceTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String NAMESPACE = "tenant-a";
    private static final String GENERIC_DENIAL = "immutable pin publication unavailable";

    @Test
    void concurrentDifferentDocumentsHaveOneWinnerAndNoOverwrite() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument first = f.signed(f.proofA, 5, (byte) 1);
        SignedPinDocument second = f.signed(f.proofA, 5, (byte) 2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> a = () -> attemptAfterGate(f.publisher, f.proofA,
                    first, ready, start);
            Callable<Boolean> b = () -> attemptAfterGate(f.publisher, f.proofA,
                    second, ready, start);
            Future<Boolean> resultA = threads.submit(a);
            Future<Boolean> resultB = threads.submit(b);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            boolean successA = resultA.get(15, TimeUnit.SECONDS);
            boolean successB = resultB.get(15, TimeUnit.SECONDS);
            assertNotEquals(successA, successB, "one first-writer-wins acceptance");
            assertEquals(1, f.store.objects.size());
            SignedPinDocument winner = successA ? first : second;
            SignedPinDocument loser = successA ? second : first;
            f.publisher.ensurePublished(f.proofA, winner);
            assertDenied(() -> f.publisher.ensurePublished(f.proofA, loser));
            assertEquals(winner.pin().sha256()[0],
                    f.publisher.read(f.proofA, f.keyA, 5).pin().sha256()[0]);
        } finally {
            start.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    void lostAcknowledgementAfterCommitAllowsRetryWithRecreatedCoordinator()
            throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument document = f.signed(f.proofA, 5, (byte) 1);
        f.store.writeMode = WriteMode.COMMIT_THEN_LOSE_ACK;
        f.publisher.ensurePublished(f.proofA, document);
        assertEquals(1, f.store.objects.size());
        assertEquals(1, f.store.writeAttempts.get());

        // A new publisher instance has no in-memory acknowledgement state.
        ImmutableSignedPinDocumentPublisher restarted =
                new ImmutableSignedPinDocumentPublisher(f.verifier, f.store);
        restarted.ensurePublished(f.proofA, document);
        assertEquals(1, f.store.objects.size());
        assertEquals(2, f.store.writeAttempts.get());
        assertEquals(5, restarted.read(f.proofA, f.keyA, 5).revision());
    }

    @Test
    void failureBeforeCommitCannotBeMistakenForLostAcknowledgement() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument document = f.signed(f.proofA, 5, (byte) 1);
        f.store.writeMode = WriteMode.REJECT_BEFORE_COMMIT;
        assertDenied(() -> f.publisher.ensurePublished(f.proofA, document));
        assertEquals(0, f.store.objects.size());
        assertEquals(1, f.store.writeAttempts.get());
        f.store.writeMode = WriteMode.NORMAL;
        f.publisher.ensurePublished(f.proofA, document);
        assertEquals(1, f.store.objects.size());
    }

    @Test
    void staleOrIncorrectRevisionReadbackIsNeverAnAcknowledgement() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument stale = f.signed(f.proofA, 4, (byte) 1);
        SignedPinDocument current = f.signed(f.proofA, 5, (byte) 2);
        f.store.staleRead = stale;
        assertDenied(() -> f.publisher.ensurePublished(f.proofA, current));
        assertEquals(1, f.store.objects.size(), "actual append may have committed");
        assertDenied(() -> f.publisher.read(f.proofA, f.keyA, 5));
        f.store.staleRead = null;
        f.publisher.ensurePublished(f.proofA, current);
        assertEquals(5, f.publisher.read(f.proofA, f.keyA, 5).revision());
    }

    @Test
    void readOutageAndSimulatedAdministrativeTamperFailClosed() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument original = f.signed(f.proofA, 5, (byte) 1);
        f.publisher.ensurePublished(f.proofA, original);
        f.store.readUnavailable = true;
        assertDenied(() -> f.publisher.ensurePublished(f.proofA, original));
        assertDenied(() -> f.publisher.read(f.proofA, f.keyA, 5));
        f.store.readUnavailable = false;

        // A mutable fixture can simulate forbidden external administration.
        f.store.objects.remove(f.store.storageKey(f.keyA, 5));
        assertDenied(() -> f.publisher.read(f.proofA, f.keyA, 5));
        SignedPinDocument tampered = f.signed(f.proofA, 5, (byte) 9);
        f.store.objects.put(f.store.storageKey(f.keyA, 5), tampered);
        assertDenied(() -> f.publisher.ensurePublished(f.proofA, original));
        assertEquals(1, f.store.objects.size());
        assertEquals((byte) 9, f.store.objects.get(
                f.store.storageKey(f.keyA, 5)).pin().sha256()[0]);

        // A truly invalid signature is also rejected on standalone read.
        byte[] badSignature = original.signature();
        badSignature[0] ^= 1;
        SignedPinDocument forged = new SignedPinDocument(Kind.ARCHIVE_DURABLE,
                "archive-pin-issuer", f.keyA, 5, NOW - 1000,
                NOW + 60000, original.pin(), badSignature);
        f.store.objects.put(f.store.storageKey(f.keyA, 5), forged);
        assertDenied(() -> f.publisher.read(f.proofA, f.keyA, 5));
    }

    @Test
    void distinctProofBoundKeysAndRevisionsStayIsolated() throws Exception {
        Fixture f = new Fixture();
        SignedPinDocument aFive = f.signed(f.proofA, 5, (byte) 1);
        SignedPinDocument aSix = f.signed(f.proofA, 6, (byte) 2);
        SignedPinDocument bFive = f.signed(f.proofB, 5, (byte) 3);
        assertNotEquals(f.keyA, f.keyB);
        f.publisher.ensurePublished(f.proofA, aFive);
        f.publisher.ensurePublished(f.proofA, aSix);
        f.publisher.ensurePublished(f.proofB, bFive);
        assertEquals(3, f.store.objects.size());
        assertEquals((byte) 1, f.publisher.read(f.proofA, f.keyA, 5).pin().sha256()[0]);
        assertEquals((byte) 2, f.publisher.read(f.proofA, f.keyA, 6).pin().sha256()[0]);
        assertEquals((byte) 3, f.publisher.read(f.proofB, f.keyB, 5).pin().sha256()[0]);
        assertDenied(() -> f.publisher.read(f.proofA, f.keyB, 5));
        assertDenied(() -> f.publisher.read(f.proofB, f.keyA, 5));
    }

    private static boolean attemptAfterGate(ImmutableSignedPinDocumentPublisher publisher,
            ReopenProof proof, SignedPinDocument candidate,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        try {
            publisher.ensurePublished(proof, candidate);
            return true;
        } catch (SecurityException collision) {
            assertEquals(GENERIC_DENIAL, collision.getMessage());
            return false;
        }
    }

    private static void assertDenied(org.junit.jupiter.api.function.Executable action) {
        assertEquals(GENERIC_DENIAL,
                assertThrows(SecurityException.class, action).getMessage());
    }

    private enum WriteMode { NORMAL, REJECT_BEFORE_COMMIT, COMMIT_THEN_LOSE_ACK }

    private static final class SimulatedStore implements ImmutableDocumentStore {
        final ConcurrentHashMap<List<String>, SignedPinDocument> objects =
                new ConcurrentHashMap<>();
        final AtomicInteger writeAttempts = new AtomicInteger();
        volatile WriteMode writeMode = WriteMode.NORMAL;
        volatile boolean readUnavailable;
        volatile SignedPinDocument staleRead;

        List<String> storageKey(String key, long revision) {
            // Tuple boundaries are unambiguous; delimiter concatenation is not.
            return List.of(key, Long.toString(revision));
        }

        @Override
        public boolean putIfAbsent(String key, long revision,
                SignedPinDocument document) {
            writeAttempts.incrementAndGet();
            if (writeMode == WriteMode.REJECT_BEFORE_COMMIT) {
                throw new IllegalStateException("simulated provider diagnostics: no commit");
            }
            boolean created = objects.putIfAbsent(storageKey(key, revision), document) == null;
            if (writeMode == WriteMode.COMMIT_THEN_LOSE_ACK) {
                throw new IllegalStateException("simulated provider diagnostics: ack lost");
            }
            return created;
        }

        @Override
        public SignedPinDocument readExact(String key, long revision) {
            if (readUnavailable) throw new IllegalStateException("simulated read outage");
            if (staleRead != null) return staleRead;
            return objects.get(storageKey(key, revision));
        }
    }

    private static final class Fixture {
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        final ReopenProof proofA;
        final ReopenProof proofB;
        final String keyA;
        final String keyB;
        final KeyPair issuer;
        final SimulatedStore store = new SimulatedStore();
        final SignedReceiptPinIndex verifier;
        final ImmutableSignedPinDocumentPublisher publisher;

        Fixture() throws Exception {
            AuditCheckpoint a = new AuditCheckpoint("ci-acceptance-a",
                    new AuditSourceCursor(10, 0));
            AuditCheckpoint b = new AuditCheckpoint("ci-acceptance-b",
                    new AuditSourceCursor(10, 0));
            proofA = new ReopenProof(0, 1, 1, a, a);
            proofB = new ReopenProof(0, 1, 1, b, b);
            keyA = CanonicalSignedReceiptTransport.receiptKey(
                    NAMESPACE, Kind.ARCHIVE_DURABLE, proofA);
            keyB = CanonicalSignedReceiptTransport.receiptKey(
                    NAMESPACE, Kind.ARCHIVE_DURABLE, proofB);
            KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
            keys.initialize(2048);
            issuer = keys.generateKeyPair();
            verifier = new SignedReceiptPinIndex(NAMESPACE, Kind.ARCHIVE_DURABLE,
                    (proof, key) -> null, (proof, key) -> 4, issuer.getPublic(),
                    "archive-pin-issuer", clock);
            publisher = new ImmutableSignedPinDocumentPublisher(verifier, store);
        }

        SignedPinDocument signed(ReopenProof proof, long revision, byte marker)
                throws Exception {
            String canonicalKey = CanonicalSignedReceiptTransport.receiptKey(
                    NAMESPACE, Kind.ARCHIVE_DURABLE, proof);
            byte[] digest = new byte[32];
            digest[0] = marker;
            Pin pin = new Pin("v-acceptance-" + revision, "etag-acceptance",
                    32, digest);
            SignedPinDocument unsigned = new SignedPinDocument(
                    Kind.ARCHIVE_DURABLE, "archive-pin-issuer", canonicalKey,
                    revision, NOW - 1000, NOW + 60000, pin, new byte[0]);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(issuer.getPrivate());
            signature.update(SignedReceiptPinIndex.signingPayload(unsigned));
            return new SignedPinDocument(Kind.ARCHIVE_DURABLE, "archive-pin-issuer",
                    canonicalKey, revision, NOW - 1000, NOW + 60000,
                    pin, signature.sign());
        }
    }
}
