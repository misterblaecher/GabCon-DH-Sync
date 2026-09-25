package be.gabcon.dhsync.client;

import be.gabcon.dhsync.distribution.DistributionManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientSyncPlannerTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @Test
    void unknownClientRequiresBootstrapAndAllDeltas() {
        var plan = ClientSyncPlanner.plan(manifest(), profile(null));
        var dimension = plan.dimensions().getFirst();
        assertNotNull(dimension.bootstrap());
        assertEquals(2, dimension.deltas().size());
        assertEquals(C, dimension.targetBaselineSha256());
    }

    @Test
    void knownMiddleBaselineDownloadsOnlyRemainingDelta() {
        var dimension = ClientSyncPlanner.plan(manifest(), profile(B)).dimensions().getFirst();
        assertNull(dimension.bootstrap());
        assertEquals(1, dimension.deltas().size());
        assertEquals(B, dimension.deltas().getFirst().oldServerBaselineSha256());
        assertEquals(C, dimension.targetBaselineSha256());
    }

    @Test
    void latestBaselineNeedsNoWork() {
        var plan = ClientSyncPlanner.plan(manifest(), profile(C));
        assertFalse(plan.needsWork());
    }

    @Test
    void staleUnknownBaselineRebasesFromBootstrap() {
        String stale = "d".repeat(64);
        var dimension = ClientSyncPlanner.plan(manifest(), profile(stale)).dimensions().getFirst();
        assertNotNull(dimension.bootstrap());
        assertEquals(2, dimension.deltas().size());
    }

    private static ClientSyncState.ServerProfile profile(String baseline) {
        return new ClientSyncState.ServerProfile(
                "example.org:25565", "gabcon-main",
                "https://example.invalid/manifest.json",
                Map.of("minecraft:overworld",
                        new ClientSyncState.DimensionState("/tmp/DistantHorizons.sqlite", baseline))
        );
    }

    private static DistributionManifest manifest() {
        var bootstrap = new DistributionManifest.BootstrapAsset(
                "minecraft_overworld.sqlite", 10, A,
                List.of(new DistributionManifest.PartAsset(
                        "bootstrap.part", 10, "f".repeat(64),
                        "https://example.invalid/bootstrap.part"))
        );
        var deltas = List.of(
                new DistributionManifest.DeltaAsset(
                        "delta_ab.sqlite", 1, "1".repeat(64),
                        "https://example.invalid/delta_ab.sqlite", A, B),
                new DistributionManifest.DeltaAsset(
                        "delta_bc.sqlite", 1, "2".repeat(64),
                        "https://example.invalid/delta_bc.sqlite", B, C)
        );
        return new DistributionManifest(
                2, "gabcon-main", "1.21.1", "21.1.251", "3.3.2", "7.2.0",
                "gabcon-data-gabcon-main",
                Map.of("minecraft:overworld",
                        new DistributionManifest.DimensionDistribution(bootstrap, deltas))
        );
    }
}
