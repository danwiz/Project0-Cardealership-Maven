package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.EmployeeLoginService;

@Tag("TARGET-BEHAVIOR")
class JdbcRepositoryTest {

    @Test
    void migrationCreatesCompleteVersionedSchemaAndIsIdempotent() throws Exception {
        JdbcDatabase database = JdbcDatabase.inMemory("schema_" + System.nanoTime());

        database.migrate();
        database.migrate();

        assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, database.schemaVersion());
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, count(statement, "schema_history"));
            assertEquals(0, count(statement, "accounts"));
            assertEquals(0, count(statement, "administrator_bootstrap_state"));
            assertEquals(0, count(statement, "inventory_listings"));
            assertEquals(0, count(statement, "purchase_requests"));
            assertEquals(0, count(statement, "owned_vehicles"));
            assertEquals(0, count(statement, "payment_plans"));
            assertEquals(0, count(statement, "payment_transactions"));
        }
    }

    @Test
    void accountRepositoryPersistsDetachedRolesAcrossRepositoryInstances() {
        JdbcDatabase database = JdbcDatabase.inMemory("accounts_" + System.nanoTime());
        JdbcUserAccountRepository first = new JdbcUserAccountRepository(database);
        User account = account("employee", "encoded-value", AccountRole.EMPLOYEE);

        User saved = first.save(account);
        account.setRole(AccountRole.CUSTOMER);
        JdbcUserAccountRepository second = new JdbcUserAccountRepository(database);
        User restored = second.findByUsername("employee").orElseThrow(AssertionError::new);

        assertNotSame(account, saved);
        assertEquals(AccountRole.EMPLOYEE, restored.getRole());
        assertEquals("encoded-value", restored.getPassword());
        assertEquals(1, second.findAll().size());
        assertThrows(UnsupportedOperationException.class, second.findAll()::clear);
    }

    @Test
    void roleServiceAuthenticatesAgainstJdbcRepositoryWithoutApplicationChanges() {
        JdbcDatabase database = JdbcDatabase.inMemory("auth_" + System.nanoTime());
        JdbcUserAccountRepository repository = new JdbcUserAccountRepository(database);
        EmployeeLoginService employees = new EmployeeLoginService(repository);
        User registered = com.revature.support.TestAccounts.seed(repository, AccountRole.EMPLOYEE, "operator", "valid-password");

        User credentials = account("operator", "valid-password", AccountRole.EMPLOYEE);
        User invalid = account("operator", "wrong-password", AccountRole.EMPLOYEE);

        assertEquals(AccountRole.EMPLOYEE, registered.getRole());
        assertTrue(employees.authenticate(credentials).isPresent());
        assertFalse(employees.authenticate(invalid).isPresent());
    }

    @Test
    void accountRepositoryRejectsUpsertsAndSupportsExplicitComparedMutations() {
        JdbcUserAccountRepository repository = new JdbcUserAccountRepository(JdbcDatabase.inMemory("mutations_" + System.nanoTime()));
        repository.save(account("root", "encoded-one", AccountRole.ADMINISTRATOR));
        assertThrows(IllegalArgumentException.class,
                () -> repository.save(account("root", "forged-credential", AccountRole.CUSTOMER)));

        List<User> accounts = repository.findAll();
        assertEquals(1, accounts.size());
        assertEquals(AccountRole.ADMINISTRATOR, accounts.get(0).getRole());
        assertEquals("encoded-one", accounts.get(0).getPassword());

        assertFalse(repository.updateCredentialIfCurrent("root", "wrong-expected", "encoded-two"));
        assertTrue(repository.updateCredentialIfCurrent("root", "encoded-one", "encoded-two"));
        assertEquals("encoded-two", repository.findByUsername("root")
                .orElseThrow(AssertionError::new).getPassword());

        assertFalse(repository.updateRoleIfCurrent("root", AccountRole.CUSTOMER, AccountRole.EMPLOYEE));
        assertThrows(SecurityException.class,
                () -> repository.updateRoleIfCurrent("root", AccountRole.ADMINISTRATOR, AccountRole.EMPLOYEE));
        repository.save(account("backup", "encoded-backup", AccountRole.ADMINISTRATOR));
        assertTrue(repository.updateRoleIfCurrent("root", AccountRole.ADMINISTRATOR, AccountRole.EMPLOYEE));
        assertEquals(AccountRole.EMPLOYEE, repository.findByUsername("root")
                .orElseThrow(AssertionError::new).getRole());

        assertTrue(repository.removeByUsername("root"));
        assertFalse(repository.removeByUsername("root"));
        assertFalse(repository.findByUsername("root").isPresent());
        assertThrows(IllegalArgumentException.class, () -> repository.save(account("", "credential", AccountRole.CUSTOMER)));
    }

    private static int count(Statement statement, String table) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next(); return result.getInt(1);
        }
    }

    private static User account(String username, String credential, AccountRole role) {
        User user = new User(); user.setUsername(username); user.setPassword(credential); user.setRole(role); return user;
    }
}
