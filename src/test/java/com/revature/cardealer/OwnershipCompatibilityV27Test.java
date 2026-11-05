package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Evidence for two still-unmerged add/add conflicts: OwnedVehicle and PaymentPlan. */
@Tag("INTEGRATION-CANDIDATE-V27")
class OwnershipCompatibilityV27Test {
    @Test
    void legacyOwnershipConstructorPreservesOriginalObjectAndPaymentContract() {
        Car vehicle = new Car("Honda", "Civic", 2020);
        PaymentPlan plan = new PaymentPlan(12000, 12);
        OwnedVehicle owned = new OwnedVehicle(vehicle, plan);
        assertSame(vehicle, owned.getVehicle());
        assertSame(plan, owned.getPaymentPlan());
        assertFalse(owned.hasOwnershipId());
        assertTrue(owned.getContractId().isEmpty());
        plan.recordPayment(1000);
        assertEquals(11000, owned.getPaymentPlan().getRemainingBalance());
        assertThrows(IllegalArgumentException.class, () -> plan.recordPayment(11001));
    }

    @Test
    void IdentifiedOwnershipAndPaymentRoundTripThroughSerialization() throws Exception {
        Car vehicle = new Car("Toyota", "Corolla", 2021);
        PaymentPlan plan = new PaymentPlan(18000, 18);
        plan.recordPayment(750);
        OwnedVehicle original = new OwnedVehicle(27, "ACQ-000027", vehicle, plan);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        OwnedVehicle restored;
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (OwnedVehicle) in.readObject();
        }
        assertEquals(27L, restored.getOwnershipId());
        assertEquals("ACQ-000027", restored.getContractId().orElseThrow());
        assertEquals("Corolla", restored.getVehicle().getCarModel());
        assertEquals(750, restored.getPaymentPlan().getAmountPaid());
        assertEquals(17250, restored.getPaymentPlan().getRemainingBalance());
    }
}
