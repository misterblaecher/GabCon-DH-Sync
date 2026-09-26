package be.gabcon.dhsync.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

final class ServerDirtyMarker {
    static Path pathFor(String worldId) {
        return Path.of("gabcondhsync", "auto-publish",
                        DhSnapshotService.safeStem(worldId) + ".dirty")
                .toAbsolutePath().normalize();
    }

    static boolean exists(Path path) {
        return Files.isRegularFile(path.toAbsolutePath().normalize());
    }

    static void mark(Path path) throws IOException {
        mark(path, Instant.now());
    }

    static void mark(Path path, Instant firstSeen) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Instant timestamp = firstSeen == null ? Instant.now() : firstSeen;
        Files.writeString(part, timestamp.toString() + "\n");
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Instant firstSeen(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) return null;
        String content = Files.readString(target).trim();
        if (!content.isBlank()) {
            try {
                return Instant.parse(content);
            } catch (RuntimeException ignored) {
                // 0.7.0 pre-review markers contained the literal "dirty".
                // Preserve their age from the filesystem timestamp.
            }
        }
        return Files.getLastModifiedTime(target).toInstant();
    }

    static void clear(Path path) throws IOException {
        Files.deleteIfExists(path.toAbsolutePath().normalize());
    }

    private ServerDirtyMarker() {}
}
