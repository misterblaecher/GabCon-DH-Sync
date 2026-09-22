package be.gabcon.dhsync.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerState {
    public static final ChangedRegionTracker CHANGED_REGIONS = new ChangedRegionTracker();
    public static final AtomicBoolean SNAPSHOT_RUNNING = new AtomicBoolean(false);
    public static final ExecutorService SNAPSHOT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GabConDHSync-Snapshot");
        thread.setDaemon(true);
        return thread;
    });

    private ServerState() {}
}
