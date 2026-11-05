package com.revature.application;

import java.util.Locale;
import java.util.Objects;

/** Immutable infrastructure selection for application composition. */
public final class InfrastructureConfiguration {
    public enum Mode { IN_MEMORY, JDBC }

    public static final String MODE_PROPERTY = "dealership.persistence";
    public static final String JDBC_URL_PROPERTY = "dealership.jdbc.url";
    public static final String JDBC_USER_PROPERTY = "dealership.jdbc.user";
    public static final String JDBC_PASSWORD_PROPERTY = "dealership.jdbc.password";

    private final Mode mode;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;

    private InfrastructureConfiguration(Mode mode, String jdbcUrl, String jdbcUser, String jdbcPassword) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
    }

    public static InfrastructureConfiguration inMemory() {
        return new InfrastructureConfiguration(Mode.IN_MEMORY, null, null, null);
    }

    /**
     * Development convenience only: passwordless H2 is allowed for a transient
     * in-memory database. Persistent JDBC storage requires explicit credentials.
     */
    public static InfrastructureConfiguration jdbc(String url) {
        String normalizedUrl = requireText(url, "url");
        if (!isInMemoryH2(normalizedUrl)) {
            throw new IllegalArgumentException(
                    "persistent JDBC storage requires explicit username and password");
        }
        return jdbc(normalizedUrl, "sa", "");
    }

    public static InfrastructureConfiguration jdbc(String url, String username, String password) {
        String normalizedUrl = requireText(url, "url");
        String configuredUser = Objects.requireNonNull(username, "username");
        String configuredPassword = Objects.requireNonNull(password, "password");
        if (!isInMemoryH2(normalizedUrl)
                && (configuredUser.trim().isEmpty() || configuredPassword.isEmpty())) {
            throw new IllegalArgumentException(
                    "persistent JDBC storage requires a nonblank username and nonempty password");
        }
        return new InfrastructureConfiguration(Mode.JDBC, normalizedUrl,
                configuredUser, configuredPassword);
    }

    public static InfrastructureConfiguration fromSystemProperties() {
        String configuredMode = System.getProperty(MODE_PROPERTY, "in-memory").trim().toLowerCase(Locale.ROOT);
        if ("in-memory".equals(configuredMode) || "memory".equals(configuredMode)) return inMemory();
        if (!"jdbc".equals(configuredMode)) {
            throw new IllegalArgumentException("unsupported persistence mode: " + configuredMode);
        }
        String url = System.getProperty(JDBC_URL_PROPERTY);
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException(JDBC_URL_PROPERTY + " is required for JDBC mode");
        }
        String configuredUser = System.getProperty(JDBC_USER_PROPERTY);
        String configuredPassword = System.getProperty(JDBC_PASSWORD_PROPERTY);
        if (isInMemoryH2(url)) {
            return jdbc(url, configuredUser == null ? "sa" : configuredUser,
                    configuredPassword == null ? "" : configuredPassword);
        }
        // Never silently substitute an H2 test identity for persistent storage.
        return jdbc(url, configuredUser == null ? "" : configuredUser,
                configuredPassword == null ? "" : configuredPassword);
    }

    public Mode getMode() { return mode; }
    public String getJdbcUrl() { return jdbcUrl; }
    public String getJdbcUser() { return jdbcUser; }
    public String getJdbcPassword() { return jdbcPassword; }

    private static boolean isInMemoryH2(String url) {
        return url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:mem:");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}