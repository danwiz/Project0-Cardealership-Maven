package com.revature.repository.jdbc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * W34.21-B: explicitly selected stress harness, NOT production functionality.
 * Runs the existing W34.16 / W34.15 four-worker test methods unchanged.
 *
 * Invoke ONLY with -Dtest=Wave3421PostgresqlReplayStressHarness.
 * The class name intentionally does not match Maven Surefire's normal *Test
 * patterns, preserving the 442/125 full-suite acceptance roster.
 *
 * Seeded outer pacing is reproducible; PostgreSQL internal thread interleavings
 * and fixture-generated UUIDs are NOT deterministically replayable by seed.
 */
public class Wave3421PostgresqlReplayStressHarness {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    @Test
    void repeatActualFourWorkerConcurrencyTest() throws Exception {
        String expectedHead = required("EXPECTED_HEAD");
        if (!SHA.matcher(expectedHead).matches())
            throw new IllegalStateException("Exact source SHA required");
        int issue = integer("W3421_ISSUE");
        int shard = integer("W3421_SHARD");
        int count = integer("W3421_COUNT");
        int start = integer("W3421_START");
        String jdk = System.getProperty("java.specification.version");
        if ((issue != 51 && issue != 53) || shard < 0 || shard > 3
                || count != 250 || start != 250 * shard
                || (!"11".equals(jdk) && !"17".equals(jdk)))
            throw new IllegalStateException("Only approved 2x2x4 250-run matrix");
        int pgVersion = verifyDisposablePg16();
        Path evidence = Paths.get("wave34-21-evidence", "issue-" + issue
                + "-jdk-" + jdk + "-shard-" + shard + ".jsonl");
        Files.createDirectories(evidence.getParent());
        Files.deleteIfExists(evidence);

        for (int offset = 0; offset < count; offset++) {
            int index = start + offset;
            long seed = 342100000L + issue * 100000L + index;
            Random scheduler = new Random(seed);
            // Seed affects only the outer launch time, not private inner latches.
            Thread.sleep(0, scheduler.nextInt(900000));
            if (scheduler.nextBoolean()) Thread.yield();
            long begun = System.nanoTime();
            try {
                if (issue == 51) {
                    new Wave34PostgresqlSettlementBatchApprovalTest()
                        .concurrentReplayIsIdempotentAndBankReferenceCannotBeReused();
                } else {
                    new Wave34PostgresqlBalancedGlReconciliationTest()
                        .concurrentReplayCommitsOnlyOneBalancedEventAndApprovalCannotBeReused();
                }
                append(evidence, issue, jdk, shard, index, seed, pgVersion,
                        expectedHead, begun, "PASS", "none", "none");
            } catch (Throwable failure) {
                String state = "none";
                String constraint = "none";
                for (Throwable part = failure; part != null; part = part.getCause()) {
                    if (part instanceof SQLException) {
                        String raw = ((SQLException) part).getSQLState();
                        if (raw != null) state = raw;
                    }
                    if (part instanceof org.postgresql.util.PSQLException) {
                        org.postgresql.util.ServerErrorMessage detail =
                            ((org.postgresql.util.PSQLException) part).getServerErrorMessage();
                        if (detail != null && detail.getConstraint() != null)
                            constraint = detail.getConstraint();
                    }
                }
                append(evidence, issue, jdk, shard, index, seed, pgVersion,
                        expectedHead, begun, "FAIL", state, constraint);
                throw new AssertionError("W34.21-B issue=" + issue
                        + " schedule_index=" + index + " outer_seed=" + seed
                        + " SQLSTATE=" + state + " constraint=" + constraint, failure);
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty())
            throw new IllegalStateException("Missing test-only environment variable " + name);
        return value;
    }

    private static int integer(String name) {
        return Integer.parseInt(required(name));
    }

    private static int verifyDisposablePg16() throws SQLException {
        if (!"true".equals(System.getenv("W34_DISPOSABLE_ONLY")))
            throw new IllegalStateException("Disposable-only gate required");
        String url = required("DEALERSHIP_PG_TEST_URL");
        if (!url.startsWith("jdbc:postgresql://localhost:5432/dealership_ci"))
            throw new IllegalStateException("Unexpected PostgreSQL target");
        try (Connection connection = DriverManager.getConnection(url,
                required("DEALERSHIP_PG_TEST_USER"),
                required("DEALERSHIP_PG_TEST_PASSWORD"));
             Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SHOW server_version_num")) {
            if (!result.next()) throw new IllegalStateException("Missing PostgreSQL version");
            int version = Integer.parseInt(result.getString(1));
            if (version < 160000 || version >= 170000)
                throw new IllegalStateException("PostgreSQL16 required");
            return version;
        }
    }

    private static void append(Path file, int issue, String jdk, int shard,
            int index, long seed, int pg, String head, long begun,
            String status, String sqlState, String constraint) throws Exception {
        String safeConstraint = constraint.replaceAll("[^a-zA-Z0-9_]", "_");
        long milliseconds = (System.nanoTime() - begun) / 1000000L;
        String record = "{\"issue\":" + issue + ",\"jdk\":\"" + jdk
            + "\",\"shard\":" + shard + ",\"schedule_index\":" + index
            + ",\"outer_seed\":" + seed + ",\"pg_server_version_num\":" + pg
            + ",\"source_head\":\"" + head + "\",\"elapsed_ms\":" + milliseconds
            + ",\"status\":\"" + status + "\",\"sqlstate\":\"" + sqlState
            + "\",\"constraint\":\"" + safeConstraint + "\",\"recorded_at\":\""
            + Instant.now().toString() + "\"}\n";
        Files.write(file, record.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
