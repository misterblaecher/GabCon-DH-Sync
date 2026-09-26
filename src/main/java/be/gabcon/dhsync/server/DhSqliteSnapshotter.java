package be.gabcon.dhsync.server;

import be.gabcon.dhsync.util.Hashes;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DhSqliteSnapshotter {
    private static final String DRIVER_CLASS = "dh_sqlite.JDBC";
    private static final String JDBC_PREFIX = "jdbc:dh_sqlite:";

    @FunctionalInterface
    public interface BackupProgress {
        void update(String stage, long currentBytes, long totalBytes);
    }

    public record BackupResult(Path path, long size, String sha256) {}

    public static BackupResult backup(Path source, Path destination) throws Exception {
        return backup(source, destination, (stage, current, total) -> {});
    }

    public static BackupResult backup(Path source, Path destination, BackupProgress progress) throws Exception {
        Path src = source.toAbsolutePath().normalize();
        Path dst = destination.toAbsolutePath().normalize();
        if (!Files.isRegularFile(src)) throw new IOException("DH database not found: " + src);
        if (src.equals(dst)) throw new IOException("Snapshot destination must differ from source database");

        Files.createDirectories(dst.getParent());
        Path part = dst.resolveSibling(dst.getFileName() + ".part");
        Files.deleteIfExists(part);

        long expectedBytes = Files.size(src);
        progress.update("sqlite-backup", 0L, expectedBytes);

        AtomicBoolean monitoring = new AtomicBoolean(true);
        Thread monitor = new Thread(() -> {
            while (monitoring.get()) {
                try {
                    long copied = Files.isRegularFile(part) ? Files.size(part) : 0L;
                    progress.update("sqlite-backup", Math.min(copied, expectedBytes), expectedBytes);
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // Progress reporting is best-effort and must never make a safe backup fail.
                }
            }
        }, "GabConDHSync-SnapshotProgress");
        monitor.setDaemon(true);

        Class.forName(DRIVER_CLASS);
        try {
            monitor.start();
            try {
                try (Connection conn = DriverManager.getConnection(JDBC_PREFIX + src);
                     Statement statement = conn.createStatement()) {
                    statement.execute("PRAGMA busy_timeout=10000");
                    statement.execute("backup to '" + sqliteQuote(part.toString()) + "'");
                }
            } finally {
                monitoring.set(false);
                monitor.interrupt();
            }

            progress.update("quick-check", expectedBytes, expectedBytes);
            verify(part);
            progress.update("sha256", expectedBytes, expectedBytes);
            String sha256 = Hashes.sha256(part);
            atomicReplace(part, dst);
            progress.update("complete", expectedBytes, expectedBytes);
            return new BackupResult(dst, Files.size(dst), sha256);
        } catch (Exception e) {
            monitoring.set(false);
            monitor.interrupt();
            Files.deleteIfExists(part);
            throw e;
        }
    }

    public static void verify(Path database) throws Exception {
        Class.forName(DRIVER_CLASS);
        try (Connection conn = DriverManager.getConnection(JDBC_PREFIX + database.toAbsolutePath().normalize());
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA quick_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new SQLException("SQLite quick_check failed for snapshot: " + database);
            }
        }
    }

    private static String sqliteQuote(String value) {
        return value.replace("'", "''");
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private DhSqliteSnapshotter() {}
}
