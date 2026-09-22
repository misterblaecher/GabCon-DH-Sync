package be.gabcon.dhsync.download;

import be.gabcon.dhsync.util.Hashes;
import be.gabcon.dhsync.util.SafePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class SecureDownloader {
    private final HttpClient client;
    private final Executor executor;

    public SecureDownloader(Executor executor) {
        this.executor = Objects.requireNonNull(executor);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    public CompletableFuture<Path> download(DownloadRequest request, Consumer<DownloadProgress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return downloadBlocking(request, progress == null ? ignored -> {} : progress);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    Path downloadBlocking(DownloadRequest request, Consumer<DownloadProgress> progress) throws IOException, InterruptedException {
        validateRequest(request);
        Files.createDirectories(request.targetDirectory());
        Path finalPath = SafePaths.resolveAsset(request.targetDirectory(), request.fileName());
        Path partPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");

        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                transferOnce(request, partPath, progress);
                long size = Files.size(partPath);
                if (size != request.expectedSize()) throw new IOException("Size mismatch: " + size + " != " + request.expectedSize());
                String hash = Hashes.sha256(partPath);
                if (!hash.equalsIgnoreCase(request.expectedSha256())) {
                    Files.deleteIfExists(partPath);
                    throw new IOException("SHA-256 mismatch");
                }
                atomicReplace(partPath, finalPath);
                return finalPath;
            } catch (IOException e) {
                last = e;
                if (attempt == 3) break;
            }
        }
        throw last == null ? new IOException("Download failed") : last;
    }

    private void transferOnce(DownloadRequest request, Path partPath, Consumer<DownloadProgress> progress) throws IOException, InterruptedException {
        long existing = Files.exists(partPath) ? Files.size(partPath) : 0;
        if (existing > request.expectedSize() || existing > request.maxBytes()) {
            Files.deleteIfExists(partPath);
            existing = 0;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .GET()
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "GabConDHSync/0.1");
        if (existing > 0) builder.header("Range", "bytes=" + existing + "-");

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        boolean append = existing > 0 && status == 206;
        if (existing > 0 && status == 200) {
            existing = 0;
            append = false;
        } else if (status != 200 && status != 206) {
            try (InputStream ignored = response.body()) {}
            throw new IOException("HTTP " + status);
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength >= 0 && existing + contentLength > request.maxBytes()) {
            try (InputStream ignored = response.body()) {}
            throw new IOException("Remote body exceeds maximum size");
        }

        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};

        long downloaded = existing;
        long started = System.nanoTime();
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partPath, options)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                downloaded += read;
                if (downloaded > request.maxBytes() || downloaded > request.expectedSize()) throw new IOException("Download exceeds expected size");
                out.write(buffer, 0, read);
                double seconds = Math.max((System.nanoTime() - started) / 1_000_000_000.0, 0.001);
                double speed = Math.max((downloaded - existing) / seconds, 0.0);
                long remaining = Math.max(request.expectedSize() - downloaded, 0);
                long eta = speed > 1 ? Math.round(remaining / speed) : -1;
                progress.accept(new DownloadProgress(downloaded, request.expectedSize(), speed, eta));
            }
        }
    }

    private static void validateRequest(DownloadRequest request) {
        URI uri = request.uri();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || (request.allowHttpForTests() && scheme.equals("http")))) throw new IllegalArgumentException("HTTPS required");
        if (uri.getHost() == null) throw new IllegalArgumentException("URL host required");
        if (request.expectedSize() < 0 || request.expectedSize() > request.maxBytes()) throw new IllegalArgumentException("Invalid expected size");
        if (request.expectedSha256() == null || !request.expectedSha256().matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid expected SHA-256");
        SafePaths.resolveAsset(request.targetDirectory(), request.fileName());
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
