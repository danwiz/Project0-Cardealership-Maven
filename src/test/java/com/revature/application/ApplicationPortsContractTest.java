package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.User;
import com.revature.service.Permission;

@Tag("TARGET-BEHAVIOR")
class ApplicationPortsContractTest {

    @Test
    void commandsUseInjectedPortsWithoutApplicationContext() {
        FakePorts ports = new FakePorts();
        RegisterAccountCommand registration = new RegisterAccountCommand(ports);
        AuthenticateAccountCommand authentication = new AuthenticateAccountCommand(ports, ports);
        ManageInventoryCommand inventory = new ManageInventoryCommand(ports, ports);
        RequestPurchaseCommand purchase = new RequestPurchaseCommand(ports, ports);
        SeedDefaultInventoryCommand seed = new SeedDefaultInventoryCommand(ports);

        User customer = registration.execute(AccountRole.CUSTOMER, "buyer", "secret");
        assertThrows(SecurityException.class,
                () -> registration.execute(AccountRole.ADMINISTRATOR, "forged", "secret"));
        User employee = ports.register(AccountRole.EMPLOYEE, "staff", "secret");

        assertTrue(authentication.execute(AccountRole.CUSTOMER, login("buyer", "secret"),
                Permission.VIEW_INVENTORY).isAuthenticated());
        assertTrue(seed.execute());
        assertEquals(4, ports.listingCount());

        inventory.add(employee, "Mazda", "3", 2021, 19000, 1);
        purchase.execute(customer, 4);

        assertEquals(5, ports.listingCount());
        assertEquals(1, ports.purchaseRequests().size());
        assertEquals("buyer", ports.purchaseRequests().get(0).getCustomerName());
    }

    private static User login(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }

    private static final class FakePorts implements ApplicationPorts.Identity,
            ApplicationPorts.Authorization, ApplicationPorts.Inventory,
            ApplicationPorts.Ownership {
        private final List<User> users = new ArrayList<>();
        private final List<Car> listings = new ArrayList<>();
        private final List<PurchaseRequest> requests = new ArrayList<>();
        private User current;

        @Override public User register(AccountRole role, String username, String password) {
            User user = new User();
            user.setUsername(username);
            user.setPassword(password);
            user.setRole(role);
            users.add(user);
            return user;
        }
        @Override public Optional<User> authenticate(AccountRole role, User credentials) {
            return users.stream().filter(user -> user.getRole() == role
                    && user.getUsername().equals(credentials.getUsername())
                    && user.getPassword().equals(credentials.getPassword())).findFirst();
        }
        @Override public void setCurrentAccount(User account) { current = account; }
        @Override public void rememberRegisteredAccount(User account) { }
        @Override public boolean isAuthorized(User account, Permission permission) { return account != null; }
        @Override public void requireAuthorized(User account, Permission permission) {
            if (!isAuthorized(account, permission)) throw new SecurityException("denied");
        }
        @Override public int listingCount() { return listings.size(); }
        @Override public void addListing(String make, String model, int year, int price, int stockQuantity) {
            listings.add(new Car(make, model, year));
        }
        @Override public void removeListing(int listingId) { listings.remove(listingId); }
        @Override public void requestPurchase(String customerName, int listingId) {
            requests.add(new PurchaseRequest(requests.size(), listingId, customerName));
        }
        @Override public List<PurchaseRequest> purchaseRequests() { return requests; }
        @Override public int decideRequest(int requestId, int paymentMonths, boolean accepted) { return 10000; }
        @Override public void rejectAllPendingRequests() { }
        @Override public Car carForListing(int listingId) { return listings.get(listingId); }
        @Override public void addOwnedVehicle(Car car, int purchasePrice, int paymentMonths) { }
    }
}