package be.gabcon.dhsync.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ServerState {
    public enum MaintenanceOperation {
        NONE,
        SNAPSHOT,
        DELTA,
        PUBLISH,
        AUTO_PUBLISH
    }

    public static final ChangedRegionTracker CHANGED_REGIONS = new ChangedRegionTracker();
    public static final AtomicBoolean SNAPSHOT_RUNNING = new AtomicBoolean(false);
    public static final AtomicBoolean DELTA_RUNNING = new AtomicBoolean(false);
    public static final AtomicBoolean PUBLISH_RUNNING = new AtomicBoolean(false);
    public static final MaintenanceProgress MAINTENANCE_PROGRESS = new MaintenanceProgress();

    private static final AtomicReference<MaintenanceOperation> ACTIVE_MAINTENANCE =
            new AtomicReference<>(MaintenanceOperation.NONE);

    public static final ExecutorService MAINTENANCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GabConDHSync-Maintenance");
        thread.setDaemon(true);
        return thread;
    });

    public static final ScheduledExecutorService AUTO_PUBLISH_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "GabConDHSync-AutoPublish");
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

    public static boolean tryBegin(MaintenanceOperation operation) {
        if (operation == null || operation == MaintenanceOperation.NONE) {
            throw new IllegalArgumentException("A concrete maintenance operation is required");
        }
        return ACTIVE_MAINTENANCE.compareAndSet(MaintenanceOperation.NONE, operation);
    }

    public static void finish(MaintenanceOperation operation) {
        ACTIVE_MAINTENANCE.compareAndSet(operation, MaintenanceOperation.NONE);
    }

    public static MaintenanceOperation activeOperation() {
        return ACTIVE_MAINTENANCE.get();
    }

    public static boolean maintenanceRunning() {
        return ACTIVE_MAINTENANCE.get() != MaintenanceOperation.NONE;
    }

    private ServerState() {}
}
