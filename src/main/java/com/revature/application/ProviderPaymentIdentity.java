package com.revature.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Wave34.1 STRICT IDENTITY ONLY: this is NOT provider authentication, a
 * payment API, or permission to post financial records.
 *
 * An independent provider adapter must authenticate a receipt first.
 * An immutable request key identifies a provider operation; identical amounts
 * under different request IDs are legitimate independent operations.
 */
public final class ProviderPaymentIdentity {
    public enum DuplicateDisposition { EXACT_REPLAY, CONFLICT }

    private final String provider;
    private final String requestId;
    private final String transactionId;
    private final String customer;
    private final long ownershipId;
    private final String contractId;
    private final int amount;
    private final String currency;
    private final String status;
    private final String keyId;
    private final String signatureHex;
    private final String digest;

    public ProviderPaymentIdentity(String provider, String requestId, String transactionId,
            String customer, long ownershipId, String contractId, int amount,
            String currency, String status, String keyId, String signatureHex) {
        this.provider = identifier(provider, 40);
        this.requestId = identifier(requestId, 100);
        this.transactionId = identifier(transactionId, 100);
        this.customer = identifier(customer, 100);
        if (ownershipId <= 0) throw new IllegalArgumentException("ownershipId");
        this.ownershipId = ownershipId;
        if (contractId == null || !contractId.matches("ACQ-\\d{6}"))
            throw new IllegalArgumentException("contractId");
        this.contractId = contractId;
        if (amount <= 0) throw new IllegalArgumentException("amount");
        this.amount = amount;
        if (currency == null || !currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("currency");
        this.currency = currency;
        if (!"COMMITTED".equals(status))
            throw new SecurityException("receipt is not a committed provider charge");
        this.status = status;
        this.keyId = identifier(keyId, 100);
        if (signatureHex == null || !signatureHex.matches("[0-9a-f]{64}"))
            throw new SecurityException("invalid signed receipt representation");
        this.signatureHex = signatureHex;
        this.digest = computeDigest();
    }

    /**
     * Only compares immutable identity; cannot authenticate a receipt.
     * Same request with different claims is a conflict, never a second payment.
     */
    public DuplicateDisposition compareSameRequest(ProviderPaymentIdentity other) {
        Objects.requireNonNull(other, "other");
        if (!provider.equals(other.provider) || !requestId.equals(other.requestId))
            throw new IllegalArgumentException("different provider operation, not a replay");
        return digest.equals(other.digest)
                ? DuplicateDisposition.EXACT_REPLAY : DuplicateDisposition.CONFLICT;
    }

    public String getProvider() { return provider; }
    public String getRequestId() { return requestId; }
    public String getTransactionId() { return transactionId; }
    public String getCustomer() { return customer; }
    public long getOwnershipId() { return ownershipId; }
    public String getContractId() { return contractId; }
    public int getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getKeyId() { return keyId; }
    public String getSignatureHex() { return signatureHex; }
    public String getDigest() { return digest; }

    private static String identifier(String value, int maximum) {
        if (value == null || value.length() < 1 || value.length() > maximum
                || !value.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("invalid provider receipt identity");
        return value;
    }

    private String computeDigest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String[] fields = { "dealership.provider-receipt.identity.v1",
                    provider, requestId, transactionId, customer,
                    Long.toString(ownershipId), contractId, Integer.toString(amount),
                    currency, status, keyId, signatureHex };
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                sha.update(bytes);
            }
            byte[] hashed = sha.digest();
            char[] symbols = "0123456789abcdef".toCharArray();
            char[] hex = new char[hashed.length * 2];
            for (int i = 0; i < hashed.length; i++) {
                hex[i * 2] = symbols[(hashed[i] >>> 4) & 15];
                hex[i * 2 + 1] = symbols[hashed[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
