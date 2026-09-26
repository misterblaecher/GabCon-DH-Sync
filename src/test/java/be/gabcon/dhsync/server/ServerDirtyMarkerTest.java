package be.gabcon.dhsync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerDirtyMarkerTest {
    @TempDir Path temp;

    @Test
    void dirtyMarkerSurvivesUntilExplicitlyCleared() throws Exception {
        Path marker = temp.resolve("gabcondhsync").resolve("auto-publish").resolve("world.dirty");

        assertFalse(ServerDirtyMarker.exists(marker));
        ServerDirtyMarker.mark(marker);
        assertTrue(ServerDirtyMarker.exists(marker));

        // Re-marking is idempotent and intentionally cheap enough for conservative recovery.
        ServerDirtyMarker.mark(marker);
        assertTrue(ServerDirtyMarker.exists(marker));

        ServerDirtyMarker.clear(marker);
        assertFalse(ServerDirtyMarker.exists(marker));
    }
}
