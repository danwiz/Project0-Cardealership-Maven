package com.revature.DAOService;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Writes trusted local snapshots using staged same-directory atomic replacement.
 * The caller is responsible for administrator authorization and approved path scope.
 * Atomic rename does not by itself guarantee durability of a parent directory.
 */
public final class AtomicSnapshotFileWriter {
    private AtomicSnapshotFileWriter() { }

    public static void writeAtomically(Path filename, Serializable snapshot) throws IOException {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(snapshot, "snapshot");
        Path target = filename.toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(target.getParent(), "parent directory");
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent) || Files.isSymbolicLink(target)) {
            throw new IOException("untrusted snapshot path or parent");
        }

        Path staged = Files.createTempFile(parent, ".cardealer-stage-", ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(staged.toFile());
                 ObjectOutputStream output = new ObjectOutputStream(stream)) {
                output.writeObject(snapshot);
                output.flush();
                stream.getFD().sync();
            }
            if (Files.isSymbolicLink(target)) {
                throw new IOException("snapshot target became a symbolic link");
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic snapshot replacement unsupported", unsupported);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
