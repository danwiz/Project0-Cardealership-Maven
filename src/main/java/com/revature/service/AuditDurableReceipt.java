package com.revature.service;

/** Claim of durable persistence for exactly one immutable record and digest. */
public final class AuditDurableReceipt {
    private final String recordKey;
    private final String contentSha256;

    public AuditDurableReceipt(String recordKey, String contentSha256) {
        if (recordKey == null || recordKey.isEmpty() || !recordKey.equals(recordKey.trim())
                || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid audit durable receipt");
        }
        this.recordKey = recordKey;
        this.contentSha256 = contentSha256;
    }

    public String recordKey() { return recordKey; }
    public String contentSha256() { return contentSha256; }
}
