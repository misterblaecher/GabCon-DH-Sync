package be.gabcon.dhsync.server;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ChangedRegionTracker {
    public record Capture(long watermark, int pendingCount, Instant oldestPendingChange) {}

    private record PendingRegion(long sequence, Instant firstSeen) {}

    private final NavigableMap<RegionKey, PendingRegion> pending = new TreeMap<>();
    private final Clock clock;
    private long sequence;

    public ChangedRegionTracker() {
        this(initialSequence(), Clock.systemUTC());
    }

    ChangedRegionTracker(long initialSequence) {
        this(initialSequence, Clock.systemUTC());
    }

    ChangedRegionTracker(long initialSequence, Clock clock) {
        this.sequence = Math.max(0L, initialSequence);
        this.clock = clock;
    }

    /**
     * Sequence allocation and pending insertion are synchronized with capture and
     * acknowledgement so a save can never receive a captured watermark before it
     * becomes visible in the pending map.
     */
    public synchronized boolean markChunkSaved(String dimension, int chunkX, int chunkZ) {
        return markChunkSavedAt(dimension, chunkX, chunkZ, clock.instant());
    }

    public synchronized boolean markChunkSavedAt(
            String dimension,
            int chunkX,
            int chunkZ,
            Instant observedAt
    ) {
        boolean wasClean = pending.isEmpty();
        RegionKey key = RegionKey.fromChunk(dimension, chunkX, chunkZ);
        PendingRegion previous = pending.get(key);
        Instant candidate = observedAt == null ? clock.instant() : observedAt;
        Instant firstSeen = previous == null
                ? candidate
                : (candidate.isBefore(previous.firstSeen()) ? candidate : previous.firstSeen());
        long next = ++sequence;
        pending.put(key, new PendingRegion(next, firstSeen));
        return wasClean;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized NavigableSet<RegionKey> snapshot() {
        return Collections.unmodifiableNavigableSet(new TreeSet<>(pending.keySet()));
    }

    public synchronized Instant oldestPendingChange() {
        return pending.values().stream()
                .map(PendingRegion::firstSeen)
                .min(Instant::compareTo)
                .orElse(null);
    }

    public synchronized Capture capture() {
        return new Capture(sequence, pending.size(), oldestPendingChange());
    }

    /**
     * Acknowledge only changes that existed at or before the captured watermark.
     * If the same region is saved again while publication is running, its newer
     * sequence remains pending.
     */
    public synchronized void acknowledgeThrough(long watermark) {
        pending.entrySet().removeIf(entry -> entry.getValue().sequence() <= watermark);
    }

    /**
     * Used when resuming a persisted publication after a server restart so newly
     * observed chunk saves always receive sequence numbers newer than the persisted
     * capture watermark.
     */
    public synchronized void ensureSequenceAtLeast(long floor) {
        sequence = Math.max(sequence, Math.max(0L, floor));
    }

    public synchronized void clear() {
        pending.clear();
    }

    private static long initialSequence() {
        long millis = System.currentTimeMillis();
        if (millis > Long.MAX_VALUE / 1_000_000L) return millis;
        return millis * 1_000_000L;
    }
}
