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
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GitHubReleaseClient {
    private static final String API = "https://api.github.com";
    private static final String UPLOAD = "https://uploads.github.com";
    private static final Gson GSON = new Gson();

    public record AssetInfo(long id, long size) {}
    public record Release(long id, String tag, Map<String, AssetInfo> assetsByName) {}

    private final String repository;
    private final String token;
    private final HttpClient client;

    public GitHubReleaseClient(String repository, String token) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid GitHub repository: " + repository);
        }
        if (token == null || token.isBlank()) throw new IllegalArgumentException("GitHub token is missing");
        this.repository = repository;
        this.token = token.trim();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Release ensureRelease(String tag, String title) throws IOException, InterruptedException {
        HttpResponse<String> existing = send(HttpRequest.newBuilder(
                URI.create(API + "/repos/" + repository + "/releases/tags/" + enc(tag)))
                .GET()
                .timeout(Duration.ofSeconds(30)));
        if (existing.statusCode() == 200) return parseRelease(existing.body());
        if (existing.statusCode() != 404) throw apiError("get release", existing);

        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", title);
        body.addProperty("draft", false);
        body.addProperty("prerelease", false);
        body.addProperty("generate_release_notes", false);

        HttpResponse<String> created = send(HttpRequest.newBuilder(
                URI.create(API + "/repos/" + repository + "/releases"))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60)));
        if (created.statusCode() != 201) throw apiError("create release", created);
        return parseRelease(created.body());
    }

    public Release refresh(String tag) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(API + "/repos/" + repository + "/releases/tags/" + enc(tag)))
                .GET()
                .timeout(Duration.ofSeconds(30)));
        if (response.statusCode() != 200) throw apiError("refresh release", response);
        return parseRelease(response.body());
    }

    public void uploadReplacing(Release release, String assetName, Path source, String contentType)
            throws IOException, InterruptedException {
        AssetInfo existing = release.assetsByName().get(assetName);
        if (existing != null) deleteAsset(existing.id());

        URI uri = URI.create(UPLOAD + "/repos/" + repository + "/releases/" + release.id()
                + "/assets?name=" + enc(assetName));
        HttpRequest request = authenticated(HttpRequest.newBuilder(uri))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .timeout(Duration.ofHours(6))
                .POST(HttpRequest.BodyPublishers.ofFile(source))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) throw apiError("upload asset " + assetName, response);
    }

    public boolean uploadImmutable(Release release, String assetName, Path source, String contentType)
            throws IOException, InterruptedException {
        long size = java.nio.file.Files.size(source);
        AssetInfo existing = release.assetsByName().get(assetName);
        if (existing != null && existing.size() == size) return false;
        uploadReplacing(release, assetName, source, contentType);
        return true;
    }

    public void deleteAsset(long assetId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(API + "/repos/" + repository + "/releases/assets/" + assetId))
                .DELETE()
                .timeout(Duration.ofSeconds(30)));
        if (response.statusCode() != 204) throw apiError("delete asset " + assetId, response);
    }

    public String assetUrl(String tag, String assetName) {
        return "https://github.com/" + repository + "/releases/download/" + enc(tag) + "/" + enc(assetName);
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

    private static Release parseRelease(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        long id = root.get("id").getAsLong();
        String tag = root.get("tag_name").getAsString();
        Map<String, AssetInfo> assets = new LinkedHashMap<>();
        JsonArray array = root.has("assets") && root.get("assets").isJsonArray()
                ? root.getAsJsonArray("assets") : new JsonArray();
        for (var item : array) {
            JsonObject asset = item.getAsJsonObject();
            assets.put(asset.get("name").getAsString(),
                    new AssetInfo(asset.get("id").getAsLong(), asset.get("size").getAsLong()));
        }
        return new Release(id, tag, Map.copyOf(assets));
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
