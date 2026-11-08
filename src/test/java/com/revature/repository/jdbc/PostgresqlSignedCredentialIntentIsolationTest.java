package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.PrivateCustomerCredentialChangeService;
import com.revature.service.UserAccountRepository;
import com.revature.support.TestAccounts;

/**
 * Wave27: database-level denial if only the credential-writer PostgreSQL
 * login is compromised. PostgreSQL16 owner function additionally requires
 * a fresh HMAC, one-time nonce, CUSTOMER role, current hash and session revision.
 * The role has no direct accounts SELECT/UPDATE/INSERT/DELETE or signing-key
 * SELECT. The signer key exists independently in private Java memory and in
 * a schema-owner-only table.
 *
 * This fixture does NOT prove resistance if the attacker compromises the
 * signer secret AND SQL login together, or production secret/workload policies.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlSignedCredentialIntentIsolationTest {
    @Test
    @Timeout(130)
    void stolenSqlWriterLoginCannotBypassSignedOneTimeCustomerCredentialIntent()
            throws Exception {
        String base=System.getenv("DEALERSHIP_PG_TEST_URL");
        String dbUser=System.getenv("DEALERSHIP_PG_TEST_USER");
        String dbPassword=System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(dbUser)
                        && "ci-only-postgres-fixture".equals(dbPassword),
                "disposable GitHub PostgreSQL16 CI only");

        String suffix=UUID.randomUUID().toString().replace("-","");
        String schema="w27_"+suffix, verifier="w27auth_"+suffix, writer="w27write_"+suffix;
        String readerPass="ci-only-private-account-verifier";
        String writerPass="ci-only-execute-signed-credential-intent";
        byte[] signingKey=new byte[32];
        new SecureRandom().nextBytes(signingKey);

        JdbcDatabase server=new JdbcDatabase(base,dbUser,dbPassword);
        boolean rolesCreated=false;
        try {
            try(Connection c=server.openConnection();Statement sql=c.createStatement()) {
                try(ResultSet result=sql.executeQuery("SHOW server_version_num")) {
                    assertTrue(result.next());
                    int version=Integer.parseInt(result.getString(1));
                    assertTrue(version>=160000&&version<170000,"PostgreSQL16 required");
                }
                sql.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
                sql.execute("CREATE SCHEMA "+schema);
                sql.execute("CREATE ROLE "+verifier+" LOGIN PASSWORD '"+readerPass+"'");
                sql.execute("CREATE ROLE "+writer+" LOGIN PASSWORD '"+writerPass+"'");
                rolesCreated=true;
            }

            String scoped=base+"?currentSchema="+schema;
            JdbcDatabase owner=new JdbcDatabase(scoped,dbUser,dbPassword);
            owner.migrate();
            JdbcUserAccountRepository accounts=new JdbcUserAccountRepository(owner);
            TestAccounts.seed(accounts,AccountRole.ADMINISTRATOR,
                    "root","synthetic-admin-password");
            TestAccounts.seed(accounts,AccountRole.CUSTOMER,
                    "buyer","synthetic-customer-password");

            try(Connection c=server.openConnection();Statement sql=c.createStatement()) {
                c.setAutoCommit(false);
                try {
                    sql.execute("CREATE TABLE "+schema+".credential_rotation_keys("
                            +"key_id INTEGER PRIMARY KEY CHECK (key_id=1), "
                            +"key_bytes BYTEA NOT NULL CHECK (octet_length(key_bytes)>=32))");
                    sql.execute("CREATE TABLE "+schema+".credential_rotation_nonces("
                            +"nonce UUID PRIMARY KEY, username TEXT NOT NULL, "
                            +"created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                    try(PreparedStatement secret=c.prepareStatement(
                            "INSERT INTO "+schema+".credential_rotation_keys(key_id,key_bytes) VALUES (1,?)")) {
                        secret.setBytes(1,signingKey);
                        assertEquals(1,secret.executeUpdate());
                    }

                    sql.execute(resource("/postgresql/wave27_signed_credential_authority_ci.sql")
                            .replace("__SCHEMA__",schema));

                    sql.execute("REVOKE ALL ON SCHEMA "+schema+" FROM PUBLIC");
                    sql.execute("REVOKE ALL ON FUNCTION "+schema
                            +".rotate_customer_credential_authorized("
                            +"text,uuid,text,text,uuid,text) FROM PUBLIC");

                    for(String role:new String[]{verifier,writer}) {
                        sql.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
                        sql.execute("GRANT SELECT ON "+schema+".schema_history TO "+role);
                    }
                    sql.execute("GRANT SELECT(username,encoded_credential,role,session_revision) "
                            +"ON "+schema+".accounts TO "+verifier);
                    sql.execute("GRANT EXECUTE ON FUNCTION "+schema
                            +".rotate_customer_credential_authorized("
                            +"text,uuid,text,text,uuid,text) TO "+writer);
                    c.commit();
                } catch(SQLException|RuntimeException error) {
                    c.rollback();
                    throw error;
                }
            }
            JdbcDatabase privateDb=new JdbcDatabase(scoped,verifier,readerPass);
            JdbcDatabase signedDb=new JdbcDatabase(scoped,writer,writerPass);
            UserAccountRepository reader=new JdbcPrivateAuthenticationReader(privateDb);
            JdbcSignedCustomerCredentialWriter authorizedWriter=
                    new JdbcSignedCustomerCredentialWriter(signedDb,signingKey);
            PrivateCustomerCredentialChangeService privateBroker=
                    new PrivateCustomerCredentialChangeService(reader,authorizedWriter);

            User before=accounts.findByUsername("buyer").orElseThrow(AssertionError::new);
            assertEquals(AccountRole.CUSTOMER,before.getRole());
            assertDenied42501(signedDb,"SELECT encoded_credential FROM accounts");
            assertDenied42501(signedDb,"SELECT key_bytes FROM credential_rotation_keys");
            assertDenied42501(signedDb,"SELECT nonce FROM credential_rotation_nonces");
            assertDenied42501(signedDb,
                    "UPDATE accounts SET encoded_credential='attacker' WHERE username='buyer'");
            assertDenied42501(signedDb,
                    "UPDATE accounts SET role='ADMINISTRATOR' WHERE username='buyer'");
            assertDenied42501(signedDb,
                    "INSERT INTO accounts(username,encoded_credential,role) "
                    +"VALUES('forged','forged','ADMINISTRATOR')");
            assertDenied42501(signedDb,"DELETE FROM accounts WHERE username='buyer'");
            assertDenied42501(privateDb,
                    "UPDATE accounts SET encoded_credential='reader-takeover' WHERE username='buyer'");

            String illicitHash=new Pbkdf2PasswordHasher().hash("attacker-password");
            UUID oldRevision=UUID.fromString(before.getSessionRevision());
            UUID nonce=UUID.randomUUID();
            assertFalse(sqlUpdate(signedDb,"buyer",oldRevision,before.getPassword(),
                    illicitHash,nonce,"0".repeat(64)),
                    "stolen SQL login cannot forge a signed update");
            assertFalse(sqlUpdate(signedDb,"buyer",oldRevision,before.getPassword(),
                    illicitHash,nonce,""),
                    "unsigned function call must not change an account");
            assertEquals(before.getPassword(),
                    accounts.findByUsername("buyer").orElseThrow(AssertionError::new).getPassword());

            assertThrows(SecurityException.class,
                    ()->privateBroker.changePassword("buyer","incorrect-customer-password",
                            "replaced-password"));
            assertThrows(SecurityException.class,
                    ()->privateBroker.changePassword("root","synthetic-admin-password",
                            "admin-takeover-password"));
            assertTrue(privateBroker.changePassword("buyer","synthetic-customer-password",
                    "new-customer-password"));
            User changed=accounts.findByUsername("buyer").orElseThrow(AssertionError::new);
            assertNotEquals(before.getPassword(),changed.getPassword());
            assertNotEquals(before.getSessionRevision(),changed.getSessionRevision());
            assertTrue(new Pbkdf2PasswordHasher().matches(
                    "new-customer-password",changed.getPassword()));
            assertThrows(SecurityException.class,
                    ()->privateBroker.changePassword("buyer","synthetic-customer-password",
                            "stale-password"));

            // Prove the nonce is one-use even with a fresh session/hash and
            // a new correctly signed intent. Both calls are *test-only* and
            // deliberately use an owner-provided synthetic signing key.
            User current=accounts.findByUsername("buyer").orElseThrow(AssertionError::new);
            UUID reusableNonce=UUID.randomUUID();
            String thirdHash=new Pbkdf2PasswordHasher().hash("third-password");
            UUID revision=UUID.fromString(current.getSessionRevision());
            assertTrue(sqlUpdate(signedDb,"buyer",revision,current.getPassword(),
                    thirdHash,reusableNonce,mac(signingKey,JdbcSignedCustomerCredentialWriter
                            .canonical("buyer",revision,current.getPassword(),thirdHash,reusableNonce))));
            User third=accounts.findByUsername("buyer").orElseThrow(AssertionError::new);
            assertNotEquals(current.getSessionRevision(),third.getSessionRevision());

            String fourthHash=new Pbkdf2PasswordHasher().hash("fourth-password");
            UUID thirdRevision=UUID.fromString(third.getSessionRevision());
            assertFalse(sqlUpdate(signedDb,"buyer",thirdRevision,third.getPassword(),
                    fourthHash,reusableNonce,mac(signingKey,JdbcSignedCustomerCredentialWriter
                            .canonical("buyer",thirdRevision,third.getPassword(),
                                    fourthHash,reusableNonce))),
                    "one-time nonce must never be reusable even with a new valid HMAC");
            User afterReplay=accounts.findByUsername("buyer")
                    .orElseThrow(AssertionError::new);
            assertEquals(third.getPassword(),afterReplay.getPassword());
            assertEquals(third.getSessionRevision(),afterReplay.getSessionRevision());
            assertEquals(AccountRole.ADMINISTRATOR,
                    accounts.findByUsername("root").orElseThrow(AssertionError::new).getRole());
            assertEquals(2,accounts.findAll().size());
        } finally {
            try(Connection c=server.openConnection(); Statement sql=c.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
                if(rolesCreated) {
                    sql.execute("DROP ROLE IF EXISTS "+verifier);
                    sql.execute("DROP ROLE IF EXISTS "+writer);
                }
            }
            java.util.Arrays.fill(signingKey,(byte)0);
        }
    }

    private static boolean sqlUpdate(JdbcDatabase db,String username,UUID revision,
                                     String expected,String replacement,UUID nonce,String mac)
            throws SQLException {
        try(Connection connection=db.openConnection();
                PreparedStatement call=connection.prepareStatement(
                        "SELECT rotate_customer_credential_authorized(?,?,?,?,?,?)")) {
            call.setString(1,username);
            call.setObject(2,revision);
            call.setString(3,expected);
            call.setString(4,replacement);
            call.setObject(5,nonce);
            call.setString(6,mac);
            try(ResultSet result=call.executeQuery()) {
                assertTrue(result.next());
                boolean accepted=result.getBoolean(1);
                assertFalse(result.next());
                return accepted;
            }
        }
    }

    private static String mac(byte[] key,String message) throws Exception {
        Mac engine=Mac.getInstance("HmacSHA256");
        engine.init(new SecretKeySpec(key,"HmacSHA256"));
        byte[] bytes=engine.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();
        for(byte b:bytes)hex.append(String.format("%02x",b&255));
        return hex.toString();
    }

    private static String resource(String name) throws Exception {
        try(InputStream input=PostgresqlSignedCredentialIntentIsolationTest.class
                .getResourceAsStream(name)) {
            if(input==null)throw new IllegalStateException("SQL resource absent "+name);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    private static void assertDenied42501(JdbcDatabase db,String sql) {
        SQLException error=assertThrows(SQLException.class,()->{
            try(Connection connection=db.openConnection();
                    PreparedStatement operation=connection.prepareStatement(sql)) {
                operation.execute();
            }
        });
        assertEquals("42501",error.getSQLState());
    }
}
