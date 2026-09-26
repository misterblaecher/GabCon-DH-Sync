package be.gabcon.dhsync.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerState {
    public static final ChangedRegionTracker CHANGED_REGIONS = new ChangedRegionTracker();
    public static final AtomicBoolean SNAPSHOT_RUNNING = new AtomicBoolean(false);
    public static final AtomicBoolean DELTA_RUNNING = new AtomicBoolean(false);
    public static final AtomicBoolean PUBLISH_RUNNING = new AtomicBoolean(false);
    public static final MaintenanceProgress MAINTENANCE_PROGRESS = new MaintenanceProgress();

    public static final ExecutorService MAINTENANCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GabConDHSync-Maintenance");
        thread.setDaemon(true);
        return thread;
    });

    private static final ScheduledExecutorService PROGRESS_HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GabConDHSync-Progress");
        thread.setDaemon(true);
        return thread;
    });

    static {
        PROGRESS_HEARTBEAT.scheduleAtFixedRate(
                MAINTENANCE_PROGRESS::logHeartbeat,
                15,
                15,
                TimeUnit.SECONDS
        );
    }

    public static boolean maintenanceRunning() {
        return SNAPSHOT_RUNNING.get() || DELTA_RUNNING.get() || PUBLISH_RUNNING.get();
    }

    private ServerState() {}
}
