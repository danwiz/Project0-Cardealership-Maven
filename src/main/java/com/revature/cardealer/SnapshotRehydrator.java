package com.revature.cardealer;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.revature.service.AdminLoginService;
import com.revature.service.CustomerLoginService;
import com.revature.service.EmployeeLoginService;
import com.revature.service.InMemoryUserAccountRepository;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;

/** Validates a complete snapshot before constructing a replacement runtime graph. */
public final class SnapshotRehydrator {

    public RehydratedApplicationState rehydrate(ApplicationStateSnapshot snapshot) {
        validate(snapshot);

        UserAccountRepository repository = new InMemoryUserAccountRepository();
        for (User account : snapshot.getAccounts()) {
            repository.save(account);
        }

        UserLoginService users = new UserLoginService(repository);
        CustomerLoginService customers = new CustomerLoginService(repository);
        customers.restoreOwnedVehicles(snapshot.getOwnedVehicles());
        EmployeeLoginService employees = new EmployeeLoginService(repository);
        AdminLoginService administrators = new AdminLoginService(repository);
        Offer inventory = Offer.restore(snapshot.getInventoryListings(), snapshot.getPurchaseRequests());
        Payments payments = Payments.restore(snapshot.getPaymentTransactions());

        return new RehydratedApplicationState(repository, users, customers, employees,
                administrators, inventory, payments);
    }

    private void validate(ApplicationStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.getVersion() != ApplicationStateSnapshot.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported snapshot version: " + snapshot.getVersion());
        }
        validateAccounts(snapshot.getAccounts());
        validateListings(snapshot.getInventoryListings());
        validateRequests(snapshot.getPurchaseRequests(), snapshot.getInventoryListings().size());
        validateOwnership(snapshot.getOwnedVehicles());
        validateTransactions(snapshot.getPaymentTransactions());
    }

    private void validateAccounts(List<User> accounts) {
        Set<String> usernames = new HashSet<>();
        for (User account : accounts) {
            if (account == null || blank(account.getUsername()) || blank(account.getPassword())
                    || account.getRole() == null) {
                throw new IllegalArgumentException("snapshot contains an invalid account");
            }
            if (!usernames.add(account.getUsername())) {
                throw new IllegalArgumentException("snapshot contains duplicate username: " + account.getUsername());
            }
        }
    }

    private void validateListings(List<InventoryListing> listings) {
        for (int i = 0; i < listings.size(); i++) {
            InventoryListing listing = listings.get(i);
            if (listing == null || listing.getId() != i || listing.getCar() == null
                    || listing.getPrice() < 0 || listing.getStockQuantity() < 0) {
                throw new IllegalArgumentException("snapshot contains an invalid inventory listing at index " + i);
            }
        }
    }

    private void validateRequests(List<PurchaseRequest> requests, int listingCount) {
        for (int i = 0; i < requests.size(); i++) {
            PurchaseRequest request = requests.get(i);
            if (request == null || request.getId() != i || blank(request.getCustomerName())
                    || request.getListingId() < 0 || request.getListingId() >= listingCount
                    || request.getStatus() == null) {
                throw new IllegalArgumentException("snapshot contains an invalid purchase request at index " + i);
            }
            if (request.getStatus() == PurchaseRequestStatus.ACCEPTED
                    && (request.getPaymentMonths() <= 0 || request.getMonthlyPayment() < 0)) {
                throw new IllegalArgumentException("accepted request has invalid payment terms at index " + i);
            }
        }
    }

    private void validateOwnership(List<OwnedVehicle> ownedVehicles) {
        for (OwnedVehicle owned : ownedVehicles) {
            if (owned == null || owned.getVehicle() == null || owned.getPaymentPlan() == null
                    || owned.getPaymentPlan().getRemainingBalance() < 0) {
                throw new IllegalArgumentException("snapshot contains an invalid ownership record");
            }
        }
    }

    private void validateTransactions(List<PaymentTransaction> transactions) {
        Set<String> ids = new HashSet<>();
        int priorTotal = 0;
        for (PaymentTransaction transaction : transactions) {
            if (transaction == null || blank(transaction.getTransactionId())
                    || blank(transaction.getCustomerName()) || transaction.getAmount() <= 0
                    || transaction.getTotalPaid() < priorTotal + transaction.getAmount()
                    || transaction.getRemainingBalance() < 0
                    || transaction.getRecordedAt() == null
                    || !ids.add(transaction.getTransactionId())) {
                throw new IllegalArgumentException("snapshot contains an invalid payment transaction");
            }
            priorTotal = transaction.getTotalPaid();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
