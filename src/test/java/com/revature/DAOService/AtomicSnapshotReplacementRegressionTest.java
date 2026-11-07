package com.revature.DAOService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.revature.cardealer.ApplicationStateSnapshot;
import com.revature.cardealer.Data;

/**
 * Wave 16: fail-closed save regression for an existing snapshot.
 * Synthetic temporary file only; requires no real customer data.
 * Expected to fail against Stage 28 direct FileOutputStream implementation.
 */
@Tag("SECURITY")
@Tag("TARGET-BEHAVIOR")
class AtomicSnapshotReplacementRegressionTest {
    @TempDir Path directory;

    @Test void failedSerializationCannotDestroyAnExistingSnapshot() throws Exception {
        Path destination = directory.resolve("CarDealer.dat");
        byte[] original = "SYNTHETIC_PREEXISTING_SNAPSHOT"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(destination, original);

        Data broken = new Data() {
            @Override public ApplicationStateSnapshot getSnapshot() {
                throw new IllegalStateException("synthetic serialization failure");
            }
        };

        new DAOService().saveData(broken, destination.toString());
        assertArrayEquals(original, Files.readAllBytes(destination),
                "a failed save must not truncate or overwrite existing bytes");
    }
}
