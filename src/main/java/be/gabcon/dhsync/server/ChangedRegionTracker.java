package be.gabcon.dhsync.server;

import java.time.Instant;
import java.util.Collections;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;

public final class ChangedRegionTracker {
    private final ConcurrentSkipListSet<RegionKey> pending = new ConcurrentSkipListSet<>();
    private final AtomicReference<Instant> lastChange = new AtomicReference<>();

    public void markChunkSaved(String dimension, int chunkX, int chunkZ) {
        pending.add(RegionKey.fromChunk(dimension, chunkX, chunkZ));
        lastChange.set(Instant.now());
    }

    public int pendingCount() { return pending.size(); }
    public NavigableSet<RegionKey> snapshot() { return Collections.unmodifiableNavigableSet(new TreeSet<>(pending)); }
    public Instant lastChange() { return lastChange.get(); }
    public void clear() { pending.clear(); }
}
