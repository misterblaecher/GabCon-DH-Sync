package be.gabcon.dhsync.distribution;

import be.gabcon.dhsync.manifest.ManifestException;
import be.gabcon.dhsync.util.SafePaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DistributionManifestCodec {
    public static final int SCHEMA_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static DistributionManifest parse(String json, long maxAssetBytes) {
        DistributionManifest manifest;
        try {
            manifest = GSON.fromJson(json, DistributionManifest.class);
        } catch (RuntimeException e) {
            throw new ManifestException("Invalid distribution manifest JSON", e);
        }
        validate(manifest, maxAssetBytes);
        return manifest;
    }

    public static String toJson(DistributionManifest manifest, long maxAssetBytes) {
        validate(manifest, maxAssetBytes);
        return GSON.toJson(manifest);
    }

    public static void validate(DistributionManifest manifest, long maxAssetBytes) {
        if (manifest == null) throw new ManifestException("Manifest is missing");
        if (manifest.schemaVersion() != SCHEMA_VERSION) {
            throw new ManifestException("Unsupported distribution schema: " + manifest.schemaVersion());
        }
        requireText(manifest.worldId(), "worldId");
        requireText(manifest.minecraftVersion(), "minecraftVersion");
        requireText(manifest.neoforgeVersion(), "neoforgeVersion");
        requireText(manifest.distantHorizonsVersion(), "distantHorizonsVersion");
        requireText(manifest.distantHorizonsApiVersion(), "distantHorizonsApiVersion");
        requireText(manifest.releaseTag(), "releaseTag");
        if (manifest.dimensions() == null || manifest.dimensions().isEmpty()) {
            throw new ManifestException("Manifest dimensions are missing");
        }

        for (Map.Entry<String, DistributionManifest.DimensionDistribution> entry : manifest.dimensions().entrySet()) {
            String dimension = entry.getKey();
            requireText(dimension, "dimension");
            DistributionManifest.DimensionDistribution dist = entry.getValue();
            if (dist == null) throw new ManifestException("Missing dimension distribution: " + dimension);
            DistributionManifest.BootstrapAsset bootstrap = dist.bootstrap();
            if (bootstrap == null) throw new ManifestException("Missing bootstrap for " + dimension);
            validateBootstrap(bootstrap, maxAssetBytes);

            String expectedBaseline = bootstrap.databaseSha256();
            Set<String> names = new HashSet<>();
            for (DistributionManifest.PartAsset part : bootstrap.parts()) {
                if (!names.add(part.fileName())) throw new ManifestException("Duplicate asset filename: " + part.fileName());
            }

            List<DistributionManifest.DeltaAsset> deltas = dist.deltas() == null ? List.of() : dist.deltas();
            for (DistributionManifest.DeltaAsset delta : deltas) {
                validateDelta(delta, maxAssetBytes);
                if (!names.add(delta.fileName())) throw new ManifestException("Duplicate asset filename: " + delta.fileName());
                if (!expectedBaseline.equalsIgnoreCase(delta.oldServerBaselineSha256())) {
                    throw new ManifestException("Broken delta chain for " + dimension + ": expected " + expectedBaseline
                            + " but got " + delta.oldServerBaselineSha256());
                }
                expectedBaseline = delta.newServerBaselineSha256();
            }
        }
    }

    private static void validateBootstrap(DistributionManifest.BootstrapAsset bootstrap, long maxAssetBytes) {
        safeFileName(bootstrap.databaseFileName());
        requireSha(bootstrap.databaseSha256(), "bootstrap databaseSha256");
        if (bootstrap.totalSize() <= 0) throw new ManifestException("Invalid bootstrap totalSize");
        if (bootstrap.parts() == null || bootstrap.parts().isEmpty()) {
            throw new ManifestException("Bootstrap parts are missing");
        }
        long sum = 0;
        for (DistributionManifest.PartAsset part : bootstrap.parts()) {
            if (part == null) throw new ManifestException("Null bootstrap part");
            safeFileName(part.fileName());
            validateSize(part.size(), maxAssetBytes, "bootstrap part");
            requireSha(part.sha256(), "bootstrap part sha256");
            requireHttps(part.url());
            sum = Math.addExact(sum, part.size());
        }
        if (sum != bootstrap.totalSize()) {
            throw new ManifestException("Bootstrap parts total " + sum + " != " + bootstrap.totalSize());
        }
    }

    private static void validateDelta(DistributionManifest.DeltaAsset delta, long maxAssetBytes) {
        if (delta == null) throw new ManifestException("Null delta");
        safeFileName(delta.fileName());
        validateSize(delta.size(), maxAssetBytes, "delta");
        requireSha(delta.sha256(), "delta sha256");
        requireSha(delta.oldServerBaselineSha256(), "delta old baseline");
        requireSha(delta.newServerBaselineSha256(), "delta new baseline");
        requireHttps(delta.url());
    }

    private static void validateSize(long size, long maxAssetBytes, String label) {
        if (size <= 0 || size > maxAssetBytes) {
            throw new ManifestException("Invalid " + label + " size: " + size);
        }
    }

    private static void requireHttps(String value) {
        requireText(value, "url");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new ManifestException("Invalid URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ManifestException("HTTPS URL required: " + value);
        }
    }

    private static void safeFileName(String fileName) {
        requireText(fileName, "fileName");
        try {
            Path root = Path.of(".").toAbsolutePath().normalize();
            SafePaths.resolveAsset(root, fileName);
        } catch (RuntimeException e) {
            throw new ManifestException("Unsafe filename: " + fileName, e);
        }
    }

    private static void requireSha(String value, String field) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new ManifestException("Invalid " + field);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new ManifestException("Missing " + field);
    }

    private DistributionManifestCodec() {}
}
