package com.revature.repository.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * Wave32 synthetic external payment processor + dealership reconciliation.
 *
 * TWO INDEPENDENT PostgreSQL16 databases: one processor container which does
 * not participate in dealership failover and one physical dealership primary/
 * standby. Payment request idempotency is owned by processor database;
 * post-to-dealership idempotency is a single atomic SQL transaction.
 *
 * This is test-only boundary research, NOT a production payment integration.
 * No card data, network merchant calls, refunds or production secrets.
 */
public final class Wave32IndependentPaymentFixture {
    private static final String REQUEST="processor-req-000001";
    private static final String PROVIDER_TX="processor-txn-000001";
    private static final String CUSTOMER="ha-buyer";
    private static final String LOCAL_PAYMENT="PAY-000001";
    private static final int PAYMENT=1250;
    private static final int PRICE=24000;
    private static final String OWNER="labowner";
    private static final String OWNER_PASSWORD="w21-ci-only-owner-not-production";
    private static final String PROCESSOR_OWNER="processor_owner";
    private static final String PROCESSOR_OWNER_PASSWORD="ci-synthetic-independent-processor";
    private static final String PROCESSOR_READER="processor_receipt_reader";
    private static final String PROCESSOR_READER_PASSWORD="ci-synthetic-read-only-receipts";

    private Wave32IndependentPaymentFixture() {}

    public static void main(String[] args) throws Exception {
        if(args.length!=1
                || !"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || !"true".equals(System.getenv("W32_DISPOSABLE_ONLY"))) {
            throw new SecurityException("Wave32 payment fixture requires disposable CI");
        }
        switch(args[0]){
            case "prepare":
                prepareProcessor();
                prepareDealership("55421");
                System.out.println("W32_INDEPENDENT_PROVIDER_AND_DEALERSHIP_READY");
                return;
            case "charge-ack-lost":
                processorCharge();
                verifyProvider();
                System.out.println("W32_PROVIDER_COMMITTED_ON_SEPARATE_POSTGRES_BUT_APP_ACK_SUPPRESSED");
                return;
            case "reconcile":
                verifyProvider();
                JdbcDatabase promoted=dealership("55422");
                promoted.requireCurrentSchema();
                verifyLocal(promoted,false);
                if(!postReceiptOnce(promoted)) {
                    throw new AssertionError("first provider receipt was already posted");
                }
                verifyLocal(promoted,true);
                if(postReceiptOnce(promoted)){
                    throw new AssertionError("replayed provider receipt double-posted");
                }
                verifyLocal(promoted,true);
                verifyProvider();
                assertProviderReaderDenied();
                System.out.println("W32_PROVIDER_CHARGED_ONCE_DEALERSHIP_POSTED_ONCE_REPLAY_IDEMPOTENT");
                return;
            default:
                throw new SecurityException("unsupported disposable mode");
        }
    }

    private static JdbcDatabase dealership(String port) {
        if(!("55421".equals(port)||"55422".equals(port)))
            throw new SecurityException("only disposable dealership lab ports allowed");
        return new JdbcDatabase("jdbc:postgresql://127.0.0.1:"+port+"/labdb",
                OWNER,OWNER_PASSWORD);
    }

