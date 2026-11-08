package com.revature.repository.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicReference;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/**
 * Disposable PG16 transactional failover experiment.
 *
 * A physical PostgreSQL COMMIT request from the actual dealership approval
 * service is intercepted BEFORE reaching its backend, while the transaction
 * has already executed its purchase SQL. CI fences the primary and promotes
 * its physical standby WHILE that COMMIT is blocked in the wire proxy. The
 * client then receives an uncertain outcome. The promoted leader has a
 * PENDING request and no sale. Replaying the same request via a new JDBC
 * context must produce exactly ONE durable sale.
 *
 * This is specifically the pre-backend-COMMIT interleaving during promotion,
 * NOT a proof of every possible in-flight/partially-durable COMMIT schedule.
 * Never point at production data/ports.
 */
public final class Wave27InFlightCommitFixture {
    private static final String OWNER="labowner";
    private static final String PASS="w21-ci-only-owner-not-production";
    private static final String DATABASE="labdb";
    private static final int PRICE=24000;

    private Wave27InFlightCommitFixture(){}

    public static void main(String[] arguments) throws Exception {
        if(!"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || arguments.length!=2
                || !(arguments[1].equals("55421")||arguments[1].equals("55422"))) {
            throw new SecurityException("only disposable PG16 CI ports accepted");
        }
        String mode=arguments[0],port=arguments[1];
        if ((mode.equals("recover") && !port.equals("55422"))
                || (!mode.equals("recover") && !port.equals("55421"))
                || !(mode.equals("seed-pending")||mode.equals("hold-commit")
                    ||mode.equals("recover"))) {
            throw new SecurityException("unexpected fixture mode");
        }
        String url="jdbc:postgresql://127.0.0.1:"+port+"/"+DATABASE;
        JdbcDatabase direct=new JdbcDatabase(url,OWNER,PASS);

        if(mode.equals("seed-pending")) {
            direct.migrate();
            ConfiguredDealershipApplication application=app(url);
            TestAccounts.seed(new JdbcUserAccountRepository(direct),AccountRole.EMPLOYEE,
                    "ha-reviewer","ha-reviewer-synthetic-password");
            application.getContext().getCustomerLoginService().registerUser(
                    "ha-buyer","ha-buyer-synthetic-password");
            application.getInventory().addListing("Toyota","Corolla",2024,PRICE,1);
            application.getInventory().requestPurchase("ha-buyer",0);
            verify(direct,false,"BEFORE_COMMIT");
        } else if(mode.equals("hold-commit")) {
            direct.requireCurrentSchema();
            ConfiguredDealershipApplication application=app(url);
            User reviewer=AuthenticatedAccounts.login(application,AccountRole.EMPLOYEE,
                    "ha-reviewer","ha-reviewer-synthetic-password");
            try(CommitHoldProxy proxy=new CommitHoldProxy(55421)) {
                String proxied="jdbc:postgresql://127.0.0.1:"+proxy.port()+"/"+DATABASE
                        +"?sslmode=disable&preferQueryMode=simple&connectTimeout=5&socketTimeout=12";
                JdbcPurchaseApprovalService service=new JdbcPurchaseApprovalService(
                        new JdbcDatabase(proxied,OWNER,PASS),
                        application.getContext().getAuthorizationService());
                AtomicReference<Throwable> response=new AtomicReference<>();
                Thread purchase=new Thread(()->{
                    try {service.approve(reviewer,0,24,false);}
                    catch(Throwable t){response.set(t);}
                },"w27-inflight-purchase");
                purchase.setDaemon(true);
                purchase.start();
                if(!proxy.awaitCommitHeld(30)) {
                    throw new AssertionError("actual outbound PostgreSQL COMMIT was not intercepted");
                }
                System.out.println("W27_COMMIT_BLOCKED_BEFORE_BACKEND");
                System.out.flush();

                Path marker=Path.of("wave27-race/release.marker");
                boolean released=false;
                for(int i=0;i<150;i++) {
                    if(Files.isRegularFile(marker)) {released=true;break;}
                    Thread.sleep(200);
                }
                if(!released)throw new AssertionError("standby promotion release marker timed out");

                // CI has fenced the old primary and promoted the standby.
                // Closing the physical proxy forces JDBC to surface uncertainty;
                // never describe this as a successful rollback from the client.
                proxy.close();
                purchase.join(14000);
                if(purchase.isAlive())throw new AssertionError("client approval remained blocked");
                Throwable failure=response.get();
                if(!(failure instanceof IllegalStateException)
                        || failure.getMessage()==null
                        || !failure.getMessage().contains("uncertain")) {
                    throw new AssertionError("client did not report unknown commit outcome",failure);
                }
                System.out.println("W27_CLIENT_COMMIT_OUTCOME_UNCERTAIN_AFTER_PROMOTION");
                System.out.flush();
            }
        } else {
            direct.requireCurrentSchema();
            verify(direct,false,"PROMOTED_PENDING_BEFORE_RETRY");
            ConfiguredDealershipApplication application=app(url);
            User reviewer=AuthenticatedAccounts.login(application,AccountRole.EMPLOYEE,
                    "ha-reviewer","ha-reviewer-synthetic-password");
            int amount=new JdbcPurchaseApprovalService(
                    direct,application.getContext().getAuthorizationService())
                    .approve(reviewer,0,24,false);
            if(amount!=PRICE)throw new AssertionError("unexpected purchase price");
            verify(direct,true,"PROMOTED_ACCEPTED_AFTER_ONE_RETRY");
            int again=new JdbcPurchaseApprovalService(
                    direct,application.getContext().getAuthorizationService())
                    .approve(reviewer,0,24,false);
            if(again!=PRICE)throw new AssertionError("idempotent approval mismatch");
            verify(direct,true,"PROMOTED_ACCEPTED_AFTER_REPLAY");
        }
    }

