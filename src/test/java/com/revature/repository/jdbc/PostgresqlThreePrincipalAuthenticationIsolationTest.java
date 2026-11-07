package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/**
 * End-to-end PostgreSQL16 privilege matrix with THREE distinct disposable
 * SQL identities: public registrar, private authenticator and credential
 * writer. Schema owner is only used by fixture setup/independent readback.
 *
 * No real production roles, credentials or grants are used or modified.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlThreePrincipalAuthenticationIsolationTest {
    @Test
    @Timeout(110)
    void registrarCannotReadHashesPrivateAuthenticatorCannotWriteAndCredentialWriterCannotEscalate()
            throws Exception {
        String base=System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName=System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword=System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "requires the isolated GitHub PostgreSQL16 CI service");

        String id=UUID.randomUUID().toString().replace("-","");
        String schema="w24_"+id, registrar="w24reg_"+id,
                verifier="w24auth_"+id, credential="w24cred_"+id;
        String registrarPass="fixture-only-public-registrar";
        String verifierPass="fixture-only-private-authenticator";
        String credentialPass="fixture-only-trusted-credential";
        JdbcDatabase server=new JdbcDatabase(base,ownerName,ownerPassword);
        boolean rolesCreated=false;
        try {
            try(Connection connection=server.openConnection();
                    Statement sql=connection.createStatement()) {
                try(ResultSet version=sql.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int v=Integer.parseInt(version.getString(1));
                    assertTrue(v>=160000 && v<170000,"real PostgreSQL16 is required");
                }
                sql.execute("CREATE SCHEMA "+schema);
                sql.execute("CREATE ROLE "+registrar+" LOGIN PASSWORD '"+registrarPass+"'");
                sql.execute("CREATE ROLE "+verifier+" LOGIN PASSWORD '"+verifierPass+"'");
                sql.execute("CREATE ROLE "+credential+" LOGIN PASSWORD '"+credentialPass+"'");
                rolesCreated=true;
            }
            String scoped=base+"?currentSchema="+schema;
            JdbcDatabase owner=new JdbcDatabase(scoped,ownerName,ownerPassword);
            owner.migrate();
            JdbcUserAccountRepository ownerAccounts=new JdbcUserAccountRepository(owner);
            TestAccounts.seed(ownerAccounts,AccountRole.ADMINISTRATOR,
                    "root","root-synthetic-password");

            try(Connection connection=server.openConnection();
                    Statement sql=connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    sql.execute(template("/postgresql/customer_only_registration_ci.sql")
                            .replace("__SCHEMA__",schema));
                    sql.execute(template("/postgresql/customer_name_exists_ci.sql")
                            .replace("__SCHEMA__",schema));
                    sql.execute("REVOKE ALL ON SCHEMA "+schema+" FROM PUBLIC");
                    sql.execute("REVOKE ALL ON FUNCTION "+schema+
                            ".register_customer_trusted(text,text) FROM PUBLIC");
                    sql.execute("REVOKE ALL ON FUNCTION "+schema+
                            ".customer_username_exists_trusted(text) FROM PUBLIC");

                    for(String principal:new String[]{registrar,verifier,credential}) {
                        sql.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+principal);
                        sql.execute("GRANT SELECT ON "+schema+".schema_history TO "+principal);
                    }
                    sql.execute("GRANT EXECUTE ON FUNCTION "+schema+
                            ".register_customer_trusted(text,text) TO "+registrar);
                    sql.execute("GRANT EXECUTE ON FUNCTION "+schema+
                            ".customer_username_exists_trusted(text) TO "+registrar);
                    sql.execute("GRANT SELECT(username,encoded_credential,role,session_revision) "
                            +"ON "+schema+".accounts TO "+verifier);
                    sql.execute("GRANT SELECT(username,encoded_credential,role,session_revision) "
                            +"ON "+schema+".accounts TO "+credential);
                    sql.execute("GRANT UPDATE(encoded_credential) ON "+schema+
                            ".accounts TO "+credential);
                    connection.commit();
                } catch(SQLException|RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            }

            JdbcDatabase publicDb=new JdbcDatabase(scoped,registrar,registrarPass);
            JdbcDatabase privateDb=new JdbcDatabase(scoped,verifier,verifierPass);
            JdbcDatabase trustedDb=new JdbcDatabase(scoped,credential,credentialPass);
            JdbcPublicCustomerRegistrationGateway publicSignup=
                    new JdbcPublicCustomerRegistrationGateway(publicDb);
            UserAccountRepository privateReader=new JdbcPrivateAuthenticationReader(privateDb);
            UserLoginService privateLogin=new UserLoginService(privateReader,
                    (UserAccountRepository)null);

            assertFalse(publicSignup.usernameExists("buyer"));
            publicSignup.registerCustomer("buyer","original-customer-password");
            assertTrue(publicSignup.usernameExists("buyer"));
            User original=privateLogin.authenticate(credentials(
                    "buyer","original-customer-password")).orElseThrow(AssertionError::new);
            assertEquals(AccountRole.CUSTOMER,original.getRole());
            assertTrue(original.getSessionRevision()!=null);

            // Public DB principal has NO access to credential hashes, even in SQL.
            assertDenied(publicDb,"SELECT encoded_credential FROM accounts");
            assertDenied(publicDb,"SELECT username FROM accounts");
            assertDenied(publicDb,"UPDATE accounts SET role='ADMINISTRATOR' WHERE username='buyer'");
            assertDenied(publicDb,"UPDATE accounts SET encoded_credential='forged' WHERE username='buyer'");
            assertDenied(publicDb,"DELETE FROM accounts WHERE username='buyer'");
            assertDenied(publicDb,"INSERT INTO accounts(username,encoded_credential,role) "
                    +"VALUES('root2','forged','ADMINISTRATOR')");

            // Authenticator can verify PBKDF2 but cannot update any account row.
            assertThrows(SecurityException.class,()->privateReader.save(original));
            assertThrows(SecurityException.class,
                    ()->privateReader.updateCredentialIfCurrent(
                            "buyer",original.getPassword(),"forged"));
            assertThrows(SecurityException.class,privateReader::findAll);
            assertDenied(privateDb,
                    "UPDATE accounts SET encoded_credential='forged' WHERE username='buyer'");
            assertDenied(privateDb,
                    "UPDATE accounts SET role='ADMINISTRATOR' WHERE username='buyer'");
            assertDenied(privateDb,"DELETE FROM accounts WHERE username='buyer'");

            // Credential principal may update only encoded_credential; PostgreSQL
            // refuses account INSERT, role UPDATE, DELETE and DDL. The trusted
            // Java writer independently verifies current plaintext and revision.
            assertDenied(trustedDb,
                    "UPDATE accounts SET role='ADMINISTRATOR' WHERE username='buyer'");
            assertDenied(trustedDb,"DELETE FROM accounts WHERE username='buyer'");
            assertDenied(trustedDb,
                    "INSERT INTO accounts(username,encoded_credential,role) "
                    +"VALUES('forged','forged','ADMINISTRATOR')");
            assertDenied(trustedDb,"CREATE TABLE credential_escalation(id INTEGER)");

            JdbcTrustedCustomerCredentialWriter trusted=
                    new JdbcTrustedCustomerCredentialWriter(trustedDb);
            String nextHash=new Pbkdf2PasswordHasher().hash("new-customer-password");
            assertThrows(SecurityException.class,()->trusted.rotateCustomerPassword(
                    original,"incorrect-current-password",nextHash));
            assertTrue(trusted.rotateCustomerPassword(
                    original,"original-customer-password",nextHash));
            User after=ownerAccounts.findByUsername("buyer").orElseThrow(AssertionError::new);
            assertNotEquals(original.getSessionRevision(),after.getSessionRevision());
            assertFalse(privateLogin.authenticate(credentials(
                    "buyer","original-customer-password")).isPresent());
            assertTrue(privateLogin.authenticate(credentials(
                    "buyer","new-customer-password")).isPresent());
            assertThrows(SecurityException.class,()->trusted.rotateCustomerPassword(
                    original,"original-customer-password",
                    new Pbkdf2PasswordHasher().hash("third-password")));
            assertEquals(AccountRole.ADMINISTRATOR,
                    ownerAccounts.findByUsername("root").orElseThrow(AssertionError::new).getRole());
            assertEquals(2,ownerAccounts.findAll().size());
        } finally {
            try(Connection connection=server.openConnection();
                    Statement sql=connection.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
                if(rolesCreated) {
                    sql.execute("DROP ROLE IF EXISTS "+registrar);
                    sql.execute("DROP ROLE IF EXISTS "+verifier);
                    sql.execute("DROP ROLE IF EXISTS "+credential);
                }
            }
        }
    }

    private static User credentials(String name,String password) {
        User value=new User();
        value.setUsername(name);
        value.setPassword(password);
        value.setRole(AccountRole.CUSTOMER);
        return value;
    }

    private static String template(String path) throws Exception {
        try(InputStream input=PostgresqlThreePrincipalAuthenticationIsolationTest.class
                .getResourceAsStream(path)) {
            if(input==null)throw new IllegalStateException("missing SQL fixture "+path);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    private static void assertDenied(JdbcDatabase database,String statement) {
        SQLException error=assertThrows(SQLException.class,()->{
            try(Connection connection=database.openConnection();
                    PreparedStatement sql=connection.prepareStatement(statement)) {
                sql.execute();
            }
        });
        assertEquals("42501",error.getSQLState(),"PostgreSQL must reject out-of-scope SQL grants");
    }
}
