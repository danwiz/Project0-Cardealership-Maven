package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.User;
import com.revature.repository.OfferInventoryRepository;
import com.revature.repository.jdbc.JdbcInventoryRepository;
import com.revature.repository.jdbc.JdbcOwnershipRepository;
import com.revature.repository.jdbc.JdbcPaymentTransactionRepository;
import com.revature.service.ReadOnlyUserAccountRepository;

class DealershipCompositionRootTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(InfrastructureConfiguration.MODE_PROPERTY);
        System.clearProperty(InfrastructureConfiguration.JDBC_URL_PROPERTY);
        System.clearProperty(InfrastructureConfiguration.JDBC_USER_PROPERTY);
        System.clearProperty(InfrastructureConfiguration.JDBC_PASSWORD_PROPERTY);
    }

    @Test
    void defaultsToInMemoryInfrastructure() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.createDefault();

        assertEquals(InfrastructureConfiguration.Mode.IN_MEMORY,
                application.getConfiguration().getMode());
        assertTrue(application.getAccounts() instanceof ReadOnlyUserAccountRepository);
        assertTrue(application.getInventory() instanceof OfferInventoryRepository);
        assertTrue(application.getContext().getAccountRepository() == application.getAccounts());
    }

    @Test
    void rejectsUnknownModeAndMissingJdbcUrl() {
        System.setProperty(InfrastructureConfiguration.MODE_PROPERTY, "unknown");
        assertThrows(IllegalArgumentException.class, DealershipCompositionRoot::createDefault);

        System.setProperty(InfrastructureConfiguration.MODE_PROPERTY, "jdbc");
        assertThrows(IllegalArgumentException.class, DealershipCompositionRoot::createDefault);
    }

    @Test
    void composesDurableJdbcRepositoriesFromProperties() {
        String url = jdbcUrl("composition");
        System.setProperty(InfrastructureConfiguration.MODE_PROPERTY, "jdbc");
        System.setProperty(InfrastructureConfiguration.JDBC_URL_PROPERTY, url);

        ConfiguredDealershipApplication first = DealershipCompositionRoot.createDefault();
        assertTrue(first.getAccounts() instanceof ReadOnlyUserAccountRepository);
        assertTrue(first.getInventory() instanceof JdbcInventoryRepository);
        assertTrue(first.getContext().getAccountRepository() == first.getAccounts());

        first.getContext().getCustomerLoginService().registerUser("alice", "sample-secret");
        first.getInventory().addListing("Honda", "Civic", 2024, 30000, 2);
        first.ownershipFor("alice").add(new Car("Honda", "Civic", 2024), 30000, 60);
        ((JdbcPaymentTransactionRepository) first.paymentsFor("alice")).recordPayment(500, 30000);

        ConfiguredDealershipApplication second = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(url));
        assertNotSame(first, second);
        assertTrue(second.getAccounts().findByUsername("alice").isPresent());
        assertEquals(1, second.getInventory().listings().size());
        assertEquals(1, second.ownershipFor("alice").findAll().size());
        assertEquals(1, second.paymentsFor("alice").findAll().size());
        assertTrue(second.ownershipFor("alice") instanceof JdbcOwnershipRepository);
        assertTrue(second.paymentsFor("alice") instanceof JdbcPaymentTransactionRepository);
    }

    @Test
    void jdbcFactoriesRemainCustomerScoped() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(jdbcUrl("scoped")));
        application.getContext().getCustomerLoginService().registerUser("alice", "sample-secret");
        application.getContext().getCustomerLoginService().registerUser("bob", "sample-secret");

        application.ownershipFor("alice").add(new Car("A", "One", 2020), 1000, 10);
        ((JdbcPaymentTransactionRepository) application.paymentsFor("alice")).recordPayment(100, 1000);

        assertEquals(1, application.ownershipFor("alice").findAll().size());
        assertEquals(0, application.ownershipFor("bob").findAll().size());
        assertEquals(1, application.paymentsFor("alice").findAll().size());
        assertEquals(0, application.paymentsFor("bob").findAll().size());
        assertFalse(application.getAccounts().findByUsername("missing").isPresent());
    }

    @Test
    void persistentJdbcRequiresExplicitNonemptyCredentials() {
        String fileUrl = "jdbc:h2:file:./test-dealership";
        String remoteUrl = "jdbc:postgresql://db.example.invalid:5432/dealership";
        assertThrows(IllegalArgumentException.class,
                () -> InfrastructureConfiguration.jdbc(fileUrl));
        assertThrows(IllegalArgumentException.class,
                () -> InfrastructureConfiguration.jdbc(remoteUrl, "service", ""));
        assertThrows(IllegalArgumentException.class,
                () -> InfrastructureConfiguration.jdbc(fileUrl, "", "not-empty"));

        System.setProperty(InfrastructureConfiguration.MODE_PROPERTY, "jdbc");
        System.setProperty(InfrastructureConfiguration.JDBC_URL_PROPERTY, fileUrl);
        assertThrows(IllegalArgumentException.class, InfrastructureConfiguration::fromSystemProperties);
        System.setProperty(InfrastructureConfiguration.JDBC_PASSWORD_PROPERTY, "configured-secret");
        assertThrows(IllegalArgumentException.class, InfrastructureConfiguration::fromSystemProperties);
        System.setProperty(InfrastructureConfiguration.JDBC_USER_PROPERTY, "restricted_service");
        InfrastructureConfiguration configured = InfrastructureConfiguration.fromSystemProperties();
        assertEquals("restricted_service", configured.getJdbcUser());
        assertEquals("configured-secret", configured.getJdbcPassword());
        assertEquals(InfrastructureConfiguration.Mode.JDBC, configured.getMode());
    }

    @Test
    void transientH2StillAllowsExplicitTestModeDefaultCredentials() {
        InfrastructureConfiguration configuration = InfrastructureConfiguration.jdbc(jdbcUrl("transient"));
        assertEquals("sa", configuration.getJdbcUser());
        assertEquals("", configuration.getJdbcPassword());
    }

    @Test
    void inMemoryPublicAccountViewCannotModifyRolesOrCredentials() {
        verifyPublicViewIsReadOnly(DealershipCompositionRoot.create(
                InfrastructureConfiguration.inMemory()));
    }

    @Test
    void jdbcPublicAccountViewCannotModifyRolesOrCredentials() {
        verifyPublicViewIsReadOnly(DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(jdbcUrl("public_account_view"))));
    }

    private static void verifyPublicViewIsReadOnly(ConfiguredDealershipApplication application) {
        application.getContext().getCustomerLoginService().registerUser("alice", "valid-password");
        User persisted = application.getAccounts().findByUsername("alice")
                .orElseThrow(AssertionError::new);
        assertEquals(AccountRole.CUSTOMER, persisted.getRole());
        assertNull(persisted.getPassword());

        persisted.setRole(AccountRole.ADMINISTRATOR);
        persisted.setPassword("forged-encoded-secret");
        assertThrows(SecurityException.class, () -> application.getAccounts().save(persisted));
        assertThrows(SecurityException.class, () ->
                application.getContext().getAccountRepository().removeByUsername("alice"));

        User inventedAdmin = new User();
        inventedAdmin.setUsername("forged-admin");
        inventedAdmin.setRole(AccountRole.ADMINISTRATOR);
        inventedAdmin.setPassword("fake-hash");
        assertThrows(SecurityException.class, () ->
                application.getContext().getAccountRepository().save(inventedAdmin));

        assertThrows(SecurityException.class, () -> application.getAccounts()
                .updateCredentialIfCurrent("alice", "old-hash", "forged-hash"));
        assertThrows(SecurityException.class, () -> application.getAccounts()
                .updateRoleIfCurrent("alice", AccountRole.CUSTOMER, AccountRole.ADMINISTRATOR));

        User reloaded = application.getAccounts().findByUsername("alice")
                .orElseThrow(AssertionError::new);
        assertEquals(AccountRole.CUSTOMER, reloaded.getRole());
        assertNull(reloaded.getPassword());
        assertFalse(application.getAccounts().findByUsername("forged-admin").isPresent());

        // Normal public customer enrollment continues through the trusted service.
        assertTrue(application.getContext().getCustomerLoginService().authenticate(
                login("alice", "valid-password")).isPresent());
    }

    private static User login(String username, String password) {
        User credentials = new User();
        credentials.setUsername(username);
        credentials.setPassword(password);
        return credentials;
    }

    private static String jdbcUrl(String name) {
        return "jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    }
}