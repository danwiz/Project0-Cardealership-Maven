package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ApplicationContextCommandHandlerTest {

    @Test
    @Tag("TARGET-BEHAVIOR")
    void separateApplicationContextsDoNotShareAccountsOrInventory() {
        DealershipApplicationContext first = new DealershipApplicationContext();
        DealershipApplicationContext second = new DealershipApplicationContext();

        first.getCustomerLoginService().registerUser("first-user", "secret");
        first.getInventory().registerOffer("Honda", "Civic", 2020, 10000, "yes", 1);

        assertEquals(1, first.getAccountRepository().findAll().size());
        assertEquals(0, second.getAccountRepository().findAll().size());
        assertEquals(1, first.getInventory().getListingCount());
        assertEquals(0, second.getInventory().getListingCount());
        assertNotSame(first.getAccountRepository(), second.getAccountRepository());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void commandHandlerUsesInjectedInputAndOutput() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        FakeConsoleIO io = new FakeConsoleIO("1", "customer-name", "secret");
        ConsoleCommandHandler handler = new ConsoleCommandHandler(context, io);

        handler.handleMainCommand("1");

        assertEquals(1, context.getAccountRepository().findAll().size());
        assertTrue(io.output().contains("Customer registered"));
    }


    @Test
    @Tag("SECURITY")
    void publicMenuRejectsEmployeeAndAdminSignupBeforeCredentials() {
        for (String option : new String[] {"2", "3"}) {
            DealershipApplicationContext context = new DealershipApplicationContext();
            FakeConsoleIO io = new FakeConsoleIO(option);
            new ConsoleCommandHandler(context, io).handleMainCommand("1");
            assertEquals(0, context.getAccountRepository().findAll().size());
            assertTrue(io.output().contains("access denied"));
        }
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void failedAuthenticationClearsOnlyTheContextSession() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        User prior = new User();
        prior.setUsername("prior");
        context.setCurrentAccount(prior);
        FakeConsoleIO io = new FakeConsoleIO("unknown", "wrong");

        new ConsoleCommandHandler(context, io).handleMainCommand("2");

        assertNull(context.getCurrentAccount());
        assertTrue(io.output().contains("failure"));
    }

    private static final class FakeConsoleIO implements ConsoleIO {
        private final Queue<String> input = new ArrayDeque<>();
        private final List<String> output = new ArrayList<>();

        private FakeConsoleIO(String... values) {
            for (String value : values) input.add(value);
        }

        @Override
        public String readLine() {
            return input.remove();
        }

        @Override
        public void writeLine(String value) {
            output.add(value);
        }

        private String output() {
            return String.join("\n", output);
        }
    }
}
