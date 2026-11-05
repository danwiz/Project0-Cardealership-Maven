package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentPlan;
import com.revature.cardealer.User;

@Tag("TARGET-BEHAVIOR")
class JdbcOwnershipRepositoryTest {

    @Test
    void persistsStableIdentifierVehicleAndPaymentPlanAcrossRepositoryInstances() {
        JdbcDatabase database = JdbcDatabase.inMemory("ownership-reopen");
        saveCustomer(database, "buyer");
        JdbcOwnershipRepository repository = new JdbcOwnershipRepository(database, "buyer");

        repository.add(new Car("Honda", "Civic", 2021), 18000, 18);
        long ownershipId = repository.findAll().get(0).getOwnershipId();

        JdbcOwnershipRepository reopened = new JdbcOwnershipRepository(database, "buyer");
        OwnedVehicle owned = reopened.findById(ownershipId).orElseThrow(AssertionError::new);
        assertTrue(ownershipId > 0);
        assertEquals(ownershipId, owned.getOwnershipId());
        assertEquals("Honda", owned.getVehicle().getCarMake());
        assertEquals(18000, owned.getPaymentPlan().getPurchasePrice());
        assertEquals(18, owned.getPaymentPlan().getTermMonths());
        assertEquals(1000, owned.getPaymentPlan().getMonthlyPayment());
        assertEquals(18000, owned.getPaymentPlan().getRemainingBalance());
        assertThrows(UnsupportedOperationException.class, reopened.findAll()::clear);
    }

    @Test
    void replacementPreservesExplicitIdentifiersAndPaidBalances() {
        JdbcDatabase database = JdbcDatabase.inMemory("ownership-replace");
        saveCustomer(database, "buyer");
        JdbcOwnershipRepository repository = new JdbcOwnershipRepository(database, "buyer");
        repository.add(new Car("Old", "Vehicle", 2010), 5000, 10);

        PaymentPlan firstPlan = new PaymentPlan(20000, 20);
        firstPlan.recordPayment(3500);
        PaymentPlan secondPlan = new PaymentPlan(12000, 12);
        secondPlan.recordPayment(12000);
        repository.replaceAll(Arrays.asList(
                new OwnedVehicle(41, new Car("Mazda", "3", 2022), firstPlan),
                new OwnedVehicle(77, new Car("Toyota", "Corolla", 2020), secondPlan)));

        assertEquals(2, repository.findAll().size());
        assertEquals("Mazda", repository.findById(41).orElseThrow(AssertionError::new).getVehicle().getCarMake());
        assertEquals(3500, repository.findById(41).orElseThrow(AssertionError::new).getPaymentPlan().getAmountPaid());
        assertEquals(16500, repository.findById(41).orElseThrow(AssertionError::new).getPaymentPlan().getRemainingBalance());
        assertEquals(0, repository.findById(77).orElseThrow(AssertionError::new).getPaymentPlan().getRemainingBalance());
    }

    @Test
    void repositoriesAreScopedToTheirCustomer() {
        JdbcDatabase database = JdbcDatabase.inMemory("ownership-scope");
        saveCustomer(database, "alpha");
        saveCustomer(database, "beta");
        JdbcOwnershipRepository alpha = new JdbcOwnershipRepository(database, "alpha");
        JdbcOwnershipRepository beta = new JdbcOwnershipRepository(database, "beta");

        alpha.add(new Car("Ford", "Focus", 2019), 9000, 9);
        beta.add(new Car("Kia", "Soul", 2021), 14000, 14);

        assertEquals("Ford", alpha.findAll().get(0).getVehicle().getCarMake());
        assertEquals("Kia", beta.findAll().get(0).getVehicle().getCarMake());
        alpha.replaceAll(Collections.emptyList());
        assertEquals(0, alpha.findAll().size());
        assertEquals(1, beta.findAll().size());
    }

    @Test
    void rejectsUnknownAndNonCustomerOwners() {
        JdbcDatabase database = JdbcDatabase.inMemory("ownership-owner-validation");
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcOwnershipRepository(database, "missing"));

        User employee = new User();
        employee.setUsername("employee");
        employee.setPassword("encoded");
        employee.setRole(AccountRole.EMPLOYEE);
        new JdbcUserAccountRepository(database).save(employee);
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcOwnershipRepository(database, "employee"));
    }

    @Test
    void invalidReplacementLeavesExistingOwnershipUnchanged() {
        JdbcDatabase database = JdbcDatabase.inMemory("ownership-atomic-replace");
        saveCustomer(database, "buyer");
        JdbcOwnershipRepository repository = new JdbcOwnershipRepository(database, "buyer");
        repository.add(new Car("Honda", "Accord", 2020), 16000, 16);
        long ownershipId = repository.findAll().get(0).getOwnershipId();

        assertThrows(NullPointerException.class,
                () -> repository.replaceAll(Arrays.asList(
                        new OwnedVehicle(91, new Car("Mazda", "6", 2021), new PaymentPlan(19000, 19)),
                        null)));

        assertEquals(1, repository.findAll().size());
        assertEquals(ownershipId, repository.findAll().get(0).getOwnershipId());
        assertEquals("Honda", repository.findAll().get(0).getVehicle().getCarMake());
    }

    private static void saveCustomer(JdbcDatabase database, String username) {
        User customer = new User();
        customer.setUsername(username);
        customer.setPassword("encoded-credential");
        customer.setRole(AccountRole.CUSTOMER);
        new JdbcUserAccountRepository(database).save(customer);
    }
}
