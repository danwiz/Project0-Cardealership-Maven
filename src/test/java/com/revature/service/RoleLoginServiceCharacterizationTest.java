package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.Car;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.User;

class RoleLoginServiceCharacterizationTest {

    private PrintStream originalOut;

    @BeforeEach
    void rememberOutput() {
        originalOut = System.out;
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void customerAuthenticationRejectsUnregisteredUser() {
        CustomerLoginService service = new CustomerLoginService();
        assertFalse(service.authenticateUser(user("unknown", "wrong")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void employeeAuthenticationRejectsUnregisteredUser() {
        EmployeeLoginService service = new EmployeeLoginService();
        assertFalse(service.authenticateUser(user("unknown", "wrong")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void administratorAuthenticationRejectsUnregisteredUser() {
        AdminLoginService service = new AdminLoginService();
        assertFalse(service.authenticateUser(user("unknown", "wrong")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void roleServicesAuthenticateRegisteredCredentials() {
        CustomerLoginService customerService = new CustomerLoginService();
        customerService.registerUser("customer", "secret");
        assertTrue(customerService.authenticateUser(user("customer", "secret")));

        UserAccountRepository employeeRepo = new InMemoryUserAccountRepository();
        com.revature.support.TestAccounts.seed(employeeRepo, com.revature.cardealer.AccountRole.EMPLOYEE, "employee", "secret");
        EmployeeLoginService employeeService = new EmployeeLoginService(employeeRepo);
        assertTrue(employeeService.authenticateUser(user("employee", "secret")));

        UserAccountRepository adminRepo = new InMemoryUserAccountRepository();
        com.revature.support.TestAccounts.seed(adminRepo, com.revature.cardealer.AccountRole.ADMINISTRATOR, "admin", "secret");
        AdminLoginService adminService = new AdminLoginService(adminRepo);
        assertTrue(adminService.authenticateUser(user("admin", "secret")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void customerAuthenticationDoesNotPrintSubmittedUsername() {
        CustomerLoginService service = new CustomerLoginService();
        service.registerUser("dane", "secret");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));

        service.authenticateUser(user("dane", "secret"));

        assertFalse(output.toString().contains("dane"));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void customerOwnershipIsNotLimitedToTwentyVehicles() {
        CustomerLoginService service = new CustomerLoginService();
        for (int i = 0; i < 25; i++) {
            service.setCarsOwned(new Car("Make" + i, "Model" + i, 2000 + i), 10000 + i, 12);
        }

        assertEquals(25, service.getOwnedVehicleRecords().size());
        assertEquals("Make24", service.getOwnedVehicle(24).getVehicle().getCarMake());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void viewingOwnedCarsPrintsOnlyExistingRecords() {
        CustomerLoginService service = new CustomerLoginService();
        service.setCarsOwned(new Car("Toyota", "Corolla", 2010), 12000, 12);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));

        service.getCarsOwned();

        String rendered = output.toString();
        assertTrue(rendered.contains("Toyota"));
        assertTrue(rendered.contains("Monthly Cost: 1000"));
        assertFalse(rendered.contains("null"));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void paymentIsAppliedToTheSelectedOwnedVehicle() {
        CustomerLoginService service = new CustomerLoginService();
        service.setCarsOwned(new Car("Toyota", "Corolla", 2010), 12000, 12);
        service.setCarsOwned(new Car("Honda", "Civic", 2018), 24000, 24);

        service.recordPayment(0, 1000);

        assertEquals(1000, service.getOwnedVehicle(0).getPaymentPlan().getAmountPaid());
        assertEquals(11000, service.getOwnedVehicle(0).getPaymentPlan().getRemainingBalance());
        assertEquals(0, service.getOwnedVehicle(1).getPaymentPlan().getAmountPaid());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void paymentCannotExceedRemainingBalance() {
        CustomerLoginService service = new CustomerLoginService();
        service.setCarsOwned(new Car("Toyota", "Corolla", 2010), 12000, 12);

        assertThrows(IllegalArgumentException.class, () -> service.recordPayment(0, 12001));
        assertEquals(12000, service.getOwnedVehicle(0).getPaymentPlan().getRemainingBalance());
    }

    @Test
    void ownedVehicleRecordViewCannotBeModified() {
        CustomerLoginService service = new CustomerLoginService();
        service.setCarsOwned(new Car("Toyota", "Corolla", 2010), 12000, 12);
        List<OwnedVehicle> records = service.getOwnedVehicleRecords();

        assertThrows(UnsupportedOperationException.class, records::clear);
    }

    @Test
    void administratorDeleteAllUsersOnlyEmitsWarningText() {
        AdminLoginService service = new AdminLoginService();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));

        service.deleteAllUsers();

        assertTrue(output.toString().contains("You better be sure"));
    }

    private static User user(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }
}