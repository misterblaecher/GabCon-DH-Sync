package be.gabcon.dhsync.server;

import be.gabcon.dhsync.distribution.DistributionManifest;
import be.gabcon.dhsync.distribution.DistributionManifestCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PublishedBaselineResolver {
    private static final Gson GSON = new GsonBuilder().create();

    public static Optional<Path> resolvePublicationBase(String worldId, long maxAssetBytes) throws Exception {
        return resolvePublicationBase(Path.of("gabcondhsync"), worldId, maxAssetBytes);
    }

    static Optional<Path> resolvePublicationBase(Path root, String worldId, long maxAssetBytes) throws Exception {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<SnapshotRef> snapshots = snapshots(normalizedRoot, worldId);
        Path manifestPath = normalizedRoot.resolve("publish")
                .resolve(DhSnapshotService.safeStem(worldId))
                .resolve("manifest.json")
                .normalize();

        if (!Files.isRegularFile(manifestPath)) {
            return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.getFirst().directory());
        }

        DistributionManifest manifest =
                DistributionManifestCodec.parse(Files.readString(manifestPath), maxAssetBytes);
        if (!worldId.equals(manifest.worldId())) {
            throw new IOException("Published manifest worldId mismatch");
        }

        Map<String, String> expected = new HashMap<>();
        for (Map.Entry<String, DistributionManifest.DimensionDistribution> entry : manifest.dimensions().entrySet()) {
            expected.put(entry.getKey(), latestBaseline(entry.getValue()));
        }

        for (int i = snapshots.size() - 1; i >= 0; i--) {
            SnapshotRef snapshot = snapshots.get(i);
            Map<String, String> actual = new HashMap<>();
            for (DhSnapshotService.SnapshotFile file : snapshot.manifest().files()) {
                actual.put(file.dimension(), file.sha256());
            }
            if (!actual.keySet().equals(expected.keySet())) continue;

            boolean matches = true;
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                String sha = actual.get(entry.getKey());
                if (sha == null || !sha.equalsIgnoreCase(entry.getValue())) {
                    matches = false;
                    break;
                }
            }
            if (matches) return Optional.of(snapshot.directory());
        }

        throw new IOException("No local snapshot matches the currently published baseline for " + worldId);
    }

    public static void requirePublishedSnapshot(String worldId, long maxAssetBytes, Path expectedSnapshot)
            throws Exception {
        Optional<Path> actual = resolvePublicationBase(worldId, maxAssetBytes);
        if (actual.isEmpty()) {
            throw new IOException("Published manifest has no resolvable snapshot for " + worldId);
        }
        Path expected = expectedSnapshot.toAbsolutePath().normalize();
        if (!actual.get().equals(expected)) {
            throw new IOException("Published baseline did not advance to expected snapshot: expected="
                    + expected + ", actual=" + actual.get());
        }
    }

    private static List<SnapshotRef> snapshots(Path root, String worldId) throws Exception {
        Path snapshotRoot = root.resolve("snapshots").resolve(DhSnapshotService.safeStem(worldId)).normalize();
        if (!Files.isDirectory(snapshotRoot)) return List.of();

        List<SnapshotRef> refs = new ArrayList<>();
        try (var stream = Files.list(snapshotRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path json = dir.resolve("snapshot.json");
                if (!Files.isRegularFile(json)) continue;
                DhSnapshotService.SnapshotManifest manifest;
                try {
                    manifest = GSON.fromJson(Files.readString(json), DhSnapshotService.SnapshotManifest.class);
                    if (manifest == null || !worldId.equals(manifest.worldId()) || manifest.files() == null) continue;
                    Instant.parse(manifest.createdAtUtc());
                } catch (RuntimeException e) {
                    continue;
                }
                refs.add(new SnapshotRef(dir.toAbsolutePath().normalize(), manifest));
            }
        }
        refs.sort(Comparator.comparing(ref -> Instant.parse(ref.manifest().createdAtUtc())));
        return List.copyOf(refs);
    }

    private static String latestBaseline(DistributionManifest.DimensionDistribution dimension) {
        if (dimension.deltas().isEmpty()) return dimension.bootstrap().databaseSha256();
        return dimension.deltas().getLast().newServerBaselineSha256();
    }

    private record SnapshotRef(Path directory, DhSnapshotService.SnapshotManifest manifest) {}

    private PublishedBaselineResolver() {}
}
