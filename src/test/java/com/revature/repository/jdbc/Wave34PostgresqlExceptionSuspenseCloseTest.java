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
 * W34.17 disposable PG16 exception and financial period-close acceptance.
 * Fake source, maker and checker key pairs are created in one test JVM.
 * This is not real bank/merchant truth, finance authorization, approved GL,
 * legal-entity account custody, production WORM, or actual money movement.
 * CI DBA owns the disposable schema and can bypass all test SQL protections.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlExceptionSuspenseCloseTest {
    private static final String URL="jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER="dealership_ci_owner";
    private static final String PASSWORD="ci-only-postgres-fixture";
    private static final String PERIOD="2026-11-LAB";
    private static final Instant NOW=Instant.parse("2026-11-09T20:00:00Z");
    private static final String DDL=
        "docs/audit/wave34/wave34_17_DISPOSABLE_exception_suspense_period_close.sql";

    @Test
    @Timeout(90)
    void exceptionHoldRequiresBalancedReleaseAndDualReviewBeforePeriodClose()
            throws Exception {
        requiredDisposable();
        try(Fixture f=new Fixture()) {
            Evidence e=f.evidence("EX-01","BANK-01",40);
            f.open(e);
            assertEquals(1,f.exceptions());
            assertEquals(1,f.openExceptions());
            assertEquals(1,f.events());
            assertEquals(2,f.lines());
            assertEquals(40L,f.suspense());
            assertEquals("OPEN",f.periodState());
            assertSqlState("23514",()->f.closePeriod(f.closeReview()));

            Review review=f.review("RESOLVE",e.id,sha(e.payload()));
            assertEquals("EX-01",f.resolve(e,review));
            assertEquals("EX-01",f.resolve(e,review));
            assertEquals(0,f.openExceptions());
            assertEquals(2,f.events());
            assertEquals(4,f.lines());
            assertEquals(0L,f.suspense());
            assertEquals(80L,f.debits());
            assertEquals(80L,f.credits());

            assertEquals(PERIOD,f.closePeriod(f.closeReview()));
            assertEquals("CLOSED",f.periodState());
            assertEquals(1,f.closeReviews());
            assertSqlState("55000",()->f.open(f.evidence("EX-02","BANK-02",10)));
            assertSqlState("55000",()->f.raw(
                    "DELETE FROM w3417_suspense_lines WHERE event_id='HOLD-EX-01'"));
            assertSqlState("55000",()->f.raw(
                    "UPDATE w3417_periods SET state='OPEN' WHERE period_id='"+PERIOD+"'"));
            f.assertLegacyUntouched();
            f.record("exception-release-and-authorized-close");
        }
    }

    @Test
    @Timeout(90)
    void forgedSourceExpiredReviewerSelfApprovalAndChangedClaimsRemainOnHold()
            throws Exception {
        requiredDisposable();
        try(Fixture f=new Fixture()) {
            Evidence original=f.evidence("EX-01","BANK-01",40);
            f.open(original);
            String digest=f.digest();
            Review valid=f.review("RESOLVE",original.id,sha(original.payload()));
            assertThrows(SecurityException.class,()->
                    f.resolve(original.withAmount(41),valid));
            assertThrows(SecurityException.class,()->
                    f.resolve(f.evidenceSignedBy("EX-01","BANK-01",40,keyPair()),valid));
            assertThrows(SecurityException.class,()->
                    f.resolve(original,valid.withChecker(f.approve(
                            "CHECKER","maker","RESOLVE",original.id,
                            sha(original.payload()),UUID.randomUUID(),
                            NOW.plusSeconds(300),f.checkerKey))));
            assertThrows(SecurityException.class,()->
                    f.resolve(original,valid.withChecker(f.approve(
                            "CHECKER","checker","RESOLVE",original.id,
                            sha(original.payload()),UUID.randomUUID(),
                            NOW.minusSeconds(1),f.checkerKey))));
            assertThrows(SecurityException.class,()->
                    f.resolve(original,f.review("RESOLVE",original.id,
                            sha("other".getBytes(StandardCharsets.UTF_8)))));
            assertEquals(digest,f.digest());
            assertEquals(1,f.openExceptions());
            assertEquals(40L,f.suspense());
            f.resolve(original,valid);
            assertEquals(0L,f.suspense());
            assertEquals(0,f.openExceptions());
            f.assertLegacyUntouched();
            f.record("forged-evidence-and-reviewer-hold");
        }
    }

    @Test
    @Timeout(90)
    void concurrentIdenticalReleasePostsOnceAndApprovalCannotCrossExceptions()
            throws Exception {
        requiredDisposable();
        try(Fixture f=new Fixture()) {
            Evidence first=f.evidence("EX-01","BANK-01",25);
            Evidence second=f.evidence("EX-02","BANK-02",15);
            f.open(first);
            f.open(second);
            Review firstReview=f.review("RESOLVE",first.id,sha(first.payload()));
            ExecutorService pool=Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready=new CountDownLatch(4);
                CountDownLatch go=new CountDownLatch(1);
                java.util.List<Future<String>> results=new java.util.ArrayList<>();
                for(int i=0;i<4;i++) {
                    results.add(pool.submit(()->{
                        ready.countDown();
                        if(!go.await(10,TimeUnit.SECONDS))
                            throw new AssertionError("test synchronization timeout");
                        return f.resolve(first,firstReview);
                    }));
                }
                assertTrue(ready.await(10,TimeUnit.SECONDS));
                go.countDown();
                for(Future<String> result:results)
                    assertEquals("EX-01",result.get(35,TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            assertEquals(3,f.events());
            assertEquals(6,f.lines());
            assertEquals(1,f.openExceptions());
            assertEquals(15L,f.suspense());
            Review duplicateApproval=f.reviewWithCheckerId(
                    "RESOLVE",second.id,sha(second.payload()),
                    firstReview.checker.id);
            assertSqlState("23505",()->f.resolve(second,duplicateApproval));
            assertEquals(3,f.events());
            assertEquals(1,f.openExceptions());
            assertEquals(15L,f.suspense());
            f.resolve(second,f.review("RESOLVE",second.id,sha(second.payload())));
            assertEquals(4,f.events());
            assertEquals(8,f.lines());
            assertEquals(0L,f.suspense());
            assertEquals(PERIOD,f.closePeriod(f.closeReview()));
            f.assertLegacyUntouched();
            f.record("concurrent-idempotency-and-approval-reuse");
        }
    }

    @Test
    @Timeout(90)
    void deferredCloseAndUnbalancedSuspenseInjectionRollbackWithoutPartialRecord()
            throws Exception {
        requiredDisposable();
        try(Fixture f=new Fixture()) {
            String empty=f.digest();
            assertSqlState("23514",f::unsafeCloseWithoutReview);
            assertEquals(empty,f.digest());
            assertSqlState("23514",f::unsafeMissingCredit);
            assertEquals(empty,f.digest());
            assertEquals(0,f.exceptions());
            Evidence e=f.evidence("EX-01","BANK-01",7);
            f.open(e);
            String before=f.digest();
            assertSqlState("23514",f::unsafeResolveWithoutReleaseAndClose);
            assertEquals(before,f.digest(),
                    "force-resolved exception cannot close while suspense remains");
            assertEquals(1,f.openExceptions());
            assertEquals(7L,f.suspense());
            f.resolve(e,f.review("RESOLVE",e.id,sha(e.payload())));
            assertEquals(0L,f.suspense());
            f.closePeriod(f.closeReview());
            assertEquals("CLOSED",f.periodState());
            f.assertLegacyUntouched();
            f.record("deferred-close-and-suspense-rollback");
        }
    }

    private interface SqlTask { void run() throws Exception; }
    private static void assertSqlState(String state,SqlTask task) {
        SQLException problem=assertThrows(SQLException.class,task::run);
        assertEquals(state,problem.getSQLState());
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
                "requires exact-commit disposable PG16 GitHub Actions");
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
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] digits="0123456789abcdef".toCharArray();
        StringBuilder out=new StringBuilder(64);
        for(byte b:hash)out.append(digits[(b>>>4)&15]).append(digits[b&15]);
        return out.toString();
    }

    private static final class Evidence {
        final String id,period,source,currency;
        final long amount;
        final byte[] signature;
        Evidence(String id,String period,String source,long amount,String currency,
                byte[] signature) {
            this.id=id;this.period=period;this.source=source;
            this.amount=amount;this.currency=currency;
            this.signature=signature.clone();
        }
        byte[] payload() {
            return canonical("W34.17|FAKE_VARIANCE|v1",id,period,source,
                    Long.toString(amount),currency);
        }
        Evidence withAmount(long amount) {
            return new Evidence(id,period,source,amount,currency,signature);
        }
    }
    private static final class Approval {
        final String role,subject,action,target,digest;
        final UUID id;
        final Instant expiration;
        final byte[] signature;
        Approval(String role,String subject,String action,String target,
                String digest,UUID id,Instant expiration,byte[] signature) {
            this.role=role;this.subject=subject;this.action=action;
            this.target=target;this.digest=digest;this.id=id;
            this.expiration=expiration;this.signature=signature.clone();
        }
        byte[] payload() {
            return canonical("W34.17|TEST_APPROVAL|v1",role,subject,action,target,
                    digest,id.toString(),expiration.toString());
        }
    }
    private static final class Review {
        final String action,target,digest;
        final Approval maker,checker;
        Review(String action,String target,String digest,Approval maker,Approval checker) {
            this.action=action;this.target=target;this.digest=digest;
            this.maker=maker;this.checker=checker;
        }
        Review withChecker(Approval checker) {
            return new Review(action,target,digest,maker,checker);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="w3417_"+UUID.randomUUID().toString().replace("-","");
        final JdbcDatabase admin=new JdbcDatabase(URL,USER,PASSWORD);
        final JdbcDatabase scoped=new JdbcDatabase(
                URL+"?currentSchema="+schema,USER,PASSWORD);
        final KeyPair sourceKey=keyPair();
        final KeyPair makerKey=keyPair();
        final KeyPair checkerKey=keyPair();

        Fixture() throws Exception {
            assertTrue(schema.matches("w3417_[0-9a-f]{32}"));
            try(Connection c=admin.openConnection();Statement s=c.createStatement()) {
                try(ResultSet version=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int n=Integer.parseInt(version.getString(1));
                    assertTrue(n>=160000&&n<170000,"actual PostgreSQL16 required");
                }
                s.execute("CREATE SCHEMA "+schema);
            }
            scoped.migrate();
            String ddl=new String(Files.readAllBytes(Paths.get(DDL)),
                    StandardCharsets.UTF_8).replace("__SCHEMA__",schema);
            assertFalse(ddl.contains("__SCHEMA__"));
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute(ddl);
                s.execute("INSERT INTO w3417_periods(period_id,currency)"
                        +" VALUES('"+PERIOD+"','USD')");
            }
            assertEquals(5,schemaVersion());
        }

        Evidence evidence(String id,String ref,long amount) throws Exception {
            return evidenceSignedBy(id,ref,amount,sourceKey);
        }
        Evidence evidenceSignedBy(String id,String ref,long amount,KeyPair key)
                throws Exception {
            Evidence original=new Evidence(id,PERIOD,ref,amount,"USD",new byte[0]);
            return new Evidence(id,PERIOD,ref,amount,"USD",
                    sign(key,original.payload()));
        }
        Approval approve(String role,String subject,String action,String target,
                String digest,UUID id,Instant expires,KeyPair signer)
                throws Exception {
            Approval unsigned=new Approval(role,subject,action,target,
                    digest,id,expires,new byte[0]);
            return new Approval(role,subject,action,target,digest,id,
                    expires,sign(signer,unsigned.payload()));
        }
        Review review(String action,String target,String digest) throws Exception {
            return reviewWithCheckerId(action,target,digest,UUID.randomUUID());
        }
        Review reviewWithCheckerId(String action,String target,String digest,UUID checkerId)
                throws Exception {
            Approval maker=approve("MAKER","maker",action,target,digest,
                    UUID.randomUUID(),NOW.plusSeconds(300),makerKey);
            Approval checker=approve("CHECKER","checker",action,target,digest,
                    checkerId,NOW.plusSeconds(300),checkerKey);
            return new Review(action,target,digest,maker,checker);
        }
        Review closeReview() throws Exception {
            return review("CLOSE",PERIOD,closeDigest());
        }

        void verifyEvidence(Evidence e) throws Exception {
            if(!e.period.equals(PERIOD) || !"USD".equals(e.currency)
                    || !e.id.matches("EX-[0-9]{2}")
                    || e.source.trim().isEmpty() || e.amount<=0
                    || !valid(sourceKey.getPublic(),e.payload(),e.signature))
                throw new SecurityException("W3417 fake variance source invalid");
        }
        void verifyReview(Review r,String action,String target,String digest)
                throws Exception {
            if(!action.equals(r.action) || !target.equals(r.target)
                    || !digest.equals(r.digest)
                    || !r.maker.role.equals("MAKER")||!r.checker.role.equals("CHECKER")
                    || !r.maker.subject.equals("maker")||!r.checker.subject.equals("checker")
                    || r.maker.subject.equals(r.checker.subject)
                    || r.maker.id.equals(r.checker.id)
                    || !r.maker.action.equals(action)||!r.checker.action.equals(action)
                    || !r.maker.target.equals(target)||!r.checker.target.equals(target)
                    || !r.maker.digest.equals(digest)||!r.checker.digest.equals(digest)
                    || !r.maker.expiration.isAfter(NOW)
                    || !r.checker.expiration.isAfter(NOW)
                    || !valid(makerKey.getPublic(),r.maker.payload(),r.maker.signature)
                    || !valid(checkerKey.getPublic(),r.checker.payload(),r.checker.signature))
                throw new SecurityException("W3417 maker/checker synthetic authority HOLD");
        }

        void open(Evidence e) throws Exception {
            verifyEvidence(e);
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3417_exceptions"
                            +"(exception_id,period_id,source_ref,source_digest,"
                            +"amount_minor,currency) VALUES(?,?,?,?,?,'USD')")) {
                        p.setString(1,e.id);p.setString(2,e.period);
                        p.setString(3,e.source);p.setString(4,sha(e.payload()));
                        p.setLong(5,e.amount);assertEquals(1,p.executeUpdate());
                    }
                    post(c,"HOLD-"+e.id,e.id,"HOLD",e.amount,null);
                    c.commit();
                } catch(SQLException|RuntimeException failure) {
                    c.rollback();throw failure;
                }
            }
        }
        String resolve(Evidence e,Review approval) throws Exception {
            verifyEvidence(e);
            verifyReview(approval,"RESOLVE",e.id,sha(e.payload()));
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    String periodState=lockPeriod(c);
                    if(!"OPEN".equals(periodState))
                        throw new SecurityException("W3417 closed period cannot resolve");
                    boolean already;
                    try(PreparedStatement p=c.prepareStatement(
                            "SELECT source_digest,amount_minor,status,resolve_digest,"
                            +"maker_approval_id,checker_approval_id FROM w3417_exceptions"
                            +" WHERE exception_id=? FOR UPDATE")) {
                        p.setString(1,e.id);
                        try(ResultSet rs=p.executeQuery()) {
                            if(!rs.next() || !sha(e.payload()).equals(rs.getString(1))
                                    || e.amount!=rs.getLong(2))
                                throw new SecurityException("W3417 source mismatch");
                            already="RESOLVED".equals(rs.getString(3));
                            if(already && (!approval.digest.equals(rs.getString(4))
                                    || !approval.maker.id.equals(rs.getObject(5))
                                    || !approval.checker.id.equals(rs.getObject(6))))
                                throw new SecurityException("W3417 changed resolution replay");
                        }
                    }
                    if(!already) {
                        post(c,"RELEASE-"+e.id,e.id,"RELEASE",e.amount,
                                approval.checker.id);
                        try(PreparedStatement p=c.prepareStatement(
                                "UPDATE w3417_exceptions SET status='RESOLVED',"
                                +"resolve_digest=?,maker_id=?,checker_id=?,"
                                +"maker_approval_id=?,checker_approval_id=?"
                                +" WHERE exception_id=? AND status='OPEN'")) {
                            p.setString(1,approval.digest);
                            p.setString(2,approval.maker.subject);
                            p.setString(3,approval.checker.subject);
                            p.setObject(4,approval.maker.id);
                            p.setObject(5,approval.checker.id);
                            p.setString(6,e.id);
                            assertEquals(1,p.executeUpdate());
                        }
                    }
                    c.commit();return e.id;
                } catch(SQLException|RuntimeException failure) {
                    c.rollback();throw failure;
                }
            }
        }
        String closePeriod(Review approval) throws Exception {
            verifyReview(approval,"CLOSE",PERIOD,approval.digest);
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    if(!"OPEN".equals(lockPeriod(c)))
                        throw new SecurityException("W3417 period already closed");
                    if(!closeDigest(c).equals(approval.digest))
                        throw new SecurityException("W3417 close snapshot drift");
                    insertCloseReview(c,approval.digest,approval.maker,approval.checker);
                    try(PreparedStatement p=c.prepareStatement(
                            "UPDATE w3417_periods SET state='CLOSED',"
                            +"closed_at=clock_timestamp(),close_digest=?"
                            +" WHERE period_id=? AND state='OPEN'")) {
                        p.setString(1,approval.digest);
                        p.setString(2,PERIOD);
                        assertEquals(1,p.executeUpdate());
                    }
                    c.commit();return PERIOD;
                } catch(SQLException|RuntimeException failure) {
                    c.rollback();throw failure;
                }
            }
        }
        void insertCloseReview(Connection c,String digest,Approval maker,
                Approval checker) throws SQLException {
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO w3417_close_reviews(period_id,review_digest,"
                    +"maker_id,checker_id,maker_approval_id,checker_approval_id)"
                    +" VALUES(?,?,?,?,?,?)")) {
                p.setString(1,PERIOD);p.setString(2,digest);
                p.setString(3,maker.subject);p.setString(4,checker.subject);
                p.setObject(5,maker.id);p.setObject(6,checker.id);
                assertEquals(1,p.executeUpdate());
            }
        }
        String lockPeriod(Connection c) throws SQLException {
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT state FROM w3417_periods WHERE period_id=? FOR UPDATE")) {
                p.setString(1,PERIOD);
                try(ResultSet rs=p.executeQuery()) {
                    assertTrue(rs.next());return rs.getString(1);
                }
            }
        }
        void post(Connection c,String event,String exception,String kind,
                long amount,UUID approvalId) throws SQLException {
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO w3417_suspense_events"
                    +"(event_id,exception_id,kind,amount_minor,currency,approval_id)"
                    +" VALUES(?,?,?,?,'USD',?)")) {
                p.setString(1,event);p.setString(2,exception);
                p.setString(3,kind);p.setLong(4,amount);
                if(approvalId==null)p.setNull(5,java.sql.Types.OTHER);
                else p.setObject(5,approvalId);
                assertEquals(1,p.executeUpdate());
            }
            boolean hold="HOLD".equals(kind);
            line(c,event,1,hold?"1390":"1990","D",amount);
            line(c,event,2,hold?"1990":"1390","C",amount);
        }
        void line(Connection c,String event,int number,String account,
                String side,long amount) throws SQLException {
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO w3417_suspense_lines(event_id,line_no,"
                    +"account_code,side,amount_minor,currency)"
                    +" VALUES(?,?,?,?,?,'USD')")) {
                p.setString(1,event);p.setInt(2,number);
                p.setString(3,account);p.setString(4,side);
                p.setLong(5,amount);assertEquals(1,p.executeUpdate());
            }
        }
        void unsafeCloseWithoutReview() throws Exception {
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    raw(c,"UPDATE w3417_periods SET state='CLOSED',"
                            +"close_digest='"+zeros()+"',closed_at=clock_timestamp()"
                            +" WHERE period_id='"+PERIOD+"'");
                    c.commit();
                } catch(SQLException|RuntimeException problem) {
                    c.rollback();throw problem;
                }
            }
        }
        void unsafeMissingCredit() throws Exception {
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    raw(c,"INSERT INTO w3417_exceptions"
                            +"(exception_id,period_id,source_ref,source_digest,"
                            +"amount_minor,currency) VALUES('EX-09','"+PERIOD+"',"
                            +"'BANK-09','"+zeros()+"',10,'USD')");
                    raw(c,"INSERT INTO w3417_suspense_events"
                            +"(event_id,exception_id,kind,amount_minor,currency)"
                            +" VALUES('HOLD-EX-09','EX-09','HOLD',10,'USD')");
                    line(c,"HOLD-EX-09",1,"1390","D",10);
                    c.commit();
                } catch(SQLException|RuntimeException problem) {
                    c.rollback();throw problem;
                }
            }
        }
        void unsafeResolveWithoutReleaseAndClose() throws Exception {
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    raw(c,"UPDATE w3417_exceptions SET status='RESOLVED',"
                            +"resolve_digest='"+zeros()+"',maker_id='maker',"
                            +"checker_id='checker',maker_approval_id='"
                            +UUID.randomUUID()+"',checker_approval_id='"
                            +UUID.randomUUID()+"' WHERE exception_id='EX-01'");
                    raw(c,"INSERT INTO w3417_close_reviews"
                            +"(period_id,review_digest,maker_id,checker_id,"
                            +"maker_approval_id,checker_approval_id) VALUES('"
                            +PERIOD+"','"+zeros()+"','maker','checker','"
                            +UUID.randomUUID()+"','"+UUID.randomUUID()+"')");
                    raw(c,"UPDATE w3417_periods SET state='CLOSED',"
                            +"close_digest='"+zeros()+"',closed_at=clock_timestamp()"
                            +" WHERE period_id='"+PERIOD+"'");
                    c.commit();
                } catch(SQLException|RuntimeException problem) {
                    c.rollback();throw problem;
                }
            }
        }
        String zeros() {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
        void raw(Connection c,String sql) throws SQLException {
            try(Statement s=c.createStatement()) {s.execute(sql);}
        }
        void raw(String sql) throws SQLException {
            try(Connection c=scoped.openConnection()) {raw(c,sql);}
        }
        long scalar(String sql) throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet result=s.executeQuery(sql)) {
                assertTrue(result.next());return result.getLong(1);
            }
        }
        int exceptions() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3417_exceptions");
        }
        int openExceptions() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3417_exceptions WHERE status='OPEN'");
        }
        int events() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3417_suspense_events");
        }
        int lines() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3417_suspense_lines");
        }
        int closeReviews() throws SQLException {
            return (int)scalar("SELECT COUNT(*) FROM w3417_close_reviews");
        }
        long suspense() throws SQLException {
            return scalar("SELECT COALESCE(SUM(CASE WHEN kind='HOLD' THEN amount_minor"
                    +" ELSE -amount_minor END),0) FROM w3417_suspense_events");
        }
        long debits() throws SQLException {
            return scalar("SELECT COALESCE(SUM(amount_minor),0) FROM"
                    +" w3417_suspense_lines WHERE side='D'");
        }
        long credits() throws SQLException {
            return scalar("SELECT COALESCE(SUM(amount_minor),0) FROM"
                    +" w3417_suspense_lines WHERE side='C'");
        }
        String periodState() throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery("SELECT state FROM w3417_periods")) {
                assertTrue(rs.next());return rs.getString(1);
            }
        }
        String closeDigest() throws Exception {
            try(Connection c=scoped.openConnection()) {return closeDigest(c);}
        }
        String closeDigest(Connection c) throws Exception {
            StringBuilder data=new StringBuilder(PERIOD).append("|");
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery(
                    "SELECT exception_id,status,source_digest,resolve_digest"
                    +" FROM w3417_exceptions ORDER BY exception_id")) {
                while(r.next()) {
                    for(int i=1;i<=4;i++)
                        data.append(r.getString(i)).append('\u0000');
                }
            }
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery(
                    "SELECT event_id,kind,amount_minor FROM w3417_suspense_events"
                    +" ORDER BY event_id")) {
                while(r.next())
                    for(int i=1;i<=3;i++)
                        data.append(r.getString(i)).append('\u0000');
            }
            return sha(data.toString().getBytes(StandardCharsets.UTF_8));
        }
        String digest() throws Exception {
            StringBuilder state=new StringBuilder();
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                            "SELECT exception_id,status,resolve_digest FROM"
                            +" w3417_exceptions ORDER BY exception_id")) {
                while(rs.next())
                    for(int i=1;i<=3;i++)
                        state.append(rs.getString(i)).append('\u0000');
            }
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                            "SELECT event_id,kind,amount_minor FROM w3417_suspense_events"
                            +" ORDER BY event_id")) {
                while(rs.next())
                    for(int i=1;i<=3;i++)
                        state.append(rs.getString(i)).append('\u0000');
            }
            state.append("period=").append(periodState())
                    .append(";reviews=").append(closeReviews())
                    .append(";dr=").append(debits()).append(";cr=").append(credits())
                    .append(";net=").append(suspense());
            return sha(state.toString().getBytes(StandardCharsets.UTF_8));
        }
        int schemaVersion() throws SQLException {
            return (int)scalar("SELECT MAX(version) FROM schema_history");
        }
        void assertLegacyUntouched() throws SQLException {
            assertEquals(0L,scalar("SELECT COUNT(*) FROM payment_transactions"));
            assertEquals(0L,scalar("SELECT COUNT(*) FROM payment_plans"));
            assertEquals(5,schemaVersion());
        }
        void record(String scenario) throws Exception {
            Path dir=Paths.get("target/wave34-17-evidence");
            Files.createDirectories(dir);
            String json="{\n"
                    +"\"scenario\":\""+scenario+"\",\n"
                    +"\"source_head\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                    +"\"fixture\":\"DISPOSABLE_PG16_TEST_ONLY_SUSPENSE_CLOSE\",\n"
                    +"\"schema_version\":"+schemaVersion()+",\n"
                    +"\"synthetic_exceptions\":"+exceptions()+",\n"
                    +"\"synthetic_open_exceptions\":"+openExceptions()+",\n"
                    +"\"synthetic_suspense_events\":"+events()+",\n"
                    +"\"synthetic_suspense_net_minor\":"+suspense()+",\n"
                    +"\"synthetic_suspense_debits_minor\":"+debits()+",\n"
                    +"\"synthetic_suspense_credits_minor\":"+credits()+",\n"
                    +"\"legacy_payment_entries\":"
                    +scalar("SELECT COUNT(*) FROM payment_transactions")+",\n"
                    +"\"reconciliation_digest_sha256\":\""+digest()+"\",\n"
                    +"\"independent_source_verified\":false,\n"
                    +"\"independent_finance_approval_verified\":false,\n"
                    +"\"production_period_close_verified\":false,\n"
                    +"\"real_funds_moved\":false,\n"
                    +"\"production_authorized\":false,\n"
                    +"\"release_disposition\":\"NO-GO\"\n"
                    +"}\n";
            Files.write(dir.resolve(scenario+".json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() throws Exception {
            try(Connection c=admin.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
            }
        }
    }
}
