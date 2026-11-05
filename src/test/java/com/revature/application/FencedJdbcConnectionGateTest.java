package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.FencedJdbcConnectionGate;
import com.revature.repository.jdbc.JdbcDatabase;

/** Disposable H2 sessions and fakes, not a production connection pool. */
class FencedJdbcConnectionGateTest {

    @Test
    void startsClosedAndOnlyReopensWithStrictlyNewAttestedGeneration() throws Exception {
        JdbcDatabase db = database();
        FencedJdbcConnectionGate gate = new FencedJdbcConnectionGate(
                db::openConnection, generation -> generation == 1 || generation == 2);
        assertDenied(() -> gate.withConnection(c -> true));
        gate.activate(1);
        assertEquals(Boolean.TRUE, gate.withConnection(c -> !c.isClosed()));
        assertDenied(() -> gate.activate(1));
        gate.pauseAndDrain(1);
        assertDenied(() -> gate.withConnection(c -> true));
        assertDenied(() -> gate.activate(1));
        gate.activate(2);
        assertEquals(Boolean.TRUE, gate.withConnection(c -> !c.isClosed()));
    }

    @Test
    void drainClosesActiveSessionFencesItsResultAndBlocksPrematureActivation()
            throws Exception {
        JdbcDatabase db = database();
        FencedJdbcConnectionGate gate = new FencedJdbcConnectionGate(
                db::openConnection, generation -> true);
        gate.activate(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Connection> observed = new AtomicReference<>();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> pending = workers.submit(() -> gate.withConnection(c -> {
                observed.set(c);
                entered.countDown();
                try {
                    resume.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("test interrupted", interrupted);
                }
                return true;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            gate.pauseAndDrain(1);
            assertTrue(observed.get().isClosed());
            assertDenied(() -> gate.activate(2));
            resume.countDown();
            assertFutureDenied(pending);
            gate.activate(2);
            assertEquals(Boolean.TRUE, gate.withConnection(c -> !c.isClosed()));
        } finally {
            resume.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void inFlightOpenIsRejectedAfterRotationEvenIfNewGenerationIsActive()
            throws Exception {
        JdbcDatabase db = database();
        CountDownLatch openerEntered = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        AtomicReference<Connection> opened = new AtomicReference<>();
        AtomicBoolean workInvoked = new AtomicBoolean(false);
        FencedJdbcConnectionGate gate = new FencedJdbcConnectionGate(() -> {
            openerEntered.countDown();
            try {
                allowOpen.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("test interrupted", interrupted);
            }
            Connection c = db.openConnection();
            opened.set(c);
            return c;
        }, generation -> true);
        gate.activate(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> pending = workers.submit(() -> gate.withConnection(c -> {
                workInvoked.set(true);
                return true;
            }));
            assertTrue(openerEntered.await(10, TimeUnit.SECONDS));
            gate.pauseAndDrain(1);
            gate.activate(2);
            allowOpen.countDown();
            assertFutureDenied(pending);
            assertFalse(workInvoked.get());
            assertTrue(opened.get().isClosed());
        } finally {
            allowOpen.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void failedOrUnavailableAttestationLeavesGateClosed() throws Exception {
        JdbcDatabase db = database();
        AtomicBoolean ready = new AtomicBoolean(false);
        FencedJdbcConnectionGate gate = new FencedJdbcConnectionGate(
                db::openConnection, generation -> {
                    if (generation == 3) throw new IllegalStateException("provider-secret");
                    return ready.get();
                });
        assertDenied(() -> gate.activate(1));
        assertDenied(() -> gate.withConnection(c -> true));
        ready.set(true);
        gate.activate(1);
        gate.pauseAndDrain(1);
        ready.set(false);
        assertDenied(() -> gate.activate(2));
        assertDenied(() -> gate.activate(3));
        assertDenied(() -> gate.withConnection(c -> true));
        ready.set(true);
        gate.activate(4);
        assertEquals(Boolean.TRUE, gate.withConnection(c -> true));
    }

    @Test
    void connectionCloseFailurePermanentlyFencesThisInstance() throws Exception {
        Connection broken = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        throw new SQLException("sensitive-driver-diagnostic");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        FencedJdbcConnectionGate gate = new FencedJdbcConnectionGate(
                () -> broken, generation -> true);
        gate.activate(1);
        assertDenied(() -> gate.withConnection(c -> 1));
        assertDenied(() -> gate.pauseAndDrain(1));
        assertDenied(() -> gate.activate(2));
        assertDenied(() -> gate.withConnection(c -> 1));
    }

    @Test
    void callbackSQLExceptionDoesNotLeakAnOpenSession() throws Exception {
        JdbcDatabase db = database();
        FencedJdbcConnectionGate gate = new FencedJdbcConnectionGate(
                db::openConnection, generation -> true);
        gate.activate(1);
        AtomicReference<Connection> observed = new AtomicReference<>();
        assertThrows(SQLException.class, () -> gate.withConnection(c -> {
            observed.set(c);
            throw new SQLException("test failed query");
        }));
        assertTrue(observed.get().isClosed());
        assertEquals(Boolean.TRUE, gate.withConnection(c -> true));
    }

    private static JdbcDatabase database() {
        return JdbcDatabase.inMemory("drain_gate_" +
                UUID.randomUUID().toString().replace("-", ""));
    }

    private static void assertDenied(CheckedAction action) {
        SecurityException denied = assertThrows(SecurityException.class, action::run);
        assertEquals("writer connection temporarily unavailable", denied.getMessage());
    }

    private static void assertFutureDenied(Future<?> future) throws Exception {
        ExecutionException rejected = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(rejected.getCause() instanceof SecurityException);
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
