package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;

class ConfiguredConsoleRuntimeTest {

    @Test
    void jdbcConsoleWorkflowPersistsAcrossConfiguredApplicationRestart() {
        String url = "jdbc:h2:mem:configured_console_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        InfrastructureConfiguration configuration = InfrastructureConfiguration.jdbc(url);
        ConfiguredDealershipApplication first = DealershipCompositionRoot.create(configuration);

        execute(first, "1", "1", "alice", "StrongPass1!");
        com.revature.support.TestAccounts.seed(
                com.revature.support.TestAccounts.trustedRepository(first), AccountRole.EMPLOYEE, "employee", "StrongPass2!");
        execute(first, "3", "employee", "StrongPass2!", "1", "1",
                "Honda,Accord,2020,10000,1");
        execute(first, "2", "alice", "StrongPass1!", "1", "0");
        execute(first, "3", "employee", "StrongPass2!", "2", "0", "12", "no");

        assertEquals(2, first.getAccounts().findAll().size());
        assertEquals(1, first.getInventory().listings().size());
        assertEquals(1, first.ownershipFor("alice").findAll().size());

        ConfiguredDealershipApplication restarted = DealershipCompositionRoot.create(configuration);
        assertEquals(2, restarted.getAccounts().findAll().size());
        assertEquals(1, restarted.getInventory().listings().size());
        assertEquals(1, restarted.ownershipFor("alice").findAll().size());
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                restarted.getInventory().purchaseRequests().get(0).getStatus());
    }

    @Test
    void jdbcAdminMenuReportsTransactionalPersistenceInsteadOfFileSerialization() {
        String url = "jdbc:h2:mem:configured_admin_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(url));
        com.revature.support.TestAccounts.seed(
                com.revature.support.TestAccounts.trustedRepository(application), AccountRole.ADMINISTRATOR, "admin", "StrongPass3!");
        CapturingIO io = execute(application, "5", "admin", "StrongPass3!");
        assertTrue(io.output.stream().anyMatch(value -> value.contains("JDBC persistence is active")));
    }

    private static CapturingIO execute(ConfiguredDealershipApplication application,
            String mainOption, String... input) {
        CapturingIO io = new CapturingIO(input);
        new ConsoleCommandHandler(application, io).handleMainCommand(mainOption);
        return io;
    }

    private static final class CapturingIO implements ConsoleIO {
        private final Deque<String> input;
        private final List<String> output = new ArrayList<>();

        private CapturingIO(String... input) {
            this.input = new ArrayDeque<>(Arrays.asList(input));
        }

        @Override public String readLine() {
            if (input.isEmpty()) throw new IllegalStateException("no configured console input remains");
            return input.removeFirst();
        }

        @Override public void writeLine(String value) { output.add(value); }
    }
}