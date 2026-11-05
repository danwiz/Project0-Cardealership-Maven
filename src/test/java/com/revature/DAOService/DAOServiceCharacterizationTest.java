package com.revature.DAOService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.ApplicationStateSnapshot;
import com.revature.cardealer.Data;

class DAOServiceCharacterizationTest {

    private File fileToDelete;

    @AfterEach
    void cleanUpCreatedFile() {
        if (fileToDelete != null && fileToDelete.exists()) fileToDelete.delete();
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void saveAndLoadRoundTripUsesExactSuppliedFilename() {
        DAOService service = new DAOService();
        String filename = "state-" + UUID.randomUUID() + ".snapshot";
        fileToDelete = new File(filename);

        SaveResult saved = service.saveData(dataWithEmptySnapshot(), filename);
        LoadResult loaded = service.loadData(filename);

        assertTrue(saved.isSuccess());
        assertTrue(fileToDelete.isFile());
        assertTrue(loaded.isSuccess());
        assertEquals(ApplicationStateSnapshot.CURRENT_VERSION,
                loaded.getSnapshot().get().getVersion());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void filenameAlreadyContainingDatIsNotGivenAnotherExtension() {
        DAOService service = new DAOService();
        String filename = "state-" + UUID.randomUUID() + ".dat";
        fileToDelete = new File(filename);

        SaveResult result = service.saveData(dataWithEmptySnapshot(), filename);

        assertTrue(result.isSuccess());
        assertTrue(fileToDelete.isFile());
        assertFalse(new File(filename + ".dat").exists());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void missingFileReturnsExplicitNotFoundResult() {
        DAOService service = new DAOService();

        LoadResult result = service.loadData("missing-" + UUID.randomUUID() + ".dat");

        assertEquals(LoadResult.Status.NOT_FOUND, result.getStatus());
        assertFalse(result.getSnapshot().isPresent());
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void invalidSaveInputReturnsExplicitFailure() {
        DAOService service = new DAOService();

        SaveResult result = service.saveData(null, "state.dat");

        assertEquals(SaveResult.Status.INVALID_INPUT, result.getStatus());
        assertFalse(result.isSuccess());
    }

    private static Data dataWithEmptySnapshot() {
        Data data = new Data();
        data.setSnapshot(new ApplicationStateSnapshot(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()));
        return data;
    }
}
