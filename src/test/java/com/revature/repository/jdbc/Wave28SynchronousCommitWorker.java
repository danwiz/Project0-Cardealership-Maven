package com.revature.repository.jdbc;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;

/**
 * Disposable PostgreSQL16 synchronous COMMIT wait test.
 *
 * This worker uses the real dealership purchase approval transaction while a
 * connected synchronous physical standby is configured for remote_apply.
 * The test orchestration then severs the replication connection, observes
 * the server's SyncRep wait event, fences the old primary, and promotes the
 * standby. The worker must return an uncertain outcome, NOT successful COMMIT.
 *
 * This is not a production app feature, not arbitrary command-line SQL,
 * and never accepts credentials, arbitrary URLs or production port settings.
 */
public final class Wave28SynchronousCommitWorker {
    private Wave28SynchronousCommitWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"approve".equals(args[0])
                || !"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) {
            throw new SecurityException("disposable CI synchronous commit worker only");
        }
        final String url="jdbc:postgresql://127.0.0.1:55421/labdb"
                +"?ApplicationName=W28SyncApproval&connectTimeout=5&socketTimeout=75";
        final String owner="labowner";
        final String password="w21-ci-only-owner-not-production";
        ConfiguredDealershipApplication app=DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(url,owner,password));
        User reviewer=AuthenticatedAccounts.login(app,AccountRole.EMPLOYEE,
                "ha-reviewer","ha-reviewer-synthetic-password");
        JdbcDatabase database=new JdbcDatabase(url,owner,password);
        JdbcPurchaseApprovalService service=new JdbcPurchaseApprovalService(
                database,app.getContext().getAuthorizationService());
        System.out.println("W28_SYNCHRONOUS_APPROVAL_START");
        System.out.flush();
        try {
            service.approve(reviewer,0,24,false);
            throw new AssertionError(
                    "remote_apply transaction unexpectedly committed despite disconnected synchronous standby");
        } catch (IllegalStateException failure) {
            if(failure.getMessage()==null
                    || !failure.getMessage().toLowerCase().contains("uncertain")) {
                throw new AssertionError("blocking COMMIT must yield uncertain outcome after old primary fence",
                        failure);
            }
            System.out.println("W28_SYNC_CLIENT_RESULT_UNCERTAIN_AFTER_FENCING");
            System.out.flush();
        }
    }
}
