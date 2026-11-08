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
import java.time.Clock;
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

/** Real disposable PG16 negative and positive signing-key lifecycle matrix. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlVersionedCredentialKeyLifecycleTest {
    @Test
    @Timeout(140)
    void keyVersionExpiryVerifyOnlyRevocationAndReplayAreEnforcedByPostgres()
            throws Exception {
        String url=System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName=System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword=System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "jdbc:postgresql://localhost:5432/dealership_ci".equals(url)
                && "dealership_ci_owner".equals(ownerName)
                && "ci-only-postgres-fixture".equals(ownerPassword),
                "only isolated PostgreSQL16 GitHub Actions test fixture");
        String suffix=UUID.randomUUID().toString().replace("-","");
        String schema="w29_"+suffix, verifier="w29read_"+suffix, writer="w29exec_"+suffix, issuer="w31issue_"+suffix;
        String readPassword="ci-only-private-verifier";
        String execPassword="ci-only-versioned-intent-executor";
        String issuePassword="ci-only-independent-issuer";
        byte[] key1=key(),key2=key(),key3=key(),revokedKey=key();
        long now=Clock.systemUTC().instant().getEpochSecond();
        JdbcDatabase server=new JdbcDatabase(url,ownerName,ownerPassword);
        boolean rolesCreated=false;
        try {
            try(Connection c=server.openConnection();Statement s=c.createStatement()) {
                try(ResultSet v=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(v.next());
                    int actual=Integer.parseInt(v.getString(1));
                    assertTrue(actual>=160000&&actual<170000);
                }
                s.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
                s.execute("CREATE SCHEMA "+schema);
                s.execute("CREATE ROLE "+verifier+" LOGIN PASSWORD '"+readPassword+"'");
                s.execute("CREATE ROLE "+writer+" LOGIN PASSWORD '"+execPassword+"'");
                s.execute("CREATE ROLE "+issuer+" LOGIN PASSWORD '" +issuePassword+"'");
                rolesCreated=true;
            }
            String scoped=url+"?currentSchema="+schema;
            JdbcDatabase owner=new JdbcDatabase(scoped,ownerName,ownerPassword);
            owner.migrate();
            JdbcUserAccountRepository accounts=new JdbcUserAccountRepository(owner);
            TestAccounts.seed(accounts,AccountRole.CUSTOMER,
                    "buyer","initial-customer-password");
            TestAccounts.seed(accounts,AccountRole.ADMINISTRATOR,
                    "root","synthetic-root-password");

            try(Connection c=server.openConnection();Statement s=c.createStatement()) {
                c.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE "+schema+".w29_signing_keys("
                            +"key_id INTEGER PRIMARY KEY, key_bytes BYTEA NOT NULL CHECK (octet_length(key_bytes)>=32),"
                            +"state TEXT NOT NULL CHECK(state IN ('SIGNING_ACTIVE','VERIFY_ONLY','REVOKED')),"
                            +"not_before_epoch BIGINT NOT NULL, not_after_epoch BIGINT NOT NULL,"
                            +"signing_disabled_epoch BIGINT,"
                            +"CHECK(not_after_epoch>not_before_epoch))");
                    s.execute("CREATE TABLE "+schema+".w29_used_nonces("
                            +"nonce UUID PRIMARY KEY, key_id INTEGER NOT NULL, username TEXT NOT NULL,"
                            +"recorded_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                    // Owner-only ledger for independent issuance receipts.
                    // A leaked retired signing key cannot backdate an unregistered nonce.
                    s.execute("CREATE TABLE "+schema+".w30_issued_intents("
                            +"nonce UUID PRIMARY KEY, key_id INTEGER NOT NULL,"
                            +"username TEXT NOT NULL, issued_epoch BIGINT NOT NULL,"
                            +"signed_hmac TEXT NOT NULL,"
                            +"registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())");
                    keyRow(c,schema,1,key1,"SIGNING_ACTIVE",now-400,now+400,null);
                    keyRow(c,schema,2,key2,"SIGNING_ACTIVE",now-400,now+400,null);
                    keyRow(c,schema,3,key3,"SIGNING_ACTIVE",now-400,now+400,null);
                    keyRow(c,schema,4,revokedKey,"REVOKED",now-400,now+400,null);

                    s.execute(resource("/postgresql/wave29_versioned_credential_intent_ci.sql")
                            .replace("__SCHEMA__",schema));
                    s.execute(resource("/postgresql/wave31_authenticated_issuance_ci.sql")
                            .replace("__SCHEMA__",schema));
                    s.execute("REVOKE ALL ON SCHEMA "+schema+" FROM PUBLIC");
                    s.execute("REVOKE ALL ON FUNCTION "+schema+".register_credential_issuance_v31("
                            +"text,text,integer,text,uuid,text,text,uuid,bigint,bigint,text) FROM PUBLIC");
                    s.execute("REVOKE ALL ON FUNCTION "+schema+".rotate_customer_credential_v29("
                            +"text,text,integer,text,uuid,text,text,uuid,bigint,bigint,text) FROM PUBLIC");
                    for(String role:new String[]{verifier,writer,issuer}) {
                        s.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
                        s.execute("GRANT SELECT ON "+schema+".schema_history TO "+role);
                    }
                    s.execute("GRANT SELECT(username,encoded_credential,role,session_revision) "
                            +"ON "+schema+".accounts TO "+verifier);
                    s.execute("GRANT EXECUTE ON FUNCTION "+schema+".rotate_customer_credential_v29("
                            +"text,text,integer,text,uuid,text,text,uuid,bigint,bigint,text) TO "+writer);
                    s.execute("GRANT EXECUTE ON FUNCTION "+schema+".register_credential_issuance_v31("
                            +"text,text,integer,text,uuid,text,text,uuid,bigint,bigint,text) TO "+issuer);
                    c.commit();
                } catch(SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }

            JdbcDatabase readDb=new JdbcDatabase(scoped,verifier,readPassword);
            JdbcDatabase executeDb=new JdbcDatabase(scoped,writer,execPassword);
            JdbcDatabase issuerDb=new JdbcDatabase(scoped,issuer,issuePassword);
            assertDenied42501(executeDb,"SELECT encoded_credential FROM accounts");
            assertDenied42501(executeDb,"SELECT key_bytes FROM w29_signing_keys");
            assertDenied42501(executeDb,"SELECT nonce FROM w29_used_nonces");
            assertDenied42501(executeDb,"SELECT nonce FROM w30_issued_intents");
            assertDenied42501(issuerDb,"SELECT nonce FROM w30_issued_intents");
            assertDenied42501(issuerDb,"SELECT key_bytes FROM w29_signing_keys");
            assertDenied42501(issuerDb,"SELECT encoded_credential FROM accounts");
            assertDenied42501(executeDb,
                    "INSERT INTO w30_issued_intents"
                    +"(nonce,key_id,username,issued_epoch,signed_hmac)"
                    +" VALUES(gen_random_uuid(),1,'buyer',0,'forged')");
            assertDenied42501(executeDb,
                    "SELECT register_credential_issuance_v31("
                    +"'credential-service','customer-password-rotation',1,'buyer',"
                    +"NULL::uuid,NULL::text,NULL::text,NULL::uuid,0,0,'forged')");
            assertDenied42501(issuerDb,
                    "UPDATE w30_issued_intents SET username='root' WHERE FALSE");
            assertDenied42501(issuerDb,
                    "DELETE FROM w30_issued_intents WHERE FALSE");
            assertDenied42501(issuerDb,
                    "SELECT rotate_customer_credential_v29("
                    +"'credential-service','customer-password-rotation',1,'buyer',"
                    +"NULL::uuid,NULL::text,NULL::text,NULL::uuid,0,0,'forged')");
            assertDenied42501(executeDb,
                    "UPDATE accounts SET encoded_credential='forged' WHERE username='buyer'");
            assertDenied42501(executeDb,
                    "UPDATE accounts SET role='ADMINISTRATOR' WHERE username='buyer'");
            assertDenied42501(readDb,
                    "UPDATE accounts SET encoded_credential='forged' WHERE username='buyer'");

            Pbkdf2PasswordHasher passwordHasher=new Pbkdf2PasswordHasher();
            User buyer=account(accounts);
            String newHash=passwordHasher.hash("customer-password-v2");
            UUID beforeRevision=UUID.fromString(buyer.getSessionRevision());

            // Unknown key ID, expired, future-dated, excessive TTL, and a
            // revoked key are rejected, even with a correct HMAC.
            assertFalse(call(executeDb,99,key2,buyer,newHash,UUID.randomUUID(),
                    now,now+45,"credential-service","customer-password-rotation"));
            assertFalse(call(executeDb,2,key2,buyer,newHash,UUID.randomUUID(),
                    now-200,now-130,"credential-service","customer-password-rotation"));
            assertFalse(call(executeDb,2,key2,buyer,newHash,UUID.randomUUID(),
                    now+60,now+100,"credential-service","customer-password-rotation"));
            assertFalse(call(executeDb,2,key2,buyer,newHash,UUID.randomUUID(),
                    now,now+500,"credential-service","customer-password-rotation"));
            assertFalse(call(executeDb,4,revokedKey,buyer,newHash,UUID.randomUUID(),
                    now,now+45,"credential-service","customer-password-rotation"));
            assertFalse(call(executeDb,2,key2,buyer,newHash,UUID.randomUUID(),
                    now,now+45,"other-issuer","customer-password-rotation"));
            assertFalse(call(executeDb,2,key2,buyer,newHash,UUID.randomUUID(),
                    now,now+45,"credential-service","admin-promotion"));

            // Wave30: register a real owner-held issuance receipt while key1
            // is still SIGNING_ACTIVE, then retire the key. A compromised
            // retired key alone cannot fabricate an earlier receipt by
            // lying about issued_epoch inside a correctly signed HMAC.
            UUID preCutoverNonce=UUID.randomUUID();
            long preIssued=Clock.systemUTC().instant().getEpochSecond();
            long cutover=preIssued+4;
            String preIntent=JdbcVersionedSignedCustomerCredentialWriter.canonical(
                    1,buyer.getUsername(),UUID.fromString(buyer.getSessionRevision()),
                    buyer.getPassword(),newHash,preCutoverNonce,preIssued,preIssued+90);
            String preMac=mac(key1,preIntent);
            // A distinct issuer DB login may only EXECUTE this owner function.
            // It cannot read the key or account and cannot insert receipts.
            assertTrue(registerIssue(issuerDb,1,buyer,newHash,preCutoverNonce,
                    preIssued,preIssued+90,preMac));
            assertFalse(registerIssue(issuerDb,1,buyer,newHash,preCutoverNonce,
                    preIssued,preIssued+90,preMac),"duplicate receipt must fail");
            assertFalse(registerIssue(issuerDb,1,buyer,newHash,UUID.randomUUID(),
                    preIssued,preIssued+90,preMac),"mismatched nonce/HMAC must fail");
            try(Connection c=server.openConnection();
                    PreparedStatement rotate=c.prepareStatement(
                        "UPDATE "+schema+".w29_signing_keys "
                        +"SET state='VERIFY_ONLY', signing_disabled_epoch=? "
                        +"WHERE key_id=1 AND state='SIGNING_ACTIVE'")) {
                rotate.setLong(1,cutover);
                assertEquals(1,rotate.executeUpdate());
            }
            // Wait until the cutover point, not just the state switch. The
            // independently recorded receipt predates cutoff by design.
            for(int attempts=0;attempts<40
                    && Clock.systemUTC().instant().getEpochSecond()<cutover;attempts++) {
                Thread.sleep(200);
            }
            assertTrue(Clock.systemUTC().instant().getEpochSecond()>=cutover);
            long postIssued=Clock.systemUTC().instant().getEpochSecond();
            assertFalse(call(executeDb,1,key1,buyer,newHash,UUID.randomUUID(),
                    postIssued,postIssued+45,"credential-service","customer-password-rotation"));
            // This malicious request is backdated and has a VALID old-key MAC,
            // but lacks an independent pre-cutover issuance receipt.
            assertFalse(call(executeDb,1,key1,buyer,newHash,UUID.randomUUID(),
                    preIssued,preIssued+90,"credential-service","customer-password-rotation"));
            UUID lateNonce=UUID.randomUUID();
            String lateCanonical=JdbcVersionedSignedCustomerCredentialWriter.canonical(
                    1,buyer.getUsername(),UUID.fromString(buyer.getSessionRevision()),
                    buyer.getPassword(),newHash,lateNonce,preIssued,preIssued+90);
            assertFalse(registerIssue(issuerDb,1,buyer,newHash,lateNonce,
                    preIssued,preIssued+90,mac(key1,lateCanonical)),
                    "issuer cannot register a backdated request after key cutoff");
            assertTrue(call(executeDb,1,key1,buyer,newHash,preCutoverNonce,
                    preIssued,preIssued+90,"credential-service","customer-password-rotation"));
            User afterFirst=account(accounts);
            assertNotEquals(buyer.getSessionRevision(),afterFirst.getSessionRevision());
            assertTrue(passwordHasher.matches("customer-password-v2",afterFirst.getPassword()));

            // A used nonce never becomes valid again even if the password,
            // revision and MAC are all fresh. The newer signing key is active.
            String thirdHash=passwordHasher.hash("customer-password-v3");
            assertFalse(call(executeDb,2,key2,afterFirst,thirdHash,preCutoverNonce,
                    now,now+45,"credential-service","customer-password-rotation"));
            assertEquals(afterFirst.getPassword(),account(accounts).getPassword());

            // The normal trusted private broker can use the active new key
            // to change plaintext and rotate the account session generation.
            UserAccountRepository privateReader=new JdbcPrivateAuthenticationReader(readDb);
            PrivateCustomerCredentialChangeService broker=
                new PrivateCustomerCredentialChangeService(privateReader,
                    new JdbcVersionedSignedCustomerCredentialWriter(
                            executeDb,2,key2,Clock.systemUTC()));
            assertThrows(SecurityException.class,()->broker.changePassword(
                    "buyer","wrong-password","should-not-work"));
            assertThrows(SecurityException.class,()->broker.changePassword(
                    "root","synthetic-root-password","should-not-work"));
            assertTrue(broker.changePassword(
                    "buyer","customer-password-v2","customer-password-v3"));
            User afterSecond=account(accounts);
            assertNotEquals(afterFirst.getSessionRevision(),afterSecond.getSessionRevision());
            assertTrue(passwordHasher.matches("customer-password-v3",afterSecond.getPassword()));
            assertThrows(SecurityException.class,()->broker.changePassword(
                    "buyer","customer-password-v2","replayed-password"));

            // An emergency revocation of the formerly active key takes effect
            // immediately, including previously issued, still-unexpired intents.
            try(Connection c=server.openConnection();
                    Statement admin=c.createStatement()) {
                assertEquals(1,admin.executeUpdate("UPDATE "+schema+
                        ".w29_signing_keys SET state='REVOKED' WHERE key_id=2"));
            }
            String fourthHash=passwordHasher.hash("customer-password-v4");
            assertFalse(call(executeDb,2,key2,afterSecond,fourthHash,UUID.randomUUID(),
                    now,now+45,"credential-service","customer-password-rotation"));
            assertThrows(SecurityException.class,()->broker.changePassword(
                    "buyer","customer-password-v3","customer-password-v4"));
            assertEquals(afterSecond.getPassword(),account(accounts).getPassword());

            // The owner-activated replacement key can independently authorize
            // the next customer credential update, with no raw DML granted.
            PrivateCustomerCredentialChangeService replacementBroker=
                new PrivateCustomerCredentialChangeService(privateReader,
                    new JdbcVersionedSignedCustomerCredentialWriter(
                            executeDb,3,key3,Clock.systemUTC()));
            assertTrue(replacementBroker.changePassword(
                    "buyer","customer-password-v3","customer-password-v4"));
            User finalUser=account(accounts);
            assertNotEquals(beforeRevision.toString(),finalUser.getSessionRevision());
            assertTrue(passwordHasher.matches("customer-password-v4",finalUser.getPassword()));
            assertEquals(AccountRole.ADMINISTRATOR,
                    accounts.findByUsername("root").orElseThrow(AssertionError::new).getRole());
            assertEquals(2,accounts.findAll().size());
        } finally {
            try(Connection c=server.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
                if(rolesCreated) {
                    s.execute("DROP ROLE IF EXISTS "+verifier);
                    s.execute("DROP ROLE IF EXISTS "+writer);
                    s.execute("DROP ROLE IF EXISTS "+issuer);
                }
            }
            java.util.Arrays.fill(key1,(byte)0);
            java.util.Arrays.fill(key2,(byte)0);
            java.util.Arrays.fill(key3,(byte)0);
            java.util.Arrays.fill(revokedKey,(byte)0);
        }
    }

    private static byte[] key(){
        byte[] bytes=new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static User account(JdbcUserAccountRepository accounts) {
        return accounts.findByUsername("buyer").orElseThrow(AssertionError::new);
    }

    private static void keyRow(Connection c,String schema,int id,byte[] key,
                               String state,long start,long end,Long cutoff)
            throws SQLException {
        try(PreparedStatement insert=c.prepareStatement(
                "INSERT INTO "+schema+".w29_signing_keys"
                +"(key_id,key_bytes,state,not_before_epoch,not_after_epoch,signing_disabled_epoch)"
                +" VALUES(?,?,?,?,?,?)")) {
            insert.setInt(1,id);
            insert.setBytes(2,key);
            insert.setString(3,state);
            insert.setLong(4,start);
            insert.setLong(5,end);
            if(cutoff==null)insert.setNull(6,java.sql.Types.BIGINT);
            else insert.setLong(6,cutoff);
            assertEquals(1,insert.executeUpdate());
        }
    }

    private static boolean call(JdbcDatabase db,int keyId,byte[] signingKey,User user,
            String replacement,UUID nonce,long issued,long expires,
            String issuer,String purpose) throws Exception {
        UUID revision=UUID.fromString(user.getSessionRevision());
        // Sign the correct canonical version. A wrong issuer/purpose is
        // independently rejected by DB even when signature is for the
        // legitimate domain. Signed alternatives must not be accepted.
        // For NEGATIVE expiry/TTL tests, intentionally forge an otherwise
        // valid HMAC around a time-invalid intent. The production Java signer
        // correctly refuses to create these inputs; the test must bypass that
        // client-side guard to exercise PostgreSQL's independent expiry check.
        String message="W29v1|credential-service|customer-password-rotation|"
                +keyId+"|"+user.getUsername()+"|"+revision+"|"
                +user.getPassword()+"|"+replacement+"|"+nonce+"|"
                +issued+"|"+expires;
        if(keyId>=1 && expires>issued && expires-issued<=120) {
            assertEquals(JdbcVersionedSignedCustomerCredentialWriter.canonical(
                    keyId,user.getUsername(),revision,user.getPassword(),
                    replacement,nonce,issued,expires),message);
        }
        String signature=mac(signingKey,message);
        try(Connection c=db.openConnection();
                PreparedStatement sql=c.prepareStatement(
                    "SELECT rotate_customer_credential_v29(?,?,?,?,?,?,?,?,?,?,?)")) {
            sql.setString(1,issuer);
            sql.setString(2,purpose);
            sql.setInt(3,keyId);
            sql.setString(4,user.getUsername());
            sql.setObject(5,revision);
            sql.setString(6,user.getPassword());
            sql.setString(7,replacement);
            sql.setObject(8,nonce);
            sql.setLong(9,issued);
            sql.setLong(10,expires);
            sql.setString(11,signature);
            try(ResultSet results=sql.executeQuery()) {
                assertTrue(results.next());
                boolean value=results.getBoolean(1);
                assertFalse(results.next());
                return value;
            }
        }
    }

    private static boolean registerIssue(JdbcDatabase issuerDb,int keyId,User snapshot,
            String replacement,UUID nonce,long issued,long expires,String signature)
            throws SQLException {
        try(Connection c=issuerDb.openConnection();
                PreparedStatement stmt=c.prepareStatement(
                    "SELECT register_credential_issuance_v31(?,?,?,?,?,?,?,?,?,?,?)")) {
            stmt.setString(1,"credential-service");
            stmt.setString(2,"customer-password-rotation");
            stmt.setInt(3,keyId);
            stmt.setString(4,snapshot.getUsername());
            stmt.setObject(5,UUID.fromString(snapshot.getSessionRevision()));
            stmt.setString(6,snapshot.getPassword());
            stmt.setString(7,replacement);
            stmt.setObject(8,nonce);
            stmt.setLong(9,issued);
            stmt.setLong(10,expires);
            stmt.setString(11,signature);
            try(ResultSet result=stmt.executeQuery()) {
                assertTrue(result.next());
                boolean success=result.getBoolean(1);
                assertFalse(result.next());
                return success;
            }
        }
    }

    private static String mac(byte[] key,String message) throws Exception {
        Mac engine=Mac.getInstance("HmacSHA256");
        engine.init(new SecretKeySpec(key,"HmacSHA256"));
        byte[] result=engine.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder text=new StringBuilder(64);
        for(byte value:result)text.append(String.format("%02x",value&255));
        return text.toString();
    }

    private static String resource(String path) throws Exception {
        try(InputStream stream=PostgresqlVersionedCredentialKeyLifecycleTest.class
                .getResourceAsStream(path)) {
            if(stream==null)throw new IllegalStateException("missing SQL fixture "+path);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    private static void assertDenied42501(JdbcDatabase db,String sql) {
        SQLException denied=assertThrows(SQLException.class,()->{
            try(Connection c=db.openConnection();
                    PreparedStatement p=c.prepareStatement(sql)) {
                p.execute();
            }
        });
        assertEquals("42501",denied.getSQLState());
    }
}
