package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** Real disposable PG16 check of hash-blind, registration-only database access. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlPublicRegistrationIsolationTest {
    @Test
    @Timeout(90)
    void registrarCanCreateCustomersWithoutAnyAccountTableReadOrWriteGrants() throws Exception {
        String url=System.getenv("DEALERSHIP_PG_TEST_URL");
        String dbUser=System.getenv("DEALERSHIP_PG_TEST_USER");
        String dbPassword=System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "jdbc:postgresql://localhost:5432/dealership_ci".equals(url)
                && "dealership_ci_owner".equals(dbUser)
                && "ci-only-postgres-fixture".equals(dbPassword),
                "disposable PostgreSQL16 CI fixture required");

        String id=UUID.randomUUID().toString().replace("-","");
        String schema="w23_"+id;
        String registrar="w23reg_"+id;
        String registrarPass="ci-disposable-registration-only";
        JdbcDatabase server=new JdbcDatabase(url,dbUser,dbPassword);
        boolean createdRole=false;
        try {
            try(Connection c=server.openConnection(); Statement s=c.createStatement()) {
                try(ResultSet ver=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(ver.next());
                    int v=Integer.parseInt(ver.getString(1));
                    assertTrue(v>=160000&&v<170000,"real PostgreSQL16 required");
                }
                s.execute("CREATE SCHEMA "+schema);
                s.execute("CREATE ROLE "+registrar+" LOGIN PASSWORD '"+registrarPass+"'");
                createdRole=true;
            }

            JdbcDatabase owner=new JdbcDatabase(url+"?currentSchema="+schema,dbUser,dbPassword);
            owner.migrate();
            try(Connection c=server.openConnection();Statement s=c.createStatement()) {
                c.setAutoCommit(false);
                try {
                    s.execute(load("/postgresql/customer_only_registration_ci.sql")
                            .replace("__SCHEMA__",schema));
                    s.execute(load("/postgresql/customer_name_exists_ci.sql")
                            .replace("__SCHEMA__",schema));
                    s.execute("REVOKE ALL ON SCHEMA "+schema+" FROM PUBLIC");
                    s.execute("REVOKE ALL ON FUNCTION "+schema+
                            ".register_customer_trusted(text,text) FROM PUBLIC");
                    s.execute("REVOKE ALL ON FUNCTION "+schema+
                            ".customer_username_exists_trusted(text) FROM PUBLIC");
                    s.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+registrar);
                    s.execute("GRANT SELECT ON "+schema+".schema_history TO "+registrar);
                    s.execute("GRANT EXECUTE ON FUNCTION "+schema+
                            ".register_customer_trusted(text,text) TO "+registrar);
                    s.execute("GRANT EXECUTE ON FUNCTION "+schema+
                            ".customer_username_exists_trusted(text) TO "+registrar);
                    c.commit();
                } catch(SQLException|RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }

            JdbcDatabase limited=new JdbcDatabase(url+"?currentSchema="+schema,registrar,registrarPass);
            JdbcPublicCustomerRegistrationGateway gateway=
                    new JdbcPublicCustomerRegistrationGateway(limited);
            assertFalse(gateway.usernameExists("buyer"));
            assertEquals("buyer",gateway.registerCustomer("buyer","synthetic-buyer-password"));
            assertTrue(gateway.usernameExists("buyer"));
            assertThrows(IllegalArgumentException.class,
                    ()->gateway.registerCustomer("buyer","different-buyer-password"));

            User saved=new JdbcUserAccountRepository(owner)
                    .findByUsername("buyer").orElseThrow(AssertionError::new);
            assertEquals(AccountRole.CUSTOMER,saved.getRole());
            assertNotNull(saved.getSessionRevision());
            assertTrue(new Pbkdf2PasswordHasher()
                    .matches("synthetic-buyer-password",saved.getPassword()));

            // Least-privilege proof is at PostgreSQL grant layer, not Java.
            assertDenied(limited,"SELECT username FROM accounts");
            assertDenied(limited,"SELECT encoded_credential FROM accounts");
            assertDenied(limited,"INSERT INTO accounts(username,encoded_credential,role) "
                    +"VALUES('root','forged','ADMINISTRATOR')");
            assertDenied(limited,"UPDATE accounts SET role='ADMINISTRATOR' WHERE username='buyer'");
            assertDenied(limited,"UPDATE accounts SET encoded_credential='forged' WHERE username='buyer'");
            assertDenied(limited,"DELETE FROM accounts WHERE username='buyer'");
            assertDenied(limited,"CREATE TABLE rogue_accounts(id integer)");
            assertEquals(1,new JdbcUserAccountRepository(owner).findAll().size());
        } finally {
            try(Connection c=server.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
                if(createdRole) s.execute("DROP ROLE IF EXISTS "+registrar);
            }
        }
    }

    private static String load(String location) throws Exception {
        try(InputStream data=PostgresqlPublicRegistrationIsolationTest.class
                .getResourceAsStream(location)) {
            if(data==null)throw new IllegalStateException("missing test SQL resource");
            return new String(data.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    private static void assertDenied(JdbcDatabase db,String sql) {
        SQLException e=assertThrows(SQLException.class,()->{
            try(Connection c=db.openConnection();
                    PreparedStatement statement=c.prepareStatement(sql)) {
                statement.execute();
            }
        });
        assertEquals("42501",e.getSQLState(),
                "PostgreSQL must deny direct table access");
    }
}
