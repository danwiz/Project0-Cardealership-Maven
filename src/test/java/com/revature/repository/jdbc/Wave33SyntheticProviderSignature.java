package com.revature.repository.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TEST-ONLY provider receipt signature contract. No real processor, KMS,
 * webhook or merchant payment network. This is a deliberately synthetic
 * HMAC interoperability exercise to expose an unsigned-receipt weakness.
 *
 * A real provider would own the signer in a separate trust domain and
 * supply signed receipts over its authenticated API; production MUST NOT
 * store this fixed CI key or enable an application-local issue() method.
 */
public final class Wave33SyntheticProviderSignature {
    public static final String KEY_ID="w33-ci-processor-key-1";
    public static final String PURPOSE="dealership.payment.captured.v1";
    private static final byte[] CI_KEY=(
            "Wave33-disposable-PG16-fixture-key-material-"
            +"not-a-production-secret-ever-2026").getBytes(StandardCharsets.US_ASCII);

    private Wave33SyntheticProviderSignature(){}

    public static String issue(String request,String processorTx,String customer,
            String contract,int amount,String status) {
        return hmac(canonical(KEY_ID,request,processorTx,customer,contract,amount,status));
    }

    public static void verify(String keyId,String signature,String request,
            String processorTx,String customer,String contract,int amount,String status) {
        if(!KEY_ID.equals(keyId) || signature==null
                || !signature.matches("[0-9a-f]{64}")) {
            throw new SecurityException("provider receipt authentication denied");
        }
        byte[] actual=fromHex(signature);
        byte[] expected=fromHex(hmac(canonical(keyId,request,
                processorTx,customer,contract,amount,status)));
        if(!MessageDigest.isEqual(actual,expected)) {
            throw new SecurityException("provider receipt authentication denied");
        }
    }

    private static byte[] canonical(String keyId,String request,String processorTx,
            String customer,String contract,int amount,String status) {
        if(!KEY_ID.equals(keyId)
                || !validId(request,1,100) || !validId(processorTx,1,100)
                || !validId(customer,1,100)
                || contract==null || !contract.matches("ACQ-\\d{6}")
                || amount<=0 || amount>100000000
                || !"COMMITTED".equals(status)) {
            throw new SecurityException("invalid provider receipt claim");
        }
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(bytes);
            put(out,PURPOSE);
            put(out,keyId);
            put(out,request);
            put(out,processorTx);
            put(out,customer);
            put(out,contract);
            out.writeInt(amount);
            put(out,status);
            out.flush();
            return bytes.toByteArray();
        } catch(IOException impossible) {
            throw new IllegalStateException("receipt canonicalization unavailable",impossible);
        }
    }

    private static boolean validId(String value,int min,int max) {
        return value!=null && value.length()>=min && value.length()<=max
                && value.matches("[A-Za-z0-9_.-]+");
    }

    private static void put(DataOutputStream out,String text) throws IOException {
        byte[] value=Objects.requireNonNull(text).getBytes(StandardCharsets.UTF_8);
        out.writeInt(value.length);
        out.write(value);
    }

    private static String hmac(byte[] canonical) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CI_KEY,"HmacSHA256"));
            byte[] digest=mac.doFinal(canonical);
            char[] hex="0123456789abcdef".toCharArray();
            char[] out=new char[digest.length*2];
            for(int i=0;i<digest.length;i++){
                out[2*i]=hex[(digest[i]>>>4)&15];
                out[2*i+1]=hex[digest[i]&15];
            }
            return new String(out);
        } catch(GeneralSecurityException e) {
            throw new IllegalStateException("CI HMAC unavailable",e);
        }
    }

    private static byte[] fromHex(String text) {
        byte[] result=new byte[text.length()/2];
        for(int i=0;i<result.length;i++){
            int hi=Character.digit(text.charAt(i*2),16);
            int lo=Character.digit(text.charAt(i*2+1),16);
            result[i]=(byte)((hi<<4)|lo);
        }
        return result;
    }
}
