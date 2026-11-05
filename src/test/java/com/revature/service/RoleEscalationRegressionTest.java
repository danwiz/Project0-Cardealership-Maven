package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.ContextIdentityAdapter;
import com.revature.application.RegisterAccountCommand;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.support.TestAccounts;

@Tag("SECURITY")
class RoleEscalationRegressionTest {

    @Test
    void publicRegistrationAndRoleSpecificServicesDenyPrivilegedSignup() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        UserLoginService general = new UserLoginService(repository);
        assertEquals(AccountRole.CUSTOMER, general.registerUser("buyer", "secret").getRole());
        assertThrows(SecurityException.class,
                () -> new EmployeeLoginService(repository).registerUser("staff", "secret"));
        assertThrows(SecurityException.class,
                () -> new AdminLoginService(repository).registerUser("root", "secret"));
        assertEquals(1, repository.findAll().size());
        assertFalse(new RoleAuthorizationService().isAuthorized(
                general.authenticate(credentials("buyer", "secret")).orElseThrow(AssertionError::new),
                Permission.MANAGE_USERS));
    }

    @Test
    void applicationAndAdapterRefuseInjectedAdminRole() {
        DealershipApplicationContext context = new DealershipApplicationContext();
        RegisterAccountCommand command = new RegisterAccountCommand(context);
        assertThrows(SecurityException.class,
                () -> command.execute(AccountRole.ADMINISTRATOR, "root", "secret"));
        assertThrows(SecurityException.class,
                () -> new ContextIdentityAdapter(context).register(AccountRole.EMPLOYEE, "staff", "secret"));
        assertTrue(context.getAccountRepository().findAll().isEmpty());
    }

    @Test
    void forgedRoleCannotProvisionOrDeleteAccounts() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        UserLoginService service = new UserLoginService(repository);
        service.registerUser("buyer", "buyer-pass");
        User forged = credentials("buyer", "buyer-pass");
        forged.setRole(AccountRole.ADMINISTRATOR);
        assertThrows(SecurityException.class,
                () -> service.provisionUser(forged, "root", "secret", AccountRole.ADMINISTRATOR));
        assertThrows(SecurityException.class,
                () -> service.removeUser(forged, "buyer"));
        assertThrows(SecurityException.class,
                () -> service.removeUser(forged));
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void verifiedAdministratorMayProvisionAndDeleteWithCredentials() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        UserLoginService service = new UserLoginService(repository);
        TestAccounts.seed(repository, AccountRole.ADMINISTRATOR, "existing-admin", "private-password");
        User adminCredentials = credentials("existing-admin", "private-password");
        User employee = service.provisionUser(adminCredentials, "staff", "employee-password", AccountRole.EMPLOYEE);
        assertEquals(AccountRole.EMPLOYEE, employee.getRole());
        assertTrue(new EmployeeLoginService(repository)
                .authenticate(credentials("staff", "employee-password")).isPresent());
        assertTrue(service.removeUser(adminCredentials, "staff"));
        assertFalse(repository.findByUsername("staff").isPresent());
        assertTrue(repository.findByUsername("existing-admin").isPresent());
    }

    @Test
    void callerCannotMutatePersistedRoleByAliasingAnAccount() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        User returned = new UserLoginService(repository).registerUser("buyer", "secret");
        returned.setRole(AccountRole.ADMINISTRATOR);
        User lookedUp = repository.findByUsername("buyer").orElseThrow(AssertionError::new);
        lookedUp.setRole(AccountRole.ADMINISTRATOR);
        assertEquals(AccountRole.CUSTOMER,
                repository.findByUsername("buyer").orElseThrow(AssertionError::new).getRole());
    }

    @Test
    void jdbcAccountPersistenceDoesNotEnablePrivilegedSelfSignup() {
        UserAccountRepository repository = new JdbcUserAccountRepository(
                JdbcDatabase.inMemory("role_escalation_" + System.nanoTime()));
        UserLoginService general = new UserLoginService(repository);
        assertEquals(AccountRole.CUSTOMER, general.registerUser("buyer", "secret").getRole());
        assertThrows(SecurityException.class,
                () -> new AdminLoginService(repository).registerUser("root", "secret"));
        assertEquals(1, repository.findAll().size());
    }

    private static User credentials(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }
}
