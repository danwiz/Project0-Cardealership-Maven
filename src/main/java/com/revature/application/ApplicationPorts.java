package com.revature.application;

import java.util.List;
import java.util.Optional;

import com.revature.DAOService.LoadResult;
import com.revature.DAOService.SaveResult;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.Data;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.User;
import com.revature.service.Permission;

/** Outbound capabilities required by application commands. */
public final class ApplicationPorts {
    private ApplicationPorts() { }

    public interface Identity {
        User register(AccountRole role, String username, String password);
        Optional<User> authenticate(AccountRole role, User credentials);
        void setCurrentAccount(User account);
        void rememberRegisteredAccount(User account);
    }

    public interface Authorization {
        boolean isAuthorized(User account, Permission permission);
        void requireAuthorized(User account, Permission permission);
    }

    public interface Inventory {
        int listingCount();
        void addListing(String make, String model, int year, int price, int stockQuantity);
        void removeListing(int listingId);
        void requestPurchase(String customerName, int listingId);
        List<PurchaseRequest> purchaseRequests();
        int decideRequest(int requestId, int paymentMonths, boolean accepted);
        void rejectAllPendingRequests();
        Car carForListing(int listingId);
    }

    /** A whole-purchase boundary, not separate inventory and ownership writes. */
    public interface PurchaseApproval {
        int approve(User reviewer, int requestId, int paymentMonths, boolean rejectOtherPending);
    }

    public interface Ownership {
        void addOwnedVehicle(Car car, int purchasePrice, int paymentMonths);

        default void addOwnedVehicle(String customerName, Car car, int purchasePrice, int paymentMonths) {
            addOwnedVehicle(car, purchasePrice, paymentMonths);
        }

        default void addOwnedVehicle(String customerName, String contractId, Car car,
                int purchasePrice, int paymentMonths) {
            addOwnedVehicle(customerName, car, purchasePrice, paymentMonths);
        }
    }

    /** Persisted file operations require a verified actor on every boundary. */
    public interface Persistence {
        SaveResult save(User actor, Data source, String filename);
        LoadResult load(User actor, String filename);
        Data snapshotSource(User actor);
        void activateLoadedSnapshot(User actor, LoadResult result);
    }
}
