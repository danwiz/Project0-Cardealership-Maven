package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.UserLoginService;
import com.revature.service.UserAccountRepository;
import com.revature.support.TestAccounts;

/**
 * Wave26: real disposable PG16 + independent child JVM private credential
 * writer over mutually authenticated TLS, with exact client-cert pinning.
 *
 * Two client certs are signed by the same disposable test CA. A rogue cert is
 * TLS-valid but application-denied by fingerprint. No-cert handshakes fail at
 * TLS; valid client requires current plaintext. No DB credentials are handed
 * to any client transport. This does not certify production RPC or secrets.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlMutualTlsCredentialBrokerTest {
    private static final String STORE_PASSWORD="ci-ephemeral-p12-password";

    @Test
    @Timeout(220)
    void isolatedPrivateBrokerDeniesUntrustedClientCertificatesAndRotatesCustomer()
            throws Exception {
        String base=System.getenv("DEALERSHIP_PG_TEST_URL");
        String dbUser=System.getenv("DEALERSHIP_PG_TEST_USER");
        String dbPass=System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(dbUser)
                        && "ci-only-postgres-fixture".equals(dbPass),
                "actual disposable GitHub PostgreSQL16 required");

        String token=UUID.randomUUID().toString().replace("-","");
        String schema="w26_"+token, verifier="w26auth_"+token, writer="w26write_"+token;
        String verifierPass="ci-private-verifier-synthetic";
        String writerPass="ci-credential-writer-synthetic";
        JdbcDatabase operator=new JdbcDatabase(base,dbUser,dbPass);
        Process server=null;
        Path certDir=Files.createTempDirectory("w26-mtls-");
        boolean rolesCreated=false;
        try {
            try(Connection connection=operator.openConnection();
                    Statement s=connection.createStatement()) {
                try(ResultSet v=s.executeQuery("SHOW server_version_num")) {
                    assertTrue(v.next());
                    int num=Integer.parseInt(v.getString(1));
                    assertTrue(num>=160000 && num<170000);
                }
                s.execute("CREATE SCHEMA "+schema);
                s.execute("CREATE ROLE "+verifier+" LOGIN PASSWORD '"+verifierPass+"'");
                s.execute("CREATE ROLE "+writer+" LOGIN PASSWORD '"+writerPass+"'");
                rolesCreated=true;
            }
            String scoped=base+"?currentSchema="+schema;
            JdbcDatabase owner=new JdbcDatabase(scoped,dbUser,dbPass);
            owner.migrate();
            JdbcUserAccountRepository ownerAccounts=new JdbcUserAccountRepository(owner);
            TestAccounts.seed(ownerAccounts,AccountRole.CUSTOMER,
                    "mtls-buyer","synthetic-old-password");

            try(Connection c=operator.openConnection();Statement s=c.createStatement()) {
                c.setAutoCommit(false);
                try {
                    s.execute("REVOKE ALL ON SCHEMA "+schema+" FROM PUBLIC");
                    for(String role:new String[]{verifier,writer}) {
                        s.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
                        s.execute("GRANT SELECT ON "+schema+".schema_history TO "+role);
                        s.execute("GRANT SELECT(username,encoded_credential,role,session_revision) "
                                +"ON "+schema+".accounts TO "+role);
                    }
                    s.execute("GRANT UPDATE(encoded_credential) ON "+schema+
                            ".accounts TO "+writer);
                    c.commit();
                } catch(SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
            // The private SQL verifier identity has no UPDATE, even by SQL.
            JdbcDatabase authDb=new JdbcDatabase(scoped,verifier,verifierPass);
            assertDenied42501(authDb,
                    "UPDATE accounts SET encoded_credential='stolen' WHERE username='mtls-buyer'");

            createCertificates(certDir);
            String pinned=sha256(certDir.resolve("good-client.pem"));
            Path serverStore=certDir.resolve("server.p12");
            Path goodStore=certDir.resolve("good-client.p12");
            Path rogueStore=certDir.resolve("rogue-client.p12");
            Path caStore=certDir.resolve("ca-trust.p12");

            String javaExe=Path.of(System.getProperty("java.home"),"bin","java").toString();
            String classpath=System.getProperty("java.class.path")
                    + java.io.File.pathSeparator + "target/test-classes"
                    + java.io.File.pathSeparator + "target/classes";
            ProcessBuilder launch=new ProcessBuilder(
                    javaExe,"-cp",classpath,
                    Wave26MtlsCredentialServiceProcess.class.getName(),"serve");
            launch.redirectErrorStream(true);
            java.util.Map<String,String> env=launch.environment();
            // No shell expansion; all environment values synthetic fixture data.
            env.put("CI","true");
            env.put("GITHUB_ACTIONS","true");
            env.put("W26_DISPOSABLE_ONLY","true");
            env.put("W26_SERVER_KEYSTORE",serverStore.toAbsolutePath().toString());
            env.put("W26_SERVER_TRUSTSTORE",caStore.toAbsolutePath().toString());
            env.put("W26_TLS_STORE_PASSWORD",STORE_PASSWORD);
            env.put("W26_ALLOWED_CLIENT_CERT_SHA256",pinned);
            env.put("W26_PG_URL",scoped);
            env.put("W26_VERIFIER_USER",verifier);
            env.put("W26_VERIFIER_PASSWORD",verifierPass);
            env.put("W26_WRITER_USER",writer);
            env.put("W26_WRITER_PASSWORD",writerPass);
            server=launch.start();
            BufferedReader processLog=new BufferedReader(new InputStreamReader(
                    server.getInputStream(),StandardCharsets.UTF_8));
            int port=-1;
            for(int i=0;i<8;i++) {
                String line=processLog.readLine();
                if(line==null)throw new AssertionError("private service exited before ready");
                if(line.startsWith("W26_TLS_READY=")) {
                    port=Integer.parseInt(line.substring("W26_TLS_READY=".length()));
                    break;
                }
            }
            assertTrue(port>0 && port<65536,"private service loopback port unavailable");

            User before=ownerAccounts.findByUsername("mtls-buyer")
                    .orElseThrow(AssertionError::new);
            SSLContext good=clientContext(goodStore,caStore);
            SSLContext rogue=clientContext(rogueStore,caStore);
            SSLContext noCert=clientContext(null,caStore);

            // Rogue cert is signed by SAME trusted CA; fingerprint pin blocks it.
            String rogueResult=request(rogue,port,"CHANGE\tmtls-buyer\tsynthetic-old-password\trogue-password");
            assertEquals("DENIED",rogueResult);
            // No client certificate is rejected by TLS handshake (or read).
            try {
                String untrustedResponse=request(
                        noCert,port,"CHANGE\tmtls-buyer\tsynthetic-old-password\tno-cert-password");
                assertFalse("OK".equals(untrustedResponse),
                        "unauthenticated client must never change credential");
            } catch (java.io.IOException deniedAtHandshakeOrSocket) {
                // Java can report a rejected no-cert peer as SSLException,
                // connection reset, or broken pipe; each is a transport denial.
                // The unchanged persisted hash assertion below is mandatory.
            }
            assertEquals(before.getPassword(),
                    ownerAccounts.findByUsername("mtls-buyer")
                            .orElseThrow(AssertionError::new).getPassword());
            assertEquals("DENIED",request(good,port,
                    "CHANGE\tmtls-buyer\twrong-old-password\tbad-password"));
            assertEquals("OK",request(good,port,
                    "CHANGE\tmtls-buyer\tsynthetic-old-password\tsynthetic-new-password"));

            User after=ownerAccounts.findByUsername("mtls-buyer")
                    .orElseThrow(AssertionError::new);
            assertNotEquals(before.getSessionRevision(),after.getSessionRevision());
            assertNotEquals(before.getPassword(),after.getPassword());
            assertFalse(new Pbkdf2PasswordHasher().matches(
                    "synthetic-old-password",after.getPassword()));
            assertTrue(new Pbkdf2PasswordHasher().matches(
                    "synthetic-new-password",after.getPassword()));

            assertEquals("DENIED",request(good,port,
                    "CHANGE\tmtls-buyer\tsynthetic-old-password\treplayed-password"));
            assertEquals(1,ownerAccounts.findAll().size());
            assertEquals(AccountRole.CUSTOMER,
                    ownerAccounts.findByUsername("mtls-buyer")
                            .orElseThrow(AssertionError::new).getRole());
        } finally {
            if(server!=null) {
                server.destroy();
                if(!server.waitFor(4,TimeUnit.SECONDS)) {
                    server.destroyForcibly();
                    server.waitFor(4,TimeUnit.SECONDS);
                }
            }
            try(Connection connection=operator.openConnection();
                    Statement sql=connection.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
                if(rolesCreated) {
                    sql.execute("DROP ROLE IF EXISTS "+verifier);
                    sql.execute("DROP ROLE IF EXISTS "+writer);
                }
            }
            try(java.util.stream.Stream<Path> paths=Files.walk(certDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{
                    try {Files.deleteIfExists(p);}catch(java.io.IOException ignored){}
                });
            }
        }
    }

    private static String request(SSLContext tls,int port,String command) throws Exception {
        SSLSocketFactory factory=tls.getSocketFactory();
        try(SSLSocket ssl=(SSLSocket)factory.createSocket(
                InetAddress.getByName("127.0.0.1"),port)) {
            ssl.setSoTimeout(6000);
            ssl.startHandshake();
            java.io.BufferedWriter output=new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(ssl.getOutputStream(),StandardCharsets.UTF_8));
            output.write(command);
            output.newLine();
            output.flush();
            return new BufferedReader(new InputStreamReader(
                    ssl.getInputStream(),StandardCharsets.UTF_8)).readLine();
        }
    }

    private static SSLContext clientContext(Path key,Path ca) throws Exception {
        KeyStore trust=KeyStore.getInstance("PKCS12");
        try(java.io.InputStream in=Files.newInputStream(ca)) {
            trust.load(in,STORE_PASSWORD.toCharArray());
        }
        javax.net.ssl.TrustManagerFactory tm=javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        javax.net.ssl.KeyManager[] keys=null;
        if(key!=null) {
            KeyStore own=KeyStore.getInstance("PKCS12");
            try(java.io.InputStream in=Files.newInputStream(key)) {
                own.load(in,STORE_PASSWORD.toCharArray());
            }
            javax.net.ssl.KeyManagerFactory km=javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            km.init(own,STORE_PASSWORD.toCharArray());
            keys=km.getKeyManagers();
        }
        SSLContext tls=SSLContext.getInstance("TLSv1.2");
        tls.init(keys,tm.getTrustManagers(),null);
        return tls;
    }

    private static String sha256(Path cert) throws Exception {
        X509Certificate x509;
        try(java.io.InputStream in=Files.newInputStream(cert)) {
            x509=(X509Certificate)CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
        }
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(x509.getEncoded());
        StringBuilder hex=new StringBuilder(64);
        for(byte b:bytes) {
            hex.append(Character.forDigit((b>>>4)&15,16));
            hex.append(Character.forDigit(b&15,16));
        }
        return hex.toString();
    }

    private static void createCertificates(Path dir) throws Exception {
        run(dir,"openssl","req","-x509","-newkey","rsa:2048","-nodes",
                "-keyout","ca.key","-out","ca.pem","-days","1",
                "-subj","/CN=W26FixtureCA");
        Files.writeString(dir.resolve("server.ext"),
                "subjectAltName=DNS:localhost,IP:127.0.0.1\n"
                +"extendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n");
        Files.writeString(dir.resolve("client.ext"),
                "extendedKeyUsage=clientAuth\nbasicConstraints=CA:FALSE\n");
        makeIdentity(dir,"server","/CN=localhost","server.ext");
        makeIdentity(dir,"good-client","/CN=W26AuthorizedVerifier","client.ext");
        makeIdentity(dir,"rogue-client","/CN=W26UnapprovedClient","client.ext");
        run(dir,"keytool","-importcert","-noprompt","-alias","wave26ca",
                "-file","ca.pem","-keystore","ca-trust.p12","-storetype","PKCS12",
                "-storepass",STORE_PASSWORD);
    }

    private static void makeIdentity(Path dir,String stem,String subject,String extension)
            throws Exception {
        run(dir,"openssl","req","-newkey","rsa:2048","-nodes",
                "-keyout",stem+".key","-out",stem+".csr","-subj",subject);
        run(dir,"openssl","x509","-req","-in",stem+".csr",
                "-CA","ca.pem","-CAkey","ca.key","-CAcreateserial",
                "-out",stem+".pem","-days","1","-sha256",
                "-extfile",extension);
        run(dir,"openssl","pkcs12","-export","-inkey",stem+".key",
                "-in",stem+".pem","-certfile","ca.pem",
                "-out",stem+".p12","-passout","pass:"+STORE_PASSWORD);
    }

    private static void run(Path dir,String...args) throws Exception {
        ProcessBuilder pb=new ProcessBuilder(Arrays.asList(args));
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process process=pb.start();
        byte[] log=process.getInputStream().readAllBytes();
        if(!process.waitFor(20,TimeUnit.SECONDS)||process.exitValue()!=0) {
            process.destroyForcibly();
            throw new AssertionError("disposable TLS fixture command failed: "
                    +args[0]+" "+new String(log,StandardCharsets.UTF_8).substring(
                            0,Math.min(log.length,500)));
        }
    }

    private static void assertDenied42501(JdbcDatabase db,String statement) {
        SQLException exception=assertThrows(SQLException.class,()->{
            try(Connection c=db.openConnection();
                    PreparedStatement s=c.prepareStatement(statement)) {
                s.execute();
            }
        });
        assertEquals("42501",exception.getSQLState());
    }
}
