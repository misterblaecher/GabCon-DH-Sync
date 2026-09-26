package be.gabcon.dhsync.server;

import java.time.Instant;
import java.util.Collections;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ChangedRegionTracker {
    public record Capture(long watermark, int pendingCount, Instant lastChange) {}

    private final ConcurrentSkipListMap<RegionKey, Long> pending = new ConcurrentSkipListMap<>();
    private final AtomicLong sequence;
    private final AtomicReference<Instant> lastChange = new AtomicReference<>();

    public ChangedRegionTracker() {
        this(initialSequence());
    }

    ChangedRegionTracker(long initialSequence) {
        this.sequence = new AtomicLong(Math.max(0L, initialSequence));
    }

    public void markChunkSaved(String dimension, int chunkX, int chunkZ) {
        long next = sequence.incrementAndGet();
        pending.put(RegionKey.fromChunk(dimension, chunkX, chunkZ), next);
        lastChange.set(Instant.now());
    }

    public int pendingCount() {
        return pending.size();
    }

    public NavigableSet<RegionKey> snapshot() {
        return Collections.unmodifiableNavigableSet(new TreeSet<>(pending.keySet()));
    }

    public Instant lastChange() {
        return lastChange.get();
    }

    public Capture capture() {
        return new Capture(sequence.get(), pending.size(), lastChange.get());
    }

    /**
     * Acknowledge only changes that existed at or before the captured watermark.
     * If the same region is saved again while publication is running, its newer
     * sequence remains pending.
     */
    public void acknowledgeThrough(long watermark) {
        pending.entrySet().removeIf(entry -> entry.getValue() <= watermark);
        if (pending.isEmpty()) lastChange.set(null);
    }

    /**
     * Used when resuming a persisted publication after a server restart so newly
     * observed chunk saves always receive sequence numbers newer than the persisted
     * capture watermark.
     */
    public void ensureSequenceAtLeast(long floor) {
        sequence.accumulateAndGet(Math.max(0L, floor), Math::max);
    }

    public void clear() {
        pending.clear();
        lastChange.set(null);
    }

    private static long initialSequence() {
        long millis = System.currentTimeMillis();
        if (millis > Long.MAX_VALUE / 1_000_000L) return millis;
        return millis * 1_000_000L;
    }
}
