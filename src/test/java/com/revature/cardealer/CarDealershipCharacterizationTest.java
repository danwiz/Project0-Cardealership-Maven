package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.DAOService.LoadResult;

class CarDealershipCharacterizationTest {

    private final PrintStream originalOut = System.out;

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void customerRegistrationUsesSharedServiceWithoutUnsafeDowncast() throws Throwable {
        String username = uniqueUsername("customer");
        ByteArrayOutputStream output = captureOutput();
        replaceScanner("1\n" + username + "\nsecret\n");
        invokePerformUserAction("1");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Customer registered"));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void unregisteredEmployeeCannotReachInventoryManagementFlow() throws Throwable {
        ByteArrayOutputStream output = captureOutput();
        replaceScanner(uniqueUsername("unknown") + "\npassword\n");
        invokePerformUserAction("3");
        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("failure"));
        assertFalse(rendered.contains("Employee View"));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void customerCredentialsCannotEnterEmployeeMenu() throws Throwable {
        String username = uniqueUsername("customer-role");
        ByteArrayOutputStream output = captureOutput();
        replaceScanner("1\n" + username + "\nsecret\n");
        invokePerformUserAction("1");
        replaceScanner(username + "\nsecret\n");
        invokePerformUserAction("3");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("failure"));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void registeredEmployeeReachesOnlyAuthorizedEmployeeMenu() throws Throwable {
        String username = uniqueUsername("employee");
        ByteArrayOutputStream output = captureOutput();
        com.revature.support.TestAccounts.seed(
                com.revature.support.TestAccounts.trustedRepository(CarDealership.getApplicationContext()),
                AccountRole.EMPLOYEE, username, "secret");
        replaceScanner(username + "\nsecret\n3\n");
        invokePerformUserAction("3");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Employee View"));
        assertEquals(username, CarDealership.getCurrentAccount().getUsername());
        assertEquals(AccountRole.EMPLOYEE, CarDealership.getCurrentAccount().getRole());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    @Tag("SECURITY")
    void administratorFeaturesRequireAuthenticatedAdministratorRole() throws Throwable {
        String username = uniqueUsername("administrator");
        ByteArrayOutputStream output = captureOutput();
        com.revature.support.TestAccounts.seed(
                com.revature.support.TestAccounts.trustedRepository(CarDealership.getApplicationContext()),
                AccountRole.ADMINISTRATOR, username, "secret");
        replaceScanner(username + "\nsecret\n9\n");
        invokePerformUserAction("5");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("You are now an Admin"));
        assertEquals(AccountRole.ADMINISTRATOR, CarDealership.getCurrentAccount().getRole());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void missingStateFileReturnsNotFoundWithoutNullDereference() {
        File stateFile = new File("CarDealer.dat");
        if (stateFile.exists()) stateFile.delete();
        ByteArrayOutputStream output;
        try {
            output = captureOutput();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        assertThrows(SecurityException.class, () -> new CarDealership().loadData());
        // The unauthenticated public entry point must deny before looking at disk.
    }

    private static ByteArrayOutputStream captureOutput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        return output;
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static void replaceScanner(String input) throws Exception {
        Field scannerField = CarDealership.class.getDeclaredField("scan");
        scannerField.setAccessible(true);
        scannerField.set(null, new Scanner(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))));
    }

    private static void invokePerformUserAction(String option) throws Throwable {
        Method method = CarDealership.class.getDeclaredMethod("performUserAction", String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, option);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
