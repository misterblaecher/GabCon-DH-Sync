package be.gabcon.dhsync.server;

import be.gabcon.dhsync.GabConDhSync;
import be.gabcon.dhsync.config.ServerConfig;
import be.gabcon.dhsync.publish.ServerDistributionPublisher;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ServerAutoPublisher {
    private static final long POLL_SECONDS = 15L;
    private static final Object LOCK = new Object();

    private static volatile ServerAutoPublishCoordinator coordinator;
    private static volatile ScheduledFuture<?> scheduled;
    private static volatile Future<?> activeAutoTask;
    private static volatile long serverGeneration;
    private static volatile Path dirtyMarker;
    private static volatile boolean dirtyMarkerPresent;

    public static void start() {
        synchronized (LOCK) {
            stopLocked();

            // A single JVM can host several integrated-server lifecycles. Never
            // carry in-memory pending regions from the previous world into the new
            // coordinator; the per-world dirty marker restores conservative pending
            // state when needed.
            ServerState.CHANGED_REGIONS.clear();
            long generation = serverGeneration;

            String worldId = ServerConfig.WORLD_ID.get();
            Path stateFile = Path.of("gabcondhsync", "auto-publish",
                    DhSnapshotService.safeStem(worldId) + ".json");
            dirtyMarker = ServerDirtyMarker.pathFor(worldId);
            dirtyMarkerPresent = ServerDirtyMarker.exists(dirtyMarker);
            if (dirtyMarkerPresent && ServerState.CHANGED_REGIONS.pendingCount() == 0) {
                // A persisted dirty marker means the previous process observed at least
                // one unpublished save. Restore its original age so repeated restarts
                // cannot postpone interval-based publication indefinitely.
                try {
                    ServerState.CHANGED_REGIONS.markChunkSavedAt(
                            "gabcondhsync:persisted-dirty",
                            0,
                            0,
                            ServerDirtyMarker.firstSeen(dirtyMarker)
                    );
                } catch (Exception e) {
                    GabConDhSync.LOGGER.error(
                            "[GabConDHSync] Failed to restore dirty marker age; using current time", e
                    );
                    ServerState.CHANGED_REGIONS.markChunkSaved(
                            "gabcondhsync:persisted-dirty", 0, 0
                    );
                }
            }

            long maxAssetBytes = ServerConfig.MAX_DOWNLOAD_BYTES.get();
            ServerAutoPublishCoordinator.Pipeline pipeline = new ServerAutoPublishCoordinator.Pipeline() {
                @Override
                public String resolvePublicationBase() throws Exception {
                    requireGeneration(generation);
                    Optional<Path> resolved = PublishedBaselineResolver.resolvePublicationBase(
                            worldId, maxAssetBytes);
                    requireGeneration(generation);
                    return resolved.map(Path::toString).orElse(null);
                }

                @Override
                public String createSnapshot() throws Exception {
                    requireGeneration(generation);
                    DhCompatibility.Status dh = DhCompatibility.detect();
                    if (!dh.compatible()) {
                        throw new IllegalStateException("Unsupported DH/API pair "
                                + dh.modVersion() + " / " + dh.apiVersion());
                    }
                    String snapshot = DhSnapshotService.create(worldId, dh).directory().toString();
                    requireGeneration(generation);
                    return snapshot;
                }

                @Override
                public String buildDelta(String baseSnapshotDirectory, String currentSnapshotDirectory)
                        throws Exception {
                    requireGeneration(generation);
                    String delta = DhDeltaBuilder.build(
                            Path.of(baseSnapshotDirectory),
                            Path.of(currentSnapshotDirectory),
                            worldId
                    ).directory().toString();
                    requireGeneration(generation);
                    return delta;
                }

                @Override
                public void publish() throws Exception {
                    requireGeneration(generation);
                    ServerDistributionPublisher.publish();
                    requireGeneration(generation);
                }

                @Override
                public void verifyPublishedSnapshot(String currentSnapshotDirectory) throws Exception {
                    requireGeneration(generation);
                    PublishedBaselineResolver.requirePublishedSnapshot(
                            worldId,
                            maxAssetBytes,
                            Path.of(currentSnapshotDirectory)
                    );
                    requireGeneration(generation);
                }
            };

            coordinator = new ServerAutoPublishCoordinator(
                    ServerState.CHANGED_REGIONS,
                    stateFile,
                    worldId,
                    Clock.systemUTC(),
                    pipeline
            );

            scheduled = ServerState.AUTO_PUBLISH_SCHEDULER.scheduleAtFixedRate(
                    ServerAutoPublisher::pollSafely,
                    5L,
                    POLL_SECONDS,
                    TimeUnit.SECONDS
            );
            GabConDhSync.LOGGER.info("[GabConDHSync] Auto-publish scheduler started for worldId={} (enabled={}).",
                    worldId, ServerConfig.AUTO_PUBLISH.get());
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    public static void recordChunkSaved(String dimension, int chunkX, int chunkZ) {
        synchronized (LOCK) {
            Path marker = dirtyMarker;
            if (marker == null) {
                marker = ServerDirtyMarker.pathFor(ServerConfig.WORLD_ID.get());
                dirtyMarker = marker;
                dirtyMarkerPresent = ServerDirtyMarker.exists(marker);
            }
            if (!dirtyMarkerPresent) {
                try {
                    // Persist before publishing the in-memory change. A crash between
                    // these operations causes at worst one conservative extra snapshot.
                    ServerDirtyMarker.mark(marker);
                    dirtyMarkerPresent = true;
                } catch (Exception e) {
                    GabConDhSync.LOGGER.error("[GabConDHSync] Failed to persist auto-publish dirty marker", e);
                }
            }
            ServerState.CHANGED_REGIONS.markChunkSaved(dimension, chunkX, chunkZ);
        }
    }

    public static String statusSummary() {
        ServerAutoPublishCoordinator current = coordinator;
        if (current == null) return "autoPhase=stopped";
        Duration interval = Duration.ofMinutes(ServerConfig.PUBLISH_INTERVAL_MINUTES.get());
        ServerAutoPublishCoordinator.Status status = current.status(interval);
        return "autoPhase=" + status.phase()
                + ", autoPending=" + status.pending()
                + ", lastAutoSuccess=" + value(status.lastSuccessUtc())
                + ", lastAutoFailure=" + value(status.lastFailure())
                + ", nextAutoAttempt=" + value(status.nextAttemptUtc());
    }

    private static void pollSafely() {
        try {
            poll();
        } catch (Exception e) {
            GabConDhSync.LOGGER.error("[GabConDHSync] Auto-publish scheduler poll failed", e);
        }
    }

    private static void poll() throws Exception {
        if (!ServerConfig.ENABLED.get() || !ServerConfig.AUTO_PUBLISH.get()) return;

        ServerAutoPublishCoordinator current = coordinator;
        long generation = serverGeneration;
        if (current == null) return;

        int threshold = ServerConfig.CHANGED_REGION_THRESHOLD.get();
        Duration interval = Duration.ofMinutes(ServerConfig.PUBLISH_INTERVAL_MINUTES.get());
        if (!current.isDue(threshold, interval)) return;
        if (!ServerState.tryBegin(ServerState.MaintenanceOperation.AUTO_PUBLISH)) return;

        synchronized (LOCK) {
            if (coordinator != current || serverGeneration != generation) {
                ServerState.finish(ServerState.MaintenanceOperation.AUTO_PUBLISH);
                return;
            }

            activeAutoTask = ServerState.MAINTENANCE_EXECUTOR.submit(() -> {
                ServerState.MAINTENANCE_PROGRESS.start(
                        "auto-publish",
                        "starting",
                        "Capturing pending DH changes"
                );
                try {
                    requireGeneration(generation);
                    ServerAutoPublishCoordinator.RunResult result =
                            current.runIfDue(threshold, interval);
                    requireGeneration(generation);
                    if (result.ran() && result.success()) {
                        ServerState.MAINTENANCE_PROGRESS.complete(
                                "automatic snapshot/delta/publication completed"
                        );
                        synchronized (LOCK) {
                            requireGeneration(generation);
                            if (ServerState.CHANGED_REGIONS.pendingCount() == 0
                                    && dirtyMarkerPresent) {
                                try {
                                    ServerDirtyMarker.clear(dirtyMarker);
                                    dirtyMarkerPresent = false;
                                } catch (Exception e) {
                                    GabConDhSync.LOGGER.warn(
                                            "[GabConDHSync] Auto-publish succeeded but dirty marker cleanup failed",
                                            e
                                    );
                                }
                            }
                        }
                        GabConDhSync.LOGGER.info(
                                "[GabConDHSync] Auto-publish complete; remaining pending buckets={}.",
                                ServerState.CHANGED_REGIONS.pendingCount()
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    GabConDhSync.LOGGER.info(
                            "[GabConDHSync] Auto-publish cancelled because the server lifecycle ended."
                    );
                } catch (Exception e) {
                    String message = e.getMessage() == null
                            ? e.getClass().getSimpleName()
                            : e.getMessage();
                    ServerState.MAINTENANCE_PROGRESS.fail(message);
                    GabConDhSync.LOGGER.error(
                            "[GabConDHSync] Auto-publish failed safely; pending changes were retained.", e
                    );
                } finally {
                    ServerState.MAINTENANCE_PROGRESS.clear();
                    ServerState.finish(ServerState.MaintenanceOperation.AUTO_PUBLISH);
                    synchronized (LOCK) {
                        if (serverGeneration == generation) activeAutoTask = null;
                    }
                }
            });
        }
    }

    private static void stopLocked() {
        // Invalidate every pipeline callback captured by the previous server before
        // cancelling work. A stale task can therefore never reach verify/acknowledge
        // after a new world starts in the same JVM.
        serverGeneration++;

        ScheduledFuture<?> currentSchedule = scheduled;
        if (currentSchedule != null) currentSchedule.cancel(false);
        scheduled = null;

        Future<?> currentTask = activeAutoTask;
        if (currentTask != null) currentTask.cancel(true);
        activeAutoTask = null;

        // If cancellation wins before the queued runnable starts, its finally block
        // will never execute. Release the logical lease here; the maintenance executor
        // is single-threaded, so any subsequently submitted operation still cannot run
        // concurrently with a stale task that is winding down.
        ServerState.finish(ServerState.MaintenanceOperation.AUTO_PUBLISH);

        coordinator = null;
        dirtyMarker = null;
        dirtyMarkerPresent = false;
    }

    private static void requireGeneration(long expected) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || serverGeneration != expected) {
            throw new InterruptedException("stale GabCon server generation");
        }
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "never" : value;
    }

    private ServerAutoPublisher() {}
}
