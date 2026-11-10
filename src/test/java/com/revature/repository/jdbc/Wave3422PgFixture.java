package com.revature.repository.jdbc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/**
 * Disposable, synthetic PostgreSQL16 SQL fixture for W34.22 only.
 * Loads the UNMODIFIED historical W3415/W3416 DDL into a NEW random schema.
 * Does not exercise production payment APIs, real approval signatures or funds.
 */
final class Wave3422PgFixture implements AutoCloseable {
    final String schema = "w3422_" + UUID.randomUUID().toString().replace("-", "");
    final String url = require("DEALERSHIP_PG_TEST_URL");
    final String user = require("DEALERSHIP_PG_TEST_USER");
    final String password = require("DEALERSHIP_PG_TEST_PASSWORD");
    final String head = require("EXPECTED_HEAD");
    final int pgVersion;
    private boolean closed = false;

    static final class Batch {
        final String id, digest;
        final long gross, fee, net;
        final UUID maker, checker;
        Batch(String id, long gross, long fee, UUID maker, UUID checker) {
            this.id = id; this.gross = gross; this.fee = fee;
            this.net = gross - fee;
            this.maker = maker; this.checker = checker;
            this.digest = hash("W3422-BATCH|" + id + "|" + gross + "|" + fee
                    + "|" + maker + "|" + checker);
        }
    }

    static final class Event {
        final String id, providerId, kind, digest;
        final long sequence, amount;
        final UUID approval;
        Event(String id, String providerId, long sequence, long amount,
                UUID approval) {
            this.id=id; this.providerId=providerId;
            this.sequence=sequence; this.amount=amount;
            this.kind="REFUNDED"; this.approval=approval;
            this.digest=hash("W3422-GL|" + id + "|" + providerId + "|"
                    + sequence + "|" + amount + "|" + approval);
        }
    }

    static String require(String key) {
        String value=System.getenv(key);
        if(value==null || value.trim().isEmpty())
            throw new IllegalStateException("W34.22 missing " + key);
        return value;
    }

    Wave3422PgFixture() throws Exception {
        if(!"true".equals(require("W34_DISPOSABLE_ONLY")) ||
                !"true".equals(require("CI")) ||
                !url.equals("jdbc:postgresql://localhost:5432/dealership_ci") ||
                !head.matches("[0-9a-f]{40}") ||
                !schema.matches("w3422_[0-9a-f]{32}"))
            throw new IllegalStateException("W34.22 disposable PG16 custody check failed");
        try(Connection c=DriverManager.getConnection(url,user,password);
                Statement s=c.createStatement()) {
            try(ResultSet version=s.executeQuery("SHOW server_version_num")) {
                if(!version.next())throw new IllegalStateException("PG version unavailable");
                pgVersion=Integer.parseInt(version.getString(1));
                if(pgVersion<160000 || pgVersion>=170000)
                    throw new IllegalStateException("PostgreSQL16 required");
            }
            s.execute("CREATE SCHEMA " + schema);
        }
        try(Connection c=open();Statement s=c.createStatement()) {
            for(String name:new String[]{
                "docs/audit/wave34/wave34_15_DISPOSABLE_balanced_gl.sql",
                "docs/audit/wave34/wave34_16_DISPOSABLE_settlement_batch_reconciliation.sql"
            }) {
                String ddl=new String(Files.readAllBytes(Paths.get(name)),
                        StandardCharsets.UTF_8).replace("__SCHEMA__",schema);
                if(ddl.contains("__SCHEMA__") || !ddl.contains("DISPOSABLE"))
                    throw new IllegalStateException("Unbounded SQL test fixture");
                s.execute(ddl);
            }
        }
    }

    Connection open() throws SQLException {
        Connection c=DriverManager.getConnection(url+"?currentSchema="+schema,user,password);
        try(Statement s=c.createStatement()) {
            s.execute("SET statement_timeout='15s'");
            s.execute("SET deadlock_timeout='300ms'");
        } catch(SQLException problem){c.close();throw problem;}
        return c;
    }

    int pid(Connection c) throws SQLException {
        try(Statement s=c.createStatement();
                ResultSet r=s.executeQuery("SELECT pg_backend_pid()")) {
            if(!r.next())throw new SQLException("No backend PID");
            return r.getInt(1);
        }
    }

