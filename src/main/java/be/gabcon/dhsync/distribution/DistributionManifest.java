package be.gabcon.dhsync.distribution;

import java.util.List;
import java.util.Map;

public record DistributionManifest(
        int schemaVersion,
        String worldId,
        String minecraftVersion,
        String neoforgeVersion,
        String distantHorizonsVersion,
        String distantHorizonsApiVersion,
        String releaseTag,
        Map<String, DimensionDistribution> dimensions
) {
    public record DimensionDistribution(
            BootstrapAsset bootstrap,
            List<DeltaAsset> deltas
    ) {}

    public record BootstrapAsset(
            String databaseFileName,
            long totalSize,
            String databaseSha256,
            List<PartAsset> parts
    ) {}

    public record PartAsset(
            String fileName,
            long size,
            String sha256,
            String url
    ) {}

    public record DeltaAsset(
            String fileName,
            long size,
            String sha256,
            String url,
            String oldServerBaselineSha256,
            String newServerBaselineSha256
    ) {}
}
