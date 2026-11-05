package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.User;

class RecordPaymentCommandTest {

    @Test
    void jdbcCustomerPaymentUpdatesPlanAndLedgerDurably() {
        String url = "jdbc:h2:mem:record_payment_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        InfrastructureConfiguration configuration = InfrastructureConfiguration.jdbc(url);
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(configuration);
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.CUSTOMER, "alice", "TestPass123!");
        application.ownershipFor("alice").add(new Car("Honda", "Accord", 2020), 1200, 12);
        long ownershipId = application.ownershipFor("alice").findAll().get(0).getOwnershipId();

        PaymentTransaction transaction = new RecordPaymentCommand(application)
                .execute(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.CUSTOMER, "alice", "TestPass123!"), "alice", ownershipId, 200);

        assertEquals(ownershipId, transaction.getOwnershipId().getAsLong());
        assertEquals(200, transaction.getAmount());
        assertEquals(1000, transaction.getRemainingBalance());
        assertEquals(200, application.ownershipFor("alice").findById(ownershipId).orElseThrow(AssertionError::new)
                .getPaymentPlan().getAmountPaid());
        assertEquals(1, application.paymentsFor("alice").findAll().size());

        ConfiguredDealershipApplication restarted = DealershipCompositionRoot.create(configuration);
        assertEquals(ownershipId, restarted.ownershipFor("alice").findAll().get(0).getOwnershipId());
        assertEquals(200, restarted.ownershipFor("alice").findById(ownershipId).orElseThrow(AssertionError::new)
                .getPaymentPlan().getAmountPaid());
        assertEquals(1000, restarted.paymentsFor("alice").ledger().getBalance());
    }

    @Test
    void employeeCanRecordCustomerPaymentButCustomerCannotPayAnotherAccount() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.inMemory());
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.CUSTOMER, "alice", "TestPass123!");
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.EMPLOYEE, "employee", "TestPass123!");
        application.ownershipFor("alice").add(new Car("Toyota", "Corolla", 2019), 600, 6);
        long ownershipId = application.ownershipFor("alice").findAll().get(0).getOwnershipId();
        RecordPaymentCommand command = new RecordPaymentCommand(application);

        command.execute(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.EMPLOYEE, "employee", "TestPass123!"), "alice", ownershipId, 100);
        assertEquals(100, application.paymentsFor("alice").ledger().getTotalPaid());
        assertThrows(SecurityException.class,
                () -> command.execute(user("bob", AccountRole.CUSTOMER), "alice", ownershipId, 100));
    }

    @Test
    void jdbcOverpaymentRollsBackPlanAndLedgerTogether() {
        String url = "jdbc:h2:mem:payment_rollback_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(url));
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.CUSTOMER, "alice", "TestPass123!");
        application.ownershipFor("alice").add(new Car("BMW", "4Series", 2018), 500, 5);
        long ownershipId = application.ownershipFor("alice").findAll().get(0).getOwnershipId();

        assertThrows(IllegalArgumentException.class,
                () -> new RecordPaymentCommand(application).execute(
                        com.revature.support.AuthenticatedAccounts.login(application, AccountRole.CUSTOMER, "alice", "TestPass123!"), "alice", ownershipId, 501));
        assertEquals(0, application.ownershipFor("alice").findById(ownershipId).orElseThrow(AssertionError::new)
                .getPaymentPlan().getAmountPaid());
        assertEquals(0, application.paymentsFor("alice").findAll().size());
    }

    private static User user(String username, AccountRole role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("encoded-credential");
        user.setRole(role);
        return user;
    }
}
