package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/**
 * Disposable PostgreSQL 16 proof: cut the real client TCP transport after the
 * backend has sent the COMMIT ReadyForQuery, but before the client sees it.
 * Does not simulate failover, replication or the SQL permit/receipt service.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlCommitAckTransportLossTest {
    @Test
    void actualDroppedCommitReplyIsUnknownToClientButReconcilesAsSingleDurablePurchase()
            throws Exception {
        String base = System.getenv("DEALERSHIP_PG_TEST_URL");
        String user = System.getenv("DEALERSHIP_PG_TEST_USER");
        String pass = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(user)
                        && "ci-only-postgres-fixture".equals(pass),
                "requires only the isolated PostgreSQL 16 GitHub Actions service");

        String schema = "ackloss_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDatabase owner = new JdbcDatabase(base, user, pass);
        try (Connection c = owner.openConnection(); Statement statement = c.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String directUrl = base + "?currentSchema=" + schema;
        JdbcDatabase direct = new JdbcDatabase(directUrl, user, pass);
        direct.migrate();
        ConfiguredDealershipApplication app = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(directUrl, user, pass));
        TestAccounts.seed(new JdbcUserAccountRepository(direct),
                AccountRole.EMPLOYEE, "reviewer", "reviewer-password");
        app.getContext().getCustomerLoginService().registerUser("buyer", "buyer-password");
        User reviewer = AuthenticatedAccounts.login(
                app, AccountRole.EMPLOYEE, "reviewer", "reviewer-password");
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(direct);
        inventory.addListing("Honda", "Accord", 2024, 23000, 2);
        inventory.requestPurchase("buyer", 0);

        // CI-only TCP proxy deliberately drops the actual network response
        // carrying COMMIT completion; it does not fabricate a JDBC exception.
        try (CommitAckDropProxy proxy = new CommitAckDropProxy()) {
            String proxiedUrl = "jdbc:postgresql://127.0.0.1:" + proxy.port()
                    + "/dealership_ci?currentSchema=" + schema
                    + "&sslmode=disable&preferQueryMode=simple"
                    + "&connectTimeout=5&socketTimeout=8";
            JdbcDatabase proxied = new JdbcDatabase(proxiedUrl, user, pass);
            JdbcPurchaseApprovalService approve = new JdbcPurchaseApprovalService(
                    proxied, app.getContext().getAuthorizationService());

            IllegalStateException uncertain = assertThrows(IllegalStateException.class,
                    () -> approve.approve(reviewer, 0, 23, false));
            assertTrue(proxy.awaitDroppedCommit(), "proxy must observe server COMMIT completion");
            assertTrue(uncertain.getMessage().contains("uncertain"),
                    "lost COMMIT reply must not be mistaken for certain rollback");
        }

        // Independent connection and durable database rows, not the failed
        // connection's local cache or guessed transaction outcome.
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                inventory.purchaseRequests().get(0).getStatus());
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
        assertEquals(1, count(direct, "owned_vehicles"));
        assertEquals(1, count(direct, "payment_plans"));
        assertEquals(23000, new JdbcPurchaseApprovalService(
                direct, app.getContext().getAuthorizationService())
                .approve(reviewer, 0, 23, false),
                "explicit, independently reconciled replay must be idempotent");
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
        assertEquals(1, count(direct, "owned_vehicles"));
    }


    @Test
    void actualDroppedOutboundCommitNeverCreatesAnUnconfirmedPurchase()
            throws Exception {
        String base = System.getenv("DEALERSHIP_PG_TEST_URL");
        String user = System.getenv("DEALERSHIP_PG_TEST_USER");
        String pass = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(user)
                        && "ci-only-postgres-fixture".equals(pass),
                "requires only the isolated PostgreSQL 16 GitHub Actions service");

        String schema = "precommit_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDatabase owner = new JdbcDatabase(base, user, pass);
        try (Connection c = owner.openConnection(); Statement statement = c.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String directUrl = base + "?currentSchema=" + schema;
        JdbcDatabase direct = new JdbcDatabase(directUrl, user, pass);
        direct.migrate();
        ConfiguredDealershipApplication app = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(directUrl, user, pass));
        TestAccounts.seed(new JdbcUserAccountRepository(direct),
                AccountRole.EMPLOYEE, "reviewer", "reviewer-password");
        app.getContext().getCustomerLoginService().registerUser("buyer", "buyer-password");
        User reviewer = AuthenticatedAccounts.login(
                app, AccountRole.EMPLOYEE, "reviewer", "reviewer-password");
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(direct);
        inventory.addListing("Toyota", "Corolla", 2023, 19000, 2);
        inventory.requestPurchase("buyer", 0);

        // Cut actual TCP *before* the COMMIT wire frame reaches the backend.
        // An error reply does not prove rollback; only independent DB readback
        // under this isolated, no-other-writer fixture establishes unchanged state.
        try (CommitAckDropProxy proxy = new CommitAckDropProxy(true)) {
            String proxiedUrl = "jdbc:postgresql://127.0.0.1:" + proxy.port()
                    + "/dealership_ci?currentSchema=" + schema
                    + "&sslmode=disable&preferQueryMode=simple"
                    + "&connectTimeout=5&socketTimeout=8";
            JdbcDatabase proxied = new JdbcDatabase(proxiedUrl, user, pass);
            JdbcPurchaseApprovalService approval = new JdbcPurchaseApprovalService(
                    proxied, app.getContext().getAuthorizationService());

            IllegalStateException uncertain = assertThrows(IllegalStateException.class,
                    () -> approval.approve(reviewer, 0, 12, false));
            assertTrue(proxy.awaitDroppedCommit(),
                    "proxy must prove the outbound COMMIT was actually withheld");
            assertTrue(uncertain.getMessage().contains("uncertain"),
                    "lost transport must be treated as unknown by the caller");
        }

        assertTrue(inventory.purchaseRequests().get(0).isPending(),
                "no COMMIT reached the server; all tentative writes must roll back");
        assertEquals(2, inventory.listings().get(0).getStockQuantity());
        assertEquals(0, count(direct, "owned_vehicles"));
        assertEquals(0, count(direct, "payment_plans"));
        // No automatic or implicit retry. A production NOT_CONFIRMED receipt
        // would require a separate trusted authorization/reconciliation decision.
    }

    private static int count(JdbcDatabase db, String table) throws Exception {
        if (!"owned_vehicles".equals(table) && !"payment_plans".equals(table)) {
            throw new IllegalArgumentException("unexpected count table");
        }
        try (Connection c = db.openConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Minimal, test-only PostgreSQL wire-protocol relay. We deliberately use
     * sslmode=disable + simple-query mode so the COMMIT command and its backend
     * ReadyForQuery acknowledgement are observable as PostgreSQL protocol bytes.
     * A production transport proxy must never inspect database credentials.
     */
    private static final class CommitAckDropProxy implements AutoCloseable {
        private static final byte[] COMMIT = "COMMIT\0".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] COMMIT_READY = new byte[] {'Z', 0, 0, 0, 5, 'I'};
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean firstCutClaimed = new AtomicBoolean();
        private final CountDownLatch droppedCommit = new CountDownLatch(1);
        private final boolean dropBeforeCommit;

        CommitAckDropProxy() throws IOException { this(false); }

        CommitAckDropProxy(boolean dropBeforeCommit) throws IOException {
            this.dropBeforeCommit = dropBeforeCommit;
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> {
                while (running.get()) {
                    try {
                        Socket client = listener.accept();
                        Socket server = new Socket("127.0.0.1", 5432);
                        sockets.add(client);
                        sockets.add(server);
                        AtomicBoolean holdCommitResponse = new AtomicBoolean(false);
                        workers.execute(() -> relay(client, server, holdCommitResponse, true));
                        workers.execute(() -> relay(server, client, holdCommitResponse, false));
                    } catch (IOException unavailable) {
                        if (running.get()) {
                            // The test will fail if it cannot actually observe a dropped COMMIT.
                            running.set(false);
                        }
                    }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        boolean awaitDroppedCommit() throws InterruptedException {
            return droppedCommit.await(5, TimeUnit.SECONDS);
        }

        private void relay(Socket source, Socket destination, AtomicBoolean holding, boolean outbound) {
            try {
                source.setSoTimeout(12000);
                InputStream input = source.getInputStream();
                OutputStream output = destination.getOutputStream();
                byte[] data = new byte[8192];
                byte[] tail = new byte[0];
                int bytes;
                while (running.get() && (bytes = input.read(data)) >= 0) {
                    byte[] window = join(tail, data, bytes);
                    if (outbound) {
                        if (!firstCutClaimed.get() && contains(window, COMMIT)
                                && firstCutClaimed.compareAndSet(false, true)) {
                            if (dropBeforeCommit) {
                                // Do not forward a single byte of the COMMIT
                                // query frame; close both sockets to force
                                // PostgreSQL to abandon its open transaction.
                                droppedCommit.countDown();
                                source.close();
                                destination.close();
                                return;
                            }
                            holding.set(true);
                        }
                        output.write(data, 0, bytes);
                        output.flush();
                    } else if (holding.get()) {
                        // Never forward any COMMIT reply bytes. Only when the
                        // backend sent its idle ReadyForQuery can this be
                        // positively classified as a dropped acknowledgment.
                        if (contains(window, COMMIT_READY)) {
                            droppedCommit.countDown();
                            source.close();
                            destination.close();
                            return;
                        }
                    } else {
                        output.write(data, 0, bytes);
                        output.flush();
                    }
                    tail = Arrays.copyOfRange(window, Math.max(0, window.length - 24), window.length);
                }
            } catch (SocketException expectedOnCut) {
                // The deliberate TCP cut terminates both relay halves.
            } catch (IOException boundedTransportFailure) {
                // A missing COMMIT-ready signal fails awaitDroppedCommit.
            }
        }

        private static byte[] join(byte[] tail, byte[] data, int size) {
            byte[] joined = Arrays.copyOf(tail, tail.length + size);
            System.arraycopy(data, 0, joined, tail.length, size);
            return joined;
        }

        private static boolean contains(byte[] data, byte[] sought) {
            outer: for (int i = 0; i <= data.length - sought.length; i++) {
                for (int j = 0; j < sought.length; j++) {
                    if (data[i + j] != sought[j]) continue outer;
                }
                return true;
            }
            return false;
        }

        @Override public void close() throws IOException {
            running.set(false);
            listener.close();
            synchronized (sockets) {
                for (Socket socket : sockets) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }
            workers.shutdownNow();
            try { workers.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
