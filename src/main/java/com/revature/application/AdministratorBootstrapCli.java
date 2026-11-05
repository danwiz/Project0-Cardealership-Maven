package com.revature.application;

import java.io.Console;
import java.util.Arrays;

import com.revature.repository.jdbc.AdministratorBootstrapService;
import com.revature.repository.jdbc.JdbcDatabase;

/**
 * Explicit offline setup command. This entry point is not wired into the public
 * dealership console, API, service registry or account registration use case.
 */
public final class AdministratorBootstrapCli {
    private AdministratorBootstrapCli() { }

    public static void main(String[] args) {
        if (args == null || args.length != 1 || !"--bootstrap-admin".equals(args[0])) {
            throw new SecurityException("explicit --bootstrap-admin operator command required");
        }

        InfrastructureConfiguration configuration = InfrastructureConfiguration.fromSystemProperties();
        if (configuration.getMode() != InfrastructureConfiguration.Mode.JDBC) {
            throw new SecurityException("administrator bootstrap requires persistent JDBC mode");
        }
        if (configuration.getJdbcUrl().startsWith("jdbc:h2:mem:")) {
            throw new SecurityException("administrator bootstrap cannot use an ephemeral in-memory JDBC database");
        }

        Console console = System.console();
        if (console == null) {
            throw new SecurityException("administrator bootstrap requires a local interactive terminal");
        }

        String username = console.readLine("Initial administrator username: ");
        char[] password = console.readPassword("Password (12+ characters): ");
        char[] confirmation = console.readPassword("Confirm password: ");

        try {
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                throw new SecurityException("administrator password confirmation failed");
            }

            JdbcDatabase database = new JdbcDatabase(
                    configuration.getJdbcUrl(),
                    configuration.getJdbcUser(),
                    configuration.getJdbcPassword());
            new AdministratorBootstrapService(database).bootstrap(username, password);
            console.printf("Initial administrator provisioned successfully. Bootstrap cannot be repeated.%n");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
