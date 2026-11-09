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
 * W34.18 synthetic prior-period adjustment authorization and hash-chain lab.
 * All three signer keys and every source record are generated in one test JVM.
 * SQL guard uses elevated CI ownership; this is NOT external key management,
 * WORM custody, a finance-approved prior-period journal, or real funds movement.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlEvidenceRevocationAdjustmentTest {
    private static final String URL="jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER="dealership_ci_owner";
    private static final String PASSWORD="ci-only-postgres-fixture";
    private static final String PRIOR="2026-10";
    private static final String CURRENT="2026-11";
    private static final String REASON="VERIFIED_PREVIOUS_PERIOD_VARIANCE";
    private static final Instant NOW=Instant.parse("2026-11-09T20:00:00Z");
    private static final String ZEROS=
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String OLD_CLOSE=
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DDL=
            "docs/audit/wave34/wave34_18_DISPOSABLE_evidence_revocation_post_close.sql";

    @Test
    @Timeout(90)
    void sequentialEvidenceHashChainVerifiesAndDisallowsHistoricalRewrite()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request first=f.request("ADJ-01","FAKE-REF-01",20);
            Request second=f.request("ADJ-02","FAKE-REF-02",10);
            assertEquals("ADJ-01",f.post(first,f.review(first,false)));
            assertEquals("ADJ-02",f.post(second,f.review(second,false)));
            assertEquals(2,f.adjustments());
            assertEquals(2,f.chainRows());
            assertEquals(2L,f.cursorSequence());
            assertTrue(f.verifyChain());
            assertEquals(OLD_CLOSE,f.periodDigest(PRIOR));
            assertSqlState("55000",()->f.raw(
                    "UPDATE w3418_evidence_chain SET event_digest='"+ZEROS+"'"
                    +" WHERE chain_sequence=1"));
            assertSqlState("55000",()->f.raw(
                    "DELETE FROM w3418_adjustments WHERE adjustment_id='ADJ-01'"));
            assertSqlState("55000",()->f.raw(
                    "UPDATE w3418_periods SET state='OPEN' WHERE period_id='"+PRIOR+"'"));
            assertTrue(f.verifyChain());
            f.assertLegacyUntouched();
            f.evidence("hash-chain-and-immutable-historical-period");
        }
    }

    @Test
    @Timeout(90)
    void revokedOverLimitExpiredOrForgedApprovalsCannotPostAdjustment()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request over=f.request("ADJ-01","FAKE-REF-01",51);
            assertSqlState("42501",()->f.post(over,f.review(over,false)));
            assertEquals(0,f.adjustments());

            Request one=f.request("ADJ-01","FAKE-REF-01",25);
            Review approved=f.review(one,false);
            assertThrows(SecurityException.class,()->f.post(
                    one.withAmount(24),approved));
            assertThrows(SecurityException.class,()->f.post(
                    one,f.reviewSignedBy(one,f.maker,f.checker, keyPair())));
            assertThrows(SecurityException.class,()->f.post(
                    one,f.expiredReview(one)));
            assertThrows(SecurityException.class,()->f.post(
                    one,f.selfReview(one)));
            f.revoke("checker");
            assertSqlState("42501",()->f.post(one,approved));
            assertSqlState("55000",()->f.raw(
                    "UPDATE w3418_principals SET active=TRUE,revoked_at=NULL"
                    +" WHERE principal_id='checker'"));
            assertEquals(0,f.adjustments());
            assertEquals(0,f.chainRows());
            assertEquals(0L,f.cursorSequence());

            // A different, still-active illustrative checker is accepted.
            assertEquals("ADJ-01",f.post(one,f.review(one,true)));
            assertEquals(1,f.adjustments());
            assertTrue(f.verifyChain());
            f.assertLegacyUntouched();
            f.evidence("revocation-limit-and-signature-hold");
        }
    }

    @Test
    @Timeout(90)
    void postCloseCorrectionUsesOnlyOpenCurrentPeriodAndPreservesOldClose()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request correction=f.request("ADJ-01","FAKE-REF-01",30);
            assertEquals("ADJ-01",f.post(correction,f.review(correction,false)));
            assertEquals("CLOSED",f.periodState(PRIOR));
            assertEquals(OLD_CLOSE,f.periodDigest(PRIOR));
            assertEquals("OPEN",f.periodState(CURRENT));

            Request backdated=correction.withPeriods(PRIOR,PRIOR);
            assertSqlState("42501",()->f.post(backdated,f.review(backdated,false)));
            assertEquals(1,f.adjustments());
            assertEquals(1,f.chainRows());

            // Simulated external close of the test current period. NOT a real
            // finance close and NOT an approved production role.
            f.raw("UPDATE w3418_periods SET state='CLOSED',close_digest='"+ZEROS+"'"
                    +" WHERE period_id='"+CURRENT+"'");
            Request next=f.request("ADJ-02","FAKE-REF-02",5);
            assertSqlState("42501",()->f.post(next,f.review(next,false)));
            assertEquals("CLOSED",f.periodState(CURRENT));
            assertEquals(OLD_CLOSE,f.periodDigest(PRIOR));
            assertEquals(1,f.adjustments());
            assertTrue(f.verifyChain());
            f.assertLegacyUntouched();
            f.evidence("prior-period-referenced-current-only-adjustment");
        }
    }

    @Test
    @Timeout(90)
    void concurrentExactReplayPostsOnceAndCrossAdjustmentApprovalReuseFailsClosed()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request original=f.request("ADJ-01","FAKE-REF-01",25);
            Review approval=f.review(original,false);
            ExecutorService workers=Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready=new CountDownLatch(4);
                CountDownLatch go=new CountDownLatch(1);
                java.util.List<Future<String>> results=new java.util.ArrayList<>();
                for(int i=0;i<4;i++) {
                    results.add(workers.submit(()->{
                        ready.countDown();
                        if(!go.await(10,TimeUnit.SECONDS))
                            throw new AssertionError("W34.18 concurrent start timed out");
                        return f.post(original,approval);
                    }));
                }
                assertTrue(ready.await(10,TimeUnit.SECONDS));
                go.countDown();
                for(Future<String> result:results)
                    assertEquals("ADJ-01",result.get(35,TimeUnit.SECONDS));
            } finally {
                workers.shutdownNow();
            }
            assertEquals(1,f.adjustments());
            assertEquals(1,f.chainRows());
            assertTrue(f.verifyChain());

            Request another=f.request("ADJ-02","FAKE-REF-02",10);
            Review reused=f.reviewUsingIds(another,false,
                    approval.maker.id,approval.checker.id);
            assertThrows(SecurityException.class,()->f.post(another,reused));
            assertEquals(1,f.adjustments());
            assertEquals(1L,f.cursorSequence());
            assertEquals("ADJ-02",f.post(another,f.review(another,false)));
            assertEquals(2,f.adjustments());
            assertEquals(2,f.chainRows());
            assertTrue(f.verifyChain());
            f.assertLegacyUntouched();
            f.evidence("concurrent-replay-and-cross-event-approval");
        }
    }

    private interface SqlTask {void run() throws Exception;}
    private static void assertSqlState(String state,SqlTask task) {
        SQLException problem=assertThrows(SQLException.class,task::run);
        assertEquals(state,problem.getSQLState());
    }
    private static void requireDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD"))
                && System.getenv("EXPECTED_HEAD")!=null
                && System.getenv("EXPECTED_HEAD").matches("[0-9a-f]{40}"),
                "requires exact-source disposable PG16 GitHub Actions");
    }
    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
    private static byte[] canonical(String... fields) {
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(DataOutputStream writer=new DataOutputStream(out)) {
                writer.writeInt(fields.length);
                for(String field:fields) {
                    byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                    writer.writeInt(bytes.length);
                    writer.write(bytes);
                }
            }
            return out.toByteArray();
        } catch(IOException unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }
    private static byte[] sign(KeyPair key,byte[] bytes)
            throws GeneralSecurityException {
        Signature signature=Signature.getInstance("SHA256withECDSA");
        signature.initSign(key.getPrivate());
        signature.update(bytes);
        return signature.sign();
    }
    private static boolean verified(PublicKey key,byte[] bytes,byte[] sig)
            throws GeneralSecurityException {
        Signature verifier=Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(bytes);
        return verifier.verify(sig);
    }
    private static String sha(byte[] bytes) throws GeneralSecurityException {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out=new StringBuilder(64);
        char[] alphabet="0123456789abcdef".toCharArray();
        for(byte b:hash)
            out.append(alphabet[(b>>>4)&15]).append(alphabet[b&15]);
        return out.toString();
    }

    private static final class Request {
        final String id,sourceReference,prior,current,reason,currency,digest;
        final long minor;
        Request(String id,String ref,long minor,String prior,String current,
                String reason,String currency,String digest) {
            this.id=id;this.sourceReference=ref;this.minor=minor;
            this.prior=prior;this.current=current;this.reason=reason;
            this.currency=currency;this.digest=digest;
        }
        Request withAmount(long amount) {
            return new Request(id,sourceReference,amount,prior,current,reason,currency,digest);
        }
        Request withPeriods(String previous,String posting) {
            return new Request(id,sourceReference,minor,previous,posting,
                    reason,currency,digest);
        }
        byte[] payload() {
            return canonical("W34.18|FAKE_CORRECTION|v1",id,sourceReference,
                    Long.toString(minor),prior,current,reason,currency,digest);
        }
    }
    private static final class Approval {
        final String role,subject,adjustmentId,requestDigest;
        final UUID id;
        final Instant expires;
        final byte[] signature;
        Approval(String role,String subject,Request request,UUID id,
                Instant expires,byte[] signature) throws Exception {
            this.role=role;this.subject=subject;this.adjustmentId=request.id;
            this.requestDigest=sha(request.payload());this.id=id;
            this.expires=expires;this.signature=signature.clone();
        }
        byte[] payload() {
            return canonical("W34.18|TEST_FINANCE_APPROVAL|v1",role,subject,
                    adjustmentId,requestDigest,id.toString(),expires.toString());
        }
    }
    private static final class Review {
        final Approval maker,checker;
        Review(Approval maker,Approval checker) {
            this.maker=maker;this.checker=checker;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="w3418_"+UUID.randomUUID().toString().replace("-","");
        final JdbcDatabase admin=new JdbcDatabase(URL,USER,PASSWORD);
        final JdbcDatabase scoped=new JdbcDatabase(
                URL+"?currentSchema="+schema,USER,PASSWORD);
        final KeyPair maker=keyPair(),checker=keyPair(),checkerAlt=keyPair();

        Fixture() throws Exception {
            assertTrue(schema.matches("w3418_[0-9a-f]{32}"));
            try(Connection c=admin.openConnection();Statement s=c.createStatement()) {
                try(ResultSet ver=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(ver.next());
                    int n=Integer.parseInt(ver.getString(1));
                    assertTrue(n>=160000&&n<170000,"actual PG16 required");
                }
                s.execute("CREATE SCHEMA "+schema);
            }
            scoped.migrate();
            String ddl=new String(Files.readAllBytes(Paths.get(DDL)),
                    StandardCharsets.UTF_8).replace("__SCHEMA__",schema);
            assertFalse(ddl.contains("__SCHEMA__"));
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute(ddl);
                s.execute("INSERT INTO w3418_periods(period_id,state,close_digest)"
                        +" VALUES('"+PRIOR+"','CLOSED','"+OLD_CLOSE+"')");
                s.execute("INSERT INTO w3418_periods(period_id,state)"
                        +" VALUES('"+CURRENT+"','OPEN')");
                s.execute("INSERT INTO w3418_principals"
                        +"(principal_id,authority_role,max_minor) VALUES"
                        +"('maker','MAKER',50),('checker','CHECKER',50),"
                        +"('checker-alt','CHECKER',50)");
            }
            assertEquals(5,schemaVersion());
        }
        Request request(String id,String ref,long amount) throws Exception {
            String digest=sha(canonical("W34.18|FAKE_SOURCE|v1",id,ref,
                    Long.toString(amount),PRIOR));
            return new Request(id,ref,amount,PRIOR,CURRENT,REASON,"USD",digest);
        }
        Approval approve(String role,String subject,Request req,UUID id,
                Instant expires,KeyPair key) throws Exception {
            Approval unsigned=new Approval(role,subject,req,id,expires,new byte[0]);
            return new Approval(role,subject,req,id,expires,
                    sign(key,unsigned.payload()));
        }
        Review review(Request req,boolean alternate) throws Exception {
            return reviewUsingIds(req,alternate,UUID.randomUUID(),UUID.randomUUID());
        }
        Review reviewUsingIds(Request req,boolean alternate,UUID makerId,
                UUID checkerId) throws Exception {
            Approval first=approve("MAKER","maker",req,makerId,
                    NOW.plusSeconds(300),maker);
            Approval second=approve("CHECKER",alternate?"checker-alt":"checker",
                    req,checkerId,NOW.plusSeconds(300),
                    alternate?checkerAlt:checker);
            return new Review(first,second);
        }
        Review reviewSignedBy(Request req,KeyPair makerKey,KeyPair checkerKey,
                KeyPair fake) throws Exception {
            // The test calls this with an untrusted fake checker signer.
            Approval first=approve("MAKER","maker",req,UUID.randomUUID(),
                    NOW.plusSeconds(300),makerKey);
            Approval second=approve("CHECKER","checker",req,UUID.randomUUID(),
                    NOW.plusSeconds(300),fake);
            return new Review(first,second);
        }
        Review expiredReview(Request req) throws Exception {
            Approval first=approve("MAKER","maker",req,UUID.randomUUID(),
                    NOW.plusSeconds(300),maker);
            Approval second=approve("CHECKER","checker",req,UUID.randomUUID(),
                    NOW.minusSeconds(1),checker);
            return new Review(first,second);
        }
        Review selfReview(Request req) throws Exception {
            Approval first=approve("MAKER","maker",req,UUID.randomUUID(),
                    NOW.plusSeconds(300),maker);
            Approval second=approve("CHECKER","maker",req,UUID.randomUUID(),
                    NOW.plusSeconds(300),maker);
            return new Review(first,second);
        }
        void verify(Request r,Review review) throws Exception {
            if(!r.id.matches("ADJ-[0-9]{2}") || r.sourceReference.trim().isEmpty()
                    || r.minor<=0 || !"USD".equals(r.currency)
                    || !REASON.equals(r.reason)
                    || !r.digest.matches("[0-9a-f]{64}")
                    || review.maker.id.equals(review.checker.id)
                    || !review.maker.role.equals("MAKER")
                    || !review.checker.role.equals("CHECKER")
                    || !review.maker.subject.equals("maker")
                    || !("checker".equals(review.checker.subject)
                    || "checker-alt".equals(review.checker.subject))
                    || review.maker.subject.equals(review.checker.subject)
                    || !review.maker.adjustmentId.equals(r.id)
                    || !review.checker.adjustmentId.equals(r.id)
                    || !review.maker.requestDigest.equals(sha(r.payload()))
                    || !review.checker.requestDigest.equals(sha(r.payload()))
                    || !review.maker.expires.isAfter(NOW)
                    || !review.checker.expires.isAfter(NOW)
                    || !verified(maker.getPublic(),review.maker.payload(),
                            review.maker.signature)
                    || !verified("checker-alt".equals(review.checker.subject)
                            ?checkerAlt.getPublic():checker.getPublic(),
                            review.checker.payload(),review.checker.signature))
                throw new SecurityException("W34.18 lab correction approval HOLD");
        }

        String post(Request r,Review review) throws Exception {
            verify(r,review);
            String claim=sha(r.payload());
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    long sequence;
                    String previous;
                    try(PreparedStatement p=c.prepareStatement(
                            "SELECT last_sequence,last_hash FROM w3418_chain_cursor"
                            +" WHERE singleton_id=1 FOR UPDATE");
                            ResultSet rs=p.executeQuery()) {
                        assertTrue(rs.next());
                        sequence=rs.getLong(1)+1;
                        previous=rs.getString(2);
                    }
                    String eventHash=sha(canonical(previous,claim));
                    try(PreparedStatement old=c.prepareStatement(
                            "SELECT source_reference,previous_period,posting_period,"
                            +"amount_minor,currency,source_digest,reason,maker_id,"
                            +"checker_id,maker_approval_id,checker_approval_id,"
                            +"evidence_hash FROM w3418_adjustments"
                            +" WHERE adjustment_id=?")) {
                        old.setString(1,r.id);
                        try(ResultSet rs=old.executeQuery()) {
                            if(rs.next()) {
                                if(!r.sourceReference.equals(rs.getString(1))
                                        || !r.prior.equals(rs.getString(2))
                                        || !r.current.equals(rs.getString(3))
                                        || r.minor!=rs.getLong(4)
                                        || !r.currency.equals(rs.getString(5))
                                        || !r.digest.equals(rs.getString(6))
                                        || !r.reason.equals(rs.getString(7))
                                        || !review.maker.subject.equals(rs.getString(8))
                                        || !review.checker.subject.equals(rs.getString(9))
                                        || !review.maker.id.equals(rs.getObject(10))
                                        || !review.checker.id.equals(rs.getObject(11))
                                        || rs.getString(12)==null)
                                    throw new SecurityException(
                                            "W34.18 changed replay identity HOLD");
                                c.commit();
                                return r.id;
                            }
                        }
                    }
                    int inserted;
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3418_adjustments"
                            +"(adjustment_id,source_reference,previous_period,"
                            +"posting_period,reason,amount_minor,currency,source_digest,"
                            +"maker_id,checker_id,maker_approval_id,checker_approval_id,"
                            +"evidence_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
                            +" ON CONFLICT DO NOTHING")) {
                        p.setString(1,r.id);p.setString(2,r.sourceReference);
                        p.setString(3,r.prior);p.setString(4,r.current);
                        p.setString(5,r.reason);p.setLong(6,r.minor);
                        p.setString(7,r.currency);p.setString(8,r.digest);
                        p.setString(9,review.maker.subject);
                        p.setString(10,review.checker.subject);
                        p.setObject(11,review.maker.id);
                        p.setObject(12,review.checker.id);
                        p.setString(13,eventHash);
                        inserted=p.executeUpdate();
                    }
                    if(inserted!=1)
                        throw new SecurityException(
                                "W34.18 approval collision belongs to another adjustment");
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3418_evidence_chain"
                            +"(chain_sequence,adjustment_id,previous_hash,event_hash,"
                            +"event_digest,source_reference,amount_minor,previous_period,"
                            +"posting_period,reason,maker_id,checker_id)"
                            +" VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        p.setLong(1,sequence);p.setString(2,r.id);
                        p.setString(3,previous);p.setString(4,eventHash);
                        p.setString(5,claim);p.setString(6,r.sourceReference);
                        p.setLong(7,r.minor);p.setString(8,r.prior);
                        p.setString(9,r.current);p.setString(10,r.reason);
                        p.setString(11,review.maker.subject);
                        p.setString(12,review.checker.subject);
                        assertEquals(1,p.executeUpdate());
                    }
                    try(PreparedStatement p=c.prepareStatement(
                            "UPDATE w3418_chain_cursor SET last_sequence=?,last_hash=?"
                            +" WHERE singleton_id=1")) {
                        p.setLong(1,sequence);p.setString(2,eventHash);
                        assertEquals(1,p.executeUpdate());
                    }
                    c.commit();
                    return r.id;
                } catch(SQLException|RuntimeException problem) {
                    c.rollback();
                    throw problem;
                }
            }
        }

        boolean verifyChain() throws Exception {
            long sequence=0;
            String prior=ZEROS;
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                        "SELECT ch.chain_sequence,ch.previous_hash,ch.event_hash,"
                        +"ch.event_digest,ch.adjustment_id,adj.source_reference,"
                        +"adj.amount_minor,adj.previous_period,adj.posting_period,"
                        +"adj.reason,adj.currency,adj.source_digest,"
                        +"adj.maker_id,adj.checker_id,adj.maker_approval_id,"
                        +"adj.checker_approval_id,adj.evidence_hash"
                        +" FROM w3418_evidence_chain ch"
                        +" JOIN w3418_adjustments adj ON ch.adjustment_id=adj.adjustment_id"
                        +" ORDER BY ch.chain_sequence")) {
                while(rs.next()) {
                    sequence++;
                    if(rs.getLong(1)!=sequence||!prior.equals(rs.getString(2)))
                        return false;
                    String claim=sha(canonical("W34.18|FAKE_CORRECTION|v1",
                            rs.getString(5),rs.getString(6),
                            Long.toString(rs.getLong(7)),rs.getString(8),
                            rs.getString(9),rs.getString(10),rs.getString(11),
                            rs.getString(12)));
                    String hash=sha(canonical(prior,claim));
                    if(!claim.equals(rs.getString(4))
                            || !hash.equals(rs.getString(3))
                            || !hash.equals(rs.getString(17))
                            || !rs.getString(13).equals("maker")
                            || !("checker".equals(rs.getString(14))
                                || "checker-alt".equals(rs.getString(14)))
                            || rs.getObject(15)==null||rs.getObject(16)==null)
                        return false;
                    prior=hash;
                }
            }
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "SELECT last_sequence,last_hash FROM w3418_chain_cursor"
                    +" WHERE singleton_id=1");ResultSet rs=p.executeQuery()) {
                assertTrue(rs.next());
                return sequence==rs.getLong(1)&&prior.equals(rs.getString(2))
                        &&sequence==adjustments();
            }
        }
        void revoke(String id) throws SQLException {
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "UPDATE w3418_principals SET active=FALSE,"
                    +"revoked_at=clock_timestamp() WHERE principal_id=?"
                    +" AND active=TRUE")) {
                p.setString(1,id);
                assertEquals(1,p.executeUpdate());
            }
        }
        void raw(String sql) throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute(sql);
            }
        }
        long scalar(String sql) throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(sql)) {
                assertTrue(rs.next());return rs.getLong(1);
            }
        }
        int adjustments() throws SQLException {
            return (int)scalar("SELECT count(*) FROM w3418_adjustments");
        }
        int chainRows() throws SQLException {
            return (int)scalar("SELECT count(*) FROM w3418_evidence_chain");
        }
        long cursorSequence() throws SQLException {
            return scalar("SELECT last_sequence FROM w3418_chain_cursor");
        }
        String periodState(String id) throws SQLException {
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "SELECT state FROM w3418_periods WHERE period_id=?")) {
                p.setString(1,id);
                try(ResultSet rs=p.executeQuery()) {
                    assertTrue(rs.next());
                    return rs.getString(1);
                }
            }
        }
        String periodDigest(String id) throws SQLException {
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "SELECT close_digest FROM w3418_periods WHERE period_id=?")) {
                p.setString(1,id);
                try(ResultSet rs=p.executeQuery()) {
                    assertTrue(rs.next());return rs.getString(1);
                }
            }
        }
        int schemaVersion() throws SQLException {
            return (int)scalar("SELECT MAX(version) FROM schema_history");
        }
        void assertLegacyUntouched() throws SQLException {
            assertEquals(0L,scalar("SELECT count(*) FROM payment_transactions"));
            assertEquals(0L,scalar("SELECT count(*) FROM payment_plans"));
            assertEquals(5,schemaVersion());
        }
        void evidence(String scenario) throws Exception {
            Path directory=Paths.get("target/wave34-18-evidence");
            Files.createDirectories(directory);
            String cursorHash;
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                            "SELECT last_hash FROM w3418_chain_cursor WHERE singleton_id=1")) {
                assertTrue(rs.next());cursorHash=rs.getString(1);
            }
            String json="{\n"
                    +"\"scenario\":\""+scenario+"\",\n"
                    +"\"source_head\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                    +"\"fixture\":\"DISPOSABLE_PG16_TEST_ONLY_EVIDENCE_CHAIN\",\n"
                    +"\"schema_version\":"+schemaVersion()+",\n"
                    +"\"synthetic_adjustments\":"+adjustments()+",\n"
                    +"\"synthetic_chain_entries\":"+chainRows()+",\n"
                    +"\"synthetic_cursor_sequence\":"+cursorSequence()+",\n"
                    +"\"chain_valid\":"+verifyChain()+",\n"
                    +"\"chain_tip_sha256\":\""+cursorHash+"\",\n"
                    +"\"historical_close_unchanged\":"+OLD_CLOSE.equals(periodDigest(PRIOR))+",\n"
                    +"\"legacy_payment_entries\":"
                        +scalar("SELECT count(*) FROM payment_transactions")+",\n"
                    +"\"independent_evidence_custody_verified\":false,\n"
                    +"\"real_approver_identity_verified\":false,\n"
                    +"\"production_revocation_integrated\":false,\n"
                    +"\"authorized_real_prior_period_adjustment\":false,\n"
                    +"\"production_authorized\":false,\n"
                    +"\"release_disposition\":\"NO-GO\"\n"
                    +"}\n";
            Files.write(directory.resolve(scenario+".json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() throws Exception {
            try(Connection c=admin.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
            }
        }
    }
}
