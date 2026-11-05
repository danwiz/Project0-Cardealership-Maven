package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;
import com.revature.application.DealershipQueryService.AccountView;
import com.revature.application.DealershipQueryService.InventoryView;
import com.revature.application.DealershipQueryService.OwnershipView;
import com.revature.application.DealershipQueryService.PaymentReportView;
import com.revature.application.DealershipQueryService.PurchaseRequestView;

@Tag("TARGET-BEHAVIOR")
class DealershipQueryServiceTest {

    @Test
    void returnsImmutableCredentialFreeAccountAndInventoryViews() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        context.getCustomerLoginService().registerUser("casey", "secret");
        User customer = com.revature.support.AuthenticatedAccounts.login(context, AccountRole.CUSTOMER, "casey", "secret");
        context.getInventory().registerOffer("Honda", "Civic", 2020, 17000, "yes", 2);
        DealershipQueryService queries = new DealershipQueryService(context);

        AccountView account = queries.account(customer);
        List<InventoryView> inventory = queries.inventory(customer);

        assertEquals("casey", account.getUsername());
        assertEquals(AccountRole.CUSTOMER, account.getRole());
        assertEquals(1, inventory.size());
        assertEquals(0, inventory.get(0).getId());
        assertEquals(2, inventory.get(0).getStockQuantity());
        assertThrows(UnsupportedOperationException.class, inventory::clear);
        assertFalse(Arrays.stream(AccountView.class.getDeclaredFields())
                .map(Field::getName).anyMatch(name -> name.toLowerCase().contains("password")));
    }

    @Test
    void restrictsQueriesAndReturnsPendingOwnershipAndPaymentModels() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        context.getCustomerLoginService().registerUser("buyer", "secret");
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(context), AccountRole.EMPLOYEE, "staff", "secret");
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(context), AccountRole.ADMINISTRATOR, "root", "secret");
        User customer = com.revature.support.AuthenticatedAccounts.login(context, AccountRole.CUSTOMER, "buyer", "secret");
        User employee = com.revature.support.AuthenticatedAccounts.login(context, AccountRole.EMPLOYEE, "staff", "secret");
        User admin = com.revature.support.AuthenticatedAccounts.login(context, AccountRole.ADMINISTRATOR, "root", "secret");
        context.getInventory().registerOffer("Toyota", "Corolla", 2019, 12000, "yes", 1);
        context.getInventory().setpOffer(customer.getUsername(), 0);
        context.getCustomerLoginService().setCarsOwned(new Car("Mazda", "3", 2018), 10000, 20);
        context.getPayments().setpPayment(customer.getUsername(), 1000, 10);
        context.getPayments().makePayment(customer.getUsername(), 100);
        DealershipQueryService queries = new DealershipQueryService(context);

        List<PurchaseRequestView> pending = queries.pendingRequests(employee);
        List<OwnershipView> ownership = queries.ownership(customer);
        PaymentReportView customerPayments = queries.payments(customer);
        PaymentReportView employeePayments = queries.payments(employee);
        PaymentReportView administratorPayments = queries.payments(admin);

        assertEquals(1, pending.size());
        assertEquals(0, pending.get(0).getListingId());
        assertEquals("buyer", pending.get(0).getCustomerName());
        assertEquals(1, ownership.size());
        assertEquals(900, customerPayments.getBalance());
        assertEquals(1, customerPayments.getTransactions().size());
        assertEquals(customerPayments.getTransactions().size(), employeePayments.getTransactions().size());
        assertEquals(customerPayments.getTransactions().size(), administratorPayments.getTransactions().size());
        assertThrows(SecurityException.class, () -> queries.pendingRequests(customer));
        assertThrows(SecurityException.class, () -> queries.ownership(employee));
        assertTrue(queries.inventory(employee).get(0).isAvailable());
    }
}
