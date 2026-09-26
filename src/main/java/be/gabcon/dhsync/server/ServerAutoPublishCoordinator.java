package be.gabcon.dhsync.server;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class ServerAutoPublishCoordinator {
    public interface Pipeline {
        /**
         * Resolve the snapshot represented by the currently published manifest.
         * When no manifest exists yet, return the snapshot that the publisher will
         * use as its bootstrap base. Return null only when no prior snapshot exists.
         */
        String resolvePublicationBase() throws Exception;

        String createSnapshot() throws Exception;

        String buildDelta(String baseSnapshotDirectory, String currentSnapshotDirectory) throws Exception;

        void publish() throws Exception;

        /**
         * Must fail unless the published manifest now represents currentSnapshotDirectory.
         */
        void verifyPublishedSnapshot(String currentSnapshotDirectory) throws Exception;
    }

    public record RunResult(boolean ran, boolean success, String detail) {}

    public record Status(
            String phase,
            int pending,
            String lastAttemptUtc,
            String lastSuccessUtc,
            String lastFailure,
            String nextAttemptUtc
    ) {}

    private final ChangedRegionTracker tracker;
    private final Path stateFile;
    private final String worldId;
    private final Clock clock;
    private final Pipeline pipeline;

    public ServerAutoPublishCoordinator(
            ChangedRegionTracker tracker,
            Path stateFile,
            String worldId,
            Clock clock,
            Pipeline pipeline
    ) {
        this.tracker = tracker;
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.worldId = worldId;
        this.clock = clock;
        this.pipeline = pipeline;
    }

    public synchronized boolean isDue(int changedRegionThreshold, Duration interval) throws IOException {
        validatePolicy(changedRegionThreshold, interval);
        ServerAutoPublishStateStore.State state = ServerAutoPublishStateStore.load(stateFile, worldId);
        tracker.ensureSequenceAtLeast(state.capturedWatermark() + 1L);
        Instant now = clock.instant();

        if (state.phase() != ServerAutoPublishStateStore.Phase.IDLE) {
            if (state.lastFailure() == null || state.lastFailure().isBlank()) return true;
            Instant attempt = parseInstant(state.lastAttemptUtc());
            return attempt == null || !attempt.plus(interval).isAfter(now);
        }

        if (tracker.pendingCount() == 0) return false;
        if (tracker.pendingCount() >= changedRegionThreshold) return true;

        Instant changed = tracker.lastChange();
        return changed != null && !changed.plus(interval).isAfter(now);
    }

    public synchronized RunResult runIfDue(int changedRegionThreshold, Duration interval) throws Exception {
        validatePolicy(changedRegionThreshold, interval);
        if (!isDue(changedRegionThreshold, interval)) {
            return new RunResult(false, false, "not due");
        }

        Instant now = clock.instant();
        ServerAutoPublishStateStore.State state = ServerAutoPublishStateStore.load(stateFile, worldId);
        tracker.ensureSequenceAtLeast(state.capturedWatermark() + 1L);

        try {
            if (state.phase() == ServerAutoPublishStateStore.Phase.IDLE) {
                ChangedRegionTracker.Capture capture = tracker.capture();
                if (capture.pendingCount() == 0) return new RunResult(false, false, "no pending regions");
                state = state.withPhase(
                        ServerAutoPublishStateStore.Phase.CAPTURED,
                        capture.watermark(),
                        state.lastPublishedSnapshotDirectory(),
                        null, null, null,
                        now.toString(), state.lastSuccessUtc(), null
                );
                ServerAutoPublishStateStore.save(stateFile, state);
            }

            if (state.phase() == ServerAutoPublishStateStore.Phase.CAPTURED) {
                String base = state.lastPublishedSnapshotDirectory();
                if (base == null || base.isBlank()) {
                    base = pipeline.resolvePublicationBase();
                }
                state = state.withPhase(
                        ServerAutoPublishStateStore.Phase.BASE_RESOLVED,
                        state.capturedWatermark(),
                        state.lastPublishedSnapshotDirectory(),
                        blankToNull(base), null, null,
                        now.toString(), state.lastSuccessUtc(), null
                );
                ServerAutoPublishStateStore.save(stateFile, state);
            }

            if (state.phase() == ServerAutoPublishStateStore.Phase.BASE_RESOLVED) {
                String snapshot = pipeline.createSnapshot();
                if (snapshot == null || snapshot.isBlank()) {
                    throw new IOException("Auto-publish snapshot step returned no directory");
                }
                state = state.withPhase(
                        ServerAutoPublishStateStore.Phase.SNAPSHOT_READY,
                        state.capturedWatermark(),
                        state.lastPublishedSnapshotDirectory(),
                        state.baseSnapshotDirectory(), snapshot, null,
                        now.toString(), state.lastSuccessUtc(), null
                );
                ServerAutoPublishStateStore.save(stateFile, state);
            }

            if (state.phase() == ServerAutoPublishStateStore.Phase.SNAPSHOT_READY) {
                String delta = null;
                if (state.baseSnapshotDirectory() != null
                        && !samePath(state.baseSnapshotDirectory(), state.snapshotDirectory())) {
                    delta = pipeline.buildDelta(state.baseSnapshotDirectory(), state.snapshotDirectory());
                }
                state = state.withPhase(
                        ServerAutoPublishStateStore.Phase.DELTA_READY,
                        state.capturedWatermark(),
                        state.lastPublishedSnapshotDirectory(),
                        state.baseSnapshotDirectory(), state.snapshotDirectory(), blankToNull(delta),
                        now.toString(), state.lastSuccessUtc(), null
                );
                ServerAutoPublishStateStore.save(stateFile, state);
            }

            if (state.phase() == ServerAutoPublishStateStore.Phase.DELTA_READY) {
                pipeline.publish();
                pipeline.verifyPublishedSnapshot(state.snapshotDirectory());

                tracker.acknowledgeThrough(state.capturedWatermark());
                String success = clock.instant().toString();
                state = state.withPhase(
                        ServerAutoPublishStateStore.Phase.IDLE,
                        0L,
                        state.snapshotDirectory(),
                        null, null, null,
                        success, success, null
                );
                ServerAutoPublishStateStore.save(stateFile, state);
                return new RunResult(true, true, "published and acknowledged");
            }

            throw new IOException("Unsupported auto-publish phase: " + state.phase());
        } catch (Exception failure) {
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            ServerAutoPublishStateStore.State failed = state.withPhase(
                    state.phase(),
                    state.capturedWatermark(),
                    state.lastPublishedSnapshotDirectory(),
                    state.baseSnapshotDirectory(),
                    state.snapshotDirectory(),
                    state.deltaDirectory(),
                    clock.instant().toString(),
                    state.lastSuccessUtc(),
                    message
            );
            try {
                ServerAutoPublishStateStore.save(stateFile, failed);
            } catch (Exception stateFailure) {
                failure.addSuppressed(stateFailure);
            }
            throw failure;
        }
    }

    public synchronized Status status(Duration interval) {
        try {
            ServerAutoPublishStateStore.State state = ServerAutoPublishStateStore.load(stateFile, worldId);
            tracker.ensureSequenceAtLeast(state.capturedWatermark() + 1L);
            String next = null;
            if (state.phase() != ServerAutoPublishStateStore.Phase.IDLE
                    && state.lastFailure() != null
                    && !state.lastFailure().isBlank()) {
                Instant attempt = parseInstant(state.lastAttemptUtc());
                if (attempt != null) next = attempt.plus(interval).toString();
            } else if (state.phase() == ServerAutoPublishStateStore.Phase.IDLE
                    && tracker.pendingCount() > 0
                    && tracker.lastChange() != null) {
                next = tracker.lastChange().plus(interval).toString();
            }
            return new Status(
                    state.phase().name(),
                    tracker.pendingCount(),
                    state.lastAttemptUtc(),
                    state.lastSuccessUtc(),
                    state.lastFailure(),
                    next
            );
        } catch (Exception e) {
            return new Status("ERROR", tracker.pendingCount(), null, null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null);
        }
    }

    private static void validatePolicy(int threshold, Duration interval) {
        if (threshold < 1) throw new IllegalArgumentException("changedRegionThreshold must be >= 1");
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("publish interval must be positive");
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null) return false;
        return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
    }
}
