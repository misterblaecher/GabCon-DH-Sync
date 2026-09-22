package be.gabcon.dhsync.manifest;

import java.util.List;

public record DimensionManifest(Asset bootstrap, List<Asset> deltas) {}
