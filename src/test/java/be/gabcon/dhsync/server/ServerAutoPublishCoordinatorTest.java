package be.gabcon.dhsync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ServerAutoPublishCoordinatorTest {
    @TempDir Path temp;

    @Test
    void thresholdRunsFullPipelineAndKeepsChangesSavedDuringPublish() throws Exception {
        ChangedRegionTracker tracker = new ChangedRegionTracker(10L);
        tracker.markChunkSaved("minecraft:overworld", 0, 0);

        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        FakePipeline pipeline = new FakePipeline();
        pipeline.base = "/snapshots/base";
        pipeline.snapshot = "/snapshots/current";
        pipeline.delta = "/deltas/base-current";
        pipeline.onPublish = () -> tracker.markChunkSaved("minecraft:overworld", 0, 0);

        ServerAutoPublishCoordinator coordinator = coordinator(tracker, clock, pipeline);
        var result = coordinator.runIfDue(1, Duration.ofMinutes(30));

        assertTrue(result.ran());
        assertTrue(result.success());
        assertEquals(1, pipeline.resolveCalls);
        assertEquals(1, pipeline.snapshotCalls);
        assertEquals(1, pipeline.deltaCalls);
        assertEquals(1, pipeline.publishCalls);
        assertEquals(1, pipeline.verifyCalls);
        assertEquals(1, tracker.pendingCount(), "save during publication must stay pending");
    }

    @Test
    void publishFailureRetainsPendingAndRetriesOnlyPublishAfterCooldown() throws Exception {
        ChangedRegionTracker tracker = new ChangedRegionTracker(20L);
        tracker.markChunkSaved("minecraft:overworld", 0, 0);

        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        FakePipeline pipeline = new FakePipeline();
        pipeline.base = "/snapshots/base";
        pipeline.snapshot = "/snapshots/current";
        pipeline.delta = "/deltas/base-current";
        pipeline.failPublish = true;

        ServerAutoPublishCoordinator coordinator = coordinator(tracker, clock, pipeline);
        assertThrows(Exception.class, () -> coordinator.runIfDue(1, Duration.ofMinutes(10)));

        assertEquals(1, tracker.pendingCount());
        assertEquals(1, pipeline.snapshotCalls);
        assertEquals(1, pipeline.deltaCalls);
        assertEquals(1, pipeline.publishCalls);
        assertFalse(coordinator.isDue(1, Duration.ofMinutes(10)), "failure must respect retry cooldown");

        clock.advance(Duration.ofMinutes(11));
        pipeline.failPublish = false;
        var result = coordinator.runIfDue(1, Duration.ofMinutes(10));

        assertTrue(result.success());
        assertEquals(1, pipeline.snapshotCalls, "completed snapshot step must not rerun");
        assertEquals(1, pipeline.deltaCalls, "completed delta step must not rerun");
        assertEquals(2, pipeline.publishCalls);
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void deltaFailureResumesFromSnapshotReadyWithoutCreatingAnotherSnapshot() throws Exception {
        ChangedRegionTracker tracker = new ChangedRegionTracker(30L);
        tracker.markChunkSaved("minecraft:overworld", 0, 0);

        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        FakePipeline pipeline = new FakePipeline();
        pipeline.base = "/snapshots/base";
        pipeline.snapshot = "/snapshots/current";
        pipeline.delta = "/deltas/base-current";
        pipeline.failDelta = true;

        ServerAutoPublishCoordinator coordinator = coordinator(tracker, clock, pipeline);
        assertThrows(Exception.class, () -> coordinator.runIfDue(1, Duration.ofMinutes(5)));
        assertEquals(1, pipeline.snapshotCalls);
        assertEquals(1, pipeline.deltaCalls);

        clock.advance(Duration.ofMinutes(6));
        pipeline.failDelta = false;
        assertTrue(coordinator.runIfDue(1, Duration.ofMinutes(5)).success());

        assertEquals(1, pipeline.snapshotCalls);
        assertEquals(2, pipeline.deltaCalls);
        assertEquals(1, pipeline.publishCalls);
    }

    @Test
    void intervalTriggersBelowThresholdFromOldestPendingChange() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        ChangedRegionTracker tracker = new ChangedRegionTracker(40L, clock);
        tracker.markChunkSaved("minecraft:overworld", 0, 0);

        clock.advance(Duration.ofMinutes(29));
        tracker.markChunkSaved("minecraft:overworld", 0, 0);
        assertFalse(coordinator(tracker, clock, new FakePipeline())
                .isDue(32, Duration.ofMinutes(30)));

        clock.advance(Duration.ofMinutes(2));
        assertTrue(coordinator(tracker, clock, new FakePipeline())
                .isDue(32, Duration.ofMinutes(30)),
                "re-saving the same pending region must not postpone its original deadline");
    }

    @Test
    void newCycleReResolvesPublicationBaseAfterManualAdvance() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        ChangedRegionTracker tracker = new ChangedRegionTracker(50L, clock);
        FakePipeline pipeline = new FakePipeline();
        pipeline.base = "/snapshots/base-a";
        pipeline.snapshot = "/snapshots/auto-a";
        pipeline.delta = "/deltas/a";

        tracker.markChunkSaved("minecraft:overworld", 0, 0);
        ServerAutoPublishCoordinator coordinator = coordinator(tracker, clock, pipeline);
        assertTrue(coordinator.runIfDue(1, Duration.ofMinutes(30)).success());
        assertEquals("/snapshots/base-a", pipeline.lastDeltaBase);

        // Simulate a supported manual snapshot/delta/publish that advances the
        // publication baseline between automatic cycles.
        pipeline.base = "/snapshots/manual-b";
        pipeline.snapshot = "/snapshots/auto-c";
        pipeline.delta = "/deltas/b-c";
        tracker.markChunkSaved("minecraft:overworld", 64, 64);

        assertTrue(coordinator.runIfDue(1, Duration.ofMinutes(30)).success());
        assertEquals(2, pipeline.resolveCalls);
        assertEquals("/snapshots/manual-b", pipeline.lastDeltaBase);
    }

    @Test
    void persistedFailedPublicationProtectsNewChangesAfterRestart() throws Exception {
        ChangedRegionTracker firstTracker = new ChangedRegionTracker(100L);
        firstTracker.markChunkSaved("minecraft:overworld", 0, 0);

        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        FakePipeline firstPipeline = new FakePipeline();
        firstPipeline.snapshot = "/snapshots/current";
        firstPipeline.failPublish = true;

        Path state = temp.resolve("auto.json");
        ServerAutoPublishCoordinator first = new ServerAutoPublishCoordinator(
                firstTracker, state, "gabcon-main", clock, firstPipeline
        );
        assertThrows(Exception.class, () -> first.runIfDue(1, Duration.ofMinutes(5)));

        ChangedRegionTracker restartedTracker = new ChangedRegionTracker(0L);
        FakePipeline resumedPipeline = new FakePipeline();
        resumedPipeline.snapshot = "/snapshots/should-not-be-used";
        ServerAutoPublishCoordinator resumed = new ServerAutoPublishCoordinator(
                restartedTracker, state, "gabcon-main", clock, resumedPipeline
        );

        clock.advance(Duration.ofMinutes(6));
        assertTrue(resumed.isDue(1, Duration.ofMinutes(5)));
        restartedTracker.markChunkSaved("minecraft:overworld", 0, 0);

        var result = resumed.runIfDue(1, Duration.ofMinutes(5));
        assertTrue(result.success());
        assertEquals(0, resumedPipeline.snapshotCalls, "persisted phase must resume without resnapshot");
        assertEquals(1, restartedTracker.pendingCount(), "new post-restart save must survive old watermark ack");
    }

    private ServerAutoPublishCoordinator coordinator(
            ChangedRegionTracker tracker,
            Clock clock,
            FakePipeline pipeline
    ) {
        return new ServerAutoPublishCoordinator(
                tracker,
                temp.resolve("auto.json"),
                "gabcon-main",
                clock,
                pipeline
        );
    }

    private static final class FakePipeline implements ServerAutoPublishCoordinator.Pipeline {
        String base;
        String snapshot;
        String delta;
        boolean failDelta;
        boolean failPublish;
        int resolveCalls;
        int snapshotCalls;
        int deltaCalls;
        int publishCalls;
        int verifyCalls;
        String lastDeltaBase;
        Runnable onPublish;

        @Override
        public String resolvePublicationBase() {
            resolveCalls++;
            return base;
        }

        @Override
        public String createSnapshot() {
            snapshotCalls++;
            return snapshot;
        }

        @Override
        public String buildDelta(String baseSnapshotDirectory, String currentSnapshotDirectory) throws Exception {
            deltaCalls++;
            lastDeltaBase = baseSnapshotDirectory;
            if (failDelta) throw new Exception("delta failed");
            return delta;
        }

        @Override
        public void publish() throws Exception {
            publishCalls++;
            if (onPublish != null) onPublish.run();
            if (failPublish) throw new Exception("publish failed");
        }

        @Override
        public void verifyPublishedSnapshot(String currentSnapshotDirectory) {
            verifyCalls++;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
