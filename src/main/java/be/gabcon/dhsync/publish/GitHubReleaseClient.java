package be.gabcon.dhsync.publish;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class GitHubReleaseClient {
    private static final String DEFAULT_API = "https://api.github.com";
    private static final String DEFAULT_UPLOAD = "https://uploads.github.com";
    private static final Gson GSON = new Gson();

    public record AssetInfo(long id, long size, String digest) {}
    public record Release(long id, String tag, Map<String, AssetInfo> assetsByName) {}

    private final String repository;
    private final String token;
    private final HttpClient client;
    private final String apiBase;
    private final String uploadBase;

    public GitHubReleaseClient(String repository, String token) {
        this(
                repository,
                token,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                DEFAULT_API,
                DEFAULT_UPLOAD
        );
    }

    GitHubReleaseClient(
            String repository,
            String token,
            HttpClient client,
            String apiBase,
            String uploadBase
    ) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid GitHub repository: " + repository);
        }
        if (token == null || token.isBlank()) throw new IllegalArgumentException("GitHub token is missing");
        if (client == null) throw new IllegalArgumentException("HTTP client is missing");
        this.repository = repository;
        this.token = token.trim();
        this.client = client;
        this.apiBase = trimTrailingSlash(apiBase);
        this.uploadBase = trimTrailingSlash(uploadBase);
    }

    public Release ensureRelease(String tag, String title) throws IOException, InterruptedException {
        HttpResponse<String> existing = send(HttpRequest.newBuilder(
                URI.create(apiBase + "/repos/" + repository + "/releases/tags/" + enc(tag)))
                .GET()
                .timeout(Duration.ofSeconds(30)));
        if (existing.statusCode() == 200) {
            Release metadata = parseReleaseMetadata(existing.body());
            return new Release(metadata.id(), metadata.tag(), loadAllAssets(metadata.id()));
        }
        if (existing.statusCode() != 404) throw apiError("get release", existing);

        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", title);
        body.addProperty("draft", false);
        body.addProperty("prerelease", false);
        body.addProperty("generate_release_notes", false);

        HttpResponse<String> created = send(HttpRequest.newBuilder(
                URI.create(apiBase + "/repos/" + repository + "/releases"))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60)));
        if (created.statusCode() != 201) throw apiError("create release", created);
        Release metadata = parseReleaseMetadata(created.body());
        return new Release(metadata.id(), metadata.tag(), Map.of());
    }

    public Release refresh(String tag) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(apiBase + "/repos/" + repository + "/releases/tags/" + enc(tag)))
                .GET()
                .timeout(Duration.ofSeconds(30)));
        if (response.statusCode() != 200) throw apiError("refresh release", response);
        Release metadata = parseReleaseMetadata(response.body());
        return new Release(metadata.id(), metadata.tag(), loadAllAssets(metadata.id()));
    }

    public AssetInfo uploadReplacing(
            Release release,
            String assetName,
            Path source,
            String contentType,
            String expectedSha256
    ) throws IOException, InterruptedException {
        String expectedDigest = expectedDigest(expectedSha256);
        long expectedSize = Files.size(source);

        AssetInfo existing = release.assetsByName().get(assetName);
        if (existing != null) deleteAsset(existing.id());

        URI uri = URI.create(uploadBase + "/repos/" + repository + "/releases/" + release.id()
                + "/assets?name=" + enc(assetName));
        HttpRequest request = authenticated(HttpRequest.newBuilder(uri))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .timeout(Duration.ofHours(6))
                .POST(HttpRequest.BodyPublishers.ofFile(source))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) throw apiError("upload asset " + assetName, response);

        JsonObject uploaded = parseObject(response.body(), "upload asset " + assetName);
        String returnedName = requiredString(uploaded, "name", "uploaded asset");
        if (!assetName.equals(returnedName)) {
            throw new IOException("GitHub upload returned unexpected asset name: " + returnedName);
        }
        AssetInfo info = parseAssetInfo(uploaded);
        requireIntegrity(assetName, info, expectedSize, expectedDigest);
        return info;
    }

    public boolean uploadImmutable(
            Release release,
            String assetName,
            Path source,
            String contentType,
            String expectedSha256
    ) throws IOException, InterruptedException {
        long size = Files.size(source);
        String digest = expectedDigest(expectedSha256);
        AssetInfo existing = release.assetsByName().get(assetName);
        if (existing != null
                && existing.size() == size
                && digest.equalsIgnoreCase(nullToEmpty(existing.digest()))) {
            return false;
        }

        uploadReplacing(release, assetName, source, contentType, expectedSha256);
        return true;
    }

    public boolean assetMatches(
            Release release,
            String assetName,
            long expectedSize,
            String expectedSha256
    ) {
        AssetInfo info = release.assetsByName().get(assetName);
        if (info == null || info.size() != expectedSize) return false;
        String expected = expectedDigest(expectedSha256);
        return expected.equalsIgnoreCase(nullToEmpty(info.digest()));
    }

    public void requireAsset(
            Release release,
            String assetName,
            long expectedSize,
            String expectedSha256
    ) throws IOException {
        AssetInfo info = release.assetsByName().get(assetName);
        if (info == null) throw new IOException("GitHub Release asset is missing: " + assetName);
        requireIntegrity(assetName, info, expectedSize, expectedDigest(expectedSha256));
    }

    public void deleteAsset(long assetId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(apiBase + "/repos/" + repository + "/releases/assets/" + assetId))
                .DELETE()
                .timeout(Duration.ofSeconds(30)));
        if (response.statusCode() != 204) throw apiError("delete asset " + assetId, response);
    }

    public String assetUrl(String tag, String assetName) {
        return "https://github.com/" + repository + "/releases/download/" + enc(tag) + "/" + enc(assetName);
    }

    private Map<String, AssetInfo> loadAllAssets(long releaseId) throws IOException, InterruptedException {
        Map<String, AssetInfo> assets = new LinkedHashMap<>();
        for (int page = 1; page <= 1000; page++) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(
                    URI.create(apiBase + "/repos/" + repository + "/releases/" + releaseId
                            + "/assets?per_page=100&page=" + page))
                    .GET()
                    .timeout(Duration.ofSeconds(30)));
            if (response.statusCode() != 200) throw apiError("list release assets", response);

            JsonArray array;
            try {
                array = JsonParser.parseString(response.body()).getAsJsonArray();
            } catch (RuntimeException e) {
                throw new IOException("Invalid GitHub release asset list JSON", e);
            }

            for (var item : array) {
                JsonObject asset = item.getAsJsonObject();
                String name = requiredString(asset, "name", "release asset");
                assets.put(name, parseAssetInfo(asset));
            }
            if (array.size() < 100) return Map.copyOf(assets);
        }
        throw new IOException("GitHub Release asset pagination exceeded safety limit");
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(authenticated(builder).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder authenticated(HttpRequest.Builder builder) {
        return builder
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "GabConDHSync");
    }

    private static Release parseReleaseMetadata(String json) throws IOException {
        JsonObject root = parseObject(json, "release");
        if (!root.has("id") || !root.has("tag_name")) {
            throw new IOException("Invalid GitHub release JSON");
        }
        long id = root.get("id").getAsLong();
        String tag = root.get("tag_name").getAsString();
        return new Release(id, tag, Map.of());
    }

    private static AssetInfo parseAssetInfo(JsonObject asset) throws IOException {
        if (!asset.has("id") || !asset.has("size")) {
            throw new IOException("Invalid GitHub asset JSON");
        }
        String digest = null;
        if (asset.has("digest") && !asset.get("digest").isJsonNull()) {
            digest = asset.get("digest").getAsString();
        }
        return new AssetInfo(asset.get("id").getAsLong(), asset.get("size").getAsLong(), digest);
    }

    private static JsonObject parseObject(String json, String label) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid GitHub " + label + " JSON", e);
        }
    }

    private static String requiredString(JsonObject object, String field, String label) throws IOException {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IOException("Missing " + field + " in GitHub " + label);
        }
        return object.get(field).getAsString();
    }

    private static void requireIntegrity(
            String assetName,
            AssetInfo info,
            long expectedSize,
            String expectedDigest
    ) throws IOException {
        if (info.size() != expectedSize) {
            throw new IOException("GitHub Release asset size mismatch for " + assetName
                    + ": expected=" + expectedSize + ", actual=" + info.size());
        }
        String actualDigest = nullToEmpty(info.digest());
        if (!expectedDigest.equalsIgnoreCase(actualDigest)) {
            throw new IOException("GitHub Release asset SHA-256 mismatch for " + assetName
                    + ": expected=" + expectedDigest + ", actual="
                    + (actualDigest.isBlank() ? "<missing>" : actualDigest));
        }
    }

    private static String expectedDigest(String sha256) {
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        return "sha256:" + sha256.toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Base URL is blank");
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static IOException apiError(String operation, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (body.length() > 1000) body = body.substring(0, 1000);
        return new IOException("GitHub " + operation + " failed: HTTP " + response.statusCode() + " " + body);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
