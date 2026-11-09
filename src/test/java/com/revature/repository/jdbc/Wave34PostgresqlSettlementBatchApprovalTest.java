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
 * W34.16 test-only three-source settlement matching and synthetic maker/checker.
 *
 * The source feeds and all five EC private keys are generated inside one JVM.
 * No externally authenticated provider, bank, GL, real finance approval,
 * production ledger, financial data, or live money is touched. The database
 * uses a privileged CI owner and is NOT a production security boundary.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlSettlementBatchApprovalTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final Instant NOW = Instant.parse("2026-11-09T20:00:00Z");
    private static final String DDL =
            "docs/audit/wave34/wave34_16_DISPOSABLE_settlement_batch_reconciliation.sql";

    @Test
    @Timeout(90)
    void independentlySignedSyntheticSourcesMustMatchBeforeMakerCheckerApproval()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            Batch batch=f.batch("BATCH-01",100,3,97,97,"BANK-REF-01");
            assertEquals("BATCH-01",f.apply(batch,false));
            assertEquals("BATCH-01",f.apply(batch,false));
            assertEquals(1,f.reviews());
            assertEquals(3,f.sources());
            assertEquals(1,f.audit());
            assertEquals(97L,f.net());
            assertEquals(5,f.schemaVersion());
            assertSqlState("55000",()->f.raw(
                    "UPDATE w3416_batch_sources SET net_minor=1 WHERE batch_id='BATCH-01'"));
            assertSqlState("55000",()->f.raw(
                    "DELETE FROM w3416_batch_reviews WHERE batch_id='BATCH-01'"));
            f.assertLegacyUntouched();
            f.evidence("three-source-match-and-maker-checker");
        }
    }

    @Test
    @Timeout(90)
    void bankGlVarianceForgedSourceAndMissingSeparationOfDutiesMustHold()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            Batch mismatch=f.batch("BATCH-01",100,3,96,97,"BANK-REF-01");
            assertThrows(SecurityException.class,()->f.apply(mismatch,false));
            Batch wrongGl=f.batch("BATCH-01",100,3,97,96,"BANK-REF-01");
            assertThrows(SecurityException.class,()->f.apply(wrongGl,false));
            Batch original=f.batch("BATCH-01",100,3,97,97,"BANK-REF-01");
            assertThrows(SecurityException.class,()->f.apply(
                    original.withBank(original.bank.withNet(96)),false));
            assertThrows(SecurityException.class,()->f.apply(
                    original.withBank(original.bank.withCurrency("EUR")),false));
            assertThrows(SecurityException.class,()->f.apply(
                    original.withChecker(f.approveBy("CHECKER","checker",original,
                            UUID.randomUUID(),NOW.plusSeconds(600),keyPair())),false));
            assertThrows(SecurityException.class,()->f.apply(
                    original.withChecker(f.approveBy("CHECKER","maker",original,
                            UUID.randomUUID(),NOW.plusSeconds(600),f.checkerKey)),false));
            assertThrows(SecurityException.class,()->f.apply(
                    original.withChecker(f.approveBy("CHECKER","checker",original,
                            UUID.randomUUID(),NOW.minusSeconds(1),f.checkerKey)),false));
            assertEquals(0,f.reviews());
            assertEquals(0,f.sources());
            assertEquals(0,f.audit());
            f.apply(original,false);
            assertEquals(1,f.reviews());
            assertEquals(3,f.sources());
            f.assertLegacyUntouched();
            f.evidence("variance-and-separation-of-duties-hold");
        }
    }

    @Test
    @Timeout(90)
    void concurrentReplayIsIdempotentAndBankReferenceCannotBeReused()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            Batch original=f.batch("BATCH-01",100,3,97,97,"BANK-REF-01");
            ExecutorService workers=Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready=new CountDownLatch(4);
                CountDownLatch go=new CountDownLatch(1);
                java.util.List<Future<String>> results=new java.util.ArrayList<>();
                for(int i=0;i<4;i++) {
                    results.add(workers.submit(()->{
                        ready.countDown();
                        if(!go.await(10,TimeUnit.SECONDS))
                            throw new AssertionError("synthetic batch start rendezvous timed out");
                        return f.apply(original,false);
                    }));
                }
                assertTrue(ready.await(10,TimeUnit.SECONDS));
                go.countDown();
                for(Future<String> result:results)
                    assertEquals("BATCH-01",result.get(35,TimeUnit.SECONDS));
            } finally {
                workers.shutdownNow();
            }
            assertEquals(1,f.reviews());
            assertEquals(3,f.sources());
            assertEquals(1,f.audit());

            Batch repeatedBankRef=f.batch("BATCH-02",100,3,97,97,"BANK-REF-01");
            assertSqlState("23505",()->f.apply(repeatedBankRef,false));
            assertEquals(1,f.reviews());
            assertEquals(3,f.sources());

            Batch fresh=f.batch("BATCH-02",100,3,97,97,"BANK-REF-02");
            f.apply(fresh,false);
            assertEquals(2,f.reviews());
            assertEquals(6,f.sources());
            assertEquals(2,f.audit());
            assertEquals(194L,f.net());
            f.assertLegacyUntouched();
            f.evidence("concurrent-replay-and-bank-reference");
        }
    }

    @Test
    @Timeout(90)
    void deferredReconciliationAndAuditFkFailuresRollbackEntireBatch()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            Batch one=f.batch("BATCH-01",100,3,97,97,"BANK-REF-01");
            String empty=f.digest();
            assertSqlState("23503",()->f.apply(one,true));
            assertEquals(empty,f.digest());
            assertEquals(0,f.reviews());
            assertEquals(0,f.sources());
            assertEquals(0,f.audit());

            assertSqlState("23514",()->f.unsafeJournal(one,true,false));
            assertEquals(empty,f.digest());
            assertSqlState("23514",()->f.unsafeJournal(one,false,true));
            assertEquals(empty,f.digest());

            f.apply(one,false);
            assertEquals(1,f.reviews());
            assertEquals(3,f.sources());
            assertEquals(1,f.audit());
            assertEquals(97L,f.net());
            f.assertLegacyUntouched();
            f.evidence("atomic-audit-rollback-and-deferred-gate");
        }
    }

    private interface SqlTask { void run() throws Exception; }
    private static void assertSqlState(String sqlState,SqlTask task) {
        SQLException thrown=assertThrows(SQLException.class,task::run);
        assertEquals(sqlState,thrown.getSQLState());
    }
    private static void requiredDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD"))
                && System.getenv("EXPECTED_HEAD")!=null
                && System.getenv("EXPECTED_HEAD").matches("[0-9a-f]{40}"),
                "only exact-commit disposable PostgreSQL16 CI may execute");
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
    private static byte[] canonical(String... fields) {
        try {
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(DataOutputStream writer=new DataOutputStream(buffer)) {
                writer.writeInt(fields.length);
                for(String field:fields) {
                    byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                    writer.writeInt(bytes.length);
                    writer.write(bytes);
                }
            }
            return buffer.toByteArray();
        } catch(IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static byte[] sign(KeyPair key,byte[] payload)
            throws GeneralSecurityException {
        Signature signer=Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(payload);
        return signer.sign();
    }
    private static boolean valid(PublicKey key,byte[] payload,byte[] signature)
            throws GeneralSecurityException {
        Signature verifier=Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(payload);
        return verifier.verify(signature);
    }
    private static String sha(byte[] bytes) throws GeneralSecurityException {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out=new StringBuilder(64);
        char[] symbols="0123456789abcdef".toCharArray();
        for(byte b:digest)out.append(symbols[(b>>>4)&15]).append(symbols[b&15]);
        return out.toString();
    }

    private static final class Source {
        final String batchId,kind,reference,currency;
        final long gross,fee,net;
        final Instant observed;
        final byte[] signature;
        Source(String batchId,String kind,String reference,String currency,
                long gross,long fee,long net,Instant observed,byte[] signature) {
            this.batchId=batchId;this.kind=kind;this.reference=reference;
            this.currency=currency;this.gross=gross;this.fee=fee;this.net=net;
            this.observed=observed;this.signature=signature.clone();
        }
        byte[] payload() {
            return canonical("W34.16|FAKE_SOURCE|v1",batchId,kind,reference,
                    currency,Long.toString(gross),Long.toString(fee),
                    Long.toString(net),observed.toString());
        }
        Source withNet(long amount) {
            return new Source(batchId,kind,reference,currency,gross,fee,
                    amount,observed,signature);
        }
        Source withCurrency(String other) {
            return new Source(batchId,kind,reference,other,gross,fee,
                    net,observed,signature);
        }
    }

    private static final class Approval {
        final String role,subject,batchId,reviewDigest;
        final UUID id;
        final Instant expires;
        final byte[] signature;
        Approval(String role,String subject,String batchId,String digest,
                UUID id,Instant expires,byte[] signature) {
            this.role=role;this.subject=subject;this.batchId=batchId;
            this.reviewDigest=digest;this.id=id;this.expires=expires;
            this.signature=signature.clone();
        }
        byte[] payload() {
            return canonical("W34.16|TEST_FINANCE_APPROVAL|v1",role,subject,
                    batchId,reviewDigest,id.toString(),expires.toString());
        }
    }

    private static final class Batch {
        final String id,reviewDigest;
        final Source provider,bank,gl;
        final Approval maker,checker;
        Batch(String id,Source provider,Source bank,Source gl,
                Approval maker,Approval checker,String reviewDigest) {
            this.id=id;this.provider=provider;this.bank=bank;this.gl=gl;
            this.maker=maker;this.checker=checker;this.reviewDigest=reviewDigest;
        }
        Batch withBank(Source replacement) {
            return new Batch(id,provider,replacement,gl,maker,checker,reviewDigest);
        }
        Batch withChecker(Approval replacement) {
            return new Batch(id,provider,bank,gl,maker,replacement,reviewDigest);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="w3416_"+UUID.randomUUID().toString().replace("-","");
        final JdbcDatabase admin=new JdbcDatabase(URL,USER,PASSWORD);
        final JdbcDatabase scoped=new JdbcDatabase(
                URL+"?currentSchema="+schema,USER,PASSWORD);
        final KeyPair providerKey=keyPair();
        final KeyPair bankKey=keyPair();
        final KeyPair glKey=keyPair();
        final KeyPair makerKey=keyPair();
        final KeyPair checkerKey=keyPair();

        Fixture() throws Exception {
            assertTrue(schema.matches("w3416_[0-9a-f]{32}"));
            try(Connection c=admin.openConnection();Statement stmt=c.createStatement()) {
                try(ResultSet version=stmt.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int v=Integer.parseInt(version.getString(1));
                    assertTrue(v>=160000 && v<170000,"actual PostgreSQL16 required");
                }
                stmt.execute("CREATE SCHEMA "+schema);
            }
            scoped.migrate();
            String ddl=new String(Files.readAllBytes(Paths.get(DDL)),
                    StandardCharsets.UTF_8).replace("__SCHEMA__",schema);
            assertFalse(ddl.contains("__SCHEMA__"));
            try(Connection c=scoped.openConnection();Statement stmt=c.createStatement()) {
                stmt.execute(ddl);
            }
            assertEquals(5,schemaVersion());
        }

        Source source(String batchId,String kind,String ref,
                long gross,long fee,long net,KeyPair key) throws Exception {
            Source unsigned=new Source(batchId,kind,ref,"USD",gross,fee,net,NOW,
                    new byte[0]);
            return new Source(batchId,kind,ref,"USD",gross,fee,net,NOW,
                    sign(key,unsigned.payload()));
        }
        Batch batch(String batchId,long gross,long fee,long bank,long gl,
                String bankReference) throws Exception {
            Source provider=source(batchId,"PROVIDER",batchId+"-capture",
                    gross,fee,gross-fee,providerKey);
            Source bankSource=source(batchId,"BANK",bankReference,
                    0,0,bank,bankKey);
            Source generalLedger=source(batchId,"GL",batchId+"-gl",
                    0,0,gl,glKey);
            String digest=sha(canonical("W34.16|BATCH_REVIEW|v1",batchId,
                    sha(provider.payload()),sha(bankSource.payload()),
                    sha(generalLedger.payload())));
            Approval maker=approveBy("MAKER","maker",batchId,digest,
                    UUID.randomUUID(),NOW.plusSeconds(600),makerKey);
            Approval checker=approveBy("CHECKER","checker",batchId,digest,
                    UUID.randomUUID(),NOW.plusSeconds(600),checkerKey);
            return new Batch(batchId,provider,bankSource,generalLedger,
                    maker,checker,digest);
        }
        Approval approveBy(String role,String subject,Batch batch,UUID id,
                Instant expires,KeyPair key) throws Exception {
            return approveBy(role,subject,batch.id,batch.reviewDigest,id,expires,key);
        }
        Approval approveBy(String role,String subject,String id,String digest,
                UUID approvalId,Instant expires,KeyPair key) throws Exception {
            Approval unsigned=new Approval(role,subject,id,digest,approvalId,
                    expires,new byte[0]);
            return new Approval(role,subject,id,digest,approvalId,expires,
                    sign(key,unsigned.payload()));
        }
        void verify(Batch b) throws Exception {
            if(!b.id.matches("BATCH-[0-9]{2}")
                    || !b.provider.batchId.equals(b.id)
                    || !b.bank.batchId.equals(b.id)
                    || !b.gl.batchId.equals(b.id)
                    || !b.provider.kind.equals("PROVIDER")
                    || !b.bank.kind.equals("BANK") || !b.gl.kind.equals("GL")
                    || !"USD".equals(b.provider.currency)
                    || !"USD".equals(b.bank.currency)
                    || !"USD".equals(b.gl.currency)
                    || b.provider.gross<=0 || b.provider.fee<0
                    || b.provider.gross<=b.provider.fee
                    || b.bank.gross!=0 || b.bank.fee!=0
                    || b.gl.gross!=0 || b.gl.fee!=0
                    || b.provider.net!=b.provider.gross-b.provider.fee
                    || b.bank.net!=b.provider.net || b.gl.net!=b.provider.net
                    || !b.provider.observed.equals(NOW)
                    || !b.bank.observed.equals(NOW)
                    || !b.gl.observed.equals(NOW)
                    || !valid(providerKey.getPublic(),b.provider.payload(),b.provider.signature)
                    || !valid(bankKey.getPublic(),b.bank.payload(),b.bank.signature)
                    || !valid(glKey.getPublic(),b.gl.payload(),b.gl.signature)) {
                throw new SecurityException("W3416 synthetic bank/provider/GL source HOLD");
            }
            String exact=sha(canonical("W34.16|BATCH_REVIEW|v1",b.id,
                    sha(b.provider.payload()),sha(b.bank.payload()),
                    sha(b.gl.payload())));
            if(!exact.equals(b.reviewDigest)
                    || !b.maker.role.equals("MAKER")
                    || !b.checker.role.equals("CHECKER")
                    || !b.maker.subject.equals("maker")
                    || !b.checker.subject.equals("checker")
                    || b.maker.subject.equals(b.checker.subject)
                    || b.maker.id.equals(b.checker.id)
                    || !b.id.equals(b.maker.batchId)
                    || !b.id.equals(b.checker.batchId)
                    || !b.reviewDigest.equals(b.maker.reviewDigest)
                    || !b.reviewDigest.equals(b.checker.reviewDigest)
                    || !b.maker.expires.isAfter(NOW)
                    || !b.checker.expires.isAfter(NOW)
                    || !valid(makerKey.getPublic(),b.maker.payload(),b.maker.signature)
                    || !valid(checkerKey.getPublic(),b.checker.payload(),b.checker.signature)) {
                throw new SecurityException("W3416 synthetic maker-checker approval HOLD");
            }
        }

        String apply(Batch batch,boolean injectBadAuditFk) throws Exception {
            verify(batch); // test-local keys only, bypassable by CI SQL admin
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    int inserted=insertReview(c,batch);
                    if(inserted==0) {
                        try(PreparedStatement p=c.prepareStatement(
                                "SELECT review_digest,maker_approval_id,checker_approval_id,"
                                +"provider_gross_minor,provider_fee_minor,expected_net_minor"
                                +" FROM w3416_batch_reviews WHERE batch_id=?")) {
                            p.setString(1,batch.id);
                            try(ResultSet rs=p.executeQuery()) {
                                if(!rs.next()
                                        || !batch.reviewDigest.equals(rs.getString(1))
                                        || !batch.maker.id.equals(rs.getObject(2))
                                        || !batch.checker.id.equals(rs.getObject(3))
                                        || batch.provider.gross!=rs.getLong(4)
                                        || batch.provider.fee!=rs.getLong(5)
                                        || batch.provider.net!=rs.getLong(6))
                                    throw new SecurityException("W3416 changed replay identity HOLD");
                            }
                        }
                        c.commit();return batch.id;
                    }
                    insertSource(c,batch.provider);
                    insertSource(c,batch.bank);
                    insertSource(c,batch.gl);
                    audit(c,injectBadAuditFk?"BATCH-99":batch.id,batch.reviewDigest);
                    c.commit(); // deferred PG16 gate checks complete 3-source match
                    return batch.id;
                } catch(SQLException|RuntimeException failure) {
                    c.rollback();
                    throw failure;
                }
            }
        }
        int insertReview(Connection c,Batch b) throws SQLException {
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO w3416_batch_reviews"
                    +"(batch_id,currency,provider_gross_minor,provider_fee_minor,"
                    +"expected_net_minor,maker_id,checker_id,maker_approval_id,"
                    +"checker_approval_id,review_digest) VALUES(?,'USD',?,?,?,?,?,?,?,?)"
                    +" ON CONFLICT (batch_id) DO NOTHING")) {
                p.setString(1,b.id);
                p.setLong(2,b.provider.gross);
                p.setLong(3,b.provider.fee);
                p.setLong(4,b.provider.net);
                p.setString(5,b.maker.subject);
                p.setString(6,b.checker.subject);
                p.setObject(7,b.maker.id);
                p.setObject(8,b.checker.id);
                p.setString(9,b.reviewDigest);
                return p.executeUpdate();
            }
        }
        void insertSource(Connection c,Source source) throws Exception {
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO w3416_batch_sources"
                    +"(batch_id,source_kind,reference_id,currency,gross_minor,"
                    +"fee_minor,net_minor,signed_digest) VALUES(?,?,?,?,?,?,?,?)")) {
                p.setString(1,source.batchId);p.setString(2,source.kind);
                p.setString(3,source.reference);p.setString(4,source.currency);
                p.setLong(5,source.gross);p.setLong(6,source.fee);
                p.setLong(7,source.net);p.setString(8,sha(source.payload()));
                assertEquals(1,p.executeUpdate());
            }
        }
        void audit(Connection c,String batchId,String digest) throws SQLException {
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO w3416_batch_audit(audit_id,batch_id,"
                    +"reconciliation_digest) VALUES(?,?,?)")) {
                p.setObject(1,UUID.randomUUID());p.setString(2,batchId);
                p.setString(3,digest);assertEquals(1,p.executeUpdate());
            }
        }
        void unsafeJournal(Batch batch,boolean missingGl,boolean wrongBankNet)
                throws Exception {
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    assertEquals(1,insertReview(c,batch));
                    insertSource(c,batch.provider);
                    insertSource(c,wrongBankNet?batch.bank.withNet(96):batch.bank);
                    if(!missingGl)insertSource(c,batch.gl);
                    audit(c,batch.id,batch.reviewDigest);
                    c.commit(); // 23514 delayed reconciliation validation
                } catch(SQLException|RuntimeException failure) {
                    c.rollback();throw failure;
                }
            }
        }
        void raw(String sql) throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute(sql);
            }
        }
        long scalar(String sql) throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet r=s.executeQuery(sql)) {
                assertTrue(r.next());return r.getLong(1);
            }
        }
        int reviews() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3416_batch_reviews");
        }
        int sources() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3416_batch_sources");
        }
        int audit() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3416_batch_audit");
        }
        long net() throws SQLException {
            return scalar("SELECT COALESCE(SUM(expected_net_minor),0)"
                    +" FROM w3416_batch_reviews");
        }
        int schemaVersion() throws SQLException {
            return (int)scalar("SELECT MAX(version) FROM schema_history");
        }
        void assertLegacyUntouched() throws SQLException {
            assertEquals(0,scalar("SELECT COUNT(*) FROM payment_transactions"));
            assertEquals(0,scalar("SELECT COUNT(*) FROM payment_plans"));
            assertEquals(5,schemaVersion());
        }
        String digest() throws Exception {
            StringBuilder evidence=new StringBuilder();
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet r=s.executeQuery("SELECT batch_id,source_kind,"
                            +"reference_id,currency,gross_minor,fee_minor,net_minor,"
                            +"signed_digest FROM w3416_batch_sources"
                            +" ORDER BY batch_id,source_kind")) {
                while(r.next())
                    for(int i=1;i<=8;i++)
                        evidence.append(r.getString(i)).append('\u0000');
            }
            evidence.append(";reviews=").append(reviews())
                    .append(";audit=").append(audit()).append(";net=").append(net());
            return sha(evidence.toString().getBytes(StandardCharsets.UTF_8));
        }
        void evidence(String scenario) throws Exception {
            Path output=Paths.get("target/wave34-16-evidence");
            Files.createDirectories(output);
            String json="{\n"
                +"\"scenario\":\""+scenario+"\",\n"
                +"\"source_head\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                +"\"fixture\":\"DISPOSABLE_PG16_IN_PROCESS_THREE_SOURCE_ONLY\",\n"
                +"\"schema_version\":"+schemaVersion()+",\n"
                +"\"synthetic_batches\":"+reviews()+",\n"
                +"\"synthetic_source_rows\":"+sources()+",\n"
                +"\"synthetic_audit_rows\":"+audit()+",\n"
                +"\"synthetic_net_minor\":"+net()+",\n"
                +"\"legacy_payment_entries\":"
                +scalar("SELECT COUNT(*) FROM payment_transactions")+",\n"
                +"\"reconciliation_digest_sha256\":\""+digest()+"\",\n"
                +"\"real_provider_verified\":false,\n"
                +"\"real_bank_verified\":false,\n"
                +"\"independent_gl_verified\":false,\n"
                +"\"independent_maker_checker_verified\":false,\n"
                +"\"production_authorized\":false,\n"
                +"\"release_disposition\":\"NO-GO\"\n"
                +"}\n";
            Files.write(output.resolve(scenario+".json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() throws Exception {
            try(Connection c=admin.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
            }
        }
    }
}
