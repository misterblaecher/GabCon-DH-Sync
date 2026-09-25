package be.gabcon.dhsync.client;

import be.gabcon.dhsync.server.DhSqliteSnapshotter;
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

public final class ClientRecoveryJournal {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 1;

    public enum Phase {
        PREPARED,
        TARGETS_INSTALLED,
        STATE_COMMITTED
    }

    public record Entry(
            String dimension,
            String targetPath,
            String workPath,
            String rollbackPath,
            boolean originalExisted
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
                .resolve(keyHash(ClientSyncStateStore.normalizeServerAddress(serverAddress)) + ".json")
                .toAbsolutePath().normalize();
    }

    public static int recoverAll() throws Exception {
        Path root = recoveryDirectory();
        if (!Files.isDirectory(root)) return 0;
        int recovered = 0;
        try (var stream = Files.list(root)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Journal journal;
                try {
                    journal = GSON.fromJson(Files.readString(file), Journal.class);
                } catch (RuntimeException e) {
                    throw new IOException("Unreadable GabCon recovery journal: " + file, e);
                }
                if (journal == null || journal.serverAddress() == null) {
                    throw new IOException("Invalid GabCon recovery journal: " + file);
                }
                if (recoverIfPresent(journal.serverAddress())) recovered++;
            }
        }
        return recovered;
    }

    public static void write(String serverAddress, Phase phase, ClientSyncState.ServerProfile previousProfile, List<Entry> entries)
            throws IOException {
        Journal journal = new Journal(SCHEMA_VERSION, ClientSyncStateStore.normalizeServerAddress(serverAddress),
                phase, previousProfile, List.copyOf(entries));
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

        Journal journal;
        try {
            journal = GSON.fromJson(Files.readString(file), Journal.class);
        } catch (RuntimeException e) {
            throw new IOException("Unreadable GabCon recovery journal: " + file, e);
        }
        if (journal == null || journal.schemaVersion() != SCHEMA_VERSION || journal.entries() == null) {
            throw new IOException("Invalid GabCon recovery journal: " + file);
        }

        if (journal.phase() == Phase.STATE_COMMITTED) {
            for (Entry entry : journal.entries()) Files.deleteIfExists(Path.of(entry.workPath()));
            Files.deleteIfExists(file);
            return true;
        }

        for (int i = journal.entries().size() - 1; i >= 0; i--) {
            Entry entry = journal.entries().get(i);
            Path target = Path.of(entry.targetPath()).toAbsolutePath().normalize();
            Path work = Path.of(entry.workPath()).toAbsolutePath().normalize();
            Path rollback = Path.of(entry.rollbackPath()).toAbsolutePath().normalize();

            if (Files.isRegularFile(rollback)) {
                Files.deleteIfExists(target);
                atomicReplace(rollback, target);
                DhSqliteSnapshotter.verify(target);
            } else if (!entry.originalExisted()) {
                Files.deleteIfExists(target);
            }
            Files.deleteIfExists(work);
        }

        if (journal.previousProfile() != null) {
            ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(), journal.previousProfile());
        }
        Files.deleteIfExists(file);
        return true;
    }

    static void atomicReplace(Path source, Path target) throws IOException {
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

    private ClientRecoveryJournal() {}
}
