package be.gabcon.dhsync.client;

import be.gabcon.dhsync.server.DhSqliteSnapshotter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

public final class DhOfflineFiles {
    private static final long SPACE_MARGIN_BYTES = 256L * 1024 * 1024;
    private static final long COPY_CHUNK_BYTES = 64L * 1024 * 1024;

    @FunctionalInterface
    public interface CopyProgress {
        void update(long copiedBytes, long totalBytes);
    }

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
        return copyToWork(source, (copied, total) -> {});
    }

    public static Path copyToWork(Path source, CopyProgress progress) throws Exception {
        CopyProgress sink = progress == null ? (copied, total) -> {} : progress;
        Path src = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(src)) throw new IOException("DH database not found: " + src);
        requireInactive(src);
        DhSqliteSnapshotter.verify(src);

        Path work = workPath(src);
        Files.deleteIfExists(work);

        long total = Files.size(src);
        requireUsableSpace(src.getParent(), total);
        sink.update(0, total);

        try {
            copyWithProgress(src, work, total, sink);
            FileTime modified = Files.getLastModifiedTime(src);
            Files.setLastModifiedTime(work, modified);
            DhSqliteSnapshotter.verify(work);
            return work;
        } catch (Exception failure) {
            Files.deleteIfExists(work);
            throw failure;
        }
    }

    private static void copyWithProgress(
            Path source,
            Path target,
            long total,
            CopyProgress progress
    ) throws IOException {
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     target,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE
             )) {
            long copied = 0;
            while (copied < total) {
                long requested = Math.min(COPY_CHUNK_BYTES, total - copied);
                long transferred = input.transferTo(copied, requested, output);
                if (transferred <= 0) {
                    throw new IOException("Unable to make progress while copying DH database");
                }
                copied += transferred;
                progress.update(copied, total);
            }
            output.force(true);
        }
    }

    public static Path workPath(Path target) {
        Path db = target.toAbsolutePath().normalize();
        return db.resolveSibling(db.getFileName() + ".gabcon-work");
    }

    private DhOfflineFiles() {}
}
