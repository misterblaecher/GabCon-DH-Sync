package be.gabcon.dhsync.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ClientSyncStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path defaultPath() {
        return FMLPaths.GAMEDIR.get()
                .resolve("gabcondhsync")
                .resolve("client-state.json")
                .toAbsolutePath().normalize();
    }

    public static ClientSyncState load(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            return new ClientSyncState(ClientSyncState.SCHEMA_VERSION, Map.of());
        }
        ClientSyncState state;
        try {
            state = GSON.fromJson(Files.readString(file), ClientSyncState.class);
        } catch (RuntimeException e) {
            throw new IOException("Invalid GabCon client state JSON: " + file, e);
        }
        validate(state);
        return state;
    }

    public static Optional<ClientSyncState.ServerProfile> find(Path path, String serverAddress) throws IOException {
        ClientSyncState state = load(path);
        String key = normalizeServerAddress(serverAddress);
        return Optional.ofNullable(state.servers().get(key));
    }

    public static void upsert(Path path, ClientSyncState.ServerProfile profile) throws IOException {
        validateProfile(profile);
        ClientSyncState current = load(path);
        Map<String, ClientSyncState.ServerProfile> servers = new LinkedHashMap<>(current.servers());
        servers.put(normalizeServerAddress(profile.serverAddress()), profile);
        save(path, new ClientSyncState(ClientSyncState.SCHEMA_VERSION, Map.copyOf(servers)));
    }

    public static void remove(Path path, String serverAddress) throws IOException {
        ClientSyncState current = load(path);
        Map<String, ClientSyncState.ServerProfile> servers = new LinkedHashMap<>(current.servers());
        servers.remove(normalizeServerAddress(serverAddress));
        save(path, new ClientSyncState(ClientSyncState.SCHEMA_VERSION, Map.copyOf(servers)));
    }

    public static void save(Path path, ClientSyncState state) throws IOException {
        validate(state);
        Path file = path.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, GSON.toJson(state));
        try {
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String normalizeServerAddress(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Server address is blank");
        return value.trim().toLowerCase();
    }

    public static void validate(ClientSyncState state) throws IOException {
        if (state == null) throw new ManifestException("Client state is missing");
        if (state.schemaVersion() != ClientSyncState.SCHEMA_VERSION) {
            throw new ManifestException("Unsupported client state schema: " + state.schemaVersion());
        }
        if (state.servers() == null) throw new ManifestException("Client state servers are missing");
        for (Map.Entry<String, ClientSyncState.ServerProfile> entry : state.servers().entrySet()) {
            validateProfile(entry.getValue());
            if (!normalizeServerAddress(entry.getKey()).equals(normalizeServerAddress(entry.getValue().serverAddress()))) {
                throw new ManifestException("Client state server key mismatch: " + entry.getKey());
            }
        }
    }

    public static void validateProfile(ClientSyncState.ServerProfile profile) throws IOException {
        if (profile == null) throw new ManifestException("Client server profile is missing");
        requireText(profile.serverAddress(), "serverAddress");
        requireText(profile.worldId(), "worldId");
        requireText(profile.manifestUrl(), "manifestUrl");
        if (!profile.manifestUrl().startsWith("https://")) {
            throw new ManifestException("Client manifest URL must use HTTPS");
        }
        if (profile.dimensions() == null || profile.dimensions().isEmpty()) {
            throw new ManifestException("Client profile has no DH dimensions");
        }
        for (Map.Entry<String, ClientSyncState.DimensionState> entry : profile.dimensions().entrySet()) {
            requireText(entry.getKey(), "dimension");
            ClientSyncState.DimensionState dimension = entry.getValue();
            if (dimension == null) throw new ManifestException("Missing dimension state: " + entry.getKey());
            requireText(dimension.databasePath(), "databasePath");
            String baseline = dimension.serverBaselineSha256();
            if (baseline != null && !baseline.isBlank() && !baseline.matches("(?i)[0-9a-f]{64}")) {
                throw new ManifestException("Invalid baseline SHA for " + entry.getKey());
            }
        }
    }

    private static void requireText(String value, String field) throws IOException {
        if (value == null || value.isBlank()) throw new ManifestException("Missing " + field);
    }

    private ClientSyncStateStore() {}
}
