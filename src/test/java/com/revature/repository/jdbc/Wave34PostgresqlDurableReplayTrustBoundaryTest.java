package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W34.11 disposable PostgreSQL16 journal/replay durability test.
 *
 * A Java test generates both issuer and sender keys. It verifies signatures
 * before creating a separate SQL transaction that atomically consumes one
 * request identity and makes a synthetic ledger+plan posting. The test JVM
 * is NOT a trusted external issuer, the DB principal is privileged, and
 * the checks are NOT an application-enforced production payment path.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlDurableReplayTrustBoundaryTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String OWNER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final String AUDIENCE = "w3411.disposable-ledger";
    private static final String TENANT = "test-tenant";
    private static final String ISSUER_KID = "synthetic-issuer";
    private static final String SUBJECT = "buyer";
    private static final String DDL =
            "docs/audit/wave34/wave34_11_DISPOSABLE_replay_claims.sql";

    @Test
    @Timeout(90)
    void reconnectCannotReconsumeCommittedSignedRequestButDistinctPaymentIsAllowed()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            Credential lease = f.credential();
            AuthorizedRequest first = lease.request(f.ownership, "PAY-000001", "REQ-001");
            f.post(first, false);
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertSqlState("23505", () -> f.post(first, false),
                    "second SQL connection must reject the same signed request");
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());

            // A separate legitimate payment of the SAME amount is not deduped.
            AuthorizedRequest second = lease.request(f.ownership, "PAY-000002", "REQ-002");
            f.post(second, false);
            assertEquals(2, f.claims());
            assertEquals(2, f.postings());
            assertEquals(2, f.paid());
            assertEquals(23998, f.balance());
            assertEquals(5, f.schemaVersion());
            f.evidence("reconnect-and-distinct-payment");
        }
    }

    @Test
    @Timeout(90)
    void concurrentSameSignedRequestHasExactlyOneAtomicLedgerCommit()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            Credential lease = f.credential();
            AuthorizedRequest same = lease.request(f.ownership, "PAY-000001", "REQ-CONCURRENT");
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready = new CountDownLatch(4);
                CountDownLatch fire = new CountDownLatch(1);
                java.util.List<Future<Boolean>> tasks = new java.util.ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    tasks.add(pool.submit(() -> {
                        ready.countDown();
                        if (!fire.await(15, TimeUnit.SECONDS))
                            throw new AssertionError("writer concurrency rendezvous failed");
                        try {
                            f.post(same, false);
                            return true;
                        } catch (SQLException duplicate) {
                            assertEquals("23505", duplicate.getSQLState(),
                                    "expected unique replay identity conflict");
                            return false;
                        }
                    }));
                }
                assertTrue(ready.await(15, TimeUnit.SECONDS));
                fire.countDown();
                int committed = 0;
                for (Future<Boolean> future : tasks) {
                    if (future.get(40, TimeUnit.SECONDS)) committed++;
                }
                assertEquals(1, committed,
                        "transactional PostgreSQL uniqueness prevents duplicate local posting");
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            f.evidence("concurrent-transactional-claim");
        }
    }

    @Test
    @Timeout(90)
    void failedLedgerInsertRollsBackClaimAndPlanAndAllowsControlledRetry()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            AuthorizedRequest request = f.credential().request(
                    f.ownership, "PAY-000001", "REQ-ROLLBACK");
            String original = f.digest();
            assertSqlState("23503", () -> f.post(request, true),
                    "injected invalid FK after plan update must rollback full transaction");
            assertEquals(0, f.claims());
            assertEquals(0, f.postings());
            assertEquals(0, f.paid());
            assertEquals(24000, f.balance());
            assertEquals(original, f.digest(), "original source ledger must remain unchanged");
            f.post(request, false);
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            f.evidence("rollback-and-retry");
        }
    }

    @Test
    @Timeout(90)
    void invalidIssuerSenderAndRequestClaimsCannotReachSqlReservation()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            Credential lease = f.credential();
            AuthorizedRequest valid = lease.request(f.ownership, "PAY-000001", "REQ-TAMPER");
            KeyPair wrongIssuer = keyPair();
            assertThrows(SecurityException.class,
                    () -> f.verify(valid, wrongIssuer.getPublic(), lease.sender.getPublic()));
            KeyPair wrongSender = keyPair();
            assertThrows(SecurityException.class,
                    () -> f.verify(valid, lease.issuer.getPublic(), wrongSender.getPublic()));

            AuthorizedRequest modifiedAmount = new AuthorizedRequest(
                    valid.issuerKid, valid.leaseJti, valid.nonce, valid.requestId,
                    valid.transactionId, valid.subject, valid.tenant, valid.audience,
                    valid.ownershipId, valid.amount+1, valid.epoch, valid.issued,
                    valid.expires, valid.senderFingerprint,
                    valid.issuerSignature, valid.senderSignature);
            assertThrows(SecurityException.class,
                    () -> f.verify(modifiedAmount, lease.issuer.getPublic(),
                            lease.sender.getPublic()));

            AuthorizedRequest modifiedTenant = valid.withTenant("other-tenant");
            assertThrows(SecurityException.class,
                    () -> f.verify(modifiedTenant, lease.issuer.getPublic(),
                            lease.sender.getPublic()));
            assertEquals(0, f.claims());
            assertEquals(0, f.postings());
            assertEquals(0, f.paid());

            f.post(valid, false);
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            f.evidence("trust-fail-closed");
        }
    }

    private interface SqlCall { void call() throws Exception; }
    private static void assertSqlState(String state, SqlCall call, String message) {
        SQLException failed = assertThrows(SQLException.class, call::call, message);
        assertEquals(state, failed.getSQLState());
    }

    private static void requireDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && OWNER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD"))
                && System.getenv("EXPECTED_HEAD") != null
                && System.getenv("EXPECTED_HEAD").matches("[a-f0-9]{40}"),
                "W34.11 SQL claims must only run on pinned disposable PG16 CI");
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
    private static byte[] message(String... values) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(out)) {
                data.writeInt(values.length);
                for (String v : values) {
                    byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
                    data.writeInt(bytes.length);
                    data.write(bytes);
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    private static byte[] signature(KeyPair signer, byte[] data)
            throws GeneralSecurityException {
        Signature ec = Signature.getInstance("SHA256withECDSA");
        ec.initSign(signer.getPrivate());
        ec.update(data);
        return ec.sign();
    }
    private static boolean check(PublicKey pub, byte[] data, byte[] signed)
            throws GeneralSecurityException {
        Signature ec = Signature.getInstance("SHA256withECDSA");
        ec.initVerify(pub);
        ec.update(data);
        return ec.verify(signed);
    }
    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(alphabet[(b >>> 4) & 15]);
            out.append(alphabet[b & 15]);
        }
        return out.toString();
    }
    private static String sha(byte[] bytes) throws GeneralSecurityException {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class AuthorizedRequest {
        final String issuerKid, requestId, transactionId, subject, tenant, audience;
        final UUID leaseJti, nonce;
        final long ownershipId, amount, epoch;
        final Instant issued, expires;
        final String senderFingerprint;
        final byte[] issuerSignature, senderSignature;
        AuthorizedRequest(String kid, UUID jti, UUID nonce, String requestId,
                String transactionId, String subject, String tenant, String audience,
                long ownershipId, long amount, long epoch, Instant issued,
                Instant expires, String senderFingerprint, byte[] issuerSignature,
                byte[] senderSignature) {
            this.issuerKid=kid;this.leaseJti=jti;this.nonce=nonce;
            this.requestId=requestId;this.transactionId=transactionId;
            this.subject=subject;this.tenant=tenant;this.audience=audience;
            this.ownershipId=ownershipId;this.amount=amount;this.epoch=epoch;
            this.issued=issued;this.expires=expires;
            this.senderFingerprint=senderFingerprint;
            this.issuerSignature=issuerSignature.clone();
            this.senderSignature=senderSignature.clone();
        }
        byte[] leaseClaims() {
            return message("W34.11|TEST_LEASE|v1",issuerKid,leaseJti.toString(),
                    subject,tenant,audience,Long.toString(epoch),
                    Long.toString(issued.getEpochSecond()),
                    Long.toString(expires.getEpochSecond()),senderFingerprint);
        }
        byte[] requestClaims() {
            return message("W34.11|TEST_PROOF|v1",leaseJti.toString(),nonce.toString(),
                    requestId,transactionId,subject,tenant,audience,
                    Long.toString(ownershipId),Long.toString(amount),
                    Long.toString(epoch),senderFingerprint);
        }
        AuthorizedRequest withTenant(String replacement) {
            return new AuthorizedRequest(issuerKid,leaseJti,nonce,requestId,
                    transactionId,subject,replacement,audience,ownershipId,amount,
                    epoch,issued,expires,senderFingerprint,issuerSignature,
                    senderSignature);
        }
    }

    private static final class Credential {
        final KeyPair issuer, sender;
        final UUID leaseJti=UUID.randomUUID();
        final long epoch=7;
        final Instant issued=Instant.now().minusSeconds(2);
        final Instant expires=Instant.now().plusSeconds(180);
        Credential() throws GeneralSecurityException {
            issuer=keyPair();
            sender=keyPair();
        }
        AuthorizedRequest request(long ownershipId, String transactionId,
                String requestId) throws Exception {
            String fingerprint=sha(sender.getPublic().getEncoded());
            UUID nonce=UUID.randomUUID();
            AuthorizedRequest unsigned=new AuthorizedRequest(
                    ISSUER_KID,leaseJti,nonce,requestId,transactionId,SUBJECT,
                    TENANT,AUDIENCE,ownershipId,1L,epoch,issued,expires,fingerprint,
                    new byte[0],new byte[0]);
            byte[] issuerProof=signature(issuer,unsigned.leaseClaims());
            byte[] senderProof=signature(sender,unsigned.requestClaims());
            return new AuthorizedRequest(ISSUER_KID,leaseJti,nonce,requestId,
                    transactionId,SUBJECT,TENANT,AUDIENCE,ownershipId,1L,epoch,
                    issued,expires,fingerprint,issuerProof,senderProof);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="w3411_"+UUID.randomUUID().toString().replace("-","");
        final JdbcDatabase admin=new JdbcDatabase(URL,OWNER,PASSWORD);
        final JdbcDatabase scoped=new JdbcDatabase(
                URL+"?currentSchema="+schema,OWNER,PASSWORD);
        long ownership;
        KeyPair trustedIssuer;
        KeyPair trustedSender;

        Fixture() throws Exception {
            try (Connection c=admin.openConnection();Statement s=c.createStatement()) {
                try (ResultSet v=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(v.next());
                    int num=Integer.parseInt(v.getString(1));
                    assertTrue(num>=160000 && num<170000,"PostgreSQL16 is required");
                }
                assertTrue(schema.matches("w3411_[0-9a-f]{32}"));
                s.execute("CREATE SCHEMA "+schema);
            }
            scoped.migrate();
            try (Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.executeUpdate("INSERT INTO accounts(username,encoded_credential,role)"
                        +" VALUES('buyer','CI-only','CUSTOMER')");
            }
            try (Connection c=scoped.openConnection();
                    PreparedStatement p=c.prepareStatement(
                        "INSERT INTO owned_vehicles"
                        +"(owner_username,make,model,vehicle_year,contract_id)"
                        +" VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                        Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1,p.executeUpdate());
                try (ResultSet r=p.getGeneratedKeys()) {
                    assertTrue(r.next());
                    ownership=r.getLong(1);
                }
            }
            try (Connection c=scoped.openConnection();
                    PreparedStatement p=c.prepareStatement(
                        "INSERT INTO payment_plans"
                        +"(ownership_id,purchase_price,payment_months,monthly_payment,"
                        +"amount_paid,balance) VALUES(?,24000,24,1000,0,24000)")) {
                p.setLong(1,ownership);
                assertEquals(1,p.executeUpdate());
            }
            String ddl=Files.readString(Paths.get(DDL),StandardCharsets.UTF_8);
            try (Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute(ddl);
            }
            assertEquals(5,schemaVersion());
        }

        Credential credential() throws Exception {
            Credential pair=new Credential();
            trustedIssuer=pair.issuer;
            trustedSender=pair.sender;
            return pair;
        }

        void verify(AuthorizedRequest r,PublicKey issuer,PublicKey sender)
                throws GeneralSecurityException {
            if (!ISSUER_KID.equals(r.issuerKid)
                    || !SUBJECT.equals(r.subject) || !TENANT.equals(r.tenant)
                    || !AUDIENCE.equals(r.audience) || r.epoch!=7
                    || r.ownershipId!=ownership || r.amount!=1L
                    || r.expires.isBefore(Instant.now())
                    || r.issued.isAfter(Instant.now())
                    || r.expires.isBefore(r.issued)
                    || !sha(sender.getEncoded()).equals(r.senderFingerprint)
                    || !check(issuer,r.leaseClaims(),r.issuerSignature)
                    || !check(sender,r.requestClaims(),r.senderSignature)) {
                throw new SecurityException("W34.11 synthetic lease/proof rejected");
            }
        }

        void post(AuthorizedRequest req, boolean injectBadForeignKey)
                throws Exception {
            verify(req,trustedIssuer.getPublic(),trustedSender.getPublic());
            try (Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement claim=c.prepareStatement(
                            "INSERT INTO w3411_verified_request_claims"
                            +"(issuer_key_id,tenant_id,lease_jti,challenge_nonce,"
                            +"request_id,request_digest) VALUES(?,?,?,?,?,?)")) {
                        claim.setString(1,req.issuerKid);
                        claim.setString(2,req.tenant);
                        claim.setObject(3,req.leaseJti);
                        claim.setObject(4,req.nonce);
                        claim.setString(5,req.requestId);
                        claim.setString(6,sha(req.requestClaims()));
                        assertEquals(1,claim.executeUpdate());
                    }
                    int cumulative, resultingBalance;
                    try (PreparedStatement plan=c.prepareStatement(
                            "UPDATE payment_plans SET amount_paid=amount_paid+1,"
                            +"balance=balance-1 WHERE ownership_id=?"
                            +" RETURNING amount_paid,balance")) {
                        plan.setLong(1,ownership);
                        try (ResultSet updated=plan.executeQuery()) {
                            assertTrue(updated.next());
                            cumulative=updated.getInt(1);
                            resultingBalance=updated.getInt(2);
                            assertFalse(updated.next());
                        }
                    }
                    try (PreparedStatement ledger=c.prepareStatement(
                            "INSERT INTO payment_transactions"
                            +"(transaction_id,customer_name,ownership_id,contract_id,"
                            +"amount,cumulative_paid,resulting_balance,recorded_at)"
                            +" VALUES(?,'buyer',?,'ACQ-000001',1,?,?,?)")) {
                        ledger.setString(1,req.transactionId);
                        ledger.setLong(2,injectBadForeignKey?ownership+999999:ownership);
                        ledger.setInt(3,cumulative);
                        ledger.setInt(4,resultingBalance);
                        ledger.setTimestamp(5,Timestamp.from(
                                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(cumulative)));
                        assertEquals(1,ledger.executeUpdate());
                    }
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }

        int claims() throws SQLException {
            return scalar("SELECT COUNT(*) FROM w3411_verified_request_claims");
        }
        int postings() throws SQLException {
            return scalar("SELECT COUNT(*) FROM payment_transactions");
        }
        int paid() throws SQLException {
            return scalar("SELECT amount_paid FROM payment_plans");
        }
        int balance() throws SQLException {
            return scalar("SELECT balance FROM payment_plans");
        }
        int schemaVersion() throws SQLException {
            return scalar("SELECT max(version) FROM schema_history");
        }
        int scalar(String sql) throws SQLException {
            try (Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet r=s.executeQuery(sql)) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }
        String digest() throws GeneralSecurityException,SQLException {
            String raw=claims()+":"+postings()+":"+paid()+":"+balance();
            return sha(raw.getBytes(StandardCharsets.UTF_8));
        }
        void evidence(String scenario) throws Exception {
            Path path=Paths.get("target/wave34-11-evidence");
            Files.createDirectories(path);
            String json="{\n"
                    +"\"scenario\":\""+scenario+"\",\n"
                    +"\"source_head\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                    +"\"fixture\":\"DISPOSABLE_PG16_JCA_REPLAY_ONLY\",\n"
                    +"\"schema_version\":"+schemaVersion()+",\n"
                    +"\"synthetic_claims\":"+claims()+",\n"
                    +"\"synthetic_postings\":"+postings()+",\n"
                    +"\"synthetic_balance\":"+balance()+",\n"
                    +"\"source_digest_sha256\":\""+digest()+"\",\n"
                    +"\"independent_issuer_verified\":false,\n"
                    +"\"production_trusted_sql_principal\":false,\n"
                    +"\"real_provider_verified\":false,\n"
                    +"\"production_authorized\":false,\n"
                    +"\"release_disposition\":\"NO-GO\"\n"
                    +"}\n";
            Files.write(path.resolve(scenario+".json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }

        @Override public void close() throws Exception {
            try (Connection c=admin.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
            }
        }
    }
}
