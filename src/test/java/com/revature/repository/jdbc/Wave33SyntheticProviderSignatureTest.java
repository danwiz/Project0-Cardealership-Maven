package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java negative signatures; separate physical PG16 receipt replay lab
 * tests SQL transaction and concurrent processing.
 */
@Tag("SECURITY")
class Wave33SyntheticProviderSignatureTest {
    @Test
    void signedProcessorReceiptRejectsTamperingWrongKeyAndDomainConfusion() {
        String request="processor-req-000001";
        String transaction="processor-txn-000001";
        String customer="ha-buyer";
        String contract="ACQ-000001";
        int amount=1250;
        String signature=Wave33SyntheticProviderSignature.issue(
                request,transaction,customer,contract,amount,"COMMITTED");
        assertEquals(64,signature.length());
        assertDoesNotThrow(()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,signature,
                request,transaction,customer,contract,amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                "unknown-provider-key",signature,request,transaction,customer,contract,
                amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                "0".repeat(64),request,transaction,customer,contract,amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,request,transaction,customer,"ACQ-000002",amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,request,transaction,customer,contract,amount+1,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,"processor-req-000002",transaction,customer,contract,amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,request,"processor-txn-000002",customer,contract,amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,request,transaction,"another-buyer",contract,amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,request,transaction,customer,contract,amount,"REFUNDED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,request,transaction,customer,"ACQ-000001|forged",amount,"COMMITTED"));
        assertThrows(SecurityException.class,()->Wave33SyntheticProviderSignature.verify(
                Wave33SyntheticProviderSignature.KEY_ID,
                signature,"processor-req-000001\nforged",transaction,customer,contract,
                amount,"COMMITTED"));
        String second=Wave33SyntheticProviderSignature.issue(
                request,transaction,customer,contract,amount+1,"COMMITTED");
        assertNotEquals(signature,second,"amount must be cryptographically bound");
    }
}
