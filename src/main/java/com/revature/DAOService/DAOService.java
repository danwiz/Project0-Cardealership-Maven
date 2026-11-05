package com.revature.DAOService;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.LinkOption;

import com.revature.cardealer.ApplicationStateSnapshot;
import com.revature.cardealer.Data;

public class DAOService implements DataDAO {
    // Legacy Java serialization is only supported for trusted local snapshots.
    private static final long MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024;
    private static final ObjectInputFilter SNAPSHOT_FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=48;maxrefs=20000;maxarray=100000;maxbytes=8388608;"
            + "com.revature.cardealer.ApplicationStateSnapshot;"
            + "com.revature.cardealer.User;"
            + "com.revature.cardealer.AccountRole;"
            + "com.revature.cardealer.Car;"
            + "com.revature.cardealer.InventoryListing;"
            + "com.revature.cardealer.PurchaseRequest;"
            + "com.revature.cardealer.PurchaseRequestStatus;"
            + "com.revature.cardealer.OwnedVehicle;"
            + "com.revature.cardealer.PaymentPlan;"
            + "com.revature.cardealer.PaymentTransaction;"
            + "java.base/java.time.Ser;java.base/java.time.Instant;"
            + "java.base/java.util.ArrayList;"
            + "java.base/java.util.Collections$UnmodifiableRandomAccessList;"
            + "java.base/java.util.Collections$UnmodifiableList;"
            + "java.base/java.util.Collections$UnmodifiableCollection;"
            + "java.base/java.util.Collections$EmptyList;"
            + "java.base/java.lang.String;java.base/java.lang.Enum;"
            + "java.base/java.lang.Long;java.base/java.lang.Integer;"
            + "java.base/java.lang.Number;java.base/java.lang.Object;!*");


    @Override
    public SaveResult saveData(Data data, String filename) {
        if (data == null || isBlank(filename)) {
            return SaveResult.failure(SaveResult.Status.INVALID_INPUT, filename,
                    "data and filename are required");
        }

        if (Files.isSymbolicLink(Paths.get(filename))) {
            return SaveResult.invalidInput("symbolic-link snapshot targets are not accepted");
        }
        try (FileOutputStream output = new FileOutputStream(filename);
                ObjectOutputStream objectOutput = new ObjectOutputStream(output)) {
            objectOutput.writeObject(data.getSnapshot());
            return SaveResult.success(filename);
        } catch (IOException | RuntimeException exception) {
            return SaveResult.failure(SaveResult.Status.IO_ERROR, filename,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    @Override
    public LoadResult loadData(String filename) {
        if (isBlank(filename)) {
            return LoadResult.failure(LoadResult.Status.IO_ERROR, "filename is required");
        }

        Path snapshotPath = Paths.get(filename);
        try {
            if (Files.isSymbolicLink(snapshotPath)) {
                return LoadResult.invalidContent("symbolic-link snapshot sources are not accepted");
            }
            if (Files.exists(snapshotPath, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(snapshotPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(snapshotPath) > MAX_SNAPSHOT_BYTES)) {
                return LoadResult.invalidContent("snapshot source must be a bounded regular file");
            }
        } catch (IOException | RuntimeException exception) {
            return LoadResult.invalidContent("snapshot source cannot be validated");
        }

        try (FileInputStream input = new FileInputStream(filename);
                ObjectInputStream objectInput = new ObjectInputStream(input)) {
            objectInput.setObjectInputFilter(SNAPSHOT_FILTER);
            Object value = objectInput.readObject();
            if (!(value instanceof ApplicationStateSnapshot)) {
                return LoadResult.failure(LoadResult.Status.INVALID_CONTENT,
                        "file does not contain an application-state snapshot");
            }

            ApplicationStateSnapshot snapshot = (ApplicationStateSnapshot) value;
            if (snapshot.getVersion() != ApplicationStateSnapshot.CURRENT_VERSION) {
                return LoadResult.failure(LoadResult.Status.UNSUPPORTED_VERSION,
                        "unsupported snapshot version: " + snapshot.getVersion());
            }
            return LoadResult.success(snapshot);
        } catch (FileNotFoundException exception) {
            return LoadResult.failure(LoadResult.Status.NOT_FOUND, "file not found: " + filename);
        } catch (InvalidClassException exception) {
            return LoadResult.invalidContent("snapshot contains prohibited or incompatible serialized classes");
        } catch (ClassNotFoundException exception) {
            return LoadResult.failure(LoadResult.Status.CLASS_NOT_FOUND, exception.getMessage());
        } catch (IOException exception) {
            return LoadResult.failure(LoadResult.Status.IO_ERROR,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
