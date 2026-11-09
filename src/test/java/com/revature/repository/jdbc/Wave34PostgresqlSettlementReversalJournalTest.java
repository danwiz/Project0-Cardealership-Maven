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
 * Wave34.14 synthetic provider-settlement and approved-reversal accounting lab.
 * Provider and finance approval keys are generated IN THIS TEST JVM.
 * Approval means an illustrative test signature, never finance authorization.
 * The journal is separate from production/legacy payment tables. The CI DBA
 * can bypass the update/delete trigger: NOT production immutability.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlSettlementReversalJournalTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final String PROVIDER = "fake-provider";
    private static final String MERCHANT = "fake-merchant";
    private static final String CURRENCY = "USD";
    private static final String SUBJECT = "buyer";
    private static final String CHARGE = "fake-charge-001";
    private static final Instant NOW = Instant.parse("2026-11-09T20:00:00Z");
    private static final String DDL =
            "docs/audit/wave34/wave34_14_DISPOSABLE_settlement_reversal_journal.sql";

    @Test
    @Timeout(90)
    void settledCapturePartialRefundAndChargebackCreateBalancedAppendOnlyJournal()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            f.apply(f.event("evt-capture",1,"CAPTURED",100),null,false);
            f.apply(f.event("evt-settle",2,"SETTLED",0),null,false);
            Event refund=f.event("evt-refund",3,"REFUNDED",40);
            f.apply(refund,f.approve(refund),false);
            Event chargeback=f.event("evt-chargeback",4,"CHARGEBACK",60);
            f.apply(chargeback,f.approve(chargeback),false);
            assertEquals(4,f.events());
            assertEquals(4,f.journalEntries());
            assertEquals(0L,f.netMinor());
            assertEquals(100L,f.reversedMinor());
            assertEquals(4L,f.lastSequence());
            assertEquals("REVERSED",f.state());
            assertSqlState("55000",()->f.raw(
                    "UPDATE w3414_reversal_journal SET delta_minor=999 WHERE journal_id=1"));
            assertSqlState("55000",()->f.raw(
                    "DELETE FROM w3414_reversal_journal WHERE journal_id=1"));
            f.assertLegacyUntouched();
            f.evidence("capture-settlement-refund-chargeback");
        }
    }

    @Test
    @Timeout(90)
    void missingOutOfOrderAndOverReversalEventsRemainOnFinancialHold()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            assertThrows(SecurityException.class,()->f.apply(
                    f.event("premature-settle",2,"SETTLED",0),null,false));
            assertEquals(0,f.events());

            f.apply(f.event("capture",1,"CAPTURED",100),null,false);
            Event earlyRefund=f.event("refund-before-settlement",2,"REFUNDED",15);
            assertThrows(SecurityException.class,()->f.apply(
                    earlyRefund,f.approve(earlyRefund),false));
            assertThrows(SecurityException.class,()->f.apply(
                    f.event("sequence-gap",3,"SETTLED",0),null,false));
            assertThrows(SecurityException.class,()->f.apply(
                    f.event("untrusted-pending",2,"PENDING",0),null,false));
            assertThrows(SecurityException.class,()->f.apply(
                    f.event("untrusted-dispute",2,"DISPUTED",0),null,false));

            f.apply(f.event("settle",2,"SETTLED",0),null,false);
            Event refund=f.event("refund90",3,"REFUNDED",90);
            f.apply(refund,f.approve(refund),false);
            Event over=f.event("chargeback11",4,"CHARGEBACK",11);
            assertThrows(SecurityException.class,()->f.apply(over,f.approve(over),false));
            assertEquals(3,f.events());
            assertEquals(3,f.journalEntries());
            assertEquals(10L,f.netMinor());
            assertEquals(90L,f.reversedMinor());
            assertEquals(3L,f.lastSequence());

            Event remaining=f.event("chargeback10",4,"CHARGEBACK",10);
            f.apply(remaining,f.approve(remaining),false);
            assertEquals(0L,f.netMinor());
            assertEquals("REVERSED",f.state());
            f.assertLegacyUntouched();
            f.evidence("out-of-order-and-over-reversal-hold");
        }
    }

    @Test
    @Timeout(90)
    void forgedEventExpiredApprovalAndReusedAuthorizationCannotPost()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            f.apply(f.event("capture",1,"CAPTURED",100),null,false);
            f.apply(f.event("settle",2,"SETTLED",0),null,false);
            Event refund=f.event("refund30",3,"REFUNDED",30);
            Approval real=f.approve(refund);
            assertThrows(SecurityException.class,()->f.apply(
                    refund.withAmount(31),real,false));
            assertThrows(SecurityException.class,()->f.apply(
                    refund.withCurrency("EUR"),real,false));
            assertThrows(SecurityException.class,()->f.apply(
                    refund.withOwnership(f.ownership+1),real,false));
            assertThrows(SecurityException.class,()->f.apply(
                    f.eventSignedBy("refund30",3,"REFUNDED",30,keyPair()),real,false));
            assertThrows(SecurityException.class,()->f.apply(
                    refund,f.approveBy(refund,UUID.randomUUID(),
                            NOW.plusSeconds(300),keyPair()),false));
            assertThrows(SecurityException.class,()->f.apply(
                    refund,f.approveBy(refund,UUID.randomUUID(),
                            NOW.minusSeconds(1),f.financeKey),false));
            assertEquals(2,f.events());
            assertEquals(0L,f.reversedMinor());

            f.apply(refund,real,false);
            f.apply(refund,real,false); // replay returns original event, no new journal
            assertEquals(3,f.events());
            assertEquals(3,f.journalEntries());
            assertEquals(70L,f.netMinor());
            // A newly signed, inconsistent reuse of event ID is still a HOLD.
            Event different=f.event("refund30",3,"REFUNDED",29);
            assertThrows(SecurityException.class,()->f.apply(
                    different,f.approve(different),false));

            // A test finance approval cannot be reused for a different event.
            Event next=f.event("refund10",4,"REFUNDED",10);
            Approval reused=f.approveBy(next,real.approvalId,
                    NOW.plusSeconds(300),f.financeKey);
            assertSqlState("23505",()->f.apply(next,reused,false));
            assertEquals(3,f.events(),"unique approval collision rolls event back");
            assertEquals(3,f.journalEntries());
            assertEquals(30L,f.reversedMinor());
            f.assertLegacyUntouched();
            f.evidence("forgery-approval-expiry-and-reuse");
        }
    }

    @Test
    @Timeout(90)
    void simultaneousReplayCommitsOnceAndFailedJournalFKRollsBackState()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            f.apply(f.event("capture",1,"CAPTURED",100),null,false);
            f.apply(f.event("settle",2,"SETTLED",0),null,false);
            Event refund=f.event("refund20",3,"REFUNDED",20);
            Approval approval=f.approve(refund);
            ExecutorService pool=Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready=new CountDownLatch(4);
                CountDownLatch fire=new CountDownLatch(1);
                java.util.List<Future<String>> futures=new java.util.ArrayList<>();
                for(int i=0;i<4;i++) {
                    futures.add(pool.submit(()->{
                        ready.countDown();
                        if(!fire.await(10,TimeUnit.SECONDS))
                            throw new AssertionError("concurrent event rendezvous timed out");
                        return f.apply(refund,approval,false);
                    }));
                }
                assertTrue(ready.await(10,TimeUnit.SECONDS));
                fire.countDown();
                for(Future<String> future:futures)
                    assertEquals("refund20",future.get(35,TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            assertEquals(3,f.events());
            assertEquals(3,f.journalEntries());
            assertEquals(80L,f.netMinor());
            assertEquals(20L,f.reversedMinor());
            assertEquals(3L,f.lastSequence());

            Event chargeback=f.event("chargeback30",4,"CHARGEBACK",30);
            Approval charged=f.approve(chargeback);
            String before=f.digest();
            assertSqlState("23503",()->f.apply(chargeback,charged,true));
            assertEquals(before,f.digest(),"FK failure must roll back event and state");
            assertEquals(3,f.events());
            assertEquals(20L,f.reversedMinor());
            f.apply(chargeback,charged,false);
            assertEquals(4,f.events());
            assertEquals(4,f.journalEntries());
            assertEquals(50L,f.netMinor());
            assertEquals(50L,f.reversedMinor());
            assertEquals(4L,f.lastSequence());
            f.assertLegacyUntouched();
            f.evidence("concurrency-and-atomic-reversal-rollback");
        }
    }

    private interface SqlCall { void run() throws Exception; }
    private static void assertSqlState(String state,SqlCall call) {
        SQLException error=assertThrows(SQLException.class,call::run);
        assertEquals(state,error.getSQLState());
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
                "only exact-head disposable PostgreSQL16 CI may execute this test");
    }
    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
    private static byte[] wire(String... fields) {
        try {
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(DataOutputStream writer=new DataOutputStream(buffer)) {
                writer.writeInt(fields.length);
                for(String field:fields) {
                    byte[] value=field.getBytes(StandardCharsets.UTF_8);
                    writer.writeInt(value.length);
                    writer.write(value);
                }
            }
            return buffer.toByteArray();
        } catch(IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static byte[] sign(KeyPair key,byte[] value) throws GeneralSecurityException {
        Signature signature=Signature.getInstance("SHA256withECDSA");
        signature.initSign(key.getPrivate());
        signature.update(value);
        return signature.sign();
    }
    private static boolean signatureValid(PublicKey pub,byte[] value,byte[] signed)
            throws GeneralSecurityException {
        Signature verifier=Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pub);
        verifier.update(value);
        return verifier.verify(signed);
    }
    private static String sha(byte[] raw) throws GeneralSecurityException {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(raw);
        char[] digits="0123456789abcdef".toCharArray();
        StringBuilder out=new StringBuilder(64);
        for(byte b:hash)out.append(digits[(b>>>4)&15]).append(digits[b&15]);
        return out.toString();
    }

    private static final class Event {
        final String provider,merchant,charge,eventId,type,customer,currency;
        final long sequence,ownership,amount;
        final Instant observed;
        final byte[] signature;
        Event(String provider,String merchant,String charge,String eventId,
                long sequence,String type,long amount,String currency,String customer,
                long ownership,Instant observed,byte[] signature) {
            this.provider=provider;this.merchant=merchant;this.charge=charge;
            this.eventId=eventId;this.sequence=sequence;this.type=type;
            this.amount=amount;this.currency=currency;this.customer=customer;
            this.ownership=ownership;this.observed=observed;
            this.signature=signature.clone();
        }
        byte[] payload() {
            return wire("W34.14|FAKE_PROVIDER_EVENT|v1",provider,merchant,charge,
                    eventId,Long.toString(sequence),type,Long.toString(amount),
                    currency,customer,Long.toString(ownership),observed.toString());
        }
        Event withAmount(long other) {
            return new Event(provider,merchant,charge,eventId,sequence,type,
                    other,currency,customer,ownership,observed,signature);
        }
        Event withCurrency(String other) {
            return new Event(provider,merchant,charge,eventId,sequence,type,
                    amount,other,customer,ownership,observed,signature);
        }
        Event withOwnership(long other) {
            return new Event(provider,merchant,charge,eventId,sequence,type,
                    amount,currency,customer,other,observed,signature);
        }
    }

    private static final class Approval {
        final UUID approvalId;
        final String eventId,charge,type,currency;
        final long amount;
        final Instant expiresAt;
        final byte[] signature;
        Approval(UUID id,String eventId,String charge,String type,String currency,
                long amount,Instant expiration,byte[] signature) {
            this.approvalId=id;this.eventId=eventId;this.charge=charge;
            this.type=type;this.currency=currency;this.amount=amount;
            this.expiresAt=expiration;this.signature=signature.clone();
        }
        byte[] payload() {
            return wire("W34.14|TEST_FINANCE_APPROVAL|v1",approvalId.toString(),
                    eventId,charge,type,currency,Long.toString(amount),
                    expiresAt.toString());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema="w3414_"+UUID.randomUUID().toString().replace("-","");
        final JdbcDatabase admin=new JdbcDatabase(URL,USER,PASSWORD);
        final JdbcDatabase scoped=new JdbcDatabase(
                URL+"?currentSchema="+schema,USER,PASSWORD);
        final KeyPair fakeProviderKey=keyPair();
        final KeyPair financeKey=keyPair();
        long ownership;

        Fixture() throws Exception {
            assertTrue(schema.matches("w3414_[0-9a-f]{32}"));
            try(Connection c=admin.openConnection();Statement s=c.createStatement()) {
                try(ResultSet rs=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(rs.next());
                    int version=Integer.parseInt(rs.getString(1));
                    assertTrue(version>=160000 && version<170000);
                }
                s.execute("CREATE SCHEMA "+schema);
            }
            scoped.migrate();
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute("INSERT INTO accounts(username,encoded_credential,role)"
                        +" VALUES('buyer','CI-only','CUSTOMER')");
            }
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "INSERT INTO owned_vehicles(owner_username,make,model,vehicle_year,contract_id)"
                    +" VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                    Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1,p.executeUpdate());
                try(ResultSet rs=p.getGeneratedKeys()) {
                    assertTrue(rs.next());
                    ownership=rs.getLong(1);
                }
            }
            try(Connection c=scoped.openConnection();PreparedStatement p=c.prepareStatement(
                    "INSERT INTO payment_plans(ownership_id,purchase_price,payment_months,"
                    +"monthly_payment,amount_paid,balance)"
                    +" VALUES(?,24000,24,1000,0,24000)")) {
                p.setLong(1,ownership);
                assertEquals(1,p.executeUpdate());
            }
            String script=new String(Files.readAllBytes(Paths.get(DDL)),
                    StandardCharsets.UTF_8);
            String[] statements=script.split("(?m)^-- W3414_STATEMENT_BREAK[ \\t]*\\r?$");
            assertEquals(5,statements.length);
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                for(String statement:statements) {
                    assertFalse(statement.trim().isEmpty());
                    s.execute(statement);
                }
            }
            assertEquals(5,schemaVersion());
        }

        Event event(String id,long seq,String type,long amount)
                throws GeneralSecurityException {
            return eventSignedBy(id,seq,type,amount,fakeProviderKey);
        }
        Event eventSignedBy(String id,long seq,String type,long amount,KeyPair signer)
                throws GeneralSecurityException {
            Event unsigned=new Event(PROVIDER,MERCHANT,CHARGE,id,seq,type,amount,
                    CURRENCY,SUBJECT,ownership,NOW.plusSeconds(seq),new byte[0]);
            return new Event(unsigned.provider,unsigned.merchant,unsigned.charge,
                    unsigned.eventId,seq,type,amount,CURRENCY,SUBJECT,ownership,
                    unsigned.observed,sign(signer,unsigned.payload()));
        }
        Approval approve(Event e) throws GeneralSecurityException {
            return approveBy(e,UUID.randomUUID(),NOW.plusSeconds(300),financeKey);
        }
        Approval approveBy(Event e,UUID id,Instant exp,KeyPair signer)
                throws GeneralSecurityException {
            Approval unsigned=new Approval(id,e.eventId,e.charge,e.type,e.currency,
                    e.amount,exp,new byte[0]);
            return new Approval(id,e.eventId,e.charge,e.type,e.currency,
                    e.amount,exp,sign(signer,unsigned.payload()));
        }

        void verify(Event e,Approval approval) throws GeneralSecurityException {
            if(!PROVIDER.equals(e.provider) || !MERCHANT.equals(e.merchant)
                    || !CHARGE.equals(e.charge) || !CURRENCY.equals(e.currency)
                    || !SUBJECT.equals(e.customer) || ownership!=e.ownership
                    || e.sequence<=0 || e.amount<0 || e.eventId.trim().isEmpty()
                    || e.observed.isBefore(NOW) || e.observed.isAfter(NOW.plusSeconds(60))
                    || !signatureValid(fakeProviderKey.getPublic(),e.payload(),e.signature))
                throw new SecurityException("W3414 HOLD: fake provider event invalid");
            boolean reversal="REFUNDED".equals(e.type)||"CHARGEBACK".equals(e.type);
            if(!reversal && !("CAPTURED".equals(e.type)||"SETTLED".equals(e.type)))
                throw new SecurityException("W3414 HOLD: unmodeled provider state");
            if(reversal) {
                if(e.amount<=0 || approval==null
                        || !approval.eventId.equals(e.eventId)
                        || !approval.charge.equals(e.charge)
                        || !approval.type.equals(e.type)
                        || !approval.currency.equals(e.currency)
                        || approval.amount!=e.amount
                        || !approval.expiresAt.isAfter(NOW.plusSeconds(60))
                        || !signatureValid(financeKey.getPublic(),approval.payload(),
                                approval.signature))
                    throw new SecurityException("W3414 HOLD: independent test approval missing");
            } else if(approval!=null || ("SETTLED".equals(e.type)&&e.amount!=0)
                    || ("CAPTURED".equals(e.type)&&e.amount<=0)) {
                throw new SecurityException("W3414 HOLD: invalid capture/settlement amount");
            }
        }

        String apply(Event e,Approval approval,boolean injectJournalFkFailure)
                throws Exception {
            verify(e,approval); // in test JVM, bypassable by privileged CI DBA
            String digest=sha(e.payload());
            try(Connection c=scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    if("CAPTURED".equals(e.type)) {
                        try(PreparedStatement insert=c.prepareStatement(
                                "INSERT INTO w3414_charge_state"
                                +"(provider_name,merchant_id,provider_charge_id,"
                                +"customer_name,ownership_id,currency,captured_minor)"
                                +" VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                            insert.setString(1,e.provider);
                            insert.setString(2,e.merchant);
                            insert.setString(3,e.charge);
                            insert.setString(4,e.customer);
                            insert.setLong(5,e.ownership);
                            insert.setString(6,e.currency);
                            insert.setLong(7,e.amount);
                            insert.executeUpdate();
                        }
                    }
                    long last,captured,reversed;
                    boolean settled;
                    try(PreparedStatement lock=c.prepareStatement(
                            "SELECT last_sequence,captured_minor,reversed_minor,settled,"
                            +"customer_name,ownership_id,currency"
                            +" FROM w3414_charge_state"
                            +" WHERE provider_name=? AND merchant_id=?"
                            +" AND provider_charge_id=? FOR UPDATE")) {
                        lock.setString(1,e.provider);
                        lock.setString(2,e.merchant);
                        lock.setString(3,e.charge);
                        try(ResultSet rs=lock.executeQuery()) {
                            if(!rs.next())
                                throw new SecurityException("W3414 HOLD: missing captured charge");
                            last=rs.getLong(1);captured=rs.getLong(2);
                            reversed=rs.getLong(3);settled=rs.getBoolean(4);
                            if(!e.customer.equals(rs.getString(5))
                                    || e.ownership!=rs.getLong(6)
                                    || !e.currency.equals(rs.getString(7)))
                                throw new SecurityException("W3414 HOLD: mismatched charge identity");
                        }
                    }
                    try(PreparedStatement old=c.prepareStatement(
                            "SELECT sequence_no,event_type,amount_minor,event_digest,"
                            +"approval_id FROM w3414_provider_events"
                            +" WHERE provider_name=? AND merchant_id=? AND event_id=?")) {
                        old.setString(1,e.provider);old.setString(2,e.merchant);
                        old.setString(3,e.eventId);
                        try(ResultSet rs=old.executeQuery()) {
                            if(rs.next()) {
                                UUID previous=(UUID)rs.getObject(5);
                                UUID current=approval==null?null:approval.approvalId;
                                if(rs.getLong(1)!=e.sequence
                                        || !rs.getString(2).equals(e.type)
                                        || rs.getLong(3)!=e.amount
                                        || !rs.getString(4).equals(digest)
                                        || !(previous==null?current==null:previous.equals(current)))
                                    throw new SecurityException("W3414 HOLD: changed existing event");
                                c.commit();return e.eventId; // exact replay; no new journal
                            }
                        }
                    }
                    if(e.sequence!=last+1)
                        throw new SecurityException("W3414 HOLD: out-of-order provider event");
                    String next;
                    String kind;
                    long delta;
                    if("CAPTURED".equals(e.type)) {
                        if(last!=0 || captured!=e.amount)
                            throw new SecurityException("W3414 HOLD: conflicting capture");
                        next="CAPTURED";kind="CAPTURE_CREDIT";delta=e.amount;
                    } else if("SETTLED".equals(e.type)) {
                        if(!settled && last==1 && reversed==0) {
                            next="SETTLED";kind="SETTLEMENT_MARKER";delta=0L;
                        } else throw new SecurityException("W3414 HOLD: invalid settlement");
                    } else {
                        if(!settled || reversed>=captured
                                || e.amount>captured-reversed)
                            throw new SecurityException("W3414 HOLD: reversal exceeds settled claim");
                        reversed=Math.addExact(reversed,e.amount);
                        next=reversed==captured?"REVERSED":"PARTIALLY_REVERSED";
                        kind="REFUNDED".equals(e.type)?"REFUND_DEBIT":"CHARGEBACK_DEBIT";
                        delta=-e.amount;
                    }
                    try(PreparedStatement insert=c.prepareStatement(
                            "INSERT INTO w3414_provider_events"
                            +"(provider_name,merchant_id,provider_charge_id,event_id,"
                            +"sequence_no,event_type,amount_minor,currency,event_digest,approval_id)"
                            +" VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                        insert.setString(1,e.provider);insert.setString(2,e.merchant);
                        insert.setString(3,e.charge);insert.setString(4,e.eventId);
                        insert.setLong(5,e.sequence);insert.setString(6,e.type);
                        insert.setLong(7,e.amount);insert.setString(8,e.currency);
                        insert.setString(9,digest);
                        if(approval==null)insert.setNull(10,java.sql.Types.OTHER);
                        else insert.setObject(10,approval.approvalId);
                        assertEquals(1,insert.executeUpdate());
                    }
                    try(PreparedStatement state=c.prepareStatement(
                            "UPDATE w3414_charge_state SET last_sequence=?,"
                            +"reversed_minor=?,settled=?,state=?"
                            +" WHERE provider_name=? AND merchant_id=? AND provider_charge_id=?")) {
                        state.setLong(1,e.sequence);state.setLong(2,reversed);
                        state.setBoolean(3,settled||"SETTLED".equals(e.type));
                        state.setString(4,next);
                        state.setString(5,e.provider);state.setString(6,e.merchant);
                        state.setString(7,e.charge);
                        assertEquals(1,state.executeUpdate());
                    }
                    try(PreparedStatement entry=c.prepareStatement(
                            "INSERT INTO w3414_reversal_journal"
                            +"(provider_name,merchant_id,provider_charge_id,event_id,"
                            +"entry_kind,delta_minor,currency,approval_id)"
                            +" VALUES(?,?,?,?,?,?,?,?)")) {
                        entry.setString(1,e.provider);entry.setString(2,e.merchant);
                        entry.setString(3,injectJournalFkFailure?"invalid-charge":e.charge);
                        entry.setString(4,e.eventId);entry.setString(5,kind);
                        entry.setLong(6,delta);entry.setString(7,e.currency);
                        if(approval==null)entry.setNull(8,java.sql.Types.OTHER);
                        else entry.setObject(8,approval.approvalId);
                        assertEquals(1,entry.executeUpdate());
                    }
                    c.commit();
                    return e.eventId;
                } catch(SQLException|RuntimeException problem) {
                    c.rollback();
                    throw problem;
                }
            }
        }

        void raw(String query) throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement()) {
                s.execute(query);
            }
        }
        int events() throws SQLException {
            return scalar("SELECT COUNT(*) FROM w3414_provider_events");
        }
        int journalEntries() throws SQLException {
            return scalar("SELECT COUNT(*) FROM w3414_reversal_journal");
        }
        long netMinor() throws SQLException {
            return scalarLong("SELECT COALESCE(SUM(delta_minor),0) "
                    +"FROM w3414_reversal_journal");
        }
        long reversedMinor() throws SQLException {
            return scalarLong("SELECT COALESCE(MAX(reversed_minor),0) "
                    +"FROM w3414_charge_state");
        }
        long lastSequence() throws SQLException {
            return scalarLong("SELECT COALESCE(MAX(last_sequence),0) "
                    +"FROM w3414_charge_state");
        }
        String state() throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery("SELECT state FROM w3414_charge_state")) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
        int schemaVersion() throws SQLException {
            return scalar("SELECT MAX(version) FROM schema_history");
        }
        int scalar(String query) throws SQLException {return (int)scalarLong(query);}
        long scalarLong(String query) throws SQLException {
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet rs=s.executeQuery(query)) {
                assertTrue(rs.next());return rs.getLong(1);
            }
        }
        void assertLegacyUntouched() throws SQLException {
            assertEquals(0,scalar("SELECT COUNT(*) FROM payment_transactions"));
            assertEquals(0,scalar("SELECT amount_paid FROM payment_plans"));
            assertEquals(24000,scalar("SELECT balance FROM payment_plans"));
            assertEquals(5,schemaVersion());
        }
        String digest() throws Exception {
            StringBuilder source=new StringBuilder();
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet r=s.executeQuery("SELECT event_id,event_type,sequence_no,"
                            +"amount_minor,event_digest FROM w3414_provider_events"
                            +" ORDER BY sequence_no,event_id")) {
                while(r.next())for(int i=1;i<=5;i++)
                    source.append(r.getString(i)).append('\u0000');
            }
            try(Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet r=s.executeQuery("SELECT event_id,entry_kind,delta_minor,"
                            +"currency,approval_id FROM w3414_reversal_journal"
                            +" ORDER BY journal_id")) {
                while(r.next())for(int i=1;i<=5;i++)
                    source.append(r.getString(i)).append('\u0000');
            }
            source.append("state=").append(state()).append(";reversed=")
                    .append(reversedMinor()).append(";last=").append(lastSequence())
                    .append(";net=").append(netMinor()).append(";legacy=")
                    .append(scalar("SELECT COUNT(*) FROM payment_transactions"));
            return sha(source.toString().getBytes(StandardCharsets.UTF_8));
        }
        void evidence(String scenario) throws Exception {
            Path output=Paths.get("target/wave34-14-evidence");
            Files.createDirectories(output);
            String json="{\n"
                    +"\"scenario\":\""+scenario+"\",\n"
                    +"\"source_head\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                    +"\"fixture\":\"IN_PROCESS_FAKE_SETTLEMENT_PG16_ONLY\",\n"
                    +"\"schema_version\":"+schemaVersion()+",\n"
                    +"\"synthetic_provider_events\":"+events()+",\n"
                    +"\"synthetic_journal_entries\":"+journalEntries()+",\n"
                    +"\"synthetic_net_minor\":"+netMinor()+",\n"
                    +"\"legacy_payment_entries\":"
                    +scalar("SELECT COUNT(*) FROM payment_transactions")+",\n"
                    +"\"ledger_digest_sha256\":\""+digest()+"\",\n"
                    +"\"external_provider_verified\":false,\n"
                    +"\"independent_finance_approval_verified\":false,\n"
                    +"\"real_refund_or_chargeback_executed\":false,\n"
                    +"\"production_immutability_verified\":false,\n"
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
