package be.gabcon.dhsync.publish;

import be.gabcon.dhsync.distribution.DistributionManifest;
import be.gabcon.dhsync.server.DhDeltaBuilder;
import be.gabcon.dhsync.server.DhSnapshotService;
import be.gabcon.dhsync.util.Hashes;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GitHubReleaseClientDigestTest {
    @TempDir Path temp;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }


    @Test
    void downloadsExistingManifestAssetWithoutTouchingReferencedAssets() throws Exception {
        String body = "{\"schemaVersion\":2,\"worldId\":\"gabcon-main\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        );

        AtomicInteger gets = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        GitHubReleaseClient client = client(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.incrementAndGet();
                respond(exchange, 204, "");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/releases/assets/99")) {
                gets.incrementAndGet();
                respond(exchange, 200, body);
                return;
            }
            respond(exchange, 404, "{}");
        });

        var release = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of(
                        "manifest.json",
                        new GitHubReleaseClient.AssetInfo(
                                99L, bytes.length, "sha256:" + sha
                        )
                )
        );

        assertEquals(body, client.downloadTextAsset(release, "manifest.json", 1024L));
        assertEquals(1, gets.get());
        assertEquals(0, deletes.get(),
                "recovering the live manifest must never delete a referenced asset");
    }

    @Test
    void remoteManifestDownloadRejectsDigestMismatch() throws Exception {
        String body = "{\"schemaVersion\":2}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        GitHubReleaseClient client = client(exchange ->
                respond(exchange, 200, body));

        var release = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of(
                        "manifest.json",
                        new GitHubReleaseClient.AssetInfo(
                                99L, bytes.length, "sha256:" + "0".repeat(64)
                        )
                )
        );

        IOException ex = assertThrows(IOException.class, () ->
                client.downloadTextAsset(release, "manifest.json", 1024L)
        );
        assertTrue(ex.getMessage().contains("SHA-256 mismatch"));
    }

    @Test
    void reusesOnlyWhenSizeAndDigestMatch() throws Exception {
        Path asset = temp.resolve("asset.bin");
        Files.writeString(asset, "hello");
        String sha = Hashes.sha256(asset);
        long size = Files.size(asset);

        AtomicInteger requests = new AtomicInteger();
        GitHubReleaseClient client = client(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "{}");
        });

        var release = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of("asset.bin", new GitHubReleaseClient.AssetInfo(7L, size, "sha256:" + sha))
        );

        assertFalse(client.uploadImmutable(
                release, "asset.bin", asset, "application/octet-stream", sha
        ));
        assertEquals(0, requests.get());
    }

    @Test
    void sameSizeWrongDigestIsDeletedAndReuploaded() throws Exception {
        Path asset = temp.resolve("asset.bin");
        Files.writeString(asset, "hello");
        String sha = Hashes.sha256(asset);
        long size = Files.size(asset);

        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        GitHubReleaseClient client = client(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.incrementAndGet();
                respond(exchange, 204, "");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                uploads.incrementAndGet();
                respond(exchange, 201, uploadedJson("asset.bin", 8L, size, "sha256:" + sha));
                return;
            }
            respond(exchange, 404, "{}");
        });

        var release = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of("asset.bin", new GitHubReleaseClient.AssetInfo(7L, size, "sha256:" + "0".repeat(64)))
        );

        assertTrue(client.uploadImmutable(
                release, "asset.bin", asset, "application/octet-stream", sha
        ));
        assertEquals(1, deletes.get());
        assertEquals(1, uploads.get());
    }

    @Test
    void missingDigestIsNeverSilentlyTrusted() throws Exception {
        Path asset = temp.resolve("asset.bin");
        Files.writeString(asset, "hello");
        String sha = Hashes.sha256(asset);
        long size = Files.size(asset);

        AtomicInteger uploads = new AtomicInteger();
        GitHubReleaseClient client = client(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, 204, "");
                return;
            }
            uploads.incrementAndGet();
            respond(exchange, 201, uploadedJson("asset.bin", 8L, size, "sha256:" + sha));
        });

        var release = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of("asset.bin", new GitHubReleaseClient.AssetInfo(7L, size, null))
        );

        assertTrue(client.uploadImmutable(
                release, "asset.bin", asset, "application/octet-stream", sha
        ));
        assertEquals(1, uploads.get());
    }

    @Test
    void wrongDigestReturnedByUploadFailsClosed() throws Exception {
        Path asset = temp.resolve("asset.bin");
        Files.writeString(asset, "hello");
        String sha = Hashes.sha256(asset);
        long size = Files.size(asset);

        GitHubReleaseClient client = client(exchange ->
                respond(exchange, 201,
                        uploadedJson("asset.bin", 8L, size, "sha256:" + "f".repeat(64))));

        var release = new GitHubReleaseClient.Release(1L, "tag", Map.of());

        IOException ex = assertThrows(IOException.class, () ->
                client.uploadImmutable(
                        release, "asset.bin", asset, "application/octet-stream", sha
                )
        );
        assertTrue(ex.getMessage().contains("SHA-256 mismatch"));
    }

    @Test
    void manifestPreflightRejectsMissingOrWrongRemoteAsset() throws Exception {
        String partSha = "a".repeat(64);
        String deltaSha = "b".repeat(64);
        DistributionManifest manifest = manifest(partSha, deltaSha);

        GitHubReleaseClient client = new GitHubReleaseClient(
                "owner/repo",
                "token",
                HttpClient.newHttpClient(),
                "http://127.0.0.1:1",
                "http://127.0.0.1:1"
        );

        var missingDelta = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of(
                        "bootstrap.part",
                        new GitHubReleaseClient.AssetInfo(1L, 10L, "sha256:" + partSha)
                )
        );
        IOException missing = assertThrows(IOException.class, () ->
                ServerDistributionPublisher.verifyReferencedAssets(manifest, client, missingDelta)
        );
        assertTrue(missing.getMessage().contains("missing"));

        var wrongDelta = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of(
                        "bootstrap.part",
                        new GitHubReleaseClient.AssetInfo(1L, 10L, "sha256:" + partSha),
                        "delta.sqlite",
                        new GitHubReleaseClient.AssetInfo(2L, 20L, "sha256:" + "c".repeat(64))
                )
        );
        IOException wrong = assertThrows(IOException.class, () ->
                ServerDistributionPublisher.verifyReferencedAssets(manifest, client, wrongDelta)
        );
        assertTrue(wrong.getMessage().contains("SHA-256 mismatch"));
    }

    @Test
    void manifestPreflightAcceptsCompleteRemoteSet() throws Exception {
        String partSha = "a".repeat(64);
        String deltaSha = "b".repeat(64);
        DistributionManifest manifest = manifest(partSha, deltaSha);

        GitHubReleaseClient client = new GitHubReleaseClient(
                "owner/repo",
                "token",
                HttpClient.newHttpClient(),
                "http://127.0.0.1:1",
                "http://127.0.0.1:1"
        );
        var release = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of(
                        "bootstrap.part",
                        new GitHubReleaseClient.AssetInfo(1L, 10L, "sha256:" + partSha),
                        "delta.sqlite",
                        new GitHubReleaseClient.AssetInfo(2L, 20L, "sha256:" + deltaSha)
                )
        );

        assertDoesNotThrow(() ->
                ServerDistributionPublisher.verifyReferencedAssets(manifest, client, release));
    }

    @Test
    void repairPassReuploadsLegacyBootstrapAndMissingDeltaFromLocalSources() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path publishRoot = dataRoot.resolve("publish").resolve("gabcon-main");
        Path snapshotDir = dataRoot.resolve("snapshots").resolve("gabcon-main").resolve("20260926-120000");
        Path deltaDir = dataRoot.resolve("deltas").resolve("gabcon-main").resolve("20260926-120000--20260926-121000");
        Files.createDirectories(snapshotDir);
        Files.createDirectories(deltaDir);

        Path bootstrapDb = snapshotDir.resolve("minecraft_overworld.sqlite");
        Files.writeString(bootstrapDb, "abcdefghij");
        String bootstrapSha = Hashes.sha256(bootstrapDb);
        long bootstrapSize = Files.size(bootstrapDb);

        var snapshotFile = new DhSnapshotService.SnapshotFile(
                "minecraft:overworld",
                "minecraft:overworld",
                bootstrapDb.getFileName().toString(),
                bootstrapSize,
                bootstrapSha
        );
        var snapshotManifest = new DhSnapshotService.SnapshotManifest(
                1,
                "gabcon-main",
                Instant.parse("2026-09-26T12:00:00Z").toString(),
                "3.3.2",
                "7.2.0",
                List.of(snapshotFile)
        );
        Files.writeString(
                snapshotDir.resolve("snapshot.json"),
                new GsonBuilder().create().toJson(snapshotManifest)
        );

        Path localDelta = deltaDir.resolve("minecraft_overworld.delta.sqlite");
        Files.writeString(localDelta, "01234567890123456789");
        String deltaSha = Hashes.sha256(localDelta);
        long deltaSize = Files.size(localDelta);
        var deltaFile = new DhDeltaBuilder.DeltaFile(
                "minecraft:overworld",
                localDelta.getFileName().toString(),
                deltaSize,
                deltaSha,
                List.of()
        );
        var deltaManifest = new DhDeltaBuilder.DeltaManifest(
                1,
                "gabcon-main",
                "2026-09-26T12:00:00Z",
                "2026-09-26T12:10:00Z",
                "3.3.2",
                "7.2.0",
                List.of(deltaFile)
        );
        Files.writeString(
                deltaDir.resolve("delta.json"),
                new GsonBuilder().create().toJson(deltaManifest)
        );

        var part = new DistributionManifest.PartAsset(
                "bootstrap.part",
                bootstrapSize,
                bootstrapSha,
                "https://example.org/bootstrap.part"
        );
        var bootstrap = new DistributionManifest.BootstrapAsset(
                bootstrapDb.getFileName().toString(),
                bootstrapSize,
                bootstrapSha,
                List.of(part)
        );
        var delta = new DistributionManifest.DeltaAsset(
                "delta.sqlite",
                deltaSize,
                deltaSha,
                "https://example.org/delta.sqlite",
                bootstrapSha,
                "d".repeat(64)
        );
        var manifest = new DistributionManifest(
                2,
                "gabcon-main",
                "1.21.1",
                "21.1.251",
                "3.3.2",
                "7.2.0",
                "tag",
                Map.of(
                        "minecraft:overworld",
                        new DistributionManifest.DimensionDistribution(bootstrap, List.of(delta))
                )
        );

        String repairedBootstrapName =
                "bootstrap.sha256-" + bootstrapSha.substring(0, 12) + ".part";
        String repairedDeltaName =
                "delta.sha256-" + deltaSha.substring(0, 12) + ".sqlite";

        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        GitHubReleaseClient client = client(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.incrementAndGet();
                respond(exchange, 204, "");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                uploads.incrementAndGet();
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null && query.contains(repairedBootstrapName)) {
                    respond(exchange, 201,
                            uploadedJson(repairedBootstrapName, 11L, bootstrapSize, "sha256:" + bootstrapSha));
                    return;
                }
                if (query != null && query.contains(repairedDeltaName)) {
                    respond(exchange, 201,
                            uploadedJson(repairedDeltaName, 12L, deltaSize, "sha256:" + deltaSha));
                    return;
                }
            }
            respond(exchange, 404, "{}");
        });

        var legacyRelease = new GitHubReleaseClient.Release(
                1L,
                "tag",
                Map.of(
                        "bootstrap.part",
                        new GitHubReleaseClient.AssetInfo(10L, bootstrapSize, null)
                )
        );

        ServerDistributionPublisher.RepairResult repair =
                ServerDistributionPublisher.repairReferencedAssets(
                        manifest,
                        client,
                        legacyRelease,
                        "gabcon-main",
                        publishRoot,
                        dataRoot
                );

        assertEquals(2, repair.uploadedAssets());
        assertEquals(2, repair.repointedAssets());
        assertEquals(0, deletes.get(),
                "assets referenced by the live manifest must remain available during repair");
        assertEquals(2, uploads.get(), "legacy bootstrap and missing delta must both be repaired");

        var repairedDistribution = repair.manifest().dimensions().get("minecraft:overworld");
        assertEquals(repairedBootstrapName, repairedDistribution.bootstrap().parts().getFirst().fileName());
        assertEquals(repairedDeltaName, repairedDistribution.deltas().getFirst().fileName());
        assertFalse(Files.exists(publishRoot.resolve("staging").resolve("bootstrap.part.repair")));
    }

    @Test
    void repairPassFailsClosedWhenLocalSourceWasLost() throws Exception {
        String partSha = "a".repeat(64);
        var part = new DistributionManifest.PartAsset(
                "bootstrap.part", 10L, partSha, "https://example.org/bootstrap.part"
        );
        var bootstrap = new DistributionManifest.BootstrapAsset(
                "DistantHorizons.sqlite", 10L, partSha, List.of(part)
        );
        var manifest = new DistributionManifest(
                2,
                "gabcon-main",
                "1.21.1",
                "21.1.251",
                "3.3.2",
                "7.2.0",
                "tag",
                Map.of("minecraft:overworld",
                        new DistributionManifest.DimensionDistribution(bootstrap, List.of()))
        );

        GitHubReleaseClient client = new GitHubReleaseClient(
                "owner/repo",
                "token",
                HttpClient.newHttpClient(),
                "http://127.0.0.1:1",
                "http://127.0.0.1:1"
        );

        IOException ex = assertThrows(IOException.class, () ->
                ServerDistributionPublisher.repairReferencedAssets(
                        manifest,
                        client,
                        new GitHubReleaseClient.Release(1L, "tag", Map.of()),
                        "gabcon-main",
                        temp.resolve("publish"),
                        temp.resolve("missing-data-root")
                )
        );
        assertTrue(ex.getMessage().contains("No local snapshot"));
    }

    private GitHubReleaseClient client(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                respond(exchange, 500, "{\"error\":\"test handler\"}");
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GitHubReleaseClient(
                "owner/repo",
                "token",
                HttpClient.newBuilder().build(),
                base,
                base
        );
    }

    private static DistributionManifest manifest(String partSha, String deltaSha) {
        var part = new DistributionManifest.PartAsset(
                "bootstrap.part", 10L, partSha, "https://example.org/bootstrap.part"
        );
        var bootstrap = new DistributionManifest.BootstrapAsset(
                "DistantHorizons.sqlite", 10L, partSha, List.of(part)
        );
        var delta = new DistributionManifest.DeltaAsset(
                "delta.sqlite",
                20L,
                deltaSha,
                "https://example.org/delta.sqlite",
                partSha,
                "d".repeat(64)
        );
        return new DistributionManifest(
                2,
                "gabcon-main",
                "1.21.1",
                "21.1.251",
                "3.3.2",
                "7.2.0",
                "tag",
                Map.of("minecraft:overworld",
                        new DistributionManifest.DimensionDistribution(bootstrap, List.of(delta)))
        );
    }

    private static String uploadedJson(
            String name,
            long id,
            long size,
            String digest
    ) {
        return "{"
                + "\"id\":" + id + ","
                + "\"name\":\"" + name + "\","
                + "\"size\":" + size + ","
                + "\"digest\":\"" + digest + "\""
                + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
