package be.gabcon.dhsync.command;

import be.gabcon.dhsync.GabConDhSync;
import be.gabcon.dhsync.config.ServerConfig;
import be.gabcon.dhsync.publish.ServerDistributionPublisher;
import be.gabcon.dhsync.server.DhCompatibility;
import be.gabcon.dhsync.server.DhDeltaBuilder;
import be.gabcon.dhsync.server.DhSnapshotService;
import be.gabcon.dhsync.server.ServerState;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public final class GabConCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gabcondhsync")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("snapshot").executes(ctx -> snapshot(ctx.getSource())))
                .then(Commands.literal("delta").executes(ctx -> delta(ctx.getSource())))
                .then(Commands.literal("publish").executes(ctx -> publish(ctx.getSource())))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        DhCompatibility.Status dh = DhCompatibility.detect();
        source.sendSuccess(() -> Component.literal("[GabConDHSync] enabled=" + ServerConfig.ENABLED.get()
                + ", worldId=" + ServerConfig.WORLD_ID.get()
                + ", pendingBuckets=" + ServerState.CHANGED_REGIONS.pendingCount()
                + ", " + ServerState.MAINTENANCE_PROGRESS.summary()
                + ", snapshotRunning=" + ServerState.SNAPSHOT_RUNNING.get()
                + ", deltaRunning=" + ServerState.DELTA_RUNNING.get()
                + ", publishRunning=" + ServerState.PUBLISH_RUNNING.get()
                + ", DH=" + dh.modVersion()
                + ", API=" + dh.apiVersion()
                + ", compatible=" + dh.compatible()), false);
        return 1;
    }

    private static int snapshot(CommandSourceStack source) {
        if (!ServerConfig.ENABLED.get()) {
            source.sendFailure(Component.literal("[GabConDHSync] Snapshot refused: mod is disabled."));
            return 0;
        }

        DhCompatibility.Status dh = DhCompatibility.detect();
        if (!dh.compatible()) {
            source.sendFailure(Component.literal("[GabConDHSync] Snapshot refused: unsupported DH/API pair "
                    + dh.modVersion() + " / " + dh.apiVersion() + "."));
            return 0;
        }

        if (ServerState.DELTA_RUNNING.get() || ServerState.PUBLISH_RUNNING.get()
                || !ServerState.SNAPSHOT_RUNNING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("[GabConDHSync] Another maintenance operation is already running: "
                    + ServerState.MAINTENANCE_PROGRESS.summary()));
            return 0;
        }

        ServerState.MAINTENANCE_PROGRESS.start("snapshot", "starting", "Preparing safe DH SQLite snapshot");
        source.sendSuccess(() -> Component.literal("[GabConDHSync] Snapshot started. Use /gabcondhsync status for live progress."), false);

        CompletableFuture.runAsync(() -> {
            try {
                DhSnapshotService.SnapshotResult result = DhSnapshotService.create(ServerConfig.WORLD_ID.get(), dh);
                ServerState.MAINTENANCE_PROGRESS.complete(result.files().size() + " database(s) captured");
                source.getServer().execute(() -> source.sendSuccess(
                        () -> Component.literal("[GabConDHSync] Snapshot complete: " + result.files().size()
                                + " database(s), manifest=" + result.manifest()), false));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ServerState.MAINTENANCE_PROGRESS.fail(message);
                GabConDhSync.LOGGER.error("[GabConDHSync] Snapshot failed", e);
                source.getServer().execute(() -> source.sendFailure(
                        Component.literal("[GabConDHSync] Snapshot failed safely: " + message)));
            } finally {
                ServerState.SNAPSHOT_RUNNING.set(false);
                ServerState.MAINTENANCE_PROGRESS.clear();
            }
        }, ServerState.MAINTENANCE_EXECUTOR);

        return 1;
    }

    private static int delta(CommandSourceStack source) {
        if (!ServerConfig.ENABLED.get()) {
            source.sendFailure(Component.literal("[GabConDHSync] Delta refused: mod is disabled."));
            return 0;
        }

        if (ServerState.SNAPSHOT_RUNNING.get() || ServerState.PUBLISH_RUNNING.get()
                || !ServerState.DELTA_RUNNING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("[GabConDHSync] Another maintenance operation is already running: "
                    + ServerState.MAINTENANCE_PROGRESS.summary()));
            return 0;
        }

        ServerState.MAINTENANCE_PROGRESS.start("delta", "starting", "Loading the two latest snapshots");
        source.sendSuccess(() -> Component.literal("[GabConDHSync] Delta generation started. Use /gabcondhsync status for live progress."), false);

        CompletableFuture.runAsync(() -> {
            try {
                DhDeltaBuilder.DeltaResult result = DhDeltaBuilder.buildLatest(ServerConfig.WORLD_ID.get());
                long operations = result.files().stream()
                        .flatMap(file -> file.tables().stream())
                        .mapToLong(table -> table.upserts() + table.deletes())
                        .sum();
                ServerState.MAINTENANCE_PROGRESS.complete(
                        result.files().size() + " changed dimension(s), " + operations + " row operation(s)");
                source.getServer().execute(() -> source.sendSuccess(
                        () -> Component.literal("[GabConDHSync] Delta complete: " + result.files().size()
                                + " changed dimension(s), " + operations
                                + " row operation(s), manifest=" + result.manifest()), false));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ServerState.MAINTENANCE_PROGRESS.fail(message);
                GabConDhSync.LOGGER.error("[GabConDHSync] Delta generation failed", e);
                source.getServer().execute(() -> source.sendFailure(
                        Component.literal("[GabConDHSync] Delta failed safely: " + message)));
            } finally {
                ServerState.DELTA_RUNNING.set(false);
                ServerState.MAINTENANCE_PROGRESS.clear();
            }
        }, ServerState.MAINTENANCE_EXECUTOR);

        return 1;
    }

    private static int publish(CommandSourceStack source) {
        if (!ServerConfig.ENABLED.get()) {
            source.sendFailure(Component.literal("[GabConDHSync] Publish refused: mod is disabled."));
            return 0;
        }
        if (ServerState.SNAPSHOT_RUNNING.get() || ServerState.DELTA_RUNNING.get()
                || !ServerState.PUBLISH_RUNNING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("[GabConDHSync] Another maintenance operation is already running: "
                    + ServerState.MAINTENANCE_PROGRESS.summary()));
            return 0;
        }

        ServerState.MAINTENANCE_PROGRESS.start(
                "publish",
                "starting",
                "Preparing GitHub Release " + ServerConfig.RELEASE_TAG.get()
        );
        source.sendSuccess(() -> Component.literal(
                "[GabConDHSync] Publish started for GitHub Release " + ServerConfig.RELEASE_TAG.get()
                        + ". Use /gabcondhsync status for live progress."
        ), false);

        CompletableFuture.runAsync(() -> {
            try {
                ServerDistributionPublisher.PublicationResult result = ServerDistributionPublisher.publish();
                ServerState.MAINTENANCE_PROGRESS.complete(
                        "uploadedAssets=" + result.uploadedAssets() + ", reusedAssets=" + result.reusedAssets());
                source.getServer().execute(() -> source.sendSuccess(
                        () -> Component.literal("[GabConDHSync] Publish complete: bootstrapCreated="
                                + result.bootstrapCreated()
                                + ", uploadedAssets=" + result.uploadedAssets()
                                + ", reusedAssets=" + result.reusedAssets()
                                + ", manifest=" + result.localManifest()), false));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ServerState.MAINTENANCE_PROGRESS.fail(message);
                GabConDhSync.LOGGER.error("[GabConDHSync] Publication failed", e);
                source.getServer().execute(() -> source.sendFailure(
                        Component.literal("[GabConDHSync] Publish failed safely: " + message)));
            } finally {
                ServerState.PUBLISH_RUNNING.set(false);
                ServerState.MAINTENANCE_PROGRESS.clear();
            }
        }, ServerState.MAINTENANCE_EXECUTOR);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[GabConDHSync] NeoForge owns config reload; current worldId=" + ServerConfig.WORLD_ID.get()), false);
        return 1;
    }

    private GabConCommands() {}
}
