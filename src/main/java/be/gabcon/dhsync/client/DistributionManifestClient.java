package be.gabcon.dhsync.client;

import be.gabcon.dhsync.distribution.DistributionManifest;
import be.gabcon.dhsync.distribution.DistributionManifestCodec;
import be.gabcon.dhsync.manifest.ManifestException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class DistributionManifestClient {
    public static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;

    public static DistributionManifest fetch(URI uri, long maxAssetBytes) throws IOException, InterruptedException, ManifestException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("Manifest URL must use HTTPS");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "GabConDHSync/0.5.2")
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {}
            throw new IOException("Manifest HTTP " + response.statusCode());
        }

        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > MAX_MANIFEST_BYTES) {
            try (InputStream ignored = response.body()) {}
            throw new IOException("Manifest exceeds maximum size");
        }

        byte[] json;
        try (InputStream in = response.body(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_MANIFEST_BYTES) throw new IOException("Manifest exceeds maximum size");
                out.write(buffer, 0, read);
            }
            json = out.toByteArray();
        }
        return DistributionManifestCodec.parse(new String(json, java.nio.charset.StandardCharsets.UTF_8), maxAssetBytes);
    }

    private DistributionManifestClient() {}
}
