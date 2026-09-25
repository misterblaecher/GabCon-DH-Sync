package be.gabcon.dhsync.client;

import be.gabcon.dhsync.server.DhSqliteSnapshotter;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DhOfflineFiles {
    private static final long SPACE_MARGIN_BYTES = 256L * 1024 * 1024;

    public static void requireInactive(Path database) throws IOException {
        Path db = database.toAbsolutePath().normalize();
        Path wal = db.resolveSibling(db.getFileName() + "-wal");
        Path shm = db.resolveSibling(db.getFileName() + "-shm");
        if ((Files.exists(wal) && Files.size(wal) > 0) || Files.exists(shm)) {
            throw new IOException("DH database appears active or uncheckpointed: " + db);
        }
    }

    public static void requireUsableSpace(Path directory, long requiredBytes) throws IOException {
        Files.createDirectories(directory);
        FileStore store = Files.getFileStore(directory);
        long needed = Math.addExact(requiredBytes, SPACE_MARGIN_BYTES);
        if (store.getUsableSpace() < needed) {
            throw new IOException("Not enough free space on " + directory
                    + ": need at least " + needed + " bytes, available " + store.getUsableSpace());
        }
    }

    public static Path copyToWork(Path source) throws Exception {
        Path src = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(src)) throw new IOException("DH database not found: " + src);
        requireInactive(src);
        DhSqliteSnapshotter.verify(src);
        Path work = workPath(src);
        Files.deleteIfExists(work);
        requireUsableSpace(src.getParent(), Files.size(src));
        Files.copy(src, work, StandardCopyOption.COPY_ATTRIBUTES);
        DhSqliteSnapshotter.verify(work);
        return work;
    }

    public static Path workPath(Path target) {
        Path db = target.toAbsolutePath().normalize();
        return db.resolveSibling(db.getFileName() + ".gabcon-work");
    }

    private DhOfflineFiles() {}
}
