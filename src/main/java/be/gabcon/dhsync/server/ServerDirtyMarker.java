package be.gabcon.dhsync.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
        Path target = path.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(part, "dirty\n");
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void clear(Path path) throws IOException {
        Files.deleteIfExists(path.toAbsolutePath().normalize());
    }

    private ServerDirtyMarker() {}
}
