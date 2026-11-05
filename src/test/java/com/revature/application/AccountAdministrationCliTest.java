package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

@Tag("SECURITY")
class AccountAdministrationCliTest {

    @Test
    void explicitCommandFlagOnlyAndNoDatabasePasswordsInArguments() {
        assertThrows(SecurityException.class, () -> AccountAdministrationCli.parseAction(null));
        assertThrows(SecurityException.class, () -> AccountAdministrationCli.parseAction(new String[0]));
        assertThrows(SecurityException.class, () ->
                AccountAdministrationCli.parseAction(new String[] {"--provision-staff", "password"}));
        assertThrows(SecurityException.class, () ->
                AccountAdministrationCli.parseAction(new String[] {"--bootstrap-admin"}));
        assertEquals(AccountAdministrationCli.Action.PROVISION,
                AccountAdministrationCli.parseAction(new String[] {"--provision-staff"}));
        assertEquals(AccountAdministrationCli.Action.CHANGE_ROLE,
                AccountAdministrationCli.parseAction(new String[] {"--change-role"}));
        assertEquals(AccountAdministrationCli.Action.DELETE,
                AccountAdministrationCli.parseAction(new String[] {"--delete-account"}));
    }

    @Test
    void deniesMemoryStoresOrSharedDatabasePrincipals() {
        assertThrows(SecurityException.class, () -> AccountAdministrationCli
                .validateSeparatePrincipals("jdbc:h2:mem:unsafe", "reader", "operator"));
        assertThrows(SecurityException.class, () -> AccountAdministrationCli
                .validateSeparatePrincipals("jdbc:h2:file:/tmp/sample", "reader", "READER"));
        assertThrows(SecurityException.class, () -> AccountAdministrationCli
                .validateSeparatePrincipals("jdbc:postgresql://localhost/test?password=exposed",
                        "reader", "operator"));
        assertThrows(IllegalArgumentException.class, () -> AccountAdministrationCli
                .validateSeparatePrincipals("jdbc:h2:file:/tmp/sample", "", "operator"));
        AccountAdministrationCli.validateSeparatePrincipals(
                "jdbc:postgresql://localhost/test", "identity_reader", "admin_operator");
    }

    @Test
    void operatorUsesDistinctGrantsAndUnauthenticatedActionsFail(@TempDir Path directory) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("operator").toAbsolutePath()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        JdbcDatabase owner = new JdbcDatabase(url, "sa", "");
        owner.migrate();
        JdbcUserAccountRepository ownerAccounts = new JdbcUserAccountRepository(owner);
        TestAccounts.seed(ownerAccounts, AccountRole.ADMINISTRATOR, "root", "correct-admin-password");
        TestAccounts.seed(ownerAccounts, AccountRole.EMPLOYEE, "existing-employee", "employee-password");

        try (Connection connection = owner.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE USER identity_reader PASSWORD 'fixture-reader-secret'");
            sql.execute("CREATE USER account_operator PASSWORD 'fixture-operator-secret'");
            sql.execute("GRANT SELECT ON schema_history TO identity_reader");
            sql.execute("GRANT SELECT ON accounts TO identity_reader");
            sql.execute("GRANT SELECT ON schema_history TO account_operator");
            sql.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON accounts TO account_operator");
        }

        JdbcUserAccountRepository reader = new JdbcUserAccountRepository(
                new JdbcDatabase(url, "identity_reader", "fixture-reader-secret"));
        JdbcUserAccountRepository privileged = new JdbcUserAccountRepository(
                new JdbcDatabase(url, "account_operator", "fixture-operator-secret"));
        UserLoginService operatorService = new UserLoginService(reader, privileged);
        User actor = credentials("root", "correct-admin-password");

        assertThrows(IllegalStateException.class,
                () -> reader.save(account("illegal", AccountRole.ADMINISTRATOR)));
        assertThrows(IllegalStateException.class,
                () -> reader.updateRoleIfCurrent("existing-employee",
                        AccountRole.EMPLOYEE, AccountRole.ADMINISTRATOR));
        assertThrows(IllegalStateException.class,
                () -> reader.removeByUsername("existing-employee"));
        assertThrows(SecurityException.class,
                () -> reader.updateRoleIfCurrent("root",
                        AccountRole.ADMINISTRATOR, AccountRole.CUSTOMER));
        assertThrows(SecurityException.class, () -> AccountAdministrationCli.execute(operatorService,
                credentials("root", "wrong-password"), AccountAdministrationCli.Action.PROVISION,
                "fake", null, AccountRole.ADMINISTRATOR, "private-password"));

        assertThrows(IllegalArgumentException.class, () -> AccountAdministrationCli.execute(
                operatorService, actor, AccountAdministrationCli.Action.PROVISION,
                "buyer", null, AccountRole.CUSTOMER, "private-password"));

        assertTrue(AccountAdministrationCli.execute(operatorService, actor,
                AccountAdministrationCli.Action.PROVISION, "staff", null,
                AccountRole.EMPLOYEE, "staff-secret-password"));
        assertEquals(AccountRole.EMPLOYEE,
                reader.findByUsername("staff").orElseThrow(AssertionError::new).getRole());

        assertTrue(AccountAdministrationCli.execute(operatorService, actor,
                AccountAdministrationCli.Action.CHANGE_ROLE, "staff",
                AccountRole.EMPLOYEE, AccountRole.ADMINISTRATOR, null));
        assertEquals(AccountRole.ADMINISTRATOR,
                reader.findByUsername("staff").orElseThrow(AssertionError::new).getRole());

        assertTrue(AccountAdministrationCli.execute(operatorService, actor,
                AccountAdministrationCli.Action.DELETE, "staff", null, null, null));
        assertFalse(reader.findByUsername("staff").isPresent());
        assertEquals(AccountRole.ADMINISTRATOR,
                reader.findByUsername("root").orElseThrow(AssertionError::new).getRole());
    }

    private static User credentials(String username, String password) {
        User value = new User();
        value.setUsername(username);
        value.setPassword(password);
        return value;
    }

    private static User account(String username, AccountRole role) {
        User value = new User();
        value.setUsername(username);
        value.setPassword("encoded-test-credential");
        value.setRole(role);
        return value;
    }
}
