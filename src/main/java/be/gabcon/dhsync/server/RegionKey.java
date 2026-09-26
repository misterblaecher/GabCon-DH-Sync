package be.gabcon.dhsync.server;

public record RegionKey(String dimension, int x, int z) implements Comparable<RegionKey> {
    public static RegionKey fromChunk(String dimension, int chunkX, int chunkZ) {
        return new RegionKey(dimension, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
    }
    @Override public int compareTo(RegionKey other) {
        int d = dimension.compareTo(other.dimension); if (d != 0) return d;
        int xCmp = Integer.compare(x, other.x); return xCmp != 0 ? xCmp : Integer.compare(z, other.z);
    }
}
