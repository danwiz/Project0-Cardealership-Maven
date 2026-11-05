package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.service.Permission;

class ApplicationCommandTest {

    @Test
    @Tag("TARGET-BEHAVIOR")
    void registrationAndAuthenticationCommandsShareTheApplicationContext() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        RegisterAccountCommand register = new RegisterAccountCommand(context);
        AuthenticateAccountCommand authenticate = new AuthenticateAccountCommand(context);

        User registered = com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(context), AccountRole.EMPLOYEE, "employee", "secret");
        User credentials = credentials("employee", "secret");
        AuthenticateAccountCommand.Result result = authenticate.execute(
                AccountRole.EMPLOYEE, credentials, Permission.MANAGE_INVENTORY);

        assertEquals(AccountRole.EMPLOYEE, registered.getRole());
        assertTrue(result.isAuthenticated());
        assertEquals("employee", result.getAccount().get().getUsername());
        assertEquals("employee", context.getCurrentAccount().getUsername());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void authenticationCommandClearsSessionWhenRoleLacksPermission() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        RegisterAccountCommand register = new RegisterAccountCommand(context);
        AuthenticateAccountCommand authenticate = new AuthenticateAccountCommand(context);
        register.execute(AccountRole.CUSTOMER, "customer", "secret");

        AuthenticateAccountCommand.Result result = authenticate.execute(
                AccountRole.CUSTOMER, credentials("customer", "secret"),
                Permission.MANAGE_INVENTORY);

        assertEquals(AuthenticateAccountCommand.Status.ACCESS_DENIED, result.getStatus());
        assertFalse(result.getAccount().isPresent());
        assertEquals(null, context.getCurrentAccount());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void inventoryAndPurchaseReviewCommandsCoordinateDomainAggregates() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        RegisterAccountCommand register = new RegisterAccountCommand(context);
        ManageInventoryCommand inventory = new ManageInventoryCommand(context);
        RequestPurchaseCommand request = new RequestPurchaseCommand(context);
        ReviewPurchaseRequestCommand review = new ReviewPurchaseRequestCommand(context);

        User employee = com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(context), AccountRole.EMPLOYEE, "employee", "secret");
        User customer = register.execute(AccountRole.CUSTOMER, "customer", "secret");
        employee = com.revature.support.AuthenticatedAccounts.login(context, AccountRole.EMPLOYEE, "employee", "secret");
        customer = com.revature.support.AuthenticatedAccounts.login(context, AccountRole.CUSTOMER, "customer", "secret");
        inventory.add(employee, "Honda", "Accord", 2020, 20000, 1);
        request.execute(customer, 0);

        int acceptedPrice = review.approve(employee, 0, 20, false);

        assertEquals(20000, acceptedPrice);
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                context.getInventory().getPurchaseRequests().get(0).getStatus());
        assertEquals(0, context.getInventory().getListings().get(0).getStockQuantity());
        assertEquals(1, context.getCustomerLoginService().getOwnedVehicleRecords().size());
        assertEquals(1000, context.getCustomerLoginService().getOwnedVehicle(0)
                .getPaymentPlan().getMonthlyPayment());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void inventoryCommandRejectsUnauthorizedActors() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        User customer = new RegisterAccountCommand(context)
                .execute(AccountRole.CUSTOMER, "customer", "secret");
        ManageInventoryCommand inventory = new ManageInventoryCommand(context);

        assertThrows(SecurityException.class,
                () -> inventory.add(customer, "Honda", "Accord", 2020, 20000, 1));
        assertEquals(0, context.getInventory().getListingCount());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void defaultInventoryCommandIsIdempotent() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        SeedDefaultInventoryCommand seed = new SeedDefaultInventoryCommand(context);

        assertTrue(seed.execute());
        assertFalse(seed.execute());
        assertEquals(4, context.getInventory().getListingCount());
        assertNotNull(context.getInventory().getListings().get(0).getCar());
    }

    private static User credentials(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }
}