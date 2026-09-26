package be.gabcon.dhsync.publish;

import be.gabcon.dhsync.distribution.DistributionManifest;
import be.gabcon.dhsync.util.Hashes;
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
