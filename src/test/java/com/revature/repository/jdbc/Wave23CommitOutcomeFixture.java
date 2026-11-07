package com.revature.repository.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/**
 * Disposable PG16 integration fixture:
 * 1. prepare an actual schema-v5 PENDING purchase on primary;
 * 2. prove a real backend COMMIT ReadyForQuery is dropped before JDBC sees it;
 * 3. reconcile an accepted purchase on the caught-up primary;
 * 4. after physical promotion, reconnect and safely replay the same request.
 *
 * This is not a production HA/fencing/partition test.
 */
public final class Wave23CommitOutcomeFixture {
    private static final String OWNER="labowner";
    private static final String PASS="w21-ci-only-owner-not-production";
    private static final String DB="labdb";
    private static final int PRICE=24000;

    private Wave23CommitOutcomeFixture() {}

    public static void main(String[] arguments) throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || arguments.length != 2) {
            throw new SecurityException("only disposable GitHub CI fixtures are permitted");
        }
        String mode=arguments[0];
        String port=arguments[1];
        if ((!port.equals("55421") && !port.equals("55422"))
                || (mode.equals("recover") ? !port.equals("55422") : !port.equals("55421"))
                || (!mode.equals("seed-pending") && !mode.equals("drop-reply")
                    && !mode.equals("recover"))) {
            throw new SecurityException("unexpected local fixture operation or port");
        }

        String directUrl=uri(port);
        JdbcDatabase direct=new JdbcDatabase(directUrl,OWNER,PASS);
        if (mode.equals("seed-pending")) {
            direct.migrate();
            ConfiguredDealershipApplication application=app(directUrl);
            TestAccounts.seed(new JdbcUserAccountRepository(direct),
                    AccountRole.EMPLOYEE,"ha-reviewer","ha-reviewer-synthetic-password");
            application.getContext().getCustomerLoginService().registerUser(
                    "ha-buyer","ha-buyer-synthetic-password");
            application.getInventory().addListing("Toyota","Corolla",2024,PRICE,1);
            application.getInventory().requestPurchase("ha-buyer",0);
            verify(direct,false,"SEED_PENDING");
        } else if (mode.equals("drop-reply")) {
            direct.requireCurrentSchema();
            ConfiguredDealershipApplication application=app(directUrl);
            User reviewer=AuthenticatedAccounts.login(application,AccountRole.EMPLOYEE,
                    "ha-reviewer","ha-reviewer-synthetic-password");
            try (CommitAckDropProxy proxy=new CommitAckDropProxy(55421)) {
                String proxied="jdbc:postgresql://127.0.0.1:"+proxy.port()+"/"+DB
                        +"?sslmode=disable&preferQueryMode=simple&connectTimeout=5&socketTimeout=8";
                JdbcPurchaseApprovalService service=new JdbcPurchaseApprovalService(
                        new JdbcDatabase(proxied,OWNER,PASS),
                        application.getContext().getAuthorizationService());
                boolean uncertain=false;
                try {
                    service.approve(reviewer,0,24,false);
                } catch (IllegalStateException expected) {
                    uncertain=expected.getMessage()!=null
                            && expected.getMessage().contains("uncertain");
                    if (!uncertain) throw expected;
                }
                if (!uncertain || !proxy.awaitDroppedCommit()) {
                    throw new AssertionError("backend COMMIT acknowledgment was not confirmed lost");
                }
                System.out.println("W23_ACTUAL_COMMIT_REPLY_DROPPED_AND_CLIENT_UNCERTAIN_PASS");
            }
            // Verify on an entirely new direct connection, not cached client state.
            verify(direct,true,"COMMITTED_WITH_LOST_ACK");
        } else {
            direct.requireCurrentSchema();
            verify(direct,true,"PROMOTED_READBACK");
            ConfiguredDealershipApplication application=app(directUrl);
            User reviewer=AuthenticatedAccounts.login(application,AccountRole.EMPLOYEE,
                    "ha-reviewer","ha-reviewer-synthetic-password");
            int price=new JdbcPurchaseApprovalService(
                    direct,application.getContext().getAuthorizationService())
                    .approve(reviewer,0,24,false);
            if (price!=PRICE)throw new AssertionError("idempotent replay price mismatch");
            verify(direct,true,"PROMOTED_AFTER_IDEMPOTENT_REPLAY");
        }
    }

    private static String uri(String port) {
        return "jdbc:postgresql://127.0.0.1:"+port+"/"+DB;
    }

    private static ConfiguredDealershipApplication app(String url) {
        return DealershipCompositionRoot.create(InfrastructureConfiguration.jdbc(url,OWNER,PASS));
    }

    private static void verify(JdbcDatabase db, boolean approved, String label) throws Exception {
        int accepted=approved?1:0;
        int pending=approved?0:1;
        int stock=approved?0:1;
        verifyCount(db,"SELECT count(*) FROM accounts",2,label);
        verifyCount(db,"SELECT count(*) FROM accounts WHERE session_revision IS NOT NULL",2,label);
        verifyCount(db,"SELECT count(*) FROM purchase_requests WHERE status='ACCEPTED' AND request_id=0",accepted,label);
        verifyCount(db,"SELECT count(*) FROM purchase_requests WHERE status='PENDING' AND request_id=0",pending,label);
        verifyCount(db,"SELECT count(*) FROM inventory_listings WHERE listing_id=0 AND stock_quantity="+stock,1,label);
        verifyCount(db,"SELECT count(*) FROM owned_vehicles",accepted,label);
        verifyCount(db,"SELECT count(*) FROM payment_plans",accepted,label);
        verifyCount(db,"SELECT count(*) FROM payment_transactions",0,label);
        verifyCount(db,"SELECT COALESCE(MAX(version),0) FROM schema_history",5,label);
        if(approved) {
            verifyCount(db,"SELECT count(*) FROM owned_vehicles WHERE contract_id IS NOT NULL",1,label);
            verifyCount(db,"SELECT count(*) FROM payment_plans WHERE purchase_price=24000 AND balance=24000",1,label);
        }
        System.out.println("W23_"+label+"_PASS: accepted="+accepted+
                " stock="+stock+" ownership="+accepted+" paymentPlans="+accepted);
    }

    private static void verifyCount(JdbcDatabase db,String sql,int expected,String label)
            throws Exception {
        try(Connection c=db.openConnection();PreparedStatement q=c.prepareStatement(sql);
                ResultSet results=q.executeQuery()) {
            if(!results.next()||results.getInt(1)!=expected) {
                throw new AssertionError(label+": expected "+expected+" from "+sql);
            }
        }
    }

    /** Test-only PostgreSQL wire relay; never use on a service or production network. */
    private static final class CommitAckDropProxy implements AutoCloseable {
        private static final byte[] COMMIT="COMMIT\0".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] COMMIT_READY=new byte[]{'Z',0,0,0,5,'I'};
        private final ServerSocket listener;
        private final ExecutorService workers=Executors.newCachedThreadPool();
        private final List<Socket> sockets=Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean running=new AtomicBoolean(true);
        private final AtomicBoolean commitClaimed=new AtomicBoolean();
        private final CountDownLatch dropped=new CountDownLatch(1);
        private final int backendPort;

        CommitAckDropProxy(int backendPort) throws IOException {
            this.backendPort=backendPort;
            this.listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            workers.execute(()->{
                while(running.get()) {
                    try {
                        Socket client=listener.accept();
                        Socket backend=new Socket("127.0.0.1",this.backendPort);
                        sockets.add(client);
                        sockets.add(backend);
                        AtomicBoolean suppress=new AtomicBoolean(false);
                        workers.execute(()->relay(client,backend,suppress,true));
                        workers.execute(()->relay(backend,client,suppress,false));
                    } catch(IOException stopped) {
                        if(running.get())running.set(false);
                    }
                }
            });
        }
        int port(){return listener.getLocalPort();}
        boolean awaitDropped() throws InterruptedException {
            return dropped.await(6,TimeUnit.SECONDS);
        }
        private void relay(Socket src,Socket dst,AtomicBoolean hold,boolean outbound) {
            try {
                src.setSoTimeout(12000);
                InputStream input=src.getInputStream();
                OutputStream output=dst.getOutputStream();
                byte[] data=new byte[8192], tail=new byte[0];
                int size;
                while(running.get()&&(size=input.read(data))>=0) {
                    byte[] window=new byte[tail.length+size];
                    System.arraycopy(tail,0,window,0,tail.length);
                    System.arraycopy(data,0,window,tail.length,size);
                    if(outbound) {
                        if(!commitClaimed.get()&&contains(window,COMMIT)
                                &&commitClaimed.compareAndSet(false,true)) {
                            hold.set(true);
                        }
                        output.write(data,0,size);
                        output.flush();
                    } else if(hold.get()) {
                        // Observe actual idle ReadyForQuery COMMIT response but do
                        // not send the successful acknowledgment to the JDBC client.
                        if(contains(window,COMMIT_READY)) {
                            dropped.countDown();
                            src.close();
                            dst.close();
                            return;
                        }
                    } else {
                        output.write(data,0,size);
                        output.flush();
                    }
                    tail=Arrays.copyOfRange(window,Math.max(0,window.length-24),window.length);
                }
            } catch(SocketException expectedAfterCut) {
            } catch(IOException transportClosed) {
                // A false pass is impossible: latch requires observed ReadyForQuery.
            }
        }
        private static boolean contains(byte[] source,byte[] pattern) {
            outer:for(int i=0;i<=source.length-pattern.length;i++){
                for(int j=0;j<pattern.length;j++){
                    if(source[i+j]!=pattern[j])continue outer;
                }
                return true;
            }
            return false;
        }
        @Override public void close() throws IOException {
            running.set(false);
            listener.close();
            synchronized(sockets) {
                for(Socket s:sockets)try{s.close();}catch(IOException ignored){}
            }
            workers.shutdownNow();
            try {workers.awaitTermination(6,TimeUnit.SECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        }
    }
}
