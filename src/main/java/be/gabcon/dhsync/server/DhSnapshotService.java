package be.gabcon.dhsync.server;

import be.gabcon.dhsync.GabConDhSync;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DhSnapshotService {
    private static final String DATABASE_NAME = "DistantHorizons.sqlite";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record SnapshotFile(
            String dimension,
            String dhIdentifier,
            String fileName,
            long size,
            String sha256
    ) {}

    public record SnapshotManifest(
            int schemaVersion,
            String worldId,
            String createdAtUtc,
            String distantHorizonsVersion,
            String distantHorizonsApiVersion,
            List<SnapshotFile> files
    ) {}

    public record SnapshotResult(Path directory, Path manifest, List<SnapshotFile> files) {}

    public static SnapshotResult create(String worldId, DhCompatibility.Status dh) throws Exception {
        if (!dh.compatible()) {
            throw new IllegalStateException("Unsupported Distant Horizons pair: " + dh.modVersion() + " / " + dh.apiVersion());
        }

        Object worldProxy = getWorldProxy();
        boolean loaded = (boolean) invoke(worldProxy, "worldLoaded");
        if (!loaded) throw new IllegalStateException("Distant Horizons world is not loaded");

        boolean wasReadOnly = (boolean) invoke(worldProxy, "getReadOnly");
        if (!wasReadOnly) invoke(worldProxy, "setReadOnly", new Class<?>[]{boolean.class}, true);

        Instant createdAt = Instant.now();
        Path directory = Path.of("gabcondhsync", "snapshots", safeStem(worldId), STAMP.format(createdAt))
                .toAbsolutePath().normalize();
        Files.createDirectories(directory);

        List<SnapshotFile> snapshots = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        try {
            Object wrappersObject = invoke(worldProxy, "getAllLoadedLevelWrappers");
            if (!(wrappersObject instanceof Iterable<?> wrappers)) {
                throw new IllegalStateException("Unexpected DH level wrapper collection");
            }

            for (Object wrapper : wrappers) {
                String dimension = String.valueOf(invoke(wrapper, "getDimensionName"));
                String dhIdentifier = String.valueOf(invoke(wrapper, "getDhIdentifier"));
                File saveFolder = (File) invoke(wrapper, "getDhSaveFolder");
                if (saveFolder == null) continue;

                Path source = saveFolder.toPath().resolve(DATABASE_NAME).toAbsolutePath().normalize();
                if (!Files.isRegularFile(source)) {
                    GabConDhSync.LOGGER.warn("[GabConDHSync] DH database missing for {} at {}", dimension, source);
                    continue;
                }

                String baseName = safeStem(dimension);
                String fileName = baseName + ".sqlite";
                for (int suffix = 2; !usedNames.add(fileName); suffix++) {
                    fileName = baseName + "-" + suffix + ".sqlite";
                }

                DhSqliteSnapshotter.BackupResult result = DhSqliteSnapshotter.backup(source, directory.resolve(fileName));
                snapshots.add(new SnapshotFile(dimension, dhIdentifier, fileName, result.size(), result.sha256()));
            }

            if (snapshots.isEmpty()) {
                throw new IllegalStateException("No loaded Distant Horizons databases were available to snapshot");
            }

            SnapshotManifest manifest = new SnapshotManifest(
                    1,
                    worldId,
                    createdAt.toString(),
                    dh.modVersion(),
                    dh.apiVersion(),
                    List.copyOf(snapshots)
            );
            Path manifestPath = directory.resolve("snapshot.json");
            Files.writeString(manifestPath, GSON.toJson(manifest));
            return new SnapshotResult(directory, manifestPath, List.copyOf(snapshots));
        } finally {
            if (!wasReadOnly) {
                try {
                    invoke(worldProxy, "setReadOnly", new Class<?>[]{boolean.class}, false);
                } catch (Exception e) {
                    GabConDhSync.LOGGER.error("[GabConDHSync] Failed to restore DH read/write mode after snapshot", e);
                }
            }
        }
    }

    private static Object getWorldProxy() throws Exception {
        Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
        Field field = delayed.getField("worldProxy");
        Object proxy = field.get(null);
        if (proxy == null) throw new IllegalStateException("Distant Horizons worldProxy is not initialized");
        return proxy;
    }

    private static Object invoke(Object target, String method) throws Exception {
        return invoke(target, method, new Class<?>[0]);
    }

    private static Object invoke(Object target, String method, Class<?>[] parameterTypes, Object... args) throws Exception {
        try {
            Method m = target.getClass().getMethod(method, parameterTypes);
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    static String safeStem(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        safe = safe.replaceAll("^[_\\.]+|[_\\.]+$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    private DhSnapshotService() {}
}
