package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.PaymentTransaction;

/**
 * Characterization — NOT a claim that two legitimate equal payments are an
 * application error. The existing record(customer, ownership, amount) API
 * cannot distinguish a legitimate second installment from a retry of a
 * single externally committed charge because it has no provider request key.
 *
 * Wave32's independent-processor demonstration solves this in a separate
 * disposable lab; the production JDBC payment API has NOT been changed.
 */
@Tag("SECURITY")
class JdbcPaymentUnkeyedRetryCharacterizationTest {
    @Test
    void sameAmountRetryCreatesSecondLocalPaymentWithoutExternalIdempotencyKey()
            throws Exception {
        JdbcDatabase db=JdbcDatabase.inMemory(
                "w32_unkeyed_payment_"+System.nanoTime());
        db.migrate();
        long ownership;
        try(Connection c=db.openConnection()) {
            try(Statement s=c.createStatement()) {
                s.executeUpdate("INSERT INTO accounts(username,encoded_credential,role)"
                        +" VALUES('buyer','encoded','CUSTOMER')");
            }
            try(PreparedStatement insert=c.prepareStatement(
                    "INSERT INTO owned_vehicles(owner_username,make,model,vehicle_year)"
                    +" VALUES('buyer','Toyota','Corolla',2024)",
                    Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1,insert.executeUpdate());
                try(ResultSet generated=insert.getGeneratedKeys()) {
                    assertTrue(generated.next());
                    ownership=generated.getLong(1);
                }
            }
            try(PreparedStatement plan=c.prepareStatement(
                    "INSERT INTO payment_plans("
                    +"ownership_id,purchase_price,payment_months,monthly_payment,"
                    +"amount_paid,balance) VALUES(?,24000,24,1000,0,24000)")){
                plan.setLong(1,ownership);
                assertEquals(1,plan.executeUpdate());
            }
        }

        // Both invocations are locally valid. But an external processor may
        // have executed only ONE request whose acknowledgement was lost.
        // No provider idempotency key is supplied to this API.
        JdbcPaymentProcessor payments=new JdbcPaymentProcessor(db);
        PaymentTransaction first=payments.record("buyer",ownership,1250);
        PaymentTransaction repeat=payments.record("buyer",ownership,1250);
        assertNotEquals(first.getTransactionId(),repeat.getTransactionId());
        assertEquals(1250,first.getAmount());
        assertEquals(1250,repeat.getAmount());
        assertEquals(2,new JdbcPaymentTransactionRepository(db,"buyer").findAll().size());

        try(Connection c=db.openConnection();
                PreparedStatement q=c.prepareStatement(
                    "SELECT amount_paid,balance FROM payment_plans WHERE ownership_id=?")) {
            q.setLong(1,ownership);
            try(ResultSet row=q.executeQuery()){
                assertTrue(row.next());
                assertEquals(2500,row.getInt(1));
                assertEquals(21500,row.getInt(2));
            }
        }
    }
}
