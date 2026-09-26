package be.gabcon.dhsync.client;

import be.gabcon.dhsync.config.ClientConfig;
import be.gabcon.dhsync.distribution.DistributionManifest;
import be.gabcon.dhsync.download.DownloadProgress;
import be.gabcon.dhsync.download.DownloadRequest;
import be.gabcon.dhsync.download.SecureDownloader;
import be.gabcon.dhsync.server.DhCompatibility;
import be.gabcon.dhsync.sync.DhDeltaApplier;
import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ClientSyncEngine {
    @FunctionalInterface
    public interface ProgressSink {
        void update(String stage, String detail, long current, long total);
    }

    public record SyncResult(
            boolean changed,
            ClientSyncState.ServerProfile profile,
            List<Path> rollbackFiles
    ) {}

    private final SecureDownloader downloader;

    public ClientSyncEngine(Executor downloadExecutor) {
        this.downloader = new SecureDownloader(downloadExecutor, ClientConfig.OPTIONAL_DOWNLOAD_SPEED_LIMIT.get());
    }

    public SyncResult sync(ClientSyncState.ServerProfile profile, ProgressSink progress) throws Exception {
        ProgressSink sink = progress == null ? (a, b, c, d) -> {} : progress;
        ClientRecoveryJournal.recoverIfPresent(profile.serverAddress());

        sink.update("manifest", "Downloading distribution manifest", 0, 0);
        DistributionManifest manifest = DistributionManifestClient.fetch(
                URI.create(profile.manifestUrl()),
                ClientConfig.MAX_DOWNLOAD_BYTES.get()
        );
        validateRuntime(manifest, profile);

        ClientSyncPlanner.SyncPlan plan = ClientSyncPlanner.plan(manifest, profile);
        if (!plan.needsWork()) {
            sink.update("done", "Distant Horizons data is already current", 1, 1);
            return new SyncResult(false, profile, List.of());
        }

        Path downloadRoot = FMLPaths.GAMEDIR.get()
                .resolve("gabcondhsync")
                .resolve("downloads")
                .resolve(safeStem(profile.worldId()))
                .toAbsolutePath().normalize();
        Files.createDirectories(downloadRoot);

        Map<String, Path> downloaded = downloadAssets(plan, downloadRoot, sink);
        List<ClientDatabaseCommitter.PreparedDimension> prepared = new ArrayList<>();

        try {
            for (ClientSyncPlanner.DimensionPlan dimension : plan.dimensions()) {
                if (!dimension.needsWork()) continue;

                sink.update("prepare", dimension.dimension(), 0, 0);
                Path work;
                String baseline;
                if (dimension.bootstrap() != null) {
                    Map<String, Path> parts = new HashMap<>();
                    for (DistributionManifest.PartAsset part : dimension.bootstrap().parts()) {
                        parts.put(part.fileName(), required(downloaded, part.fileName()));
                    }
                    work = DhBootstrapAssembler.assemble(dimension.databasePath(), dimension.bootstrap(), parts);
                    baseline = dimension.bootstrap().databaseSha256();
                } else {
                    work = DhOfflineFiles.copyToWork(dimension.databasePath());
                    baseline = dimension.startingBaselineSha256();
                    if (baseline == null) throw new IllegalStateException("Missing starting baseline for " + dimension.dimension());
                }

                for (DistributionManifest.DeltaAsset delta : dimension.deltas()) {
                    sink.update("apply", dimension.dimension() + " / " + delta.fileName(), 0, 0);
                    DhDeltaApplier.WorkingApplyResult applied = DhDeltaApplier.applyToWorkingCopy(
                            work,
                            required(downloaded, delta.fileName()),
                            baseline
                    );
                    baseline = applied.nextServerBaselineSha256();
                }

                if (!baseline.equalsIgnoreCase(dimension.targetBaselineSha256())) {
                    throw new IllegalStateException("Prepared baseline mismatch for " + dimension.dimension());
                }
                prepared.add(new ClientDatabaseCommitter.PreparedDimension(
                        dimension.dimension(),
                        dimension.databasePath(),
                        work,
                        baseline
                ));
            }

            sink.update("commit", "Installing verified Distant Horizons databases", 0, prepared.size());
            ClientDatabaseCommitter.CommitResult committed = ClientDatabaseCommitter.commit(profile, prepared);
            sink.update("done", "Distant Horizons sync complete", prepared.size(), prepared.size());

            for (Path asset : downloaded.values()) {
                try {
                    Files.deleteIfExists(asset);
                } catch (IOException ignored) {}
            }
            return new SyncResult(true, committed.profile(), committed.rollbackFiles());
        } catch (Exception e) {
            for (ClientDatabaseCommitter.PreparedDimension dimension : prepared) {
                try {
                    Files.deleteIfExists(dimension.workDatabase());
                } catch (IOException ignored) {}
            }
            throw e;
        }
    }

    private Map<String, Path> downloadAssets(
            ClientSyncPlanner.SyncPlan plan,
            Path downloadRoot,
            ProgressSink sink
    ) throws Exception {
        record PendingAsset(String url, String fileName, long size, String sha256) {}

        Map<String, PendingAsset> pending = new LinkedHashMap<>();
        long maxBytes = ClientConfig.MAX_DOWNLOAD_BYTES.get();

        for (ClientSyncPlanner.DimensionPlan dimension : plan.dimensions()) {
            if (dimension.bootstrap() != null) {
                for (DistributionManifest.PartAsset part : dimension.bootstrap().parts()) {
                    pending.putIfAbsent(part.fileName(),
                            new PendingAsset(part.url(), part.fileName(), part.size(), part.sha256()));
                }
            }
            for (DistributionManifest.DeltaAsset delta : dimension.deltas()) {
                pending.putIfAbsent(delta.fileName(),
                        new PendingAsset(delta.url(), delta.fileName(), delta.size(), delta.sha256()));
            }
        }

        List<PendingAsset> assets = List.copyOf(pending.values());
        Map<String, Path> result = new HashMap<>();
        int batchSize = Math.max(1, ClientConfig.MAX_CONCURRENT_DOWNLOADS.get());

        for (int offset = 0; offset < assets.size(); offset += batchSize) {
            int end = Math.min(offset + batchSize, assets.size());
            List<PendingAsset> batch = assets.subList(offset, end);
            List<CompletableFuture<Path>> futures = new ArrayList<>(batch.size());

            for (PendingAsset asset : batch) {
                sink.update("download", asset.fileName(), 0, asset.size());
                futures.add(startDownload(
                        asset.url(), asset.fileName(), asset.size(), asset.sha256(),
                        maxBytes, downloadRoot, sink
                ));
            }

            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            } catch (java.util.concurrent.CompletionException e) {
                for (CompletableFuture<Path> future : futures) future.cancel(true);
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw e;
            }

            for (int i = 0; i < batch.size(); i++) {
                result.put(batch.get(i).fileName(), futures.get(i).join());
            }
        }

        return Map.copyOf(result);
    }

    private CompletableFuture<Path> startDownload(
            String url,
            String fileName,
            long size,
            String sha256,
            long maxBytes,
            Path downloadRoot,
            ProgressSink sink
    ) {
        DownloadRequest request = new DownloadRequest(
                URI.create(url),
                downloadRoot,
                fileName,
                size,
                sha256,
                maxBytes,
                false
        );
        return downloader.download(request, p -> reportDownload(sink, fileName, p));
    }

    private static void reportDownload(ProgressSink sink, String fileName, DownloadProgress p) {
        sink.update("download", fileName, p.downloadedBytes(), p.totalBytes());
    }

    private static Path required(Map<String, Path> assets, String fileName) throws IOException {
        Path path = assets.get(fileName);
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Downloaded asset missing: " + fileName);
        return path;
    }

    private static void validateRuntime(DistributionManifest manifest, ClientSyncState.ServerProfile profile) {
        if (!manifest.worldId().equals(profile.worldId())) {
            throw new IllegalStateException("Manifest worldId mismatch");
        }

        String mc = SharedConstants.getCurrentVersion().getName();
        if (!manifest.minecraftVersion().equals(mc)) {
            throw new IllegalStateException("Minecraft mismatch: manifest=" + manifest.minecraftVersion() + ", client=" + mc);
        }

        String neo = ModList.get().getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        if (!manifest.neoforgeVersion().equals(neo)) {
            throw new IllegalStateException("NeoForge mismatch: manifest=" + manifest.neoforgeVersion() + ", client=" + neo);
        }

        DhCompatibility.Status dh = DhCompatibility.detect();
        if (!dh.compatible()) throw new IllegalStateException("Unsupported local DH/API pair");
        if (!manifest.distantHorizonsVersion().equals(dh.modVersion())
                || !manifest.distantHorizonsApiVersion().equals(dh.apiVersion())) {
            throw new IllegalStateException("Distant Horizons mismatch: manifest="
                    + manifest.distantHorizonsVersion() + "/" + manifest.distantHorizonsApiVersion()
                    + ", client=" + dh.modVersion() + "/" + dh.apiVersion());
        }
    }

    private static String safeStem(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isBlank() ? "unknown" : safe;
    }
}
