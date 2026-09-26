package be.gabcon.dhsync.publish;

import be.gabcon.dhsync.GabConDhSync;
import be.gabcon.dhsync.config.ServerConfig;
import be.gabcon.dhsync.distribution.DistributionManifest;
import be.gabcon.dhsync.distribution.DistributionManifestCodec;
import be.gabcon.dhsync.server.DhDeltaBuilder;
import be.gabcon.dhsync.server.DhSnapshotService;
import be.gabcon.dhsync.server.ServerState;
import be.gabcon.dhsync.sync.DhDeltaApplier;
import be.gabcon.dhsync.util.Hashes;
import be.gabcon.dhsync.util.SafePaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServerDistributionPublisher {
    public static final String TOKEN_ENV = "GABCON_DH_GITHUB_TOKEN";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record PublicationResult(
            boolean bootstrapCreated,
            int uploadedAssets,
            int reusedAssets,
            Path localManifest,
            DistributionManifest manifest
    ) {}

    private record SnapshotRef(Path directory, DhSnapshotService.SnapshotManifest manifest) {}
    private record DeltaRef(Path directory, DhDeltaBuilder.DeltaManifest manifest) {}
    private record Counters(int uploaded, int reused) {
        Counters add(Counters other) { return new Counters(uploaded + other.uploaded, reused + other.reused); }
    }

    public static PublicationResult publish() throws Exception {
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(TOKEN_ENV + " environment variable is not set");
        }

        String repository = ServerConfig.REPOSITORY.get();
        String worldId = ServerConfig.WORLD_ID.get();
        String tag = ServerConfig.RELEASE_TAG.get();
        long maxAssetBytes = ServerConfig.MAX_DOWNLOAD_BYTES.get();

        GitHubReleaseClient github = new GitHubReleaseClient(repository, token);
        ServerState.MAINTENANCE_PROGRESS.updatePercent(
                "github-release",
                "Checking GitHub Release " + tag,
                5
        );
        GitHubReleaseClient.Release release =
                github.ensureRelease(tag, "GabCon DH data - " + worldId);

        Path publishRoot = Path.of("gabcondhsync", "publish", safeStem(worldId)).toAbsolutePath().normalize();
        Files.createDirectories(publishRoot);
        Path manifestPath = publishRoot.resolve("manifest.json");

        boolean bootstrapCreated = !Files.isRegularFile(manifestPath);
        DistributionManifest manifest;
        Counters counters = new Counters(0, 0);

        if (bootstrapCreated) {
            ServerState.MAINTENANCE_PROGRESS.updatePercent(
                    "bootstrap",
                    "Preparing bootstrap assets from oldest snapshot",
                    10
            );
            SnapshotRef bootstrapSnapshot = oldestSnapshot(worldId);
            BuildBootstrapResult built = buildBootstrap(
                    bootstrapSnapshot, github, release, repository, tag, publishRoot, maxAssetBytes
            );
            manifest = built.manifest();
            counters = counters.add(built.counters());
        } else {
            manifest = DistributionManifestCodec.parse(Files.readString(manifestPath), maxAssetBytes);
            validateLocalPublicationIdentity(manifest, worldId, tag);
        }

        ServerState.MAINTENANCE_PROGRESS.updatePercent(
                "deltas",
                "Checking unpublished delta chain",
                75
        );
        AppendDeltaResult appended = appendAvailableDeltas(
                manifest, github, release, worldId, tag, maxAssetBytes
        );
        manifest = appended.manifest();
        counters = counters.add(appended.counters());

        String json = DistributionManifestCodec.toJson(manifest, maxAssetBytes);
        Path part = manifestPath.resolveSibling("manifest.json.part");
        Files.writeString(part, json);
        Files.move(part, manifestPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Manifest is always replaced last. Clients never see a chain that references assets still uploading.
        ServerState.MAINTENANCE_PROGRESS.updatePercent(
                "manifest",
                "Publishing manifest.json last",
                95
        );
        GitHubReleaseClient.Release refreshed = github.refresh(tag);
        int repairedAssets = repairReferencedAssets(
                manifest,
                github,
                refreshed,
                worldId,
                publishRoot,
                Path.of("gabcondhsync").toAbsolutePath().normalize()
        );
        if (repairedAssets > 0) {
            counters = counters.add(new Counters(repairedAssets, 0));
            refreshed = github.refresh(tag);
        }
        verifyReferencedAssets(manifest, github, refreshed);
        GabConDhSync.LOGGER.info("[GabConDHSync] Publishing manifest.json last after remote asset SHA-256 preflight.");
        github.uploadReplacing(
                refreshed,
                "manifest.json",
                manifestPath,
                "application/json",
                Hashes.sha256(manifestPath)
        );
        GabConDhSync.LOGGER.info("[GabConDHSync] Published manifest.json.");

        return new PublicationResult(
                bootstrapCreated,
                counters.uploaded() + 1,
                counters.reused(),
                manifestPath,
                manifest
        );
    }

    private record BuildBootstrapResult(DistributionManifest manifest, Counters counters) {}

    private static BuildBootstrapResult buildBootstrap(
            SnapshotRef snapshot,
            GitHubReleaseClient github,
            GitHubReleaseClient.Release release,
            String repository,
            String tag,
            Path publishRoot,
            long maxAssetBytes
    ) throws Exception {
        Map<String, DistributionManifest.DimensionDistribution> dimensions = new LinkedHashMap<>();
        Counters counters = new Counters(0, 0);

        Path staging = publishRoot.resolve("staging");
        Files.createDirectories(staging);
        long partBytes = ServerConfig.BOOTSTRAP_PART_BYTES.get();

        for (DhSnapshotService.SnapshotFile file : snapshot.manifest().files()) {
            Path source = SafePaths.resolveAsset(snapshot.directory(), file.fileName());
            if (!Files.isRegularFile(source)) throw new IOException("Bootstrap snapshot DB missing: " + source);
            if (Files.size(source) != file.size()) throw new IOException("Bootstrap snapshot size mismatch: " + source);
            if (!Hashes.sha256(source).equalsIgnoreCase(file.sha256())) {
                throw new IOException("Bootstrap snapshot SHA mismatch: " + source);
            }

            List<DistributionManifest.PartAsset> parts = new ArrayList<>();
            String stem = safeStem(file.dimension());
            String baselineShort = file.sha256().substring(0, 12);
            int partCount = Math.toIntExact((file.size() + partBytes - 1) / partBytes);

            try (InputStream in = Files.newInputStream(source)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int index = 0; index < partCount; index++) {
                    String name = String.format(java.util.Locale.ROOT,
                            "bootstrap_%s_%s_%03d-of-%03d.part",
                            stem, baselineShort, index + 1, partCount);
                    Path temp = staging.resolve(name);
                    Files.deleteIfExists(temp);

                    long remainingForPart = Math.min(partBytes, file.size() - (long) index * partBytes);
                    long written = 0;
                    try (OutputStream out = Files.newOutputStream(temp,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        while (written < remainingForPart) {
                            int want = (int) Math.min(buffer.length, remainingForPart - written);
                            int read = in.read(buffer, 0, want);
                            if (read < 0) throw new IOException("Unexpected EOF while splitting " + source);
                            if (read == 0) continue;
                            out.write(buffer, 0, read);
                            written += read;
                        }
                    }

                    if (written <= 0 || written > maxAssetBytes) {
                        Files.deleteIfExists(temp);
                        throw new IOException("Invalid bootstrap part size: " + written);
                    }
                    String sha = Hashes.sha256(temp);
                    ServerState.MAINTENANCE_PROGRESS.updatePercent(
                            "upload-bootstrap",
                            file.dimension() + " part " + (index + 1) + "/" + partCount + ": " + name,
                            25
                    );
                    GabConDhSync.LOGGER.info("[GabConDHSync] Publishing bootstrap asset {}/{}: {} ({} bytes)",
                            index + 1, partCount, name, written);
                    boolean uploaded = github.uploadImmutable(
                            release, name, temp, "application/octet-stream", sha
                    );
                    if (uploaded) {
                        GabConDhSync.LOGGER.info("[GabConDHSync] Uploaded bootstrap asset: {}", name);
                    } else {
                        GabConDhSync.LOGGER.info("[GabConDHSync] Reused existing bootstrap asset: {}", name);
                    }
                    counters = counters.add(uploaded ? new Counters(1, 0) : new Counters(0, 1));
                    parts.add(new DistributionManifest.PartAsset(
                            name,
                            written,
                            sha,
                            github.assetUrl(tag, name)
                    ));
                    Files.deleteIfExists(temp);
                }
                if (in.read() != -1) throw new IOException("Bootstrap split did not consume exact source length");
            }

            DistributionManifest.BootstrapAsset bootstrap = new DistributionManifest.BootstrapAsset(
                    file.fileName(),
                    file.size(),
                    file.sha256(),
                    List.copyOf(parts)
            );
            dimensions.put(file.dimension(), new DistributionManifest.DimensionDistribution(bootstrap, List.of()));
        }

        DistributionManifest manifest = new DistributionManifest(
                DistributionManifestCodec.SCHEMA_VERSION,
                snapshot.manifest().worldId(),
                SharedConstants.getCurrentVersion().getName(),
                neoForgeVersion(),
                snapshot.manifest().distantHorizonsVersion(),
                snapshot.manifest().distantHorizonsApiVersion(),
                tag,
                Map.copyOf(dimensions)
        );
        DistributionManifestCodec.validate(manifest, maxAssetBytes);
        return new BuildBootstrapResult(manifest, counters);
    }

    private record AppendDeltaResult(DistributionManifest manifest, Counters counters) {}

    private static AppendDeltaResult appendAvailableDeltas(
            DistributionManifest manifest,
            GitHubReleaseClient github,
            GitHubReleaseClient.Release release,
            String worldId,
            String tag,
            long maxAssetBytes
    ) throws Exception {
        Map<String, DistributionManifest.DimensionDistribution> dimensions =
                new LinkedHashMap<>(manifest.dimensions());
        Counters counters = new Counters(0, 0);

        for (DeltaRef deltaRef : allDeltas(worldId)) {
            if (!deltaRef.manifest().distantHorizonsVersion().equals(manifest.distantHorizonsVersion())
                    || !deltaRef.manifest().distantHorizonsApiVersion().equals(manifest.distantHorizonsApiVersion())) {
                continue;
            }

            for (DhDeltaBuilder.DeltaFile file : deltaRef.manifest().files()) {
                DistributionManifest.DimensionDistribution current = dimensions.get(file.dimension());
                if (current == null) throw new IOException("Delta references unknown dimension " + file.dimension());

                Path source = SafePaths.resolveAsset(deltaRef.directory(), file.fileName());
                if (!Files.isRegularFile(source)) throw new IOException("Delta file missing: " + source);
                if (Files.size(source) != file.size()) throw new IOException("Delta size mismatch: " + source);
                if (!Hashes.sha256(source).equalsIgnoreCase(file.sha256())) {
                    throw new IOException("Delta SHA mismatch: " + source);
                }

                DhDeltaApplier.DeltaMetadata metadata = DhDeltaApplier.inspectDelta(source);
                if (containsDelta(current.deltas(), metadata)) continue;

                String expected = latestBaseline(current);
                if (!metadata.oldSha256().equalsIgnoreCase(expected)) {
                    throw new IOException("Delta chain gap for " + file.dimension()
                            + ": expected old baseline " + expected + ", got " + metadata.oldSha256());
                }

                String remoteName = "delta_" + safeStem(file.dimension()) + "_"
                        + metadata.oldSha256().substring(0, 12) + "_"
                        + metadata.newSha256().substring(0, 12) + ".sqlite";
                if (file.size() <= 0 || file.size() > maxAssetBytes) {
                    throw new IOException("Delta exceeds configured asset size limit: " + file.size());
                }

                ServerState.MAINTENANCE_PROGRESS.updatePercent(
                        "upload-delta",
                        file.dimension() + ": " + remoteName,
                        85
                );
                GabConDhSync.LOGGER.info("[GabConDHSync] Publishing delta asset: {} ({} bytes)", remoteName, file.size());
                boolean uploaded = github.uploadImmutable(
                        release, remoteName, source, "application/octet-stream", file.sha256()
                );
                if (uploaded) {
                    GabConDhSync.LOGGER.info("[GabConDHSync] Uploaded delta asset: {}", remoteName);
                } else {
                    GabConDhSync.LOGGER.info("[GabConDHSync] Reused existing delta asset: {}", remoteName);
                }
                counters = counters.add(uploaded ? new Counters(1, 0) : new Counters(0, 1));

                List<DistributionManifest.DeltaAsset> deltas = new ArrayList<>(current.deltas());
                deltas.add(new DistributionManifest.DeltaAsset(
                        remoteName,
                        file.size(),
                        file.sha256(),
                        github.assetUrl(tag, remoteName),
                        metadata.oldSha256(),
                        metadata.newSha256()
                ));
                dimensions.put(file.dimension(),
                        new DistributionManifest.DimensionDistribution(current.bootstrap(), List.copyOf(deltas)));
            }
        }

        DistributionManifest updated = new DistributionManifest(
                manifest.schemaVersion(),
                manifest.worldId(),
                manifest.minecraftVersion(),
                manifest.neoforgeVersion(),
                manifest.distantHorizonsVersion(),
                manifest.distantHorizonsApiVersion(),
                manifest.releaseTag(),
                Map.copyOf(dimensions)
        );
        DistributionManifestCodec.validate(updated, maxAssetBytes);
        return new AppendDeltaResult(updated, counters);
    }

    static int repairReferencedAssets(
            DistributionManifest manifest,
            GitHubReleaseClient github,
            GitHubReleaseClient.Release release,
            String worldId,
            Path publishRoot,
            Path dataRoot
    ) throws Exception {
        int repaired = 0;
        Path staging = publishRoot.toAbsolutePath().normalize().resolve("staging");
        Files.createDirectories(staging);

        for (Map.Entry<String, DistributionManifest.DimensionDistribution> dimensionEntry
                : manifest.dimensions().entrySet()) {
            String dimension = dimensionEntry.getKey();
            DistributionManifest.DimensionDistribution distribution = dimensionEntry.getValue();
            DistributionManifest.BootstrapAsset bootstrap = distribution.bootstrap();

            boolean bootstrapNeedsRepair = bootstrap.parts().stream().anyMatch(part ->
                    !github.assetMatches(release, part.fileName(), part.size(), part.sha256()));

            if (bootstrapNeedsRepair) {
                SnapshotAssetSource source = findBootstrapSource(
                        dataRoot, worldId, dimension, bootstrap.databaseSha256()
                );
                if (Files.size(source.path()) != bootstrap.totalSize()) {
                    throw new IOException("Local bootstrap source size mismatch for " + dimension);
                }
                if (!Hashes.sha256(source.path()).equalsIgnoreCase(bootstrap.databaseSha256())) {
                    throw new IOException("Local bootstrap source SHA mismatch for " + dimension);
                }

                long offset = 0L;
                for (DistributionManifest.PartAsset part : bootstrap.parts()) {
                    if (!github.assetMatches(release, part.fileName(), part.size(), part.sha256())) {
                        Path temp = staging.resolve(part.fileName() + ".repair");
                        Files.deleteIfExists(temp);
                        writeSlice(source.path(), offset, part.size(), temp);
                        try {
                            if (Files.size(temp) != part.size()) {
                                throw new IOException("Rebuilt bootstrap part size mismatch: " + part.fileName());
                            }
                            String sha = Hashes.sha256(temp);
                            if (!sha.equalsIgnoreCase(part.sha256())) {
                                throw new IOException("Rebuilt bootstrap part SHA mismatch: " + part.fileName());
                            }
                            github.uploadImmutable(
                                    release,
                                    part.fileName(),
                                    temp,
                                    "application/octet-stream",
                                    part.sha256()
                            );
                            repaired++;
                            GabConDhSync.LOGGER.info(
                                    "[GabConDHSync] Repaired legacy/missing bootstrap asset: {}",
                                    part.fileName()
                            );
                        } finally {
                            Files.deleteIfExists(temp);
                        }
                    }
                    offset = Math.addExact(offset, part.size());
                }
                if (offset != bootstrap.totalSize()) {
                    throw new IOException("Bootstrap parts do not cover exact source size for " + dimension);
                }
            }

            for (DistributionManifest.DeltaAsset delta : distribution.deltas()) {
                if (github.assetMatches(release, delta.fileName(), delta.size(), delta.sha256())) {
                    continue;
                }
                Path source = findDeltaSource(dataRoot, worldId, dimension, delta.sha256(), delta.size());
                github.uploadImmutable(
                        release,
                        delta.fileName(),
                        source,
                        "application/octet-stream",
                        delta.sha256()
                );
                repaired++;
                GabConDhSync.LOGGER.info(
                        "[GabConDHSync] Repaired legacy/missing delta asset: {}",
                        delta.fileName()
                );
            }
        }
        return repaired;
    }

    private record SnapshotAssetSource(Path path, DhSnapshotService.SnapshotFile metadata) {}

    private static SnapshotAssetSource findBootstrapSource(
            Path dataRoot,
            String worldId,
            String dimension,
            String databaseSha256
    ) throws Exception {
        for (SnapshotRef snapshot : allSnapshots(dataRoot, worldId)) {
            for (DhSnapshotService.SnapshotFile file : snapshot.manifest().files()) {
                if (!dimension.equals(file.dimension())) continue;
                if (!databaseSha256.equalsIgnoreCase(file.sha256())) continue;

                Path source = SafePaths.resolveAsset(snapshot.directory(), file.fileName());
                if (!Files.isRegularFile(source)) {
                    throw new IOException("Bootstrap repair source is missing: " + source);
                }
                if (Files.size(source) != file.size()) {
                    throw new IOException("Bootstrap repair source size mismatch: " + source);
                }
                return new SnapshotAssetSource(source, file);
            }
        }
        throw new IOException(
                "No local snapshot can repair bootstrap asset for " + dimension
                        + " baseline=" + databaseSha256
        );
    }

    private static Path findDeltaSource(
            Path dataRoot,
            String worldId,
            String dimension,
            String sha256,
            long expectedSize
    ) throws Exception {
        for (DeltaRef deltaRef : allDeltas(dataRoot, worldId)) {
            for (DhDeltaBuilder.DeltaFile file : deltaRef.manifest().files()) {
                if (!dimension.equals(file.dimension())) continue;
                if (!sha256.equalsIgnoreCase(file.sha256())) continue;
                Path source = SafePaths.resolveAsset(deltaRef.directory(), file.fileName());
                if (!Files.isRegularFile(source)) {
                    throw new IOException("Delta repair source is missing: " + source);
                }
                if (Files.size(source) != expectedSize || file.size() != expectedSize) {
                    throw new IOException("Delta repair source size mismatch: " + source);
                }
                if (!Hashes.sha256(source).equalsIgnoreCase(sha256)) {
                    throw new IOException("Delta repair source SHA mismatch: " + source);
                }
                return source;
            }
        }
        throw new IOException(
                "No local delta can repair remote asset for " + dimension + " sha256=" + sha256
        );
    }

    private static void writeSlice(Path source, long offset, long size, Path destination) throws IOException {
        if (offset < 0L || size <= 0L) throw new IOException("Invalid bootstrap slice");
        Files.createDirectories(destination.toAbsolutePath().normalize().getParent());

        try (SeekableByteChannel in = Files.newByteChannel(source, StandardOpenOption.READ);
             OutputStream out = Files.newOutputStream(
                     destination,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE
             )) {
            if (offset > in.size() || size > in.size() - offset) {
                throw new IOException("Bootstrap repair slice exceeds source size");
            }
            in.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            long remaining = size;
            while (remaining > 0L) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = in.read(buffer);
                if (read < 0) throw new IOException("Unexpected EOF rebuilding bootstrap part");
                if (read == 0) continue;
                out.write(buffer.array(), 0, read);
                remaining -= read;
            }
        } catch (Exception e) {
            Files.deleteIfExists(destination);
            throw e;
        }
    }

    static void verifyReferencedAssets(
            DistributionManifest manifest,
            GitHubReleaseClient github,
            GitHubReleaseClient.Release release
    ) throws IOException {
        for (DistributionManifest.DimensionDistribution dimension : manifest.dimensions().values()) {
            for (DistributionManifest.PartAsset part : dimension.bootstrap().parts()) {
                github.requireAsset(release, part.fileName(), part.size(), part.sha256());
            }
            for (DistributionManifest.DeltaAsset delta : dimension.deltas()) {
                github.requireAsset(release, delta.fileName(), delta.size(), delta.sha256());
            }
        }
    }

    private static SnapshotRef oldestSnapshot(String worldId) throws Exception {
        List<SnapshotRef> snapshots = allSnapshots(
                Path.of("gabcondhsync").toAbsolutePath().normalize(), worldId
        );
        if (snapshots.isEmpty()) throw new IOException("No valid snapshots found for " + worldId);
        return snapshots.getFirst();
    }

    private static List<SnapshotRef> allSnapshots(Path dataRoot, String worldId) throws Exception {
        Path root = dataRoot.toAbsolutePath().normalize()
                .resolve("snapshots")
                .resolve(safeStem(worldId))
                .normalize();
        if (!Files.isDirectory(root)) return List.of();

        List<SnapshotRef> snapshots = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path json = dir.resolve("snapshot.json");
                if (!Files.isRegularFile(json)) continue;
                DhSnapshotService.SnapshotManifest manifest =
                        GSON.fromJson(Files.readString(json), DhSnapshotService.SnapshotManifest.class);
                if (manifest != null && worldId.equals(manifest.worldId()) && manifest.files() != null) {
                    Instant.parse(manifest.createdAtUtc());
                    snapshots.add(new SnapshotRef(dir.toAbsolutePath().normalize(), manifest));
                }
            }
        }
        snapshots.sort(Comparator.comparing(s -> Instant.parse(s.manifest().createdAtUtc())));
        return List.copyOf(snapshots);
    }

    private static List<DeltaRef> allDeltas(String worldId) throws Exception {
        return allDeltas(Path.of("gabcondhsync").toAbsolutePath().normalize(), worldId);
    }

    private static List<DeltaRef> allDeltas(Path dataRoot, String worldId) throws Exception {
        Path root = dataRoot.toAbsolutePath().normalize()
                .resolve("deltas")
                .resolve(safeStem(worldId))
                .normalize();
        if (!Files.isDirectory(root)) return List.of();

        List<DeltaRef> deltas = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path json = dir.resolve("delta.json");
                if (!Files.isRegularFile(json)) continue;
                DhDeltaBuilder.DeltaManifest manifest =
                        GSON.fromJson(Files.readString(json), DhDeltaBuilder.DeltaManifest.class);
                if (manifest != null && worldId.equals(manifest.worldId())) {
                    Instant.parse(manifest.fromCreatedAtUtc());
                    Instant.parse(manifest.toCreatedAtUtc());
                    deltas.add(new DeltaRef(dir.toAbsolutePath().normalize(), manifest));
                }
            }
        }
        deltas.sort(Comparator.comparing(d -> Instant.parse(d.manifest().fromCreatedAtUtc())));
        return List.copyOf(deltas);
    }

    private static boolean containsDelta(
            List<DistributionManifest.DeltaAsset> deltas,
            DhDeltaApplier.DeltaMetadata metadata
    ) {
        return deltas.stream().anyMatch(d ->
                d.oldServerBaselineSha256().equalsIgnoreCase(metadata.oldSha256())
                        && d.newServerBaselineSha256().equalsIgnoreCase(metadata.newSha256()));
    }

    private static String latestBaseline(DistributionManifest.DimensionDistribution dimension) {
        if (dimension.deltas().isEmpty()) return dimension.bootstrap().databaseSha256();
        return dimension.deltas().getLast().newServerBaselineSha256();
    }

    private static void validateLocalPublicationIdentity(
            DistributionManifest manifest,
            String worldId,
            String tag
    ) {
        if (!worldId.equals(manifest.worldId())) throw new IllegalStateException("Local publication worldId mismatch");
        if (!tag.equals(manifest.releaseTag())) throw new IllegalStateException("Local publication releaseTag mismatch");
        if (!SharedConstants.getCurrentVersion().getName().equals(manifest.minecraftVersion())) {
            throw new IllegalStateException("Local publication Minecraft version mismatch");
        }
    }

    private static String neoForgeVersion() {
        return ModList.get().getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElseThrow(() -> new IllegalStateException("NeoForge version unavailable"));
    }

    private static String safeStem(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        safe = safe.replaceAll("^[_\\.]+|[_\\.]+$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    private ServerDistributionPublisher() {}
}
