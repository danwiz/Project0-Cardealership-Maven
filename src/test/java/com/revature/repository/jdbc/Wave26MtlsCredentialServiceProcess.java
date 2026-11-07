package com.revature.repository.jdbc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Objects;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import com.revature.service.PrivateCustomerCredentialChangeService;

/**
 * Wave26 test-only, separate-JVM, mutual TLS credential broker.
 *
 * The child process alone receives verifier and writer database credentials.
 * The loopback client only owns a specifically pinned client certificate.
 * It is a disposable CI demonstration of authenticated transport and process
 * separation, NOT a deployable service, production RPC protocol, or secret
 * management solution.
 *
 * The public registrar must never instantiate this service or get these
 * environment variables. There is no production composition wiring.
 */
public final class Wave26MtlsCredentialServiceProcess {
    private Wave26MtlsCredentialServiceProcess() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || !"serve".equals(arguments[0])
                || !"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || !"true".equals(System.getenv("W26_DISPOSABLE_ONLY"))) {
            throw new SecurityException("Wave26 private TLS fixture is authorized only in disposable CI");
        }
        Path privateKeyStore = Path.of(required("W26_SERVER_KEYSTORE"));
        Path caTrustStore = Path.of(required("W26_SERVER_TRUSTSTORE"));
        String password = required("W26_TLS_STORE_PASSWORD");
        String expectedPin = required("W26_ALLOWED_CLIENT_CERT_SHA256").toLowerCase();
        if (expectedPin.length() != 64 || !expectedPin.matches("[0-9a-f]{64}")) {
            throw new SecurityException("explicit client certificate pin required");
        }

        String base = required("W26_PG_URL");
        if (!base.startsWith("jdbc:postgresql://localhost:5432/dealership_ci?currentSchema=w26_")) {
            throw new SecurityException("only ephemeral PostgreSQL CI schema allowed");
        }
        JdbcDatabase verifier = new JdbcDatabase(base,
                required("W26_VERIFIER_USER"), required("W26_VERIFIER_PASSWORD"));
        JdbcDatabase credential = new JdbcDatabase(base,
                required("W26_WRITER_USER"), required("W26_WRITER_PASSWORD"));
        PrivateCustomerCredentialChangeService broker = new PrivateCustomerCredentialChangeService(
                new JdbcPrivateAuthenticationReader(verifier),
                new JdbcTrustedCustomerCredentialWriter(credential));

        SSLContext ssl = context(privateKeyStore, caTrustStore, password);
        try (SSLServerSocket listener = (SSLServerSocket) ssl.getServerSocketFactory()
                .createServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))) {
            listener.setNeedClientAuth(true);
            listener.setSoTimeout(25000);
            System.out.println("W26_TLS_READY=" + listener.getLocalPort());
            System.out.flush();
            // Bounded fixture server: no public binding or persistent secrets.
            for (int attempt = 0; attempt < 8; attempt++) {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(6500);
                    socket.setUseClientMode(false);
                    try {
                        socket.startHandshake();
                        X509Certificate peer = (X509Certificate) socket.getSession()
                                .getPeerCertificates()[0];
                        String actualPin = hex(MessageDigest.getInstance("SHA-256")
                                .digest(peer.getEncoded()));
                        boolean authorized = MessageDigest.isEqual(
                                expectedPin.getBytes(StandardCharsets.US_ASCII),
                                actualPin.getBytes(StandardCharsets.US_ASCII));
                        BufferedReader input = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.UTF_8));
                        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                                socket.getOutputStream(), StandardCharsets.UTF_8));
                        String response = "DENIED";
                        if (authorized) {
                            String request = input.readLine();
                            if (request != null && request.length() <= 750) {
                                String[] parts = request.split("\\t", -1);
                                if (parts.length == 4 && "CHANGE".equals(parts[0])
                                        && printable(parts[1]) && printable(parts[2])
                                        && printable(parts[3])) {
                                    try {
                                        if (broker.changePassword(parts[1], parts[2], parts[3])) {
                                            response = "OK";
                                        }
                                    } catch (SecurityException rejected) {
                                        // Return a generic denial. Do not log password content.
                                    }
                                }
                            }
                        }
                        output.write(response);
                        output.newLine();
                        output.flush();
                    } catch (javax.net.ssl.SSLException rejectedClientCertificate) {
                        // TLS rejects clients without a trusted certificate;
                        // no broker/database change is attempted.
                    }
                } catch (java.net.SocketTimeoutException idle) {
                    break;
                }
            }
        }
    }

    static SSLContext context(Path keyStorePath, Path trustStorePath, String password)
            throws Exception {
        Objects.requireNonNull(password, "store password");
        KeyStore ownKey = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(keyStorePath)) {
            ownKey.load(in, password.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keys.init(ownKey, password.toCharArray());
        KeyStore ca = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(trustStorePath)) {
            ca.load(in, password.toCharArray());
        }
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(ca);
        SSLContext ssl = SSLContext.getInstance("TLSv1.2");
        ssl.init(keys.getKeyManagers(), trusts.getTrustManagers(), null);
        return ssl;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing isolated test configuration " + name);
        }
        return value;
    }

    private static boolean printable(String value) {
        return !value.isEmpty() && value.length() <= 256 &&
                value.indexOf('\r') < 0 && value.indexOf('\n') < 0 &&
                value.indexOf('\0') < 0;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(Character.forDigit((b >>> 4) & 0xf, 16));
            result.append(Character.forDigit(b & 0xf, 16));
        }
        return result.toString();
    }
}
