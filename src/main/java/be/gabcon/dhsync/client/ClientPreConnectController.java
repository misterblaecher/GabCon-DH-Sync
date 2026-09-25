package be.gabcon.dhsync.client;

import be.gabcon.dhsync.GabConDhSync;
import be.gabcon.dhsync.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientPreConnectController {
    private static final AtomicBoolean BYPASS_ONCE = new AtomicBoolean(false);
    private static final AtomicBoolean SYNC_RUNNING = new AtomicBoolean(false);
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GabConDHSync-PreConnect");
        t.setDaemon(true);
        return t;
    });
    private static volatile ExecutorService downloadExecutor;
    private static volatile int downloadExecutorSize;

    public static boolean intercept(
            Screen parent,
            Minecraft minecraft,
            ServerAddress serverAddress,
            ServerData serverData,
            boolean isQuickPlay,
            TransferState transferState
    ) {
        if (BYPASS_ONCE.getAndSet(false)) return false;
        if (!ClientConfig.ENABLED.get()
                || !ClientConfig.AUTO_DOWNLOAD.get()
                || !ClientConfig.INTERCEPT_MANAGED_CONNECTIONS.get()) {
            return false;
        }

        String serverKey = ClientServerKeys.of(serverAddress);
        ClientSyncState.ServerProfile profile;
        try {
            var found = ClientSyncStateStore.find(ClientSyncStateStore.defaultPath(), serverKey);
            if (found.isEmpty()) return false;
            profile = found.get();
        } catch (Exception e) {
            GabConDhSync.LOGGER.error("[GabConDHSync] Cannot read managed client profile for {}", serverKey, e);
            if (ClientConfig.ALLOW_FALLBACK.get()) return false;
            ClientSyncScreen errorScreen = new ClientSyncScreen(parent);
            minecraft.setScreen(errorScreen);
            errorScreen.fail("Cannot read GabCon client state: " + message(e));
            return true;
        }

        if (!SYNC_RUNNING.compareAndSet(false, true)) {
            GabConDhSync.LOGGER.warn("[GabConDHSync] Pre-connect sync already running; duplicate connection ignored.");
            return true;
        }

        ClientSyncScreen screen = new ClientSyncScreen(parent);
        minecraft.setScreen(screen);
        ClientSyncEngine engine = new ClientSyncEngine(downloadExecutor());

        CompletableFuture.supplyAsync(() -> {
            try {
                return engine.sync(profile, screen::progress);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, SYNC_EXECUTOR).whenComplete((result, error) -> minecraft.execute(() -> {
            SYNC_RUNNING.set(false);
            if (error == null) {
                GabConDhSync.LOGGER.info("[GabConDHSync] Pre-connect sync complete for {}; changed={}",
                        serverKey, result.changed());
                if (ClientConfig.CONNECT_ON_COMPLETE.get()) {
                    resume(parent, minecraft, serverAddress, serverData, isQuickPlay, transferState);
                } else {
                    minecraft.setScreen(parent);
                }
                return;
            }

            Throwable cause = unwrap(error);
            GabConDhSync.LOGGER.error("[GabConDHSync] Pre-connect sync failed for " + serverKey, cause);
            boolean recoveryPending = Files.exists(ClientRecoveryJournal.pathFor(serverKey));
            if (ClientConfig.ALLOW_FALLBACK.get() && !recoveryPending) {
                GabConDhSync.LOGGER.warn("[GabConDHSync] Falling back to normal Distant Horizons networking for {}.", serverKey);
                resume(parent, minecraft, serverAddress, serverData, isQuickPlay, transferState);
            } else {
                screen.fail(message(cause) + (recoveryPending ? " (recovery journal still pending; connection blocked)" : ""));
            }
        }));

        return true;
    }

    private static synchronized ExecutorService downloadExecutor() {
        int desired = Math.max(1, ClientConfig.MAX_CONCURRENT_DOWNLOADS.get());
        if (downloadExecutor == null || downloadExecutor.isShutdown() || downloadExecutorSize != desired) {
            if (downloadExecutor != null) downloadExecutor.shutdown();
            downloadExecutorSize = desired;
            downloadExecutor = Executors.newFixedThreadPool(desired, r -> {
                Thread t = new Thread(r, "GabConDHSync-Download");
                t.setDaemon(true);
                return t;
            });
        }
        return downloadExecutor;
    }

    private static void resume(
            Screen parent,
            Minecraft minecraft,
            ServerAddress serverAddress,
            ServerData serverData,
            boolean isQuickPlay,
            TransferState transferState
    ) {
        BYPASS_ONCE.set(true);
        ConnectScreen.startConnecting(parent, minecraft, serverAddress, serverData, isQuickPlay, transferState);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof RuntimeException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private ClientPreConnectController() {}
}
