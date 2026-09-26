package be.gabcon.dhsync.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChangedRegionTrackerTest {
    @Test
    void acknowledgementDoesNotDropSameRegionSavedAfterCapture() {
        ChangedRegionTracker tracker = new ChangedRegionTracker(100L);
        tracker.markChunkSaved("minecraft:overworld", 0, 0);
        tracker.markChunkSaved("minecraft:overworld", 64, 64);

        ChangedRegionTracker.Capture capture = tracker.capture();
        assertEquals(2, capture.pendingCount());

        tracker.markChunkSaved("minecraft:overworld", 0, 0);
        tracker.acknowledgeThrough(capture.watermark());

        assertEquals(1, tracker.pendingCount());
        assertTrue(tracker.snapshot().contains(RegionKey.fromChunk("minecraft:overworld", 0, 0)));
        assertFalse(tracker.snapshot().contains(RegionKey.fromChunk("minecraft:overworld", 64, 64)));
    }

    @Test
    void ensureSequenceFloorProtectsChangesObservedAfterRestart() {
        ChangedRegionTracker tracker = new ChangedRegionTracker(0L);
        tracker.ensureSequenceAtLeast(500L);
        tracker.markChunkSaved("minecraft:overworld", 0, 0);
        tracker.acknowledgeThrough(500L);

        assertEquals(1, tracker.pendingCount());
    }
}
