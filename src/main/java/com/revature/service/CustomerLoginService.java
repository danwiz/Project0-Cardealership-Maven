package com.revature.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.revature.cardealer.Car;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentPlan;
import com.revature.cardealer.User;

public class CustomerLoginService extends UserLoginService {

    private final List<OwnedVehicle> ownedVehicles = new ArrayList<>();
    private long nextOwnershipId = 1;

    public CustomerLoginService() { super(); }
    public CustomerLoginService(UserAccountRepository userRepository) { super(userRepository); }
    public CustomerLoginService(UserAccountRepository userRepository,
            UserAccountRepository administrativeRepository) {
        super(userRepository, administrativeRepository);
    }

    public void restoreOwnedVehicles(List<OwnedVehicle> restoredVehicles) {
        ownedVehicles.clear();
        long next = 1;
        for (OwnedVehicle vehicle : Objects.requireNonNull(restoredVehicles, "restoredVehicles")) {
            OwnedVehicle restored = vehicle.hasOwnershipId() ? vehicle
                    : new OwnedVehicle(next, vehicle.getContractId().orElse(null),
                            vehicle.getVehicle(), vehicle.getPaymentPlan());
            ownedVehicles.add(restored);
            next = Math.max(next, restored.getOwnershipId() + 1);
        }
        nextOwnershipId = next;
    }

    @Override
    public User registerUser(String username, String password) {
        return super.registerUser(username, password);
    }

    public OwnedVehicle setCarsOwned(Car owned, int purchasePrice, int termMonths) {
        return setCarsOwned(null, owned, purchasePrice, termMonths);
    }

    public OwnedVehicle setCarsOwned(String contractId, Car owned, int purchasePrice, int termMonths) {
        OwnedVehicle vehicle = new OwnedVehicle(nextOwnershipId++, contractId, owned,
                new PaymentPlan(purchasePrice, termMonths));
        ownedVehicles.add(vehicle);
        return vehicle;
    }

    public void setPayments(int payment) {
        if (ownedVehicles.isEmpty()) throw new IllegalStateException("no owned vehicle is available for payment");
        ownedVehicles.get(ownedVehicles.size() - 1).getPaymentPlan().recordPayment(payment);
    }

    public void setPayments(String[] payments) {
        if (payments == null) throw new IllegalArgumentException("payments must not be null");
        for (String payment : payments) setPayments(Integer.parseInt(payment));
    }

    public void recordPayment(int ownedVehicleIndex, int payment) { getOwnedVehicle(ownedVehicleIndex).getPaymentPlan().recordPayment(payment); }
    public void recordPaymentById(long ownershipId, int payment) { getOwnedVehicleById(ownershipId).getPaymentPlan().recordPayment(payment); }

    public OwnedVehicle getOwnedVehicle(int index) {
        if (index < 0 || index >= ownedVehicles.size()) throw new IndexOutOfBoundsException("owned vehicle index out of range: " + index);
        return ownedVehicles.get(index);
    }

    public OwnedVehicle getOwnedVehicleById(long ownershipId) {
        for (OwnedVehicle vehicle : ownedVehicles) if (vehicle.getOwnershipId() == ownershipId) return vehicle;
        throw new IllegalArgumentException("unknown ownership id: " + ownershipId);
    }

    public List<OwnedVehicle> getOwnedVehicleRecords() { return Collections.unmodifiableList(new ArrayList<>(ownedVehicles)); }

    public void getCarsOwned() {
        if (ownedVehicles.isEmpty()) { System.out.println("No vehicles owned."); return; }
        for (OwnedVehicle ownedVehicle : ownedVehicles) {
            PaymentPlan plan = ownedVehicle.getPaymentPlan();
            System.out.println("[" + ownedVehicle.getOwnershipId() + "] " + ownedVehicle.getVehicle().getCar()
                    + "   Contract: " + ownedVehicle.getContractId().orElse("historical")
                    + "   Price: " + plan.getPurchasePrice()
                    + "   Amount Paid: " + plan.getAmountPaid()
                    + "   Balance: " + plan.getRemainingBalance()
                    + "   Monthly Cost: " + plan.getMonthlyPayment());
        }
    }
}
