package com.revature.repository.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.TrustedCustomerCredentialWriter;

/**
 * Opt-in Wave29 signer for a versioned, short-lived customer credential intent.
 *
 * REVIEW ONLY: caller MUST be an authenticated, independent private service.
 * The SQL executor gets EXECUTE on owner-controlled function only. The signer
 * key must be delivered through a reviewed secret authority, not a public API.
 * A compromised signer + SQL executor is NOT protected by HMAC.
 *
 * Wire format: strict ASCII fields, positional separators, fixed domain tag,
 * and no delimiter permitted in username or PBKDF2 hashes. Not deployed.
 */
public final class JdbcVersionedSignedCustomerCredentialWriter implements TrustedCustomerCredentialWriter {
    private static final String ISSUER="credential-service";
    private static final String PURPOSE="customer-password-rotation";
    private static final long TTL_SECONDS=90L;

    private final JdbcDatabase database;
    private final int keyId;
    private final byte[] signingKey;
    private final Clock clock;
    private final Pbkdf2PasswordHasher hasher=new Pbkdf2PasswordHasher();

    public JdbcVersionedSignedCustomerCredentialWriter(
            JdbcDatabase database, int keyId, byte[] key, Clock clock) {
        this.database=Objects.requireNonNull(database,"database");
        if(database.isH2())throw new IllegalArgumentException("PostgreSQL required");
        database.requireCurrentSchema();
        if(keyId < 1 || key==null || key.length<32 || key.length>128) {
            throw new IllegalArgumentException("independent 256-bit signing key and key ID required");
        }
        this.keyId=keyId;
        this.signingKey=key.clone();
        this.clock=Objects.requireNonNull(clock,"clock");
    }

    @Override
    public boolean rotateCustomerPassword(User snapshot,
                                          String currentPlaintext,
                                          String replacementEncoded) {
        if(snapshot==null || snapshot.getRole()!=AccountRole.CUSTOMER
                || !validUsername(snapshot.getUsername())
                || !validHash(snapshot.getPassword())
                || !validHash(replacementEncoded)
                || snapshot.getPassword().equals(replacementEncoded)
                || currentPlaintext==null || currentPlaintext.isEmpty()
                || !hasher.matches(currentPlaintext,snapshot.getPassword())
                || hasher.matches(currentPlaintext,replacementEncoded)) {
            throw new SecurityException("invalid customer credential intent");
        }
        UUID revision;
        try {revision=UUID.fromString(snapshot.getSessionRevision());}
        catch(RuntimeException bad) {throw new SecurityException("invalid session generation",bad);}
        UUID nonce=UUID.randomUUID();
        long issued=clock.instant().getEpochSecond();
        long expires=Math.addExact(issued,TTL_SECONDS);
        String signature=sign(canonical(keyId,snapshot.getUsername(),revision,
                snapshot.getPassword(),replacementEncoded,nonce,issued,expires));

        try(Connection c=database.openConnection();
                PreparedStatement sql=c.prepareStatement(
                    "SELECT rotate_customer_credential_v29(?,?,?,?,?,?,?,?,?,?,?)")) {
            sql.setString(1,ISSUER);
            sql.setString(2,PURPOSE);
            sql.setInt(3,keyId);
            sql.setString(4,snapshot.getUsername());
            sql.setObject(5,revision);
            sql.setString(6,snapshot.getPassword());
            sql.setString(7,replacementEncoded);
            sql.setObject(8,nonce);
            sql.setLong(9,issued);
            sql.setLong(10,expires);
            sql.setString(11,signature);
            try(ResultSet results=sql.executeQuery()) {
                if(!results.next() || !results.getBoolean(1) || results.next()) {
                    throw new SecurityException("signed credential change denied");
                }
                return true;
            }
        } catch(SQLException sqlFailure) {
            throw new IllegalStateException("versioned credential mutation unavailable",sqlFailure);
        }
    }

    public static String canonical(int keyId,String username,UUID revision,
            String priorHash,String nextHash,UUID nonce,long issued,long expires) {
        if(keyId<1 || !validUsername(username) || !validHash(priorHash)
                || !validHash(nextHash) || revision==null || nonce==null
                || expires<=issued || expires-issued>120) {
            throw new SecurityException("invalid canonical signed intent");
        }
        return "W29v1|"+ISSUER+"|"+PURPOSE+"|"+keyId+"|"+username+"|"
                +revision+"|"+priorHash+"|"+nextHash+"|"+nonce+"|"
                +issued+"|"+expires;
    }

    private String sign(String canonical) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.clone(),"HmacSHA256"));
            byte[] out=mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            char[] alphabet="0123456789abcdef".toCharArray();
            char[] hex=new char[out.length*2];
            for(int i=0;i<out.length;i++) {
                hex[i*2]=alphabet[(out[i]>>>4)&15];
                hex[i*2+1]=alphabet[out[i]&15];
            }
            return new String(hex);
        } catch(GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable",e);
        }
    }

    private static boolean validUsername(String value) {
        return value!=null && value.matches("[A-Za-z0-9_.-]{1,100}");
    }

    private static boolean validHash(String value) {
        return value!=null && value.length()<=500
                && value.startsWith("pbkdf2$")
                && value.indexOf('|')<0 && value.indexOf('\r')<0
                && value.indexOf('\n')<0;
    }
}
