package be.gabcon.dhsync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

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
    @Test
    void dirtyMarkerPreservesOriginalFirstSeenAcrossRestart() throws Exception {
        Path marker = temp.resolve("gabcondhsync").resolve("auto-publish").resolve("world-age.dirty");
        Instant firstSeen = Instant.parse("2026-09-26T12:00:00Z");

        ServerDirtyMarker.mark(marker, firstSeen);
        assertEquals(firstSeen, ServerDirtyMarker.firstSeen(marker));

        // Reading after a simulated restart must not replace the original age.
        assertEquals(firstSeen, ServerDirtyMarker.firstSeen(marker));
    }

    @Test
    void legacyDirtyMarkerFallsBackToFileTimestamp() throws Exception {
        Path marker = temp.resolve("legacy.dirty");
        Files.writeString(marker, "dirty\n");
        Instant expected = Instant.parse("2026-09-26T11:30:00Z");
        Files.setLastModifiedTime(marker, FileTime.from(expected));

        assertEquals(expected, ServerDirtyMarker.firstSeen(marker));
    }

}
