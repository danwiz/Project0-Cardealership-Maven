package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves persistent runtime wiring does not require migration/DDL privileges. */
@Tag("SECURITY")
class JdbcRuntimeSchemaPrivilegeTest {

    @Test
    void persistentRuntimeFailsClosedUntilOperatorMigrates(@TempDir Path directory) {
        JdbcDatabase database = new JdbcDatabase(fileUrl(directory, "uninitialized"), "sa", "");

        // Even a database owner must explicitly execute migrations for persistence.
        assertThrows(IllegalStateException.class, () -> new JdbcUserAccountRepository(database));

        database.migrate();
        assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, database.schemaVersion());
        assertDoesNotThrow(() -> new JdbcUserAccountRepository(database));
    }

    @Test
    void restrictedRuntimePrincipalValidatesSchemaWithoutAnyDdl(@TempDir Path directory)
            throws SQLException {
        String url = fileUrl(directory, "restricted");
        JdbcDatabase migrator = new JdbcDatabase(url, "sa", "");
        migrator.migrate();

        try (Connection connection = migrator.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER runtime_reader PASSWORD 'reader-test-secret'");
            statement.execute("GRANT SELECT ON schema_history TO runtime_reader");
            statement.execute("GRANT SELECT ON accounts TO runtime_reader");
        }

        JdbcDatabase runtime = new JdbcDatabase(url, "runtime_reader", "reader-test-secret");

        assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, runtime.schemaVersion());
        assertDoesNotThrow(runtime::requireCurrentSchema);
        assertDoesNotThrow(() -> new JdbcUserAccountRepository(runtime));
        assertFalse(new JdbcUserAccountRepository(runtime).findByUsername("missing").isPresent());

        // The same principal is unable to mutate the schema even if it tries.
        assertThrows(SQLException.class, () -> {
            try (Connection connection = runtime.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE runtime_forbidden_ddl (id INTEGER)");
            }
        });
    }

    private static String fileUrl(Path directory, String name) {
        return "jdbc:h2:file:" + directory.resolve(name).toAbsolutePath()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    }
}
