package be.gabcon.dhsync.server;

import be.gabcon.dhsync.GabConDhSync;

import java.util.Locale;

public final class MaintenanceProgress {
    private static final int BAR_WIDTH = 20;

    private record State(
            boolean active,
            String operation,
            String stage,
            String detail,
            int percent,
            long startedAtMillis,
            long updatedAtMillis
    ) {
        static State idle() {
            long now = System.currentTimeMillis();
            return new State(false, "idle", "idle", "", 0, now, now);
        }
    }

    private volatile State state = State.idle();
    private int lastLoggedPercent = -1;
    private String lastLoggedStage = "";

    public synchronized void start(String operation, String stage, String detail) {
        long now = System.currentTimeMillis();
        state = new State(true, safe(operation, "maintenance"), safe(stage, "starting"),
                safe(detail, ""), 0, now, now);
        lastLoggedPercent = -1;
        lastLoggedStage = "";
        logNow();
    }

    public synchronized void update(String stage, String detail, long current, long total) {
        if (!state.active()) return;
        int percent = total > 0
                ? (int) Math.min(99L, Math.max(0L, current) * 100L / Math.max(1L, total))
                : state.percent();
        set(stage, detail, percent);
    }

    public synchronized void updatePercent(String stage, String detail, int percent) {
        if (!state.active()) return;
        set(stage, detail, Math.max(0, Math.min(99, percent)));
    }

    public synchronized void complete(String detail) {
        if (!state.active()) return;
        long now = System.currentTimeMillis();
        state = new State(true, state.operation(), "complete", safe(detail, ""), 100,
                state.startedAtMillis(), now);
        logNow();
    }

    public synchronized void fail(String detail) {
        if (!state.active()) return;
        long now = System.currentTimeMillis();
        state = new State(true, state.operation(), "failed", safe(detail, ""), state.percent(),
                state.startedAtMillis(), now);
        GabConDhSync.LOGGER.warn("[GabConDHSync] {}", summary());
    }

    public synchronized void clear() {
        state = State.idle();
        lastLoggedPercent = -1;
        lastLoggedStage = "";
    }

    public void logHeartbeat() {
        if (state.active()) {
            GabConDhSync.LOGGER.info("[GabConDHSync] {}", summary());
        }
    }

    public String summary() {
        State current = state;
        if (!current.active()) return "maintenance=idle";

        long elapsed = Math.max(0L, System.currentTimeMillis() - current.startedAtMillis());
        return "maintenance=" + current.operation()
                + " " + bar(current.percent())
                + " " + current.percent() + "%"
                + ", stage=" + current.stage()
                + (current.detail().isBlank() ? "" : ", " + current.detail())
                + ", elapsed=" + formatElapsed(elapsed);
    }

    public boolean active() {
        return state.active();
    }

    public static String formatBytes(long bytes) {
        double value = Math.max(0L, bytes);
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return unit == 0
                ? String.format(Locale.ROOT, "%.0f %s", value, units[unit])
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private synchronized void set(String stage, String detail, int percent) {
        long now = System.currentTimeMillis();
        String safeStage = safe(stage, state.stage());
        state = new State(true, state.operation(), safeStage, safe(detail, ""),
                percent, state.startedAtMillis(), now);

        boolean stageChanged = !safeStage.equals(lastLoggedStage);
        boolean meaningfulAdvance = lastLoggedPercent < 0 || percent >= lastLoggedPercent + 10;
        if (stageChanged || meaningfulAdvance) logNow();
    }

    private void logNow() {
        lastLoggedPercent = state.percent();
        lastLoggedStage = state.stage();
        GabConDhSync.LOGGER.info("[GabConDHSync] {}", summary());
    }

    private static String bar(int percent) {
        int filled = Math.max(0, Math.min(BAR_WIDTH, percent * BAR_WIDTH / 100));
        return "[" + "#".repeat(filled) + "-".repeat(BAR_WIDTH - filled) + "]";
    }

    private static String formatElapsed(long millis) {
        long seconds = millis / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
