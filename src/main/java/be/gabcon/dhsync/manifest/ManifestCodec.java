package be.gabcon.dhsync.manifest;

import be.gabcon.dhsync.util.SafePaths;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ManifestCodec {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Gson GSON = new Gson();

    public static SyncManifest parseAndValidate(String json, String expectedWorldId, long maxAssetBytes) throws ManifestException {
        final SyncManifest manifest;
        try {
            manifest = GSON.fromJson(json, SyncManifest.class);
        } catch (JsonParseException e) {
            throw new ManifestException("Invalid JSON", e);
        }
        validate(manifest, expectedWorldId, maxAssetBytes);
        return manifest;
    }

    public static void validate(SyncManifest m, String expectedWorldId, long maxAssetBytes) throws ManifestException {
        if (m == null) throw new ManifestException("Manifest is null");
        if (m.schemaVersion() != SUPPORTED_SCHEMA_VERSION) throw new ManifestException("Unsupported schemaVersion: " + m.schemaVersion());
        if (m.worldId() == null || m.worldId().isBlank()) throw new ManifestException("worldId is required");
        if (expectedWorldId != null && !expectedWorldId.equals(m.worldId())) throw new ManifestException("worldId mismatch");
        if (m.baseVersion() < 0 || m.latestDelta() < 0) throw new ManifestException("Negative version");
        if (m.dimensions() == null || m.dimensions().isEmpty()) throw new ManifestException("No dimensions");

        for (Map.Entry<String, DimensionManifest> entry : m.dimensions().entrySet()) {
            String dimension = entry.getKey();
            DimensionManifest dm = entry.getValue();
            if (dimension == null || dimension.isBlank() || dm == null) throw new ManifestException("Invalid dimension entry");
            if (dm.bootstrap() != null) validateAsset(dm.bootstrap(), dimension, maxAssetBytes, m.baseVersion());
            if (dm.deltas() == null) throw new ManifestException("deltas must not be null for " + dimension);
            Set<Long> seen = new HashSet<>();
            long previous = -1;
            for (Asset asset : dm.deltas()) {
                validateAsset(asset, dimension, maxAssetBytes, m.baseVersion());
                if (!seen.add(asset.version())) throw new ManifestException("Duplicate delta version " + asset.version());
                if (asset.version() <= previous) throw new ManifestException("Deltas must be strictly increasing");
                previous = asset.version();
            }
        }
    }

    private static void validateAsset(Asset asset, String dimension, long maxAssetBytes, long baseVersion) throws ManifestException {
        if (asset == null) throw new ManifestException("Null asset");
        if (asset.version() < 0 || asset.size() < 0 || asset.size() > maxAssetBytes) throw new ManifestException("Invalid asset size/version: " + asset.fileName());
        if (!dimension.equals(asset.dimension())) throw new ManifestException("Asset dimension mismatch: " + asset.fileName());
        if (asset.requiresBaseVersion() != baseVersion) throw new ManifestException("Asset base dependency mismatch: " + asset.fileName());
        try { SafePaths.resolveAsset(java.nio.file.Path.of("safe-root"), asset.fileName()); }
        catch (RuntimeException e) { throw new ManifestException("Unsafe file name: " + asset.fileName(), e); }
        if (asset.sha256() == null || !asset.sha256().toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) throw new ManifestException("Invalid SHA-256: " + asset.fileName());
        if (asset.url() == null || asset.url().isBlank()) {
            throw new ManifestException("Asset URL is required: " + asset.fileName());
        }
        try {
            URI uri = URI.create(asset.url());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new ManifestException("Only HTTPS asset URLs are allowed");
        } catch (IllegalArgumentException e) {
            throw new ManifestException("Invalid asset URL", e);
        }
    }

    private ManifestCodec() {}
}
