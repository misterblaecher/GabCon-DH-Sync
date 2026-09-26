package be.gabcon.dhsync.server;

import be.gabcon.dhsync.GabConDhSync;
import be.gabcon.dhsync.config.ServerConfig;
import be.gabcon.dhsync.publish.ServerDistributionPublisher;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ServerAutoPublisher {
    private static final long POLL_SECONDS = 15L;
    private static final Object LOCK = new Object();

    private static volatile ServerAutoPublishCoordinator coordinator;
    private static volatile ScheduledFuture<?> scheduled;

    public static void start() {
        synchronized (LOCK) {
            stopLocked();

            String worldId = ServerConfig.WORLD_ID.get();
            Path stateFile = Path.of("gabcondhsync", "auto-publish",
                    DhSnapshotService.safeStem(worldId) + ".json");

            ServerAutoPublishCoordinator.Pipeline pipeline = new ServerAutoPublishCoordinator.Pipeline() {
                @Override
                public String resolvePublicationBase() throws Exception {
                    Optional<Path> resolved = PublishedBaselineResolver.resolvePublicationBase(
                            worldId, ServerConfig.MAX_DOWNLOAD_BYTES.get());
                    return resolved.map(Path::toString).orElse(null);
                }

                @Override
                public String createSnapshot() throws Exception {
                    DhCompatibility.Status dh = DhCompatibility.detect();
                    if (!dh.compatible()) {
                        throw new IllegalStateException("Unsupported DH/API pair "
                                + dh.modVersion() + " / " + dh.apiVersion());
                    }
                    return DhSnapshotService.create(worldId, dh).directory().toString();
                }

                @Override
                public String buildDelta(String baseSnapshotDirectory, String currentSnapshotDirectory)
                        throws Exception {
                    return DhDeltaBuilder.build(
                            Path.of(baseSnapshotDirectory),
                            Path.of(currentSnapshotDirectory),
                            worldId
                    ).directory().toString();
                }

                @Override
                public void publish() throws Exception {
                    ServerDistributionPublisher.publish();
                }

                @Override
                public void verifyPublishedSnapshot(String currentSnapshotDirectory) throws Exception {
                    PublishedBaselineResolver.requirePublishedSnapshot(
                            worldId,
                            ServerConfig.MAX_DOWNLOAD_BYTES.get(),
                            Path.of(currentSnapshotDirectory)
                    );
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
        if (current == null) return;

        int threshold = ServerConfig.CHANGED_REGION_THRESHOLD.get();
        Duration interval = Duration.ofMinutes(ServerConfig.PUBLISH_INTERVAL_MINUTES.get());
        if (!current.isDue(threshold, interval)) return;
        if (!ServerState.tryBegin(ServerState.MaintenanceOperation.AUTO_PUBLISH)) return;

        ServerState.MAINTENANCE_EXECUTOR.execute(() -> {
            ServerState.MAINTENANCE_PROGRESS.start(
                    "auto-publish",
                    "starting",
                    "Capturing pending DH changes"
            );
            try {
                ServerAutoPublishCoordinator.RunResult result = current.runIfDue(threshold, interval);
                if (result.ran() && result.success()) {
                    ServerState.MAINTENANCE_PROGRESS.complete(
                            "automatic snapshot/delta/publication completed"
                    );
                    GabConDhSync.LOGGER.info(
                            "[GabConDHSync] Auto-publish complete; remaining pending buckets={}.",
                            ServerState.CHANGED_REGIONS.pendingCount()
                    );
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ServerState.MAINTENANCE_PROGRESS.fail(message);
                GabConDhSync.LOGGER.error(
                        "[GabConDHSync] Auto-publish failed safely; pending changes were retained.", e
                );
            } finally {
                ServerState.MAINTENANCE_PROGRESS.clear();
                ServerState.finish(ServerState.MaintenanceOperation.AUTO_PUBLISH);
            }
        });
    }

    private static void stopLocked() {
        ScheduledFuture<?> current = scheduled;
        if (current != null) current.cancel(false);
        scheduled = null;
        coordinator = null;
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "never" : value;
    }

    private ServerAutoPublisher() {}
}
