package be.gabcon.dhsync.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ServerAutoPublishStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 1;

    enum Phase {
        IDLE,
        CAPTURED,
        BASE_RESOLVED,
        SNAPSHOT_READY,
        DELTA_READY
    }

    record State(
            int schemaVersion,
            String worldId,
            Phase phase,
            long capturedWatermark,
            String lastPublishedSnapshotDirectory,
            String baseSnapshotDirectory,
            String snapshotDirectory,
            String deltaDirectory,
            String lastAttemptUtc,
            String lastSuccessUtc,
            String lastFailure
    ) {
        static State idle(String worldId) {
            return new State(
                    SCHEMA_VERSION, worldId, Phase.IDLE, 0L,
                    null, null, null, null,
                    null, null, null
            );
        }

        State withPhase(
                Phase nextPhase,
                long nextWatermark,
                String nextLastPublished,
                String nextBase,
                String nextSnapshot,
                String nextDelta,
                String nextAttempt,
                String nextSuccess,
                String nextFailure
        ) {
            return new State(
                    SCHEMA_VERSION, worldId, nextPhase, nextWatermark,
                    nextLastPublished, nextBase, nextSnapshot, nextDelta,
                    nextAttempt, nextSuccess, nextFailure
            );
        }
    }

    static State load(Path file, String worldId) throws IOException {
        if (!Files.isRegularFile(file)) return State.idle(worldId);
        State state;
        try {
            state = GSON.fromJson(Files.readString(file), State.class);
        } catch (RuntimeException e) {
            throw new IOException("Unreadable GabCon auto-publish state: " + file, e);
        }
        if (state == null
                || state.schemaVersion() != SCHEMA_VERSION
                || state.worldId() == null
                || !state.worldId().equals(worldId)
                || state.phase() == null
                || state.capturedWatermark() < 0L) {
            throw new IOException("Invalid GabCon auto-publish state: " + file);
        }
        return state;
    }

    static void save(Path file, State state) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Path target = file.toAbsolutePath().normalize();
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(part, GSON.toJson(state));
        atomicReplace(part, target);
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ServerAutoPublishStateStore() {}
}
