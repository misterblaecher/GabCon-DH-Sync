package be.gabcon.dhsync.distribution;

import be.gabcon.dhsync.manifest.ManifestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DistributionManifestCodecTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @Test
    void acceptsSegmentedBootstrapAndContinuousDeltaChain() {
        DistributionManifest manifest = manifest(List.of(
                new DistributionManifest.DeltaAsset(
                        "delta_ab.sqlite", 1234, C,
                        "https://example.invalid/delta_ab.sqlite", A, B)
        ));
        String json = DistributionManifestCodec.toJson(manifest, 2L * 1024 * 1024 * 1024);
        DistributionManifest parsed = DistributionManifestCodec.parse(json, 2L * 1024 * 1024 * 1024);
        assertEquals("gabcon-main", parsed.worldId());
        assertEquals(B, parsed.dimensions().get("minecraft:overworld")
                .deltas().getFirst().newServerBaselineSha256());
    }

    @Test
    void rejectsBrokenDeltaChain() {
        DistributionManifest bad = manifest(List.of(
                new DistributionManifest.DeltaAsset(
                        "delta.sqlite", 1, C,
                        "https://example.invalid/delta.sqlite", B, C)
        ));
        assertThrows(ManifestException.class,
                () -> DistributionManifestCodec.validate(bad, 2L * 1024 * 1024 * 1024));
    }

    @Test
    void rejectsDuplicateNamesAcrossDimensions() {
        DistributionManifest.BootstrapAsset bootstrap = bootstrap("shared.part", A);
        DistributionManifest bad = new DistributionManifest(
                2, "gabcon-main", "1.21.1", "21.1.251", "3.3.2", "7.2.0",
                "gabcon-data-gabcon-main",
                Map.of(
                        "minecraft:overworld", new DistributionManifest.DimensionDistribution(bootstrap, List.of()),
                        "minecraft:the_nether", new DistributionManifest.DimensionDistribution(bootstrap, List.of())
                )
        );
        assertThrows(ManifestException.class,
                () -> DistributionManifestCodec.validate(bad, 2L * 1024 * 1024 * 1024));
    }

    private static DistributionManifest manifest(List<DistributionManifest.DeltaAsset> deltas) {
        return new DistributionManifest(
                2, "gabcon-main", "1.21.1", "21.1.251", "3.3.2", "7.2.0",
                "gabcon-data-gabcon-main",
                Map.of("minecraft:overworld",
                        new DistributionManifest.DimensionDistribution(bootstrap("bootstrap.part", A), deltas))
        );
    }

    private static DistributionManifest.BootstrapAsset bootstrap(String name, String sha) {
        return new DistributionManifest.BootstrapAsset(
                "minecraft_overworld.sqlite", 100, sha,
                List.of(new DistributionManifest.PartAsset(
                        name, 100, B, "https://example.invalid/" + name))
        );
    }
}
