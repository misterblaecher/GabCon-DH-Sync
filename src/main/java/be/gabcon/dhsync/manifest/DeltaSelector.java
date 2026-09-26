package be.gabcon.dhsync.manifest;

import java.util.ArrayList;
import java.util.List;

public final class DeltaSelector {
    public record ClientState(long baseVersion, long lastDelta) {}
    public record Selection(boolean bootstrapRequired, List<Asset> assets) {}

    public static Selection select(DimensionManifest dimension, long manifestBaseVersion, long latestDelta, ClientState state) {
        boolean bootstrap = state.baseVersion() != manifestBaseVersion;
        List<Asset> selected = new ArrayList<>();
        if (bootstrap && dimension.bootstrap() != null) selected.add(dimension.bootstrap());
        long fromDelta = bootstrap ? 0 : state.lastDelta();
        for (Asset delta : dimension.deltas()) {
            if (delta.version() > fromDelta && delta.version() <= latestDelta) selected.add(delta);
        }
        return new Selection(bootstrap, List.copyOf(selected));
    }

    private DeltaSelector() {}
}
