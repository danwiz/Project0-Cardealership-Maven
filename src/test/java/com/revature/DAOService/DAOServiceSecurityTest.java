package com.revature.DAOService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.revature.cardealer.ApplicationStateSnapshot;
import com.revature.cardealer.Data;

/** Defensive tests for legacy Java snapshot format. This is not a trust claim for arbitrary files. */
@Tag("SECURITY")
class DAOServiceSecurityTest {

    @TempDir Path directory;

    @Test
    void stillLoadsATrustedLegacyFormatSnapshot() throws Exception {
        Path path = directory.resolve("trusted.dat");
        Data source = new Data();
        source.setSnapshot(new ApplicationStateSnapshot(Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()));
        DAOService store = new DAOService();
        assertTrue(store.saveData(source, path.toString()).isSuccess());
        assertTrue(store.loadData(path.toString()).isSuccess());
    }

    @Test
    void rejectsUnapprovedSerializedTypeBeforeAcceptingASnapshot() throws Exception {
        Path path = directory.resolve("foreign.dat");
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(path))) {
            output.writeObject(new Date());
        }
        assertEquals(LoadResult.Status.INVALID_CONTENT,
                new DAOService().loadData(path.toString()).getStatus());
    }

    @Test
    void rejectsOversizedSnapshotWithoutDeserialization() throws Exception {
        Path path = directory.resolve("oversized.dat");
        try (java.io.OutputStream stream = Files.newOutputStream(path)) {
            byte[] block = new byte[8192];
            for (int i = 0; i < 1025; i++) stream.write(block);
        }
        assertEquals(LoadResult.Status.INVALID_CONTENT,
                new DAOService().loadData(path.toString()).getStatus());
    }

    @Test
    void rejectsSymbolicLinkSnapshotInputAndOutput() throws Exception {
        Path real = directory.resolve("real.dat");
        Data source = new Data();
        source.setSnapshot(new ApplicationStateSnapshot(Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()));
        DAOService store = new DAOService();
        assertTrue(store.saveData(source, real.toString()).isSuccess());
        Path link = directory.resolve("linked.dat");
        Files.createSymbolicLink(link, real.getFileName());
        assertEquals(LoadResult.Status.INVALID_CONTENT,
                store.loadData(link.toString()).getStatus());
        assertEquals(SaveResult.Status.INVALID_INPUT,
                store.saveData(source, link.toString()).getStatus());
        assertTrue(store.loadData(real.toString()).isSuccess());
    }
}
