package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W34.22 review-only PostgreSQL16 barrier tests against the original, frozen
 * W34.15/16 DDL. No original Java fixture or production code is modified.
 * Only a subset of the interleaving schedule is controlled and observed.
 */
public class Wave3422DeterministicPgInterleavingTest {
    private static final int DEADLINE_SECONDS = 25;

    private static Wave3422PgFixture.Batch batch(String id,long gross,UUID maker) {
        return new Wave3422PgFixture.Batch(id,gross,3,maker,UUID.randomUUID());
    }
    private static Wave3422PgFixture.Event event(String id,String provider,
            long sequence,long amount,UUID approval) {
        return new Wave3422PgFixture.Event(id,provider,sequence,amount,approval);
    }
    private static ExecutorService executor() {return Executors.newSingleThreadExecutor();}
    private static void ready(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(10,TimeUnit.SECONDS),"Worker did not reach test barrier");
    }
    private static void assertCounts(Wave3422PgFixture f,int reviews,int sources,
            int audits,int events,int lines) throws Exception {
        try(Connection c=f.open()) {
            assertEquals(reviews,f.count(c,"w3416_batch_reviews"));
            assertEquals(sources,f.count(c,"w3416_batch_sources"));
            assertEquals(audits,f.count(c,"w3416_batch_audit"));
            assertEquals(events,f.count(c,"w3415_gl_events"));
            assertEquals(lines,f.count(c,"w3415_gl_lines"));
        }
    }
    private static void commitOrRollback(Connection c,boolean commit) throws SQLException {
        if(commit)c.commit();else c.rollback();
    }
    private static void assertIsolation(Connection a,Connection b) throws SQLException {
        assertEquals(Connection.TRANSACTION_READ_COMMITTED,a.getTransactionIsolation());
        assertEquals(Connection.TRANSACTION_READ_COMMITTED,b.getTransactionIsolation());
    }

    @Test @Timeout(65)
    void W3422_01_sameSettlementWaitsOnUncommittedUniqueKeyAndReadbacksExact() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture();
                Connection a=f.open();Connection b=f.open()) {
            a.setAutoCommit(false);b.setAutoCommit(false);assertIsolation(a,b);
            Wave3422PgFixture.Batch original=batch("BATCH-01",100,UUID.randomUUID());
            assertEquals(1,f.acceptBatch(a,original));
            int pa=f.pid(a),pb=f.pid(b);
            ExecutorService pool=executor();
            try {
                CountDownLatch started=new CountDownLatch(1);
                Future<Integer> pending=pool.submit(()->{
                    started.countDown();
                    try {int result=f.acceptBatch(b,original);b.commit();return result;}
                    catch(SQLException|RuntimeException failure){b.rollback();throw failure;}
                });
                ready(started);
                f.waitBlocked(pb,pa); // server confirms the unique index conflict wait
                assertCounts(f,0,0,0,0,0); // no uncommitted primary evidence leaked
                a.commit();
                assertEquals(0,pending.get(DEADLINE_SECONDS,TimeUnit.SECONDS).intValue());
                assertCounts(f,1,3,1,0,0);
                f.emit("W3422-01","exact_batch_replay",
                        "unique_index_block_observed pidA="+pa+" pidB="+pb);
            } finally {a.rollback();b.rollback();pool.shutdownNow();}
        }
    }

    @Test @Timeout(65)
    void W3422_02_foreignBatchMakerApprovalWaitsThenFailsClosed() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture();
                Connection a=f.open();Connection b=f.open()) {
            a.setAutoCommit(false);b.setAutoCommit(false);
            UUID maker=UUID.randomUUID();
            assertEquals(1,f.acceptBatch(a,batch("BATCH-01",100,maker)));
            Wave3422PgFixture.Batch invalid=batch("BATCH-02",100,maker);
            int pa=f.pid(a),pb=f.pid(b);
            ExecutorService pool=executor();
            try {
                CountDownLatch started=new CountDownLatch(1);
                Future<String> result=pool.submit(()->{
                    started.countDown();
                    try {f.acceptBatch(b,invalid);b.commit();return "ACCEPTED";}
                    catch(SecurityException hold){b.rollback();return "DENIED";}
                    catch(SQLException failure){b.rollback();return "SQLSTATE_"+failure.getSQLState();}
                });
                ready(started);
                f.waitBlocked(pb,pa);
                a.commit();
                assertEquals("DENIED",result.get(DEADLINE_SECONDS,TimeUnit.SECONDS));
                assertCounts(f,1,3,1,0,0);
                f.emit("W3422-02","cross_batch_approval_hold",
                        "unique_maker_block_observed pidA="+pa+" pidB="+pb);
            } finally {a.rollback();b.rollback();pool.shutdownNow();}
        }
    }

    @Test @Timeout(65)
    void W3422_03_mutatedSettlementDigestAmountAndApprovalsFailClosed() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture()) {
            Wave3422PgFixture.Batch original=batch("BATCH-01",100,UUID.randomUUID());
            try(Connection a=f.open()) {
                a.setAutoCommit(false);
                assertEquals(1,f.acceptBatch(a,original));a.commit();
            }
            try(Connection c=f.open()) {
                c.setAutoCommit(false);
                assertEquals(0,f.acceptBatch(c,original));c.commit();
                assertThrows(SecurityException.class,
                        ()->f.acceptBatch(c,new Wave3422PgFixture.Batch(
                                original.id,101,original.fee,original.maker,original.checker)));
                c.rollback();
                assertThrows(SecurityException.class,
                        ()->f.acceptBatch(c,new Wave3422PgFixture.Batch(
                                original.id,original.gross,original.fee,
                                UUID.randomUUID(),original.checker)));
                c.rollback();
                assertCounts(f,1,3,1,0,0);
            }
            f.emit("W3422-03","mutated_batch_replay_hold",
                    "committed_identity_immutable");
        }
    }

    @Test @Timeout(65)
    void W3422_04_duplicateGlEventWaitsForCommittedBalancedLines() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture();
                Connection a=f.open();Connection b=f.open()) {
            a.setAutoCommit(false);b.setAutoCommit(false);assertIsolation(a,b);
            Wave3422PgFixture.Event original=event("GL-A","provider-A",1,25,UUID.randomUUID());
            assertEquals(1,f.acceptEvent(a,original));
            int pa=f.pid(a),pb=f.pid(b);
            ExecutorService pool=executor();
            try {
                CountDownLatch started=new CountDownLatch(1);
                Future<Integer> pending=pool.submit(()->{
                    started.countDown();
                    try {int result=f.acceptEvent(b,original);b.commit();return result;}
                    catch(SQLException|RuntimeException failure){b.rollback();throw failure;}
                });
                ready(started);
                f.waitBlocked(pb,pa);
                assertCounts(f,0,0,0,0,0);
                a.commit();
                assertEquals(0,pending.get(DEADLINE_SECONDS,TimeUnit.SECONDS).intValue());
                assertCounts(f,0,0,0,1,2);
                try(Connection c=f.open()) {
                    assertEquals(25L,f.total(c,"D"));
                    assertEquals(25L,f.total(c,"C"));
                }
                f.emit("W3422-04","exact_gl_event_replay",
                        "unique_event_block_observed pidA="+pa+" pidB="+pb);
            } finally {a.rollback();b.rollback();pool.shutdownNow();}
        }
    }

    @Test @Timeout(65)
    void W3422_05_crossEventProviderSequenceApprovalReuseDenied() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture();
                Connection a=f.open();Connection b=f.open()) {
            a.setAutoCommit(false);b.setAutoCommit(false);
            UUID approval=UUID.randomUUID();
            assertEquals(1,f.acceptEvent(a,event("GL-A","provider-A",1,25,approval)));
            Wave3422PgFixture.Event duplicateProvider=event(
                    "GL-B","provider-A",2,10,UUID.randomUUID());
            int pa=f.pid(a),pb=f.pid(b);
            ExecutorService pool=executor();
            try {
                CountDownLatch started=new CountDownLatch(1);
                Future<String> pending=pool.submit(()->{
                    started.countDown();
                    try{f.acceptEvent(b,duplicateProvider);b.commit();return "ACCEPTED";}
                    catch(SecurityException deny){b.rollback();return "DENIED";}
                    catch(SQLException problem){b.rollback();return "SQLSTATE_"+problem.getSQLState();}
                });
                ready(started);f.waitBlocked(pb,pa);a.commit();
                assertEquals("DENIED",pending.get(DEADLINE_SECONDS,TimeUnit.SECONDS));
                try(Connection c=f.open()) {
                    c.setAutoCommit(false);
                    assertThrows(SecurityException.class,()->f.acceptEvent(c,event(
                            "GL-C","provider-C",1,10,UUID.randomUUID())));
                    c.rollback();
                    assertThrows(SecurityException.class,()->f.acceptEvent(c,event(
                            "GL-D","provider-D",4,10,approval)));
                    c.rollback();
                    assertEquals(25L,f.total(c,"D"));assertEquals(25L,f.total(c,"C"));
                }
                assertCounts(f,0,0,0,1,2);
                f.emit("W3422-05","foreign_gl_key_hold",
                        "unique_provider_block_observed pidA="+pa+" pidB="+pb);
            } finally {a.rollback();b.rollback();pool.shutdownNow();}
        }
    }

    @Test @Timeout(65)
    void W3422_06_deferredBalanceFailureRollsBackPartialHeaderAndLines() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture()) {
            Wave3422PgFixture.Event e=event("GL-ROLL","provider-ROLL",1,50,UUID.randomUUID());
            try(Connection a=f.open()) {
                a.setAutoCommit(false);
                f.insertHeader(a,e);
                f.insertLine(a,e.id,1,"1100","D",50);
                try(Connection observer=f.open()) {
                    assertEquals(0,f.count(observer,"w3415_gl_events"));
                    assertEquals(0,f.count(observer,"w3415_gl_lines"));
                }
                SQLException mismatch=assertThrows(SQLException.class,a::commit);
                assertEquals("23514",mismatch.getSQLState());
                a.rollback();
            }
            assertCounts(f,0,0,0,0,0);
            try(Connection b=f.open()) {
                b.setAutoCommit(false);
                f.insertHeader(b,e);
                f.insertLine(b,e.id,1,"1100","D",50);
                f.insertLine(b,e.id,2,"1200","C",49);
                SQLException mismatch=assertThrows(SQLException.class,b::commit);
                assertEquals("23514",mismatch.getSQLState());
                b.rollback();
            }
            assertCounts(f,0,0,0,0,0);
            f.emit("W3422-06","deferred_balance_rollback",
                    "pg_sqlstate=23514 two_partial_transactions_rolled_back");
        }
    }

    private static void advisory(Connection c,long key) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            p.setLong(1,key);
            try(ResultSet r=p.executeQuery()){assertTrue(r.next());}
        }
    }
    private static String crossLock(Connection c,long key) throws Exception {
        try {advisory(c,key);c.commit();return "COMMITTED";}
        catch(SQLException failure) {
            c.rollback();return failure.getSQLState();
        }
    }
    @Test @Timeout(65)
    void W3422_07_deadlockVictimAbortsAndOtherTransactionRecovers() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture();
                Connection a=f.open();Connection b=f.open()) {
            a.setAutoCommit(false);b.setAutoCommit(false);
            long k1=UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
            long k2=k1+1;
            advisory(a,k1);advisory(b,k2);
            int pa=f.pid(a),pb=f.pid(b);
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<String> first=pool.submit(()->crossLock(a,k2));
                f.waitBlocked(pa,pb);
                Future<String> second=pool.submit(()->crossLock(b,k1));
                String r1=first.get(DEADLINE_SECONDS,TimeUnit.SECONDS);
                String r2=second.get(DEADLINE_SECONDS,TimeUnit.SECONDS);
                assertTrue(("40P01".equals(r1)&&"COMMITTED".equals(r2))
                        || ("40P01".equals(r2)&&"COMMITTED".equals(r1)),
                        "Expected exactly one PostgreSQL 40P01 deadlock victim, got "+r1+"/"+r2);
                assertCounts(f,0,0,0,0,0);
                f.emit("W3422-07","observed_deadlock_recovery",
                        "advisory_block_observed one_sqlstate=40P01 no_rows_committed");
            } finally {a.rollback();b.rollback();pool.shutdownNow();}
        }
    }

    @Test @Timeout(65)
    void W3422_08_repeatedExactReadbackRejectsChangedIdentity() throws Exception {
        try(Wave3422PgFixture f=new Wave3422PgFixture()) {
            Wave3422PgFixture.Batch batch=batch("BATCH-01",100,UUID.randomUUID());
            Wave3422PgFixture.Event event=event("GL-A","provider-A",1,25,UUID.randomUUID());
            try(Connection c=f.open()) {
                c.setAutoCommit(false);
                assertEquals(1,f.acceptBatch(c,batch));assertEquals(1,f.acceptEvent(c,event));
                c.commit();
                assertEquals(0,f.acceptBatch(c,batch));assertEquals(0,f.acceptEvent(c,event));
                c.commit();
                assertEquals(0,f.acceptBatch(c,batch));assertEquals(0,f.acceptEvent(c,event));
                c.commit();
                assertThrows(SecurityException.class,()->f.acceptEvent(c,
                        event(event.id,event.providerId,event.sequence,26,event.approval)));
                c.rollback();
                assertThrows(SecurityException.class,()->f.acceptBatch(c,
                        new Wave3422PgFixture.Batch(batch.id,102,batch.fee,batch.maker,batch.checker)));
                c.rollback();
            }
            assertCounts(f,1,3,1,1,2);
            f.emit("W3422-08","exact_readback_vs_changed_identity",
                    "repeated_committed_conflict_checked and_mutated_digest_rejected");
        }
    }
}
