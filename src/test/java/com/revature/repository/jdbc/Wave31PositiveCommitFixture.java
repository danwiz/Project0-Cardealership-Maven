package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;

/**
 * Actual dealership approval on a disposable PostgreSQL16 physical primary,
 * with positive (not deliberately lost) client COMMIT acknowledgment.
 * Only loopback port 55421 and the synthetic seeded schema are permitted.
 */
public final class Wave31PositiveCommitFixture {
    private Wave31PositiveCommitFixture() {}

    public static void main(String[] args) throws Exception {
        if (args.length!=1 || !"approve".equals(args[0])
                || !"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || !"true".equals(System.getenv("W31_DISPOSABLE_ONLY"))) {
            throw new SecurityException("Wave31 positive commit fixture is disposable CI only");
        }
        String url="jdbc:postgresql://127.0.0.1:55421/labdb";
        JdbcDatabase database=new JdbcDatabase(url,
                "labowner","w21-ci-only-owner-not-production");
        database.requireCurrentSchema();
        ConfiguredDealershipApplication app=DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(url,
                        "labowner","w21-ci-only-owner-not-production"));
        User reviewer=AuthenticatedAccounts.login(app,AccountRole.EMPLOYEE,
                "ha-reviewer","ha-reviewer-synthetic-password");
        int result=new JdbcPurchaseApprovalService(
                database,app.getContext().getAuthorizationService())
                .approve(reviewer,0,24,false);
        if(result!=24000)throw new AssertionError("unexpected dealership contract price");

        check(database,"SELECT count(*) FROM purchase_requests "
                +"WHERE request_id=0 AND status='ACCEPTED'",1);
        check(database,"SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'",0);
        check(database,"SELECT count(*) FROM inventory_listings WHERE listing_id=0 AND stock_quantity=0",1);
        check(database,"SELECT count(*) FROM owned_vehicles",1);
        check(database,"SELECT count(*) FROM payment_plans",1);
        check(database,"SELECT count(*) FROM payment_transactions",0);
        System.out.println("W31_CLIENT_RECEIVED_SUCCESSFUL_BACKEND_COMMIT_ACK");
        System.out.flush();
    }

    private static void check(JdbcDatabase db,String sql,int expected) throws Exception {
        try(Connection connection=db.openConnection();
                PreparedStatement read=connection.prepareStatement(sql);
                ResultSet rows=read.executeQuery()){
            if(!rows.next()||rows.getInt(1)!=expected||rows.next()) {
                throw new AssertionError("actual dealership primary mismatch on "+sql);
            }
        }
    }
}