    private static ConfiguredDealershipApplication app(String url) {
        return DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(url,OWNER,PASS));
    }

    private static void verify(JdbcDatabase database,boolean accepted,String phase)
            throws Exception {
        int n=accepted?1:0;
        assertCount(database,"SELECT count(*) FROM accounts",2,phase);
        assertCount(database,"SELECT count(*) FROM schema_history WHERE version=5",1,phase);
        assertCount(database,"SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='ACCEPTED'",n,phase);
        assertCount(database,"SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'",1-n,phase);
        assertCount(database,"SELECT count(*) FROM inventory_listings WHERE listing_id=0 AND stock_quantity="+(1-n),1,phase);
        assertCount(database,"SELECT count(*) FROM owned_vehicles",n,phase);
        assertCount(database,"SELECT count(*) FROM payment_plans",n,phase);
        assertCount(database,"SELECT count(*) FROM payment_transactions",0,phase);
        if(accepted) {
            assertCount(database,"SELECT count(*) FROM owned_vehicles WHERE contract_id IS NOT NULL",1,phase);
            assertCount(database,"SELECT count(*) FROM payment_plans WHERE purchase_price=24000 AND balance=24000",1,phase);
        }
        System.out.println("W27_"+phase+"_PASS: accepted="+n+" ownership="+n+" plans="+n);
    }
    private static void assertCount(JdbcDatabase database,String sql,int expected,String phase)
            throws Exception {
        try(Connection c=database.openConnection();
                PreparedStatement q=c.prepareStatement(sql);ResultSet rows=q.executeQuery()) {
            if(!rows.next()||rows.getInt(1)!=expected) {
                throw new AssertionError(phase+": expected "+expected+" from "+sql);
            }
        }
    }

    private static final class CommitHoldProxy implements AutoCloseable {
        private static final byte[] COMMIT="COMMIT\0".getBytes(StandardCharsets.US_ASCII);
        private final ServerSocket listener;
        private final ExecutorService workers=Executors.newCachedThreadPool();
        private final List<Socket> sockets=Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean running=new AtomicBoolean(true);
        private final AtomicBoolean claimed=new AtomicBoolean(false);
        private final CountDownLatch held=new CountDownLatch(1);
        private final int backendPort;

        CommitHoldProxy(int backendPort) throws IOException {
            this.backendPort=backendPort;
            listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            workers.execute(()->{
                while(running.get()) {
                    try {
                        Socket client=listener.accept();
                        Socket server=new Socket("127.0.0.1",this.backendPort);
                        sockets.add(client);sockets.add(server);
                        workers.execute(()->relay(client,server,true));
                        workers.execute(()->relay(server,client,false));
                    } catch(IOException stopped) {
                        if(running.get())running.set(false);
                    }
                }
            });
        }
        int port(){return listener.getLocalPort();}
        boolean awaitCommitHeld(int seconds) throws InterruptedException {
            return held.await(seconds,TimeUnit.SECONDS);
        }
        private void relay(Socket from,Socket to,boolean clientToServer) {
            try {
                from.setSoTimeout(35000);
                InputStream input=from.getInputStream();
                OutputStream output=to.getOutputStream();
                byte[] buffer=new byte[8192],tail=new byte[0];
                int size;
                while(running.get() && (size=input.read(buffer))>=0) {
                    byte[] sample=new byte[tail.length+size];
                    System.arraycopy(tail,0,sample,0,tail.length);
                    System.arraycopy(buffer,0,sample,tail.length,size);
                    if(clientToServer && !claimed.get() && contains(sample,COMMIT)
                            && claimed.compareAndSet(false,true)) {
                        // Deliberately DO NOT forward this COMMIT frame to the
                        // old backend. Signal CI to fence+promote while client
                        // is still awaiting the result.
                        held.countDown();
                        while(running.get())Thread.sleep(50);
                        return;
                    }
                    output.write(buffer,0,size);
                    output.flush();
                    tail=Arrays.copyOfRange(sample,Math.max(0,sample.length-24),sample.length);
                }
            } catch(SocketException closed) {
            } catch(IOException disconnected) {
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
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
                for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}
            }
            workers.shutdownNow();
            try{workers.awaitTermination(6,TimeUnit.SECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        }
    }
}
