package com.revature.repository;

import java.util.List;
import java.util.Optional;

import com.revature.cardealer.Car;
import com.revature.cardealer.OwnedVehicle;

public interface OwnershipRepository {
    List<OwnedVehicle> findAll();

    default Optional<OwnedVehicle> findById(long ownershipId) {
        return findAll().stream().filter(vehicle -> vehicle.getOwnershipId() == ownershipId).findFirst();
    }

    void add(Car car, int purchasePrice, int paymentMonths);

    default void add(String contractId, Car car, int purchasePrice, int paymentMonths) {
        add(car, purchasePrice, paymentMonths);
    }

    void replaceAll(List<OwnedVehicle> vehicles);
}
