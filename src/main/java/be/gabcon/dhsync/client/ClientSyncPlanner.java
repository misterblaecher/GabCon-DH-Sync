package be.gabcon.dhsync.client;

import be.gabcon.dhsync.distribution.DistributionManifest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ClientSyncPlanner {
    public record DimensionPlan(
            String dimension,
            Path databasePath,
            DistributionManifest.BootstrapAsset bootstrap,
            List<DistributionManifest.DeltaAsset> deltas,
            String startingBaselineSha256,
            String targetBaselineSha256
    ) {
        public boolean needsWork() {
            return bootstrap != null || !deltas.isEmpty();
        }
    }

    public record SyncPlan(List<DimensionPlan> dimensions) {
        public boolean needsWork() {
            return dimensions.stream().anyMatch(DimensionPlan::needsWork);
        }
    }

    public static SyncPlan plan(
            DistributionManifest manifest,
            ClientSyncState.ServerProfile profile
    ) {
        if (!manifest.worldId().equals(profile.worldId())) {
            throw new IllegalStateException("worldId mismatch: profile=" + profile.worldId()
                    + ", manifest=" + manifest.worldId());
        }

        List<DimensionPlan> plans = new ArrayList<>();
        for (Map.Entry<String, ClientSyncState.DimensionState> entry : profile.dimensions().entrySet()) {
            String dimension = entry.getKey();
            ClientSyncState.DimensionState local = entry.getValue();
            DistributionManifest.DimensionDistribution remote = manifest.dimensions().get(dimension);
            if (remote == null) {
                throw new IllegalStateException("Manifest does not contain registered dimension " + dimension);
            }

            String baseline = normalizeSha(local.serverBaselineSha256());
            DistributionManifest.BootstrapAsset bootstrap = null;
            List<DistributionManifest.DeltaAsset> selected = new ArrayList<>();

            if (baseline == null) {
                bootstrap = remote.bootstrap();
                baseline = bootstrap.databaseSha256();
                selected.addAll(remote.deltas());
            } else {
                String latest = latestBaseline(remote);
                if (!baseline.equalsIgnoreCase(latest)) {
                    int start = findDeltaStartingAt(remote.deltas(), baseline);
                    if (start < 0) {
                        // A stale or unknown local chain is never merged blindly. Rebase from trusted bootstrap.
                        bootstrap = remote.bootstrap();
                        baseline = bootstrap.databaseSha256();
                        selected.addAll(remote.deltas());
                    } else {
                        selected.addAll(remote.deltas().subList(start, remote.deltas().size()));
                    }
                }
            }

            String target = selected.isEmpty()
                    ? baseline
                    : selected.getLast().newServerBaselineSha256();

            plans.add(new DimensionPlan(
                    dimension,
                    Path.of(local.databasePath()).toAbsolutePath().normalize(),
                    bootstrap,
                    List.copyOf(selected),
                    normalizeSha(local.serverBaselineSha256()),
                    target
            ));
        }
        return new SyncPlan(List.copyOf(plans));
    }

    public static String latestBaseline(DistributionManifest.DimensionDistribution remote) {
        if (remote.deltas() == null || remote.deltas().isEmpty()) return remote.bootstrap().databaseSha256();
        return remote.deltas().getLast().newServerBaselineSha256();
    }

    private static int findDeltaStartingAt(List<DistributionManifest.DeltaAsset> deltas, String baseline) {
        for (int i = 0; i < deltas.size(); i++) {
            if (deltas.get(i).oldServerBaselineSha256().equalsIgnoreCase(baseline)) return i;
        }
        return -1;
    }

    private static String normalizeSha(String value) {
        return value == null || value.isBlank() ? null : value.toLowerCase();
    }

    private ClientSyncPlanner() {}
}
