package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.PrivateCustomerCredentialChangeService;
import com.revature.service.TrustedCustomerCredentialWriter;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/**
 * Test the application-level private credential broker using two independent
 * disposable PostgreSQL16 SQL principals, not a shared public database owner.
 *
 * This does NOT prove separate process isolation, mTLS, RPC authentication,
 * secret vault policy or production grant deployment.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlPrivateCredentialBrokerTest {
    @Test
    @Timeout(110)
    void privateBrokerRechecksCustomerProofWhileReaderCannotWriteAndWrongPlaintextFails()
            throws Exception {
        String base=System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName=System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPass=System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                && "dealership_ci_owner".equals(ownerName)
                && "ci-only-postgres-fixture".equals(ownerPass),
                "only disposable PostgreSQL16 CI allowed");

        String suffix=UUID.randomUUID().toString().replace("-","");
        String schema="w25_"+suffix;
        String authRole="w25auth_"+suffix, credRole="w25cred_"+suffix;
        String authPass="synthetic-verifier-only-password";
        String credPass="synthetic-credential-writer-only-password";
        JdbcDatabase operator=new JdbcDatabase(base,ownerName,ownerPass);
        boolean roleCreated=false;
        try {
            try(Connection connection=operator.openConnection();
                    Statement sql=connection.createStatement()) {
                try(ResultSet version=sql.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int num=Integer.parseInt(version.getString(1));
                    assertTrue(num>=160000 && num<170000);
                }
                sql.execute("CREATE SCHEMA "+schema);
                sql.execute("CREATE ROLE "+authRole+" LOGIN PASSWORD '"+authPass+"'");
                sql.execute("CREATE ROLE "+credRole+" LOGIN PASSWORD '"+credPass+"'");
                roleCreated=true;
            }

            String scoped=base+"?currentSchema="+schema;
            JdbcDatabase owner=new JdbcDatabase(scoped,ownerName,ownerPass);
            owner.migrate();
            JdbcUserAccountRepository accounts=new JdbcUserAccountRepository(owner);
            TestAccounts.seed(accounts,AccountRole.ADMINISTRATOR,
                    "admin","synthetic-admin-password");
            TestAccounts.seed(accounts,AccountRole.CUSTOMER,
                    "customer","original-customer-password");

            try(Connection connection=operator.openConnection();
                    Statement sql=connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    sql.execute("REVOKE ALL ON SCHEMA "+schema+" FROM PUBLIC");
                    for(String principal:new String[]{authRole,credRole}) {
                        sql.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+principal);
                        sql.execute("GRANT SELECT ON "+schema+
                                ".schema_history TO "+principal);
                        sql.execute("GRANT SELECT(username,encoded_credential,role,session_revision) "
                                +"ON "+schema+".accounts TO "+principal);
                    }
                    sql.execute("GRANT UPDATE(encoded_credential) ON "
                            +schema+".accounts TO "+credRole);
                    connection.commit();
                } catch(SQLException|RuntimeException e) {
                    connection.rollback();
                    throw e;
                }
            }

            JdbcDatabase privateDb=new JdbcDatabase(scoped,authRole,authPass);
            JdbcDatabase credentialDb=new JdbcDatabase(scoped,credRole,credPass);
            UserAccountRepository reader=new JdbcPrivateAuthenticationReader(privateDb);
            TrustedCustomerCredentialWriter trusted=
                    new JdbcTrustedCustomerCredentialWriter(credentialDb);
            PrivateCustomerCredentialChangeService broker=
                    new PrivateCustomerCredentialChangeService(reader,trusted);
            UserLoginService login=new UserLoginService(reader,(UserAccountRepository)null);

            assertFalse(TrustedCustomerCredentialWriter.class.isAssignableFrom(
                    JdbcPublicCustomerRegistrationGateway.class));
            assertFalse(TrustedCustomerCredentialWriter.class.isAssignableFrom(
                    JdbcPrivateAuthenticationReader.class));
            assertTrue(login.authenticate(credentials("customer",
                    "original-customer-password")).isPresent());

            assertThrows(SecurityException.class,
                    ()->broker.changePassword("missing","anything-password",
                            "a-different-password"));
            assertThrows(SecurityException.class,
                    ()->broker.changePassword("customer","incorrect-password",
                            "replacement-customer-password"));
            assertThrows(SecurityException.class,
                    ()->broker.changePassword("customer","original-customer-password",
                            "original-customer-password"));
            assertThrows(SecurityException.class,
                    ()->broker.changePassword("admin","synthetic-admin-password",
                            "forbidden-admin-password"));

            // No direct database writes permitted to the private reader.
            assertDenied42501(privateDb,
                    "UPDATE accounts SET encoded_credential='stolen' "
                    +"WHERE username='customer'");
            assertDenied42501(privateDb,
                    "DELETE FROM accounts WHERE username='customer'");
            assertDenied42501(credentialDb,
                    "UPDATE accounts SET role='ADMINISTRATOR' WHERE username='customer'");
            assertDenied42501(credentialDb,
                    "INSERT INTO accounts(username,encoded_credential,role) "
                    +"VALUES ('fake','fake','ADMINISTRATOR')");

            User previous=accounts.findByUsername("customer").orElseThrow(AssertionError::new);
            assertTrue(broker.changePassword("customer","original-customer-password",
                    "replacement-customer-password"));
            User updated=accounts.findByUsername("customer").orElseThrow(AssertionError::new);
            assertNotEquals(previous.getPassword(),updated.getPassword());
            assertNotEquals(previous.getSessionRevision(),updated.getSessionRevision());
            assertFalse(login.authenticate(credentials("customer",
                    "original-customer-password")).isPresent());
            assertTrue(login.authenticate(credentials("customer",
                    "replacement-customer-password")).isPresent());
            assertThrows(SecurityException.class,
                    ()->broker.changePassword("customer","original-customer-password",
                            "another-replacement-password"));
            assertThrows(SecurityException.class,
                    ()->trusted.rotateCustomerPassword(previous,
                            "replacement-customer-password",
                            new com.revature.service.Pbkdf2PasswordHasher()
                                    .hash("stale-session-password")));

            assertEquals(AccountRole.CUSTOMER,
                    accounts.findByUsername("customer").orElseThrow(AssertionError::new)
                            .getRole());
            assertEquals(AccountRole.ADMINISTRATOR,
                    accounts.findByUsername("admin").orElseThrow(AssertionError::new)
                            .getRole());
            assertEquals(2,accounts.findAll().size());
        } finally {
            try(Connection connection=operator.openConnection();
                    Statement sql=connection.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
                if(roleCreated) {
                    sql.execute("DROP ROLE IF EXISTS "+authRole);
                    sql.execute("DROP ROLE IF EXISTS "+credRole);
                }
            }
        }
    }

    private static User credentials(String username,String plaintext) {
        User result=new User();
        result.setUsername(username);
        result.setPassword(plaintext);
        result.setRole(AccountRole.CUSTOMER);
        return result;
    }

    private static void assertDenied42501(JdbcDatabase database,String statement) {
        SQLException sql=assertThrows(SQLException.class,()->{
            try(Connection c=database.openConnection();
                    PreparedStatement q=c.prepareStatement(statement)) {
                q.execute();
            }
        });
        assertEquals("42501",sql.getSQLState());
    }
}