    void waitBlocked(int waitingPid,int blockingPid) throws Exception {
        long cutoff=System.nanoTime()+8_000_000_000L;
        while(System.nanoTime()<cutoff) {
            try(Connection c=open();PreparedStatement p=c.prepareStatement(
                    "SELECT ? = ANY(pg_blocking_pids(?))")) {
                p.setInt(1,blockingPid);p.setInt(2,waitingPid);
                try(ResultSet rs=p.executeQuery()) {
                    if(rs.next() && rs.getBoolean(1))return;
                }
            }
            Thread.sleep(20); // Polling only: pg_blocking_pids is the success oracle.
        }
        throw new AssertionError("W34.22 expected PG blocking edge "
                + waitingPid + " -> " + blockingPid + " not observed");
    }

    static String hash(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(byte b:bytes)out.append(String.format("%02x",b & 0xff));
            return out.toString();
        } catch(Exception e){throw new IllegalStateException(e);}
    }

    int acceptBatch(Connection c,Batch b) throws SQLException {
        int inserted;
        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO w3416_batch_reviews(batch_id,currency,"
                +"provider_gross_minor,provider_fee_minor,expected_net_minor,"
                +"maker_id,checker_id,maker_approval_id,checker_approval_id,review_digest)"
                +" VALUES(?,'USD',?,?,?,'maker','checker',?,?,?) ON CONFLICT DO NOTHING")) {
            p.setString(1,b.id);
            p.setLong(2,b.gross);p.setLong(3,b.fee);p.setLong(4,b.net);
            p.setObject(5,b.maker);p.setObject(6,b.checker);p.setString(7,b.digest);
            inserted=p.executeUpdate();
        }
        if(inserted==0) {
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT currency,provider_gross_minor,provider_fee_minor,"
                    +"expected_net_minor,maker_id,checker_id,maker_approval_id,"
                    +"checker_approval_id,review_digest FROM w3416_batch_reviews WHERE batch_id=?")) {
                p.setString(1,b.id);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next() || !"USD".equals(r.getString(1).trim())
                            || b.gross!=r.getLong(2) || b.fee!=r.getLong(3)
                            || b.net!=r.getLong(4) || !"maker".equals(r.getString(5))
                            || !"checker".equals(r.getString(6))
                            || !b.maker.equals(r.getObject(7))
                            || !b.checker.equals(r.getObject(8))
                            || !b.digest.equals(r.getString(9)))
                        throw new SecurityException("W3422 non-exact batch conflict HOLD");
                }
            }
            if(count(c,"w3416_batch_sources")!=3 || count(c,"w3416_batch_audit")!=1)
                throw new SecurityException("W3422 incomplete committed batch HOLD");
            return 0;
        }
        insertSource(c,b.id,"PROVIDER",b.id+"-provider",b.gross,b.fee,b.net,b.digest);
        insertSource(c,b.id,"BANK",b.id+"-bank",0,0,b.net,b.digest);
        insertSource(c,b.id,"GL",b.id+"-gl",0,0,b.net,b.digest);
        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO w3416_batch_audit(audit_id,batch_id,reconciliation_digest) VALUES(?,?,?)")) {
            p.setObject(1,UUID.randomUUID());p.setString(2,b.id);p.setString(3,b.digest);
            if(p.executeUpdate()!=1)throw new AssertionError("audit insert failed");
        }
        return 1;
    }

    private void insertSource(Connection c,String batch,String kind,String ref,
            long gross,long fee,long net,String digest) throws SQLException {
        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO w3416_batch_sources(batch_id,source_kind,"
                +"reference_id,currency,gross_minor,fee_minor,net_minor,signed_digest)"
                +" VALUES(?,?,?,'USD',?,?,?,?)")) {
            p.setString(1,batch);p.setString(2,kind);p.setString(3,ref);
            p.setLong(4,gross);p.setLong(5,fee);p.setLong(6,net);
            p.setString(7,digest);
            if(p.executeUpdate()!=1)throw new AssertionError("source insert failed");
        }
    }

    int acceptEvent(Connection c,Event e) throws SQLException {
        int inserted;
        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO w3415_gl_events(event_id,provider_event_id,"
                +"sequence_no,event_kind,amount_minor,currency,canonical_digest,approval_id)"
                +" VALUES(?,?,?,?,?,'USD',?,?) ON CONFLICT DO NOTHING")) {
            p.setString(1,e.id);p.setString(2,e.providerId);
            p.setLong(3,e.sequence);p.setString(4,e.kind);
            p.setLong(5,e.amount);p.setString(6,e.digest);p.setObject(7,e.approval);
            inserted=p.executeUpdate();
        }
        if(inserted==0) {
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT provider_event_id,sequence_no,event_kind,amount_minor,"
                    +"currency,canonical_digest,approval_id FROM w3415_gl_events WHERE event_id=?")) {
                p.setString(1,e.id);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next() || !e.providerId.equals(r.getString(1))
                            || e.sequence!=r.getLong(2) || !e.kind.equals(r.getString(3))
                            || e.amount!=r.getLong(4) || !"USD".equals(r.getString(5).trim())
                            || !e.digest.equals(r.getString(6))
                            || !e.approval.equals(r.getObject(7)))
                        throw new SecurityException("W3422 non-exact GL event conflict HOLD");
                }
            }
            if(count(c,"w3415_gl_lines")!=2)
                throw new SecurityException("W3422 incomplete GL line replay HOLD");
            return 0;
        }
        insertLine(c,e.id,1,"1100","D",e.amount);
        insertLine(c,e.id,2,"1200","C",e.amount);
        return 1;
    }

    void insertHeader(Connection c,Event e) throws SQLException {
        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO w3415_gl_events(event_id,provider_event_id,"
                +"sequence_no,event_kind,amount_minor,currency,canonical_digest,approval_id)"
                +" VALUES(?,?,?,?,?,'USD',?,?)")) {
            p.setString(1,e.id);p.setString(2,e.providerId);p.setLong(3,e.sequence);
            p.setString(4,e.kind);p.setLong(5,e.amount);
            p.setString(6,e.digest);p.setObject(7,e.approval);
            p.executeUpdate();
        }
    }

    void insertLine(Connection c,String event,int num,String account,
            String side,long amount) throws SQLException {
        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO w3415_gl_lines(event_id,line_no,account_code,side,amount_minor,currency)"
                +" VALUES(?,?,?,?,?,'USD')")) {
            p.setString(1,event);p.setInt(2,num);p.setString(3,account);
            p.setString(4,side);p.setLong(5,amount);p.executeUpdate();
        }
    }

    int count(Connection c,String table) throws SQLException {
        if(!table.matches("w341[56]_[a-z_]+"))
            throw new IllegalArgumentException("disallowed table");
        try(Statement s=c.createStatement();
                ResultSet r=s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if(!r.next())throw new SQLException("count result missing");
            return r.getInt(1);
        }
    }

    long total(Connection c,String side) throws SQLException {
        if(!"D".equals(side) && !"C".equals(side))throw new IllegalArgumentException();
        try(PreparedStatement p=c.prepareStatement(
                "SELECT COALESCE(SUM(amount_minor),0) FROM w3415_gl_lines WHERE side=?")) {
            p.setString(1,side);
            try(ResultSet r=p.executeQuery()){r.next();return r.getLong(1);}
        }
    }

    void emit(String id,String oracle,String evidence) throws Exception {
        if(!id.matches("W3422-0[1-8]") || !oracle.matches("[a-z0-9_]+")
                || !evidence.matches("[a-zA-Z0-9_ ,:=-]+"))
            throw new IllegalArgumentException("Unsafe manifest text");
        Path folder=Paths.get("target","wave34-22-evidence");
        Files.createDirectories(folder);
        String json="{\"case_id\":\""+id+"\",\"status\":\"PASS\","
                +"\"source_head\":\""+head+"\",\"schema\":\""+schema+"\","
                +"\"pg_server_version_num\":"+pgVersion+","
                +"\"isolation\":\"READ_COMMITTED\",\"oracle\":\""+oracle+"\","
                +"\"barrier_evidence\":\""+evidence+"\","
                +"\"historical_ddl_modified\":false,"
                +"\"real_provider_tested\":false,\"independent_witness\":false,"
                +"\"external_receiver_verified\":false,\"production_authorized\":false,"
                +"\"release_disposition\":\"NO-GO\","
                +"\"recorded_at\":\""+Instant.now().toString()+"\"}\n";
        Files.write(folder.resolve(id+".json"),json.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override public void close() throws Exception {
        if(closed)return;
        closed=true;
        if(!schema.matches("w3422_[0-9a-f]{32}"))throw new IllegalStateException();
        try(Connection c=DriverManager.getConnection(url,user,password);
                Statement s=c.createStatement()) {
            s.execute("DROP SCHEMA "+schema+" CASCADE");
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT 1 FROM pg_namespace WHERE nspname=?")) {
                p.setString(1,schema);
                try(ResultSet r=p.executeQuery()) {
                    if(r.next())throw new AssertionError("Disposable schema cleanup failure");
                }
            }
        }
    }
}
