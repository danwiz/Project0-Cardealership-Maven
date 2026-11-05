package com.revature.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Versioned, length-delimited SHA-256 for receipt matching, not authentication. */
final class AuditContentDigest {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AuditContentDigest() { }

    static String archive(CredentialAuditEvent event) {
        MessageDigest hash = sha256();
        field(hash, "credential-audit-archive/v1");
        field(hash, event.streamId());
        number(hash, event.sourceCursor().commitPosition());
        integer(hash, event.sourceCursor().eventOrdinal());
        number(hash, event.auditId());
        field(hash, event.accountUsername());
        field(hash, event.databaseActor());
        number(hash, event.changedAt().getEpochSecond());
        integer(hash, event.changedAt().getNano());
        return hex(hash.digest());
    }

    static String alert(AuditFailureNotice notice) {
        MessageDigest hash = sha256();
        field(hash, "credential-audit-alert/v1");
        field(hash, notice.noticeId());
        field(hash, notice.phase());
        field(hash, notice.streamId());
        number(hash, notice.sourceCursor().commitPosition());
        integer(hash, notice.sourceCursor().eventOrdinal());
        number(hash, notice.auditId());
        return hex(hash.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void field(MessageDigest hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        integer(hash, bytes.length);
        hash.update(bytes);
    }

    private static void number(MessageDigest hash, long value) {
        hash.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void integer(MessageDigest hash, int value) {
        hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = HEX[(bytes[i] >>> 4) & 0xf];
            chars[i * 2 + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(chars);
    }
}
