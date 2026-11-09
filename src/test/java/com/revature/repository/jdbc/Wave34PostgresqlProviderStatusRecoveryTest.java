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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * W34.13 fake-provider status query and synthetic financial reconciliation.
 *
 * All provider data, P-256 signer keys and status transitions are generated
 * inside one JUnit process. No provider network, charge, merchant trust,
 * customer data, financial currency approval or production schema is touched.
 * A privileged CI database connection can bypass this test-only verifier.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlProviderStatusRecoveryTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final String PROVIDER = "synthetic-provider";
    private static final String MERCHANT = "synthetic-merchant";
    private static final String CURRENCY = "USD";
    private static final String SUBJECT = "buyer";
    private static final Instant TEST_NOW = Instant.parse("2026-11-09T19:00:00Z");
    private static final String DDL =
            "docs/audit/wave34/wave34_13_DISPOSABLE_provider_capture_recovery.sql";

    @Test
    @Timeout(90)
    void missingCaptureStatusIsHeldUntilFreshSignedProviderQueryConfirmsCapture()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            Request request = f.request("REQ-ACK-LOST", "PROVIDER-001", "PAY-000001");
            assertThrows(SecurityException.class, () -> f.recover(request));
            assertEquals(0, f.claims());
            f.provider.store(request, "PENDING");
            assertThrows(SecurityException.class, () -> f.recover(request));
            assertEquals(0, f.postings());

            // Provider captured independently; the simulated caller lost its
            // original acknowledgement. Recovery uses READ-ONLY status lookup,
            // never a second provider charge or capture attempt.
            f.provider.simulateCaptureWithoutAcknowledgement(request);
            assertEquals("PAY-000001", f.recover(request));
            assertEquals("PAY-000001", f.recover(request));
            assertEquals(1, f.provider.syntheticCaptureCount);
            assertEquals(0, f.provider.recoveryCaptureCalls);
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            assertEquals(5, f.schemaVersion());
            f.evidence("unknown-ack-read-only-provider-query");
        }
    }

    @Test
    @Timeout(90)
    void pendingUnknownDeclinedRefundedOrDisputedStatusNeverPostsAsNewPayment()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            String[] statuses = {
                    "PENDING", "UNKNOWN", "DECLINED", "REFUNDED", "DISPUTED"
            };
            for (int i = 0; i < statuses.length; i++) {
                Request req = f.request("REQ-HOLD-" + i, "PROVIDER-HOLD-" + i,
                        "PAY-00000" + (i + 1));
                f.provider.store(req, statuses[i]);
                assertThrows(SecurityException.class, () -> f.recover(req));
                assertEquals(0, f.claims(), "status " + statuses[i] + " must HOLD");
            }
            assertEquals(0, f.postings());
            assertEquals(0, f.paid());
            assertEquals(24000, f.balance());
            f.evidence("noncaptured-and-reversal-hold");
        }
    }

    @Test
    @Timeout(90)
    void forgedOrMismatchedProviderClaimsCannotAuthorizeLedgerPosting()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            Request request = f.request("REQ-TRUST", "PROVIDER-TRUST",
                    "PAY-000001");
            Snapshot signed = f.provider.store(request, "CAPTURED");
            assertThrows(SecurityException.class, () ->
                    f.recoverVerifiedSnapshot(request, signed.withAmount(2)));
            assertThrows(SecurityException.class, () ->
                    f.recoverVerifiedSnapshot(request, signed.withCurrency("EUR")));
            assertThrows(SecurityException.class, () ->
                    f.recoverVerifiedSnapshot(request, signed.withOwner(f.ownership + 1)));
            assertThrows(SecurityException.class, () ->
                    f.recoverVerifiedSnapshot(request, signed.withMerchant("unknown-merchant")));
            assertThrows(SecurityException.class, () ->
                    f.recoverVerifiedSnapshot(request, signed.withStatus("PENDING")));

            KeyPair forged = keyPair();
            Snapshot forgedIssuer = Snapshot.signed(request, "CAPTURED", forged);
            assertThrows(SecurityException.class, () ->
                    f.recoverVerifiedSnapshot(request, forgedIssuer));
            // A validly signed capture for another merchant/request is also held.
            Request wrongRequest = f.request("REQ-OTHER", "PROVIDER-OTHER",
                    "PAY-000099");
            Snapshot wrongSigned = f.provider.store(wrongRequest, "CAPTURED");
            assertThrows(SecurityException.class, () ->
                    f.recoverVerifiedSnapshot(request, wrongSigned));
            assertEquals(0, f.claims());
            assertEquals(0, f.postings());
            assertEquals(0, f.paid());

            assertEquals("PAY-000001", f.recoverVerifiedSnapshot(request, signed));
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            f.evidence("signed-identity-and-financial-claims");
        }
    }

    @Test
    @Timeout(90)
    void concurrentCaptureReplayAndConflictingProviderIdsFailClosedWithAtomicRollback()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            Request original = f.request("REQ-CONCURRENT", "PROVIDER-CAPTURE-A",
                    "PAY-000001");
            f.provider.store(original, "CAPTURED");
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready = new CountDownLatch(4);
                CountDownLatch start = new CountDownLatch(1);
                java.util.List<Future<String>> all = new java.util.ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    all.add(pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("test concurrent setup failed");
                        return f.recover(original);
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                for (Future<String> item : all)
                    assertEquals("PAY-000001", item.get(30, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());

            // Same provider charge under a different client request is not
            // silently accepted as a new payment.
            Request duplicateProvider = f.request("REQ-ANOTHER",
                    "PROVIDER-CAPTURE-A", "PAY-000099");
            f.provider.store(duplicateProvider, "CAPTURED");
            assertThrows(SecurityException.class, () -> f.recover(duplicateProvider));

            // Same request ID with a different provider transaction also HOLD.
            Request conflictingRequest = f.request("REQ-CONCURRENT",
                    "PROVIDER-NEW-CHARGE", "PAY-000098");
            f.provider.store(conflictingRequest, "CAPTURED");
            assertThrows(SecurityException.class, () -> f.recover(conflictingRequest));

            // A separate provider capture can be valid, but a duplicate local
            // ledger ID causes PostgreSQL 23505 and rolls back receipt/plan.
            Request badLedger = f.request("REQ-LEDGER-CONFLICT",
                    "PROVIDER-CAPTURE-B", "PAY-000001");
            f.provider.store(badLedger, "CAPTURED");
            assertSqlState("23505", () -> f.recover(badLedger));
            assertEquals(1, f.claims());
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());

            Request distinct = f.request("REQ-DISTINCT", "PROVIDER-CAPTURE-C",
                    "PAY-000002");
            f.provider.store(distinct, "CAPTURED");
            assertEquals("PAY-000002", f.recover(distinct));
            assertEquals(2, f.claims());
            assertEquals(2, f.postings());
            assertEquals(2, f.paid());
            assertEquals(23998, f.balance());
            f.evidence("concurrent-provider-replay-and-rollback");
        }
    }

    private interface SqlTask { void run() throws Exception; }
    private static void assertSqlState(String state, SqlTask task) {
        SQLException actual = assertThrows(SQLException.class, task::run);
        assertEquals(state, actual.getSQLState());
    }

    private static void requiredDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD"))
                && System.getenv("EXPECTED_HEAD") != null
                && System.getenv("EXPECTED_HEAD").matches("[0-9a-f]{40}"),
                "only pinned disposable PostgreSQL16 GitHub Actions is permitted");
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
    private static byte[] canonical(String... fields) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream writer = new DataOutputStream(buffer)) {
                writer.writeInt(fields.length);
                for (String field : fields) {
                    byte[] data = field.getBytes(StandardCharsets.UTF_8);
                    writer.writeInt(data.length);
                    writer.write(data);
                }
            }
            return buffer.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static byte[] sign(KeyPair key, byte[] message)
            throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key.getPrivate());
        signature.update(message);
        return signature.sign();
    }
    private static boolean signatureValid(PublicKey key, byte[] message,
            byte[] signed) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(message);
        return verifier.verify(signed);
    }
    private static String sha(byte[] bytes) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest)
            value.append(hex[(b >>> 4) & 15]).append(hex[b & 15]);
        return value.toString();
    }

    private static final class Request {
        final String requestId,providerTransactionId,localTransactionId;
        final long ownershipId;
        Request(String requestId, String providerTransactionId,
                String localTransactionId, long ownershipId) {
            this.requestId=requestId;
            this.providerTransactionId=providerTransactionId;
            this.localTransactionId=localTransactionId;
            this.ownershipId=ownershipId;
        }
    }

    private static final class Snapshot {
        final String provider,merchant,requestId,providerTransactionId;
        final String subject,currency,status;
        final long ownershipId,amountMinor;
        final Instant capturedObservedAt;
        final byte[] signature;

        Snapshot(String provider,String merchant,String requestId,
                String providerTransactionId,String subject,long ownershipId,
                long amountMinor,String currency,String status,
                Instant observed,byte[] signature) {
            this.provider=provider;this.merchant=merchant;
            this.requestId=requestId;this.providerTransactionId=providerTransactionId;
            this.subject=subject;this.ownershipId=ownershipId;
            this.amountMinor=amountMinor;this.currency=currency;this.status=status;
            this.capturedObservedAt=observed;this.signature=signature.clone();
        }
        byte[] payload() {
            return canonical("W34.13|FAKE_PROVIDER_RECEIPT|v1",
                    provider,merchant,requestId,providerTransactionId,subject,
                    Long.toString(ownershipId),Long.toString(amountMinor),currency,
                    status,capturedObservedAt.toString());
        }
        static Snapshot signed(Request request,String status,KeyPair key)
                throws GeneralSecurityException {
            Snapshot unsigned=new Snapshot(PROVIDER,MERCHANT,request.requestId,
                    request.providerTransactionId,SUBJECT,request.ownershipId,
                    1,CURRENCY,status,TEST_NOW,new byte[0]);
            return new Snapshot(unsigned.provider,unsigned.merchant,unsigned.requestId,
                    unsigned.providerTransactionId,unsigned.subject,unsigned.ownershipId,
                    unsigned.amountMinor,unsigned.currency,unsigned.status,
                    unsigned.capturedObservedAt,sign(key,unsigned.payload()));
        }
        Snapshot changed(String merchant,long ownershipId,long amountMinor,
                String currency,String status) {
            return new Snapshot(provider,merchant,requestId,providerTransactionId,
                    subject,ownershipId,amountMinor,currency,status,
                    capturedObservedAt,signature);
        }
        Snapshot withAmount(long a) {
            return changed(merchant,ownershipId,a,currency,status);
        }
        Snapshot withCurrency(String c) {
            return changed(merchant,ownershipId,amountMinor,c,status);
        }
        Snapshot withOwner(long id) {
            return changed(merchant,id,amountMinor,currency,status);
        }
        Snapshot withMerchant(String id) {
            return changed(id,ownershipId,amountMinor,currency,status);
        }
        Snapshot withStatus(String value) {
            return changed(merchant,ownershipId,amountMinor,currency,value);
        }
    }

    private static final class FakeProvider {
        final KeyPair key;
        final Map<String,Snapshot> states = new ConcurrentHashMap<>();
        int syntheticCaptureCount;
        int recoveryCaptureCalls;
        FakeProvider() throws GeneralSecurityException { key=keyPair(); }
        Snapshot store(Request request,String status) throws GeneralSecurityException {
            Snapshot result=Snapshot.signed(request,status,key);
            states.put(request.requestId,result);
            return result;
        }
        void simulateCaptureWithoutAcknowledgement(Request request)
                throws GeneralSecurityException {
            syntheticCaptureCount++;
            store(request,"CAPTURED");
        }
        Snapshot queryReadOnly(String requestId) { return states.get(requestId); }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="w3413_"+UUID.randomUUID().toString().replace("-","");
        final JdbcDatabase owner=new JdbcDatabase(URL,USER,PASSWORD);
        final JdbcDatabase scoped=new JdbcDatabase(
                URL+"?currentSchema="+schema,USER,PASSWORD);
        final FakeProvider provider=new FakeProvider();
        long ownership;

        Fixture() throws Exception {
            assertTrue(schema.matches("w3413_[0-9a-f]{32}"));
            try (Connection c=owner.openConnection();Statement s=c.createStatement()) {
                try (ResultSet rs=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(rs.next());
                    int ver=Integer.parseInt(rs.getString(1));
                    assertTrue(ver>=160000 && ver<170000,"actual PostgreSQL16 required");
                }
                s.execute("CREATE SCHEMA "+schema);
            }
            scoped.migrate();
            try (Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute("INSERT INTO accounts(username,encoded_credential,role)"
                        +" VALUES('buyer','CI-only','CUSTOMER')");
            }
            try (Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "INSERT INTO owned_vehicles(owner_username,make,model,vehicle_year,contract_id)"
                    +" VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                    Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1,p.executeUpdate());
                try (ResultSet rs=p.getGeneratedKeys()) {
                    assertTrue(rs.next());
                    ownership=rs.getLong(1);
                }
            }
            try (Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "INSERT INTO payment_plans(ownership_id,purchase_price,payment_months,"
                    +"monthly_payment,amount_paid,balance)"
                    +" VALUES(?,24000,24,1000,0,24000)")) {
                p.setLong(1,ownership);
                assertEquals(1,p.executeUpdate());
            }
            try (Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                String fixture=new String(Files.readAllBytes(Paths.get(DDL)),
                        StandardCharsets.UTF_8);
                s.execute(fixture);
            }
            assertEquals(5,schemaVersion());
        }

        Request request(String requestId,String providerTxn,String localTxn) {
            return new Request(requestId,providerTxn,localTxn,ownership);
        }

        String recover(Request expected) throws Exception {
            return recoverVerifiedSnapshot(expected,
                    provider.queryReadOnly(expected.requestId));
        }

        String recoverVerifiedSnapshot(Request expected,Snapshot result)
                throws Exception {
            // This client-side signature validation can be bypassed by privileged
            // CI DBA; not a production provider trust or financial authorization.
            if (result==null
                    || !PROVIDER.equals(result.provider)
                    || !MERCHANT.equals(result.merchant)
                    || !expected.requestId.equals(result.requestId)
                    || !expected.providerTransactionId.equals(result.providerTransactionId)
                    || !SUBJECT.equals(result.subject)
                    || expected.ownershipId!=result.ownershipId
                    || result.amountMinor!=1
                    || !CURRENCY.equals(result.currency)
                    || !signatureValid(provider.key.getPublic(),result.payload(),
                            result.signature)
                    || result.capturedObservedAt.isBefore(TEST_NOW.minusSeconds(300))
                    || result.capturedObservedAt.isAfter(TEST_NOW.plusSeconds(30))
                    || !"CAPTURED".equals(result.status)) {
                throw new SecurityException("W34.13 HOLD: provider capture not independently verified");
            }
            return postOnce(expected,result);
        }

        String postOnce(Request expected,Snapshot captured) throws Exception {
            String digest=sha(captured.payload());
            try (Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    int inserted;
                    try (PreparedStatement receipt=c.prepareStatement(
                            "INSERT INTO w3413_provider_captures"
                            +"(provider_name,merchant_id,provider_transaction_id,"
                            +"client_request_id,receipt_digest,customer_name,"
                            +"ownership_id,amount_minor,currency,status)"
                            +" VALUES(?,?,?,?,?,?,?,?,?,'CAPTURED')"
                            +" ON CONFLICT DO NOTHING")) {
                        receipt.setString(1,captured.provider);
                        receipt.setString(2,captured.merchant);
                        receipt.setString(3,captured.providerTransactionId);
                        receipt.setString(4,captured.requestId);
                        receipt.setString(5,digest);
                        receipt.setString(6,captured.subject);
                        receipt.setLong(7,captured.ownershipId);
                        receipt.setLong(8,captured.amountMinor);
                        receipt.setString(9,captured.currency);
                        inserted=receipt.executeUpdate();
                    }
                    if(inserted==0) {
                        try (PreparedStatement original=c.prepareStatement(
                                "SELECT provider_transaction_id,receipt_digest,"
                                +"customer_name,ownership_id,amount_minor,currency,"
                                +"local_transaction_id"
                                +" FROM w3413_provider_captures"
                                +" WHERE provider_name=? AND merchant_id=?"
                                +" AND client_request_id=?")) {
                            original.setString(1,captured.provider);
                            original.setString(2,captured.merchant);
                            original.setString(3,captured.requestId);
                            try (ResultSet rs=original.executeQuery()) {
                                if(!rs.next()
                                        || !captured.providerTransactionId.equals(rs.getString(1))
                                        || !digest.equals(rs.getString(2))
                                        || !captured.subject.equals(rs.getString(3))
                                        || captured.ownershipId!=rs.getLong(4)
                                        || captured.amountMinor!=rs.getLong(5)
                                        || !captured.currency.equals(rs.getString(6))
                                        || rs.getString(7)==null)
                                    throw new SecurityException("W34.13 HOLD: conflicting provider identity");
                                String previous=rs.getString(7);
                                if(rs.next())throw new SecurityException("duplicate receipt identity");
                                c.commit();
                                return previous;
                            }
                        }
                    }
                    int paid,remaining;
                    try (PreparedStatement update=c.prepareStatement(
                            "UPDATE payment_plans SET amount_paid=amount_paid+1,"
                            +"balance=balance-1 WHERE ownership_id=? AND balance>=1"
                            +" RETURNING amount_paid,balance")) {
                        update.setLong(1,expected.ownershipId);
                        try (ResultSet result=update.executeQuery()) {
                            if(!result.next())throw new SecurityException("W34.13 HOLD: plan not payable");
                            paid=result.getInt(1);
                            remaining=result.getInt(2);
                        }
                    }
                    try (PreparedStatement ledger=c.prepareStatement(
                            "INSERT INTO payment_transactions"
                            +"(transaction_id,customer_name,ownership_id,contract_id,"
                            +"amount,cumulative_paid,resulting_balance,recorded_at)"
                            +" VALUES(?,'buyer',?,'ACQ-000001',1,?,?,?)")) {
                        ledger.setString(1,expected.localTransactionId);
                        ledger.setLong(2,expected.ownershipId);
                        ledger.setInt(3,paid);
                        ledger.setInt(4,remaining);
                        ledger.setTimestamp(5,Timestamp.from(
                                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(paid)));
                        assertEquals(1,ledger.executeUpdate());
                    }
                    try (PreparedStatement bind=c.prepareStatement(
                            "UPDATE w3413_provider_captures SET local_transaction_id=?"
                            +" WHERE provider_name=? AND merchant_id=?"
                            +" AND client_request_id=? AND local_transaction_id IS NULL")) {
                        bind.setString(1,expected.localTransactionId);
                        bind.setString(2,captured.provider);
                        bind.setString(3,captured.merchant);
                        bind.setString(4,captured.requestId);
                        assertEquals(1,bind.executeUpdate());
                    }
                    c.commit();
                    return expected.localTransactionId;
                } catch (SQLException|RuntimeException problem) {
                    c.rollback();
                    throw problem;
                }
            }
        }

        int claims() throws SQLException { return scalar("SELECT count(*) FROM w3413_provider_captures"); }
        int postings() throws SQLException { return scalar("SELECT count(*) FROM payment_transactions"); }
        int paid() throws SQLException { return scalar("SELECT amount_paid FROM payment_plans"); }
        int balance() throws SQLException { return scalar("SELECT balance FROM payment_plans"); }
        int schemaVersion() throws SQLException { return scalar("SELECT max(version) FROM schema_history"); }
        int scalar(String sql) throws SQLException {
            try (Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(sql)) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
        String digest() throws Exception {
            StringBuilder state=new StringBuilder();
            try (Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                            "SELECT transaction_id,customer_name,ownership_id,contract_id,"
                            +"amount,cumulative_paid,resulting_balance"
                            +" FROM payment_transactions ORDER BY transaction_id")) {
                while(rs.next())
                    for(int i=1;i<=7;i++)state.append(rs.getString(i)).append('\u0000');
            }
            state.append("claims=").append(claims()).append(";paid=")
                    .append(paid()).append(";balance=").append(balance());
            return sha(state.toString().getBytes(StandardCharsets.UTF_8));
        }
        void evidence(String scenario) throws Exception {
            Path path=Paths.get("target/wave34-13-evidence");
            Files.createDirectories(path);
            String json="{\n"
                    +"\"scenario\":\""+scenario+"\",\n"
                    +"\"source_head\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                    +"\"fixture\":\"IN_PROCESS_FAKE_PROVIDER_PG16_ONLY\",\n"
                    +"\"schema_version\":"+schemaVersion()+",\n"
                    +"\"synthetic_captures\":"+claims()+",\n"
                    +"\"synthetic_postings\":"+postings()+",\n"
                    +"\"synthetic_balance\":"+balance()+",\n"
                    +"\"ledger_digest_sha256\":\""+digest()+"\",\n"
                    +"\"real_provider_contacted\":false,\n"
                    +"\"external_charge_reissued\":false,\n"
                    +"\"independent_provider_trust_verified\":false,\n"
                    +"\"financial_policy_approved\":false,\n"
                    +"\"production_authorized\":false,\n"
                    +"\"release_disposition\":\"NO-GO\"\n"
                    +"}\n";
            Files.write(path.resolve(scenario+".json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() throws Exception {
            try (Connection c=owner.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
            }
        }
    }
}
