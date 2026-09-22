package be.gabcon.dhsync.manifest;

import java.util.Map;

public record SyncManifest(
        int schemaVersion,
        String worldId,
        String minecraftVersion,
        String neoforgeVersion,
        String distantHorizonsVersion,
        long baseVersion,
        long latestDelta,
        Map<String, DimensionManifest> dimensions
) {}
