package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/** Normal JDBC startup must not inherit privileged account administration. */
@Tag("SECURITY")
class AccountAdministrationChannelTest {

    @Test
    void composedJdbcRuntimeCannotProvisionReassignOrDeleteAccounts() {
        ConfiguredDealershipApplication app = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc("jdbc:h2:mem:restricted_admin_channel_"
                        + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        UserAccountRepository trustedFixture = TestAccounts.trustedRepository(app);
        TestAccounts.seed(trustedFixture, AccountRole.ADMINISTRATOR, "root", "operator-password");
        app.getContext().getUserLoginService().registerUser("buyer", "buyer-password");
        User root = user("root", "operator-password");
        UserLoginService[] runtimeServices = {
                app.getContext().getUserLoginService(),
                app.getContext().getCustomerLoginService(),
                app.getContext().getEmployeeLoginService(),
                app.getContext().getAdminLoginService()
        };

        for (UserLoginService service : runtimeServices) {
            assertThrows(SecurityException.class, () ->
                    service.provisionUser(root, "invented-admin", "new-password", AccountRole.ADMINISTRATOR));
            assertThrows(SecurityException.class, () ->
                    service.changeRole(root, "buyer", AccountRole.CUSTOMER, AccountRole.EMPLOYEE));
            assertThrows(SecurityException.class, () -> service.removeUser(root, "buyer"));
        }
        assertFalse(trustedFixture.findByUsername("invented-admin").isPresent());
        assertEquals(AccountRole.CUSTOMER, trustedFixture.findByUsername("buyer")
                .orElseThrow(AssertionError::new).getRole());

        assertTrue(app.getContext().getUserLoginService().changePassword(
                user("buyer", "buyer-password"), "replacement-password"));
        assertTrue(app.getContext().getUserLoginService().authenticate(
                user("buyer", "replacement-password")).isPresent());
    }

    @Test
    void operatorCanExplicitlySupplyDistinctPrivilegedRepository() {
        JdbcDatabase database = JdbcDatabase.inMemory("operator_account_channel_" + System.nanoTime());
        UserAccountRepository identity = new JdbcUserAccountRepository(database);
        UserAccountRepository privileged = new JdbcUserAccountRepository(database);
        TestAccounts.seed(identity, AccountRole.ADMINISTRATOR, "root", "root-password");
        UserLoginService operator = new UserLoginService(identity, privileged);

        User root = user("root", "root-password");
        operator.provisionUser(root, "staff", "employee-password", AccountRole.EMPLOYEE);
        assertEquals(AccountRole.EMPLOYEE, identity.findByUsername("staff")
                .orElseThrow(AssertionError::new).getRole());
        assertTrue(operator.changeRole(root, "staff", AccountRole.EMPLOYEE, AccountRole.CUSTOMER));
        assertEquals(AccountRole.CUSTOMER, identity.findByUsername("staff")
                .orElseThrow(AssertionError::new).getRole());
        assertTrue(operator.removeUser(root, "staff"));
        assertFalse(identity.findByUsername("staff").isPresent());
    }

    @Test
    void administrativeOperationsCannotRemoveTheirExecutingAdministrator() {
        UserAccountRepository repository = new com.revature.service.InMemoryUserAccountRepository();
        TestAccounts.seed(repository, AccountRole.ADMINISTRATOR, "root", "root-password");
        UserLoginService operator = new UserLoginService(repository);
        User root = user("root", "root-password");

        assertThrows(SecurityException.class, () -> operator.removeUser(root, "root"));
        assertThrows(SecurityException.class, () -> operator.changeRole(
                root, "root", AccountRole.ADMINISTRATOR, AccountRole.CUSTOMER));
        operator.provisionUser(root, "other-admin", "second-password", AccountRole.ADMINISTRATOR);
        assertTrue(operator.removeUser(root, "other-admin"));
        assertEquals(AccountRole.ADMINISTRATOR, repository.findByUsername("root")
                .orElseThrow(AssertionError::new).getRole());
    }

    private static User user(String username, String password) {
        User value = new User();
        value.setUsername(username);
        value.setPassword(password);
        return value;
    }
}
