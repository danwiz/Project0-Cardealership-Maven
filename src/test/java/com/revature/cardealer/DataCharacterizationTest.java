package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.service.CustomerLoginService;
import com.revature.service.EmployeeLoginService;
import com.revature.service.InMemoryUserAccountRepository;
import com.revature.service.UserAccountRepository;

class DataCharacterizationTest {

    @Test
    @Tag("TARGET-BEHAVIOR")
    void parameterizedConstructorAssignsRuntimeObjectsAndBuildsSnapshot() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        CustomerLoginService customerService = new CustomerLoginService(repository);
        EmployeeLoginService employeeService = new EmployeeLoginService(repository);
        User customer = customerService.registerUser("customer", "secret");
        User employee = com.revature.support.TestAccounts.seed(repository, AccountRole.EMPLOYEE, "employee", "secret");
        Offer inventory = new Offer();
        inventory.registerOffer("Honda", "Accord", 2018, 15_000, "yes", 2);
        inventory.setpOffer("customer", 0);
        Payments payments = new Payments();
        payments.setAmtOwed(15_000);
        payments.makePayment("customer", 500);
        customerService.setCarsOwned(new Car("Honda", "Accord", 2018), 15_000, 12);

        Data data = new Data(customerService, employeeService, employee, customer, inventory, payments);
        ApplicationStateSnapshot snapshot = data.getSnapshot();

        assertSame(customerService, data.getCustomerLoginService());
        assertSame(employeeService, data.getEmployeeLoginService());
        assertSame(customer, data.getCustomer());
        assertSame(employee, data.getEmployee());
        assertSame(inventory, data.getOffer());
        assertSame(payments, data.getPayments());
        assertEquals(2, snapshot.getAccounts().size());
        assertEquals(1, snapshot.getInventoryListings().size());
        assertEquals(1, snapshot.getPurchaseRequests().size());
        assertEquals(1, snapshot.getOwnedVehicles().size());
        assertEquals(1, snapshot.getPaymentTransactions().size());
    }
}