    private static Connection providerOwner() throws SQLException{
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:55423/processor_lab",
                PROCESSOR_OWNER,PROCESSOR_OWNER_PASSWORD);
    }

    private static Connection providerReader() throws SQLException{
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:55423/processor_lab",
                PROCESSOR_READER,PROCESSOR_READER_PASSWORD);
    }

    private static void prepareProcessor() throws Exception{
        try(Connection c=providerOwner();Statement sql=c.createStatement()){
            try(ResultSet v=sql.executeQuery("SHOW server_version_num")){
                if(!v.next() || Integer.parseInt(v.getString(1))<160000
                        || Integer.parseInt(v.getString(1))>=170000) {
                    throw new SecurityException("only PG16 provider fixture supported");
                }
            }
            sql.execute("CREATE TABLE processor_charges("
                    +"request_key TEXT PRIMARY KEY,"
                    +"processor_tx TEXT NOT NULL UNIQUE,"
                    +"customer_name TEXT NOT NULL,"
                    +"contract_id TEXT NOT NULL,"
                    +"amount INTEGER NOT NULL CHECK(amount>0),"
                    +"status TEXT NOT NULL CHECK(status='COMMITTED'),"
                    +"created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())");
            sql.execute("CREATE ROLE "+PROCESSOR_READER+
                    " LOGIN PASSWORD '"+PROCESSOR_READER_PASSWORD+"'");
            sql.execute("REVOKE ALL ON processor_charges FROM PUBLIC");
            sql.execute("GRANT SELECT ON processor_charges TO "+PROCESSOR_READER);
        }
    }

    private static void prepareDealership(String port) throws Exception{
        JdbcDatabase db=dealership(port);
        db.requireCurrentSchema();
        if(count(db,"SELECT count(*) FROM purchase_requests "
                +"WHERE request_id=0 AND status='ACCEPTED'")!=1
                || count(db,"SELECT count(*) FROM owned_vehicles")!=1
                || count(db,"SELECT count(*) FROM payment_plans")!=1
                || count(db,"SELECT count(*) FROM payment_transactions")!=0){
            throw new AssertionError("must seed actual approved dealership contract first");
        }
        try(Connection c=db.openConnection();Statement sql=c.createStatement()){
            sql.execute("CREATE TABLE external_receipt_postings("
                    +"request_key TEXT PRIMARY KEY,"
                    +"processor_tx TEXT NOT NULL UNIQUE,"
                    +"customer_name TEXT NOT NULL,"
                    +"contract_id TEXT NOT NULL,"
                    +"receipt_sha256 CHAR(64) NOT NULL,"
                    +"posted_transaction_id TEXT NOT NULL UNIQUE,"
                    +"amount INTEGER NOT NULL CHECK(amount>0),"
                    +"posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())");
        }
        verifyLocal(db,false);
    }

    private static String contract(JdbcDatabase db) throws Exception{
        try(Connection c=db.openConnection();
                PreparedStatement s=c.prepareStatement(
                    "SELECT contract_id FROM owned_vehicles WHERE owner_username=?")){
            s.setString(1,CUSTOMER);
            try(ResultSet r=s.executeQuery()){
                if(!r.next())throw new AssertionError("expected customer vehicle ownership");
                String value=r.getString(1);
                if(value==null||!value.matches("ACQ-\\d{6}")||r.next()){
                    throw new AssertionError("invalid or duplicated contract linkage");
                }
                return value;
            }
        }
    }

    private static void processorCharge() throws Exception {
        String contract=contract(dealership("55421"));
        try(Connection c=providerOwner()){
            c.setAutoCommit(false);
            try {
                try(PreparedStatement insert=c.prepareStatement(
                    "INSERT INTO processor_charges"
                    +"(request_key,processor_tx,customer_name,contract_id,amount,status)"
                    +" VALUES(?,?,?,?,?,'COMMITTED') ON CONFLICT(request_key) DO NOTHING")) {
                    insert.setString(1,REQUEST);
                    insert.setString(2,PROVIDER_TX);
                    insert.setString(3,CUSTOMER);
                    insert.setString(4,contract);
                    insert.setInt(5,PAYMENT);
                    if(insert.executeUpdate()!=1)throw new AssertionError(
                            "synthetic provider expected new immutable request");
                }
                c.commit();
            } catch(Exception e) {
                c.rollback();
                throw e;
            }
        }
        // Deliberately do not send a success event to dealership. This models
        // a lost application acknowledgment AFTER provider's durable COMMIT;
        // it is NOT an intercepted TCP/TLS processor response.
    }

    private static Receipt readProvider() throws Exception {
        try(Connection c=providerReader();
                PreparedStatement s=c.prepareStatement(
                    "SELECT request_key,processor_tx,customer_name,contract_id,amount,status "
                    +"FROM processor_charges WHERE request_key=?")){
            s.setString(1,REQUEST);
            try(ResultSet r=s.executeQuery()){
                if(!r.next())throw new SecurityException("external receipt absent: NO-POST");
                Receipt value=new Receipt(r.getString(1),r.getString(2),
                    r.getString(3),r.getString(4),r.getInt(5),r.getString(6));
                if(r.next())throw new SecurityException("duplicate processor receipt");
                return value;
            }
        }
    }

    private static void verifyProvider() throws Exception {
        Receipt receipt=readProvider();
        if(!REQUEST.equals(receipt.request)
                || !PROVIDER_TX.equals(receipt.processorTx)
                || !CUSTOMER.equals(receipt.customer)
                || PAYMENT!=receipt.amount
                || !"COMMITTED".equals(receipt.status)) {
            throw new SecurityException("provider charge identity changed");
        }
        try(Connection c=providerReader();Statement q=c.createStatement();
                ResultSet rows=q.executeQuery("SELECT count(*) FROM processor_charges")){
            if(!rows.next()||rows.getInt(1)!=1) {
                throw new AssertionError("processor must contain exactly one charge");
            }
        }
    }

    private static boolean postReceiptOnce(JdbcDatabase local) throws Exception{
        Receipt remote=readProvider();
        if(!"COMMITTED".equals(remote.status)
                || !REQUEST.equals(remote.request)
                || !PROVIDER_TX.equals(remote.processorTx)
                || !CUSTOMER.equals(remote.customer)
                || remote.amount!=PAYMENT
                || !remote.contract.equals(contract(local))) {
            throw new SecurityException("external processor receipt failed reconciliation");
        }
        String digest=sha256(remote.request+"|"+remote.processorTx+"|"
                +remote.customer+"|"+remote.contract+"|"+remote.amount+"|"+remote.status);
        try(Connection c=local.openConnection()){
            c.setAutoCommit(false);
            try {
                int inserted;
                try(PreparedStatement record=c.prepareStatement(
                    "INSERT INTO external_receipt_postings("
                    +"request_key,processor_tx,customer_name,contract_id,receipt_sha256,"
                    +"posted_transaction_id,amount) VALUES(?,?,?,?,?,?,?) "
                    +"ON CONFLICT(request_key) DO NOTHING")){
                    record.setString(1,remote.request);
                    record.setString(2,remote.processorTx);
                    record.setString(3,remote.customer);
                    record.setString(4,remote.contract);
                    record.setString(5,digest);
                    record.setString(6,LOCAL_PAYMENT);
                    record.setInt(7,remote.amount);
                    inserted=record.executeUpdate();
                }
                if(inserted==0) {
                    try(PreparedStatement old=c.prepareStatement(
                        "SELECT processor_tx,customer_name,contract_id,receipt_sha256,"
                        +"posted_transaction_id,amount FROM external_receipt_postings "
                        +"WHERE request_key=? FOR UPDATE")){
                        old.setString(1,REQUEST);
                        try(ResultSet r=old.executeQuery()){
                            if(!r.next() || !remote.processorTx.equals(r.getString(1))
                                    || !remote.customer.equals(r.getString(2))
                                    || !remote.contract.equals(r.getString(3))
                                    || !digest.equals(r.getString(4))
                                    || !LOCAL_PAYMENT.equals(r.getString(5))
                                    || remote.amount!=r.getInt(6)
                                    || r.next()) {
                                throw new SecurityException("replayed receipt mismatched");
                            }
                        }
                    }
                    c.commit();
                    return false;
                }
                if(inserted!=1)throw new AssertionError("unexpected external receipt insert");
                long ownershipId;
                int balance;
                int alreadyPaid;
                try(PreparedStatement plan=c.prepareStatement(
                    "SELECT pp.ownership_id,pp.balance,pp.amount_paid "
                    +"FROM payment_plans pp JOIN owned_vehicles ov "
                    +"ON ov.ownership_id=pp.ownership_id "
                    +"WHERE ov.owner_username=? AND ov.contract_id=? FOR UPDATE OF pp")){
                    plan.setString(1,CUSTOMER);
                    plan.setString(2,remote.contract);
                    try(ResultSet row=plan.executeQuery()){
                        if(!row.next())throw new SecurityException("payment contract missing");
                        ownershipId=row.getLong(1);
                        balance=row.getInt(2);
                        alreadyPaid=row.getInt(3);
                        if(row.next() || balance!=PRICE || alreadyPaid!=0){
                            throw new SecurityException("unrecognized prior plan state");
                        }
                    }
                }
                try(PreparedStatement update=c.prepareStatement(
                    "UPDATE payment_plans SET amount_paid=?,balance=? "
                    +"WHERE ownership_id=? AND amount_paid=0 AND balance=?")){
                    update.setInt(1,PAYMENT);
                    update.setInt(2,PRICE-PAYMENT);
                    update.setLong(3,ownershipId);
                    update.setInt(4,PRICE);
                    if(update.executeUpdate()!=1)throw new SecurityException("payment plan changed");
                }
                try(PreparedStatement ledger=c.prepareStatement(
                    "INSERT INTO payment_transactions("
                    +"transaction_id,customer_name,ownership_id,contract_id,amount,"
                    +"cumulative_paid,resulting_balance,recorded_at)"
                    +" VALUES(?,?,?,?,?,?,?,?)")){
                    ledger.setString(1,LOCAL_PAYMENT);
                    ledger.setString(2,CUSTOMER);
                    ledger.setLong(3,ownershipId);
                    ledger.setString(4,remote.contract);
                    ledger.setInt(5,PAYMENT);
                    ledger.setInt(6,PAYMENT);
                    ledger.setInt(7,PRICE-PAYMENT);
                    ledger.setTimestamp(8,Timestamp.from(Instant.now()));
                    if(ledger.executeUpdate()!=1)throw new SecurityException("ledger write failed");
                }
                c.commit();
                return true;
            } catch(Exception problem){
                c.rollback();
                throw problem;
            }
        }
    }

    private static void verifyLocal(JdbcDatabase db,boolean posted) throws Exception{
        int n=posted?1:0;
        if(count(db,"SELECT count(*) FROM external_receipt_postings")!=n
                || count(db,"SELECT count(*) FROM payment_transactions")!=n
                || count(db,"SELECT count(*) FROM owned_vehicles")!=1
                || count(db,"SELECT count(*) FROM payment_plans")!=1
                || count(db,"SELECT count(*) FROM purchase_requests WHERE status='ACCEPTED'")!=1
                || count(db,"SELECT count(*) FROM inventory_listings WHERE stock_quantity=0")!=1
                || count(db,"SELECT count(*) FROM payment_plans "
                    +"WHERE amount_paid="+(n*PAYMENT)
                    +" AND balance="+(PRICE-n*PAYMENT))!=1) {
            throw new AssertionError("debit reconciliation or ownership totals invalid");
        }
        if(posted && count(db,"SELECT count(*) FROM payment_transactions "
                +"WHERE transaction_id='PAY-000001' AND amount=1250 "
                +"AND cumulative_paid=1250 AND resulting_balance=22750")!=1){
            throw new AssertionError("linked payment ledger corrupted");
        }
    }

    private static int count(JdbcDatabase db,String sql) throws Exception{
        try(Connection c=db.openConnection();Statement s=c.createStatement();
                ResultSet r=s.executeQuery(sql)){
            if(!r.next())throw new AssertionError("no count result");
            return r.getInt(1);
        }
    }

    private static void assertProviderReaderDenied() throws Exception{
        try(Connection c=providerReader();Statement s=c.createStatement()){
            try {
                s.execute("UPDATE processor_charges SET amount=500 WHERE request_key='"
                        +REQUEST+"'");
                throw new AssertionError("untrusted provider reader can mutate charge");
            } catch(SQLException denied) {
                if(!"42501".equals(denied.getSQLState()))throw denied;
            }
        }
    }

    private static String sha256(String input) throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(
                input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(64);
        for(byte value:digest)hex.append(String.format("%02x",value&255));
        return hex.toString();
    }

    private static final class Receipt{
        final String request,processorTx,customer,contract,status;
        final int amount;
        Receipt(String request,String processorTx,String customer,String contract,
                int amount,String status){
            this.request=Objects.requireNonNull(request);
            this.processorTx=Objects.requireNonNull(processorTx);
            this.customer=Objects.requireNonNull(customer);
            this.contract=Objects.requireNonNull(contract);
            this.amount=amount;
            this.status=Objects.requireNonNull(status);
        }
    }
}
