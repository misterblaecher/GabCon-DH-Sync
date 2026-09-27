package be.gabcon.dhsync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerAutoPublishStateStoreTest {
    @TempDir Path temp;

    @Test
    void savesAndLoadsStateAtomically() throws Exception {
        Path file = temp.resolve("auto.json");
        var state = ServerAutoPublishStateStore.State.idle("gabcon-main").withPhase(
                ServerAutoPublishStateStore.Phase.SNAPSHOT_READY,
                42L,
                "/snapshots/previous",
                "/snapshots/base",
                "/snapshots/current",
                null,
                "2026-09-26T12:00:00Z",
                null,
                null
        );

        ServerAutoPublishStateStore.save(file, state);
        var loaded = ServerAutoPublishStateStore.load(file, "gabcon-main");

        assertEquals(ServerAutoPublishStateStore.Phase.SNAPSHOT_READY, loaded.phase());
        assertEquals(42L, loaded.capturedWatermark());
        assertEquals("/snapshots/current", loaded.snapshotDirectory());
        assertFalse(Files.exists(file.resolveSibling("auto.json.part")));
    }

    @Test
    void refusesStateFromAnotherWorld() throws Exception {
        Path file = temp.resolve("auto.json");
        ServerAutoPublishStateStore.save(file, ServerAutoPublishStateStore.State.idle("other-world"));
        assertThrows(Exception.class, () -> ServerAutoPublishStateStore.load(file, "gabcon-main"));
    }
}
