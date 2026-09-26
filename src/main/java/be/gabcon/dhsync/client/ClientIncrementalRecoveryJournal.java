package be.gabcon.dhsync.client;

import be.gabcon.dhsync.sync.DhDeltaApplier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class ClientIncrementalRecoveryJournal {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 1;

    public enum Phase {
        APPLYING,
        STATE_COMMITTED
    }

    public record Entry(
            String dimension,
            String targetPath,
            String reverseDeltaPath,
            String baselineBefore,
            String baselineAfter
    ) {}

    public record Journal(
            int schemaVersion,
            String serverAddress,
            Phase phase,
            ClientSyncState.ServerProfile previousProfile,
            List<Entry> entries
    ) {}

    public static Path recoveryDirectory() {
        return FMLPaths.GAMEDIR.get()
                .resolve("gabcondhsync")
                .resolve("recovery")
                .toAbsolutePath().normalize();
    }

    public static Path pathFor(String serverAddress) {
        return recoveryDirectory()
                .resolve(keyHash(ClientSyncStateStore.normalizeServerAddress(serverAddress)) + ".incremental.json")
                .toAbsolutePath().normalize();
    }

    public static Path reverseDeltaPath(String serverAddress, String dimension, int index) {
        String server = keyHash(ClientSyncStateStore.normalizeServerAddress(serverAddress));
        String safeDimension = dimension == null ? "unknown" : dimension.replaceAll("[^A-Za-z0-9._-]+", "_");
        return recoveryDirectory()
                .resolve(server + "-" + safeDimension + "-" + index + ".reverse.sqlite")
                .toAbsolutePath().normalize();
    }

    public static int recoverAll() throws Exception {
        Path root = recoveryDirectory();
        if (!Files.isDirectory(root)) return 0;
        int recovered = 0;
        try (var stream = Files.list(root)) {
            for (Path file : stream
                    .filter(p -> p.getFileName().toString().endsWith(".incremental.json"))
                    .toList()) {
                Journal journal = read(file);
                if (recoverIfPresent(journal.serverAddress())) recovered++;
            }
        }
        return recovered;
    }

    public static void write(
            String serverAddress,
            Phase phase,
            ClientSyncState.ServerProfile previousProfile,
            List<Entry> entries
    ) throws IOException {
        Journal journal = new Journal(
                SCHEMA_VERSION,
                ClientSyncStateStore.normalizeServerAddress(serverAddress),
                phase,
                previousProfile,
                List.copyOf(entries)
        );
        Path file = pathFor(serverAddress);
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, GSON.toJson(journal));
        atomicReplace(part, file);
    }

    public static void delete(String serverAddress) throws IOException {
        Files.deleteIfExists(pathFor(serverAddress));
    }

    public static boolean recoverIfPresent(String serverAddress) throws Exception {
        Path file = pathFor(serverAddress);
        if (!Files.isRegularFile(file)) return false;

        Journal journal = read(file);
        if (journal.phase() == Phase.STATE_COMMITTED) {
            cleanupReverseDeltas(journal.entries());
            Files.deleteIfExists(file);
            return true;
        }

        List<Entry> entries = journal.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            Path target = Path.of(entry.targetPath()).toAbsolutePath().normalize();
            Path reverse = Path.of(entry.reverseDeltaPath()).toAbsolutePath().normalize();

            if (!Files.isRegularFile(reverse)) {
                throw new IOException("Incremental rollback delta is missing: " + reverse);
            }
            if (!Files.isRegularFile(target)) {
                throw new IOException("Incremental rollback target is missing: " + target);
            }

            DhOfflineFiles.requireInactive(target);
            DhDeltaApplier.WorkingApplyResult restored = DhDeltaApplier.applyToWorkingCopy(
                    target,
                    reverse,
                    entry.baselineAfter()
            );
            if (!restored.nextServerBaselineSha256().equalsIgnoreCase(entry.baselineBefore())) {
                throw new IOException("Incremental rollback baseline mismatch for " + entry.dimension());
            }
            Files.deleteIfExists(reverse);
        }

        if (journal.previousProfile() != null) {
            ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(), journal.previousProfile());
        }
        Files.deleteIfExists(file);
        return true;
    }

    public static void cleanupReverseDeltas(List<Entry> entries) {
        if (entries == null) return;
        for (Entry entry : entries) {
            try {
                Files.deleteIfExists(Path.of(entry.reverseDeltaPath()));
            } catch (IOException ignored) {
                // A stale compact rollback is harmless and can be removed later.
            }
        }
    }

    private static Journal read(Path file) throws IOException {
        Journal journal;
        try {
            journal = GSON.fromJson(Files.readString(file), Journal.class);
        } catch (RuntimeException e) {
            throw new IOException("Unreadable GabCon incremental recovery journal: " + file, e);
        }
        if (journal == null
                || journal.schemaVersion() != SCHEMA_VERSION
                || journal.serverAddress() == null
                || journal.phase() == null
                || journal.entries() == null) {
            throw new IOException("Invalid GabCon incremental recovery journal: " + file);
        }
        return journal;
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String keyHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ClientIncrementalRecoveryJournal() {}
}
