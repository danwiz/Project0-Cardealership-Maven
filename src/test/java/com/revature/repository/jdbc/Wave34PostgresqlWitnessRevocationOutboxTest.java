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
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W34.19: one-JVM simulated witness and finance approvals, with a disposable
 * PG16 local outbox. No independent witness custody, real IDP, financial
 * signing authority, production GL, real delivery or exactly-once transport.
 * The CI PostgreSQL fixture owner may bypass every local SQL guard.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlWitnessRevocationOutboxTest {
    private static final String URL="jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER="dealership_ci_owner";
    private static final String PASSWORD="ci-only-postgres-fixture";
    private static final String OLD="2026-10", CURRENT="2026-11";
    private static final String ZERO=
        "0000000000000000000000000000000000000000000000000000000000000000";
    private static final Instant NOW=Instant.parse("2026-11-09T20:00:00Z");
    private static final String DDL=
        "docs/audit/wave34/wave34_19_DISPOSABLE_witness_revocation_outbox.sql";

    @Test
    @Timeout(90)
    void signedLabWitnessCheckpointDetectsTamperedPrimaryWithoutIndependentCustody()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request a=f.request("ADJ-01","FAKE-SOURCE-01",20);
            Request b=f.request("ADJ-02","FAKE-SOURCE-02",10);
            f.submit(a,f.review(a,false));
            f.submit(b,f.review(b,false));
            assertEquals(2,f.adjustments());
            assertEquals(2,f.chainEntries());
            assertTrue(f.verifyChain());
            f.anchor();
            assertEquals(1,f.witnesses());
            assertTrue(f.verifyWitness());
            assertSqlState("55000",()->f.raw(
                "UPDATE w3419_chain SET event_hash='"+ZERO+"' WHERE chain_sequence=1"));

            // Owner-only fault injection bypasses the *test-only* SQL trigger.
            // The local test-generated witness remains unchanged and detects it.
            String original=f.chainHash(1);
            f.privilegedChangeChainHash(ZERO);
            assertFalse(f.verifyChain());
            assertFalse(f.verifyWitness());
            f.privilegedChangeChainHash(original);
            assertTrue(f.verifyChain());
            assertTrue(f.verifyWitness());
            f.assertLegacyUntouched();
            f.evidence("simulated-witness-detects-primary-tamper");
        }
    }

    @Test
    @Timeout(90)
    void revocationCommittedWhileStaleApprovalWaitsMustDenySqlInsert()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request over=f.request("ADJ-01","FAKE-SOURCE-01",60);
            assertSqlState("42501",()->f.submit(over,f.review(over,false)));
            assertEquals(0,f.adjustments());

            Request r=f.request("ADJ-01","FAKE-SOURCE-01",25);
            assertThrows(SecurityException.class,()->f.submit(r,f.expired(r)));
            assertThrows(SecurityException.class,()->f.submit(r,f.forged(r)));
            assertThrows(SecurityException.class,()->f.submit(r,f.selfApproved(r)));

            Review stale=f.review(r,false); // Signed before checker revocation.
            ExecutorService workers=Executors.newSingleThreadExecutor();
            try(Connection revoker=f.scoped.openConnection()) {
                revoker.setAutoCommit(false);
                try(Statement statement=revoker.createStatement()) {
                    assertEquals(1,statement.executeUpdate(
                        "UPDATE w3419_principals SET active=FALSE,"
                        +"revoked_at=clock_timestamp() WHERE principal_id='checker'"));
                }
                CountDownLatch entered=new CountDownLatch(1);
                Future<String> pending=workers.submit(()->{
                    entered.countDown();
                    return f.submit(r,stale);
                });
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                // SQL trigger must wait on the CHECKER principal row lock.
                assertThrows(TimeoutException.class,()->
                    pending.get(300,TimeUnit.MILLISECONDS));
                revoker.commit();
                ExecutionException denial=assertThrows(ExecutionException.class,
                    ()->pending.get(30,TimeUnit.SECONDS));
                assertTrue(denial.getCause() instanceof SQLException);
                assertEquals("42501",((SQLException)denial.getCause()).getSQLState());
            } finally {
                workers.shutdownNow();
            }
            assertEquals(0,f.adjustments());
            assertEquals(0,f.outboxMessages());
            assertSqlState("55000",()->f.raw(
                "UPDATE w3419_principals SET active=TRUE,revoked_at=NULL"
                +" WHERE principal_id='checker'"));

            assertEquals("ADJ-01",f.submit(r,f.review(r,true)));
            assertEquals(1,f.adjustments());
            assertEquals(1,f.outboxMessages());
            assertTrue(f.verifyChain());
            f.assertLegacyUntouched();
            f.evidence("revocation-at-sql-insert-lock");
        }
    }

    @Test
    @Timeout(90)
    void crashBeforeAckPermitsRedeliveryButOneLocalReceiptAndOutbox()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request r=f.request("ADJ-01","FAKE-SOURCE-01",12);
            f.submit(r,f.review(r,false));
            assertEquals(1,f.outboxMessages());
            assertEquals("PENDING",f.outboxState("MSG-ADJ-01"));
            String first=f.deliverWithoutAck("MSG-ADJ-01");
            String retry=f.deliverWithoutAck("MSG-ADJ-01");
            assertEquals(first,retry,
                "A crash before ACK can cause duplicate external delivery");
            assertEquals(0,f.deliveryReceipts());
            assertEquals("PENDING",f.outboxState("MSG-ADJ-01"));

            assertEquals("MSG-ADJ-01",f.ack("MSG-ADJ-01","ACK-01",first));
            assertEquals("MSG-ADJ-01",f.ack("MSG-ADJ-01","ACK-01",first));
            assertEquals(1,f.deliveryReceipts());
            assertEquals(1,f.outboxMessages());
            assertEquals("ACKED",f.outboxState("MSG-ADJ-01"));
            assertThrows(SecurityException.class,()->
                f.ack("MSG-ADJ-01","ACK-02",first));
            assertSqlState("55000",()->f.raw(
                "DELETE FROM w3419_outbox WHERE message_id='MSG-ADJ-01'"));
            assertTrue(f.verifyChain());
            f.assertLegacyUntouched();
            f.evidence("crash-before-ack-local-outbox-replay");
        }
    }

    @Test
    @Timeout(90)
    void concurrentExactEnqueueIsIdempotentAndApprovalReuseAcrossIdsHeld()
            throws Exception {
        requireDisposable();
        try(Fixture f=new Fixture()) {
            Request r=f.request("ADJ-01","FAKE-SOURCE-01",20);
            Review review=f.review(r,false);
            ExecutorService workers=Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready=new CountDownLatch(4);
                CountDownLatch go=new CountDownLatch(1);
                java.util.List<Future<String>> results=new java.util.ArrayList<>();
                for(int i=0;i<4;i++) {
                    results.add(workers.submit(()->{
                        ready.countDown();
                        if(!go.await(10,TimeUnit.SECONDS))
                            throw new AssertionError("W3419 concurrency latch timeout");
                        return f.submit(r,review);
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
            assertEquals(1,f.chainEntries());
            assertEquals(1,f.outboxMessages());

            Request second=f.request("ADJ-02","FAKE-SOURCE-02",10);
            Review recycled=f.reviewWithIds(second,false,
                review.maker.id,review.checker.id);
            assertThrows(SecurityException.class,()->
                f.submit(second,recycled));
            assertEquals(1,f.adjustments());
            assertEquals(1,f.outboxMessages());

            assertEquals("ADJ-02",f.submit(second,f.review(second,false)));
            assertEquals(2,f.adjustments());
            assertEquals(2,f.chainEntries());
            assertEquals(2,f.outboxMessages());
            assertTrue(f.verifyChain());
            f.assertLegacyUntouched();
            f.evidence("concurrent-adjustment-enqueue-and-approval-reuse");
        }
    }

    private interface SqlTask {void run() throws Exception;}
    private static void assertSqlState(String expected,SqlTask task) {
        SQLException error=assertThrows(SQLException.class,task::run);
        assertEquals(expected,error.getSQLState());
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
            "W34.19 runs only on exact-head disposable PostgreSQL16 CI");
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
    private static boolean valid(PublicKey key,byte[] payload,byte[] sig)
            throws GeneralSecurityException {
        Signature verifier=Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(payload);
        return verifier.verify(sig);
    }
    private static String sha(byte[] bytes) throws GeneralSecurityException {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] chars="0123456789abcdef".toCharArray();
        StringBuilder out=new StringBuilder(64);
        for(byte b:hash)
            out.append(chars[(b>>>4)&15]).append(chars[b&15]);
        return out.toString();
    }

    private static final class Request {
        final String id,sourceRef,sourceDigest,historical,posting,currency;
        final long amount;
        Request(String id,String sourceRef,String sourceDigest,String old,
                String now,String currency,long amount) {
            this.id=id;this.sourceRef=sourceRef;this.sourceDigest=sourceDigest;
            this.historical=old;this.posting=now;this.currency=currency;
            this.amount=amount;
        }
        byte[] payload() {
            return canonical("W34.19|FAKE_REQUEST|v1",id,sourceRef,sourceDigest,
                historical,posting,currency,Long.toString(amount));
        }
    }
    private static final class Approval {
        final String role,subject,target,requestDigest;
        final UUID id;
        final Instant expires;
        final byte[] signature;
        Approval(String role,String subject,Request request,UUID id,
                Instant expires,byte[] signature) throws Exception {
            this.role=role;this.subject=subject;this.target=request.id;
            this.requestDigest=sha(request.payload());this.id=id;
            this.expires=expires;this.signature=signature.clone();
        }
        byte[] payload() {
            return canonical("W34.19|FAKE_APPROVAL|v1",role,subject,target,
                requestDigest,id.toString(),expires.toString());
        }
    }
    private static final class Review {
        final Approval maker,checker;
        Review(Approval maker,Approval checker) {
            this.maker=maker;this.checker=checker;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="w3419_"+UUID.randomUUID().toString().replace("-","");
        final JdbcDatabase admin=new JdbcDatabase(URL,USER,PASSWORD);
        final JdbcDatabase scoped=new JdbcDatabase(
            URL+"?currentSchema="+schema,USER,PASSWORD);
        final KeyPair makerKey=keyPair(),checkerKey=keyPair();
        final KeyPair checkerAltKey=keyPair(),witnessKey=keyPair();

        Fixture() throws Exception {
            assertTrue(schema.matches("w3419_[0-9a-f]{32}"));
            try(Connection c=admin.openConnection();Statement s=c.createStatement()) {
                try(ResultSet v=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(v.next());
                    int number=Integer.parseInt(v.getString(1));
                    assertTrue(number>=160000&&number<170000,"PG16 required");
                }
                s.execute("CREATE SCHEMA "+schema);
            }
            scoped.migrate();
            String ddl=new String(Files.readAllBytes(Paths.get(DDL)),
                    StandardCharsets.UTF_8).replace("__SCHEMA__",schema);
            assertFalse(ddl.contains("__SCHEMA__"));
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute(ddl);
                s.execute("INSERT INTO w3419_periods(period_id,state) VALUES"
                    +"('"+OLD+"','CLOSED'),('"+CURRENT+"','OPEN')");
                s.execute("INSERT INTO w3419_principals"
                    +"(principal_id,authority_role,max_minor) VALUES"
                    +"('maker','MAKER',50),('checker','CHECKER',50),"
                    +"('checker-alt','CHECKER',50)");
            }
            assertEquals(5,schemaVersion());
        }

        Request request(String id,String ref,long amount) throws Exception {
            String sourceDigest=sha(canonical("W34.19|FAKE_SOURCE|v1",
                    id,ref,Long.toString(amount)));
            return new Request(id,ref,sourceDigest,OLD,CURRENT,"USD",amount);
        }
        Approval approve(String role,String subject,Request req,UUID id,
                Instant expiry,KeyPair signer) throws Exception {
            Approval unsigned=new Approval(role,subject,req,id,expiry,new byte[0]);
            return new Approval(role,subject,req,id,expiry,
                    sign(signer,unsigned.payload()));
        }
        Review review(Request req,boolean alt) throws Exception {
            return reviewWithIds(req,alt,UUID.randomUUID(),UUID.randomUUID());
        }
        Review reviewWithIds(Request req,boolean alt,UUID maker,UUID checker)
                throws Exception {
            return new Review(
                approve("MAKER","maker",req,maker,NOW.plusSeconds(300),makerKey),
                approve("CHECKER",alt?"checker-alt":"checker",req,checker,
                    NOW.plusSeconds(300),alt?checkerAltKey:checkerKey));
        }
        Review expired(Request r) throws Exception {
            return new Review(
                approve("MAKER","maker",r,UUID.randomUUID(),NOW.plusSeconds(300),
                    makerKey),
                approve("CHECKER","checker",r,UUID.randomUUID(),
                    NOW.minusSeconds(1),checkerKey));
        }
        Review forged(Request r) throws Exception {
            return new Review(
                approve("MAKER","maker",r,UUID.randomUUID(),NOW.plusSeconds(300),
                    makerKey),
                approve("CHECKER","checker",r,UUID.randomUUID(),
                    NOW.plusSeconds(300),keyPair()));
        }
        Review selfApproved(Request r) throws Exception {
            return new Review(
                approve("MAKER","maker",r,UUID.randomUUID(),NOW.plusSeconds(300),
                    makerKey),
                approve("CHECKER","maker",r,UUID.randomUUID(),
                    NOW.plusSeconds(300),makerKey));
        }

        void verify(Request r,Review v) throws Exception {
            String expected=sha(canonical("W34.19|FAKE_SOURCE|v1",
                r.id,r.sourceRef,Long.toString(r.amount)));
            if(!r.id.matches("ADJ-[0-9]{2}")
                || r.sourceRef.trim().isEmpty()
                || !r.sourceDigest.equals(expected)
                || !r.historical.equals(OLD) || !r.posting.equals(CURRENT)
                || !"USD".equals(r.currency) || r.amount<=0
                || v.maker.id.equals(v.checker.id)
                || !"MAKER".equals(v.maker.role)
                || !"CHECKER".equals(v.checker.role)
                || !"maker".equals(v.maker.subject)
                || !("checker".equals(v.checker.subject)
                     || "checker-alt".equals(v.checker.subject))
                || v.maker.subject.equals(v.checker.subject)
                || !r.id.equals(v.maker.target)||!r.id.equals(v.checker.target)
                || !v.maker.requestDigest.equals(sha(r.payload()))
                || !v.checker.requestDigest.equals(sha(r.payload()))
                || !v.maker.expires.isAfter(NOW)
                || !v.checker.expires.isAfter(NOW)
                || !valid(makerKey.getPublic(),v.maker.payload(),v.maker.signature)
                || !valid("checker".equals(v.checker.subject)
                    ?checkerKey.getPublic():checkerAltKey.getPublic(),
                    v.checker.payload(),v.checker.signature))
                throw new SecurityException("W3419 forged or expired lab approval HOLD");
        }
        String claim(Request r,Review approval) throws Exception {
            return sha(canonical("W34.19|CLAIM|v1",sha(r.payload()),
                approval.maker.id.toString(),approval.checker.id.toString()));
        }
        String submit(Request r,Review review) throws Exception {
            verify(r,review);
            String requestDigest=sha(r.payload()),digest=claim(r,review);
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    long seq;
                    String previous;
                    try(PreparedStatement p=c.prepareStatement(
                            "SELECT last_sequence,last_hash FROM w3419_chain_cursor"
                            +" WHERE singleton_id=1 FOR UPDATE");
                            ResultSet rs=p.executeQuery()) {
                        assertTrue(rs.next());
                        seq=rs.getLong(1)+1;
                        previous=rs.getString(2);
                    }
                    try(PreparedStatement p=c.prepareStatement(
                            "SELECT source_ref,source_digest,historical_period,"
                            +"posting_period,currency,amount_minor,request_digest,"
                            +"claim_digest,maker_id,checker_id,maker_approval_id,"
                            +"checker_approval_id FROM w3419_adjustments"
                            +" WHERE adjustment_id=?")) {
                        p.setString(1,r.id);
                        try(ResultSet rs=p.executeQuery()) {
                            if(rs.next()) {
                                if(!r.sourceRef.equals(rs.getString(1))
                                    || !r.sourceDigest.equals(rs.getString(2))
                                    || !r.historical.equals(rs.getString(3))
                                    || !r.posting.equals(rs.getString(4))
                                    || !r.currency.equals(rs.getString(5))
                                    || r.amount!=rs.getLong(6)
                                    || !requestDigest.equals(rs.getString(7))
                                    || !digest.equals(rs.getString(8))
                                    || !review.maker.subject.equals(rs.getString(9))
                                    || !review.checker.subject.equals(rs.getString(10))
                                    || !review.maker.id.equals(rs.getObject(11))
                                    || !review.checker.id.equals(rs.getObject(12)))
                                    throw new SecurityException("W3419 changed replay HOLD");
                                try(PreparedStatement outbox=c.prepareStatement(
                                        "SELECT payload_digest FROM w3419_outbox"
                                        +" WHERE adjustment_id=?")) {
                                    outbox.setString(1,r.id);
                                    try(ResultSet message=outbox.executeQuery()) {
                                        if(!message.next()
                                            || !digest.equals(message.getString(1)))
                                            throw new SecurityException("W3419 replay has no outbox");
                                    }
                                }
                                c.commit();
                                return r.id;
                            }
                        }
                    }

                    int inserted;
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3419_adjustments"
                            +"(adjustment_id,source_ref,source_digest,historical_period,"
                            +"posting_period,currency,amount_minor,request_digest,"
                            +"claim_digest,maker_id,checker_id,maker_approval_id,"
                            +"checker_approval_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
                            +" ON CONFLICT DO NOTHING")) {
                        p.setString(1,r.id);p.setString(2,r.sourceRef);
                        p.setString(3,r.sourceDigest);p.setString(4,r.historical);
                        p.setString(5,r.posting);p.setString(6,r.currency);
                        p.setLong(7,r.amount);p.setString(8,requestDigest);
                        p.setString(9,digest);p.setString(10,review.maker.subject);
                        p.setString(11,review.checker.subject);
                        p.setObject(12,review.maker.id);
                        p.setObject(13,review.checker.id);
                        inserted=p.executeUpdate();
                    }
                    if(inserted!=1)
                        throw new SecurityException(
                            "W3419 cross-adjustment approval or source collision HOLD");
                    String eventHash=sha(canonical("W34.19|LINK|v1",previous,digest));
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3419_chain(chain_sequence,adjustment_id,"
                            +"previous_hash,event_digest,event_hash)"
                            +" VALUES(?,?,?,?,?)")) {
                        p.setLong(1,seq);p.setString(2,r.id);
                        p.setString(3,previous);p.setString(4,digest);
                        p.setString(5,eventHash);assertEquals(1,p.executeUpdate());
                    }
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3419_outbox(message_id,adjustment_id,"
                            +"payload_digest) VALUES(?,?,?)")) {
                        p.setString(1,"MSG-"+r.id);p.setString(2,r.id);
                        p.setString(3,digest);assertEquals(1,p.executeUpdate());
                    }
                    try(PreparedStatement p=c.prepareStatement(
                            "UPDATE w3419_chain_cursor SET last_sequence=?,last_hash=?"
                            +" WHERE singleton_id=1")) {
                        p.setLong(1,seq);p.setString(2,eventHash);
                        assertEquals(1,p.executeUpdate());
                    }
                    c.commit();
                    return r.id;
                } catch(Exception problem) {
                    c.rollback();
                    throw problem;
                }
            }
        }

        boolean verifyChain() throws Exception {
            long sequence=0;
            String previous=ZERO;
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                        "SELECT ch.chain_sequence,ch.previous_hash,ch.event_digest,"
                        +"ch.event_hash,a.adjustment_id,a.source_ref,a.source_digest,"
                        +"a.historical_period,a.posting_period,a.currency,"
                        +"a.amount_minor,a.request_digest,a.claim_digest,"
                        +"a.maker_approval_id,a.checker_approval_id"
                        +" FROM w3419_chain ch JOIN w3419_adjustments a"
                        +" ON a.adjustment_id=ch.adjustment_id"
                        +" ORDER BY ch.chain_sequence")) {
                while(rs.next()) {
                    sequence++;
                    if(rs.getLong(1)!=sequence||!previous.equals(rs.getString(2)))
                        return false;
                    Request r=new Request(rs.getString(5),rs.getString(6),
                        rs.getString(7),rs.getString(8),rs.getString(9),
                        rs.getString(10),rs.getLong(11));
                    String req=sha(r.payload());
                    String claim=sha(canonical("W34.19|CLAIM|v1",req,
                        rs.getObject(14).toString(),rs.getObject(15).toString()));
                    String eventHash=sha(canonical("W34.19|LINK|v1",
                        previous,claim));
                    if(!req.equals(rs.getString(12))
                        || !claim.equals(rs.getString(3))
                        || !claim.equals(rs.getString(13))
                        || !eventHash.equals(rs.getString(4)))
                        return false;
                    previous=eventHash;
                }
            }
            try(Connection c=scoped.openConnection();
                    PreparedStatement p=c.prepareStatement(
                        "SELECT last_sequence,last_hash FROM w3419_chain_cursor"
                        +" WHERE singleton_id=1");ResultSet rs=p.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1)==sequence && previous.equals(rs.getString(2))
                    && sequence==adjustments()&&sequence==outboxMessages();
            }
        }
        byte[] witnessPayload(long sequence,String hash) {
            return canonical("W34.19|SIMULATED_WITNESS|v1","simulated-witness",
                Long.toString(sequence),hash);
        }
        void anchor() throws Exception {
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    long n;
                    String tip;
                    try(PreparedStatement p=c.prepareStatement(
                            "SELECT last_sequence,last_hash FROM w3419_chain_cursor"
                            +" WHERE singleton_id=1 FOR SHARE");
                            ResultSet rs=p.executeQuery()) {
                        assertTrue(rs.next());n=rs.getLong(1);tip=rs.getString(2);
                    }
                    assertTrue(n>0);
                    String sig=Base64.getEncoder().encodeToString(
                        sign(witnessKey,witnessPayload(n,tip)));
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3419_witness_receipts"
                            +"(anchor_sequence,tip_hash,witness_id,signature_b64)"
                            +" VALUES(?,?,'simulated-witness',?)")) {
                        p.setLong(1,n);p.setString(2,tip);p.setString(3,sig);
                        assertEquals(1,p.executeUpdate());
                    }
                    c.commit();
                } catch(Exception e) {
                    c.rollback();throw e;
                }
            }
        }
        boolean verifyWitness() throws Exception {
            if(!verifyChain())return false;
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                        "SELECT anchor_sequence,tip_hash,signature_b64"
                        +" FROM w3419_witness_receipts"
                        +" ORDER BY anchor_sequence DESC LIMIT 1")) {
                if(!rs.next())return false;
                long seq=rs.getLong(1);
                String tip=rs.getString(2);
                if(seq!=cursorSequence()||!tip.equals(cursorHash()))return false;
                return valid(witnessKey.getPublic(),witnessPayload(seq,tip),
                    Base64.getDecoder().decode(rs.getString(3)));
            }
        }
        void privilegedChangeChainHash(String newHash) throws SQLException {
            // This is an explicit malicious-DB-owner simulation, NOT WORM.
            raw("ALTER TABLE w3419_chain DISABLE TRIGGER w3419_chain_immutable");
            try {
                raw("UPDATE w3419_chain SET event_hash='"+newHash+"'"
                    +" WHERE chain_sequence=1");
            } finally {
                raw("ALTER TABLE w3419_chain ENABLE TRIGGER w3419_chain_immutable");
            }
        }
        String chainHash(long sequence) throws SQLException {
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "SELECT event_hash FROM w3419_chain WHERE chain_sequence=?")) {
                p.setLong(1,sequence);
                try(ResultSet rs=p.executeQuery()) {
                    assertTrue(rs.next());return rs.getString(1);
                }
            }
        }
        String cursorHash() throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(
                        "SELECT last_hash FROM w3419_chain_cursor WHERE singleton_id=1")) {
                assertTrue(rs.next());return rs.getString(1);
            }
        }
        long cursorSequence() throws SQLException {
            return scalar("SELECT last_sequence FROM w3419_chain_cursor WHERE singleton_id=1");
        }

        String deliverWithoutAck(String message) throws SQLException {
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "SELECT payload_digest FROM w3419_outbox WHERE message_id=?"
                    +" AND state='PENDING'")) {
                p.setString(1,message);
                try(ResultSet rs=p.executeQuery()) {
                    assertTrue(rs.next());return rs.getString(1);
                }
            }
        }
        String ack(String message,String receipt,String digest) throws Exception {
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    String state;
                    String expected;
                    String previousAck;
                    try(PreparedStatement p=c.prepareStatement(
                            "SELECT state,payload_digest,ack_ref FROM w3419_outbox"
                            +" WHERE message_id=? FOR UPDATE")) {
                        p.setString(1,message);
                        try(ResultSet rs=p.executeQuery()) {
                            if(!rs.next())throw new SecurityException("unknown outbox");
                            state=rs.getString(1);expected=rs.getString(2);
                            previousAck=rs.getString(3);
                        }
                    }
                    if(!digest.equals(expected))
                        throw new SecurityException("outbox digest mismatch");
                    if("ACKED".equals(state)) {
                        if(!receipt.equals(previousAck))
                            throw new SecurityException("changed delivery ACK replay");
                        c.commit();return message;
                    }
                    try(PreparedStatement p=c.prepareStatement(
                            "INSERT INTO w3419_delivery_receipts"
                            +"(ack_ref,message_id,payload_digest) VALUES(?,?,?)")) {
                        p.setString(1,receipt);p.setString(2,message);
                        p.setString(3,digest);assertEquals(1,p.executeUpdate());
                    }
                    try(PreparedStatement p=c.prepareStatement(
                            "UPDATE w3419_outbox SET state='ACKED',ack_ref=?,"
                            +"acknowledged_at=clock_timestamp() WHERE message_id=?"
                            +" AND state='PENDING'")) {
                        p.setString(1,receipt);p.setString(2,message);
                        assertEquals(1,p.executeUpdate());
                    }
                    c.commit();return message;
                } catch(Exception error) {
                    c.rollback();throw error;
                }
            }
        }
        String outboxState(String message) throws SQLException {
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "SELECT state FROM w3419_outbox WHERE message_id=?")) {
                p.setString(1,message);
                try(ResultSet rs=p.executeQuery()) {
                    assertTrue(rs.next());return rs.getString(1);
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
                    ResultSet rs=s.executeQuery(sql)) {
                assertTrue(rs.next());return rs.getLong(1);
            }
        }
        int adjustments() throws SQLException {
            return (int)scalar("SELECT count(*) FROM w3419_adjustments");
        }
        int chainEntries() throws SQLException {
            return (int)scalar("SELECT count(*) FROM w3419_chain");
        }
        int outboxMessages() throws SQLException {
            return (int)scalar("SELECT count(*) FROM w3419_outbox");
        }
        int deliveryReceipts() throws SQLException {
            return (int)scalar("SELECT count(*) FROM w3419_delivery_receipts");
        }
        int witnesses() throws SQLException {
            return (int)scalar("SELECT count(*) FROM w3419_witness_receipts");
        }
        int schemaVersion() throws SQLException {
            return (int)scalar("SELECT MAX(version) FROM schema_history");
        }
        void assertLegacyUntouched() throws SQLException {
            assertEquals(0,scalar("SELECT count(*) FROM payment_transactions"));
            assertEquals(0,scalar("SELECT count(*) FROM payment_plans"));
            assertEquals(5,schemaVersion());
        }
        void evidence(String scenario) throws Exception {
            Path directory=Paths.get("target/wave34-19-evidence");
            Files.createDirectories(directory);
            String json="{\n"
                +"\"scenario\":\""+scenario+"\",\n"
                +"\"source_head\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                +"\"fixture\":\"DISPOSABLE_PG16_TEST_LOCAL_WITNESS_OUTBOX\",\n"
                +"\"schema_version\":"+schemaVersion()+",\n"
                +"\"synthetic_adjustments\":"+adjustments()+",\n"
                +"\"synthetic_chain_entries\":"+chainEntries()+",\n"
                +"\"synthetic_outbox_messages\":"+outboxMessages()+",\n"
                +"\"synthetic_delivery_receipts\":"+deliveryReceipts()+",\n"
                +"\"synthetic_witness_receipts\":"+witnesses()+",\n"
                +"\"simulated_chain_valid\":"+verifyChain()+",\n"
                +"\"chain_tip_sha256\":\""+cursorHash()+"\",\n"
                +"\"legacy_payment_entries\":"
                    +scalar("SELECT count(*) FROM payment_transactions")+",\n"
                +"\"real_independent_witness_verified\":false,\n"
                +"\"external_finance_idp_verified\":false,\n"
                +"\"real_provider_delivery_verified\":false,\n"
                +"\"exactly_once_external_delivery_verified\":false,\n"
                +"\"production_outbox_integrated\":false,\n"
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
