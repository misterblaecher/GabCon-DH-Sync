package be.gabcon.dhsync.client;

import be.gabcon.dhsync.server.DhSqliteSnapshotter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientDatabaseCommitter {
    public record PreparedDimension(
            String dimension,
            Path targetDatabase,
            Path workDatabase,
            String newServerBaselineSha256
    ) {}

    public record CommitResult(
            ClientSyncState.ServerProfile profile,
            List<Path> rollbackFiles
    ) {}

    public static CommitResult commit(
            ClientSyncState.ServerProfile previousProfile,
            List<PreparedDimension> prepared
    ) throws Exception {
        if (prepared.isEmpty()) return new CommitResult(previousProfile, List.of());

        List<ClientRecoveryJournal.Entry> entries = new ArrayList<>();
        for (PreparedDimension dimension : prepared) {
            Path target = dimension.targetDatabase().toAbsolutePath().normalize();
            Path work = dimension.workDatabase().toAbsolutePath().normalize();
            if (!Files.isRegularFile(work)) throw new IOException("Prepared DB missing: " + work);
            DhSqliteSnapshotter.verify(work);

            boolean originalExisted = Files.isRegularFile(target);
            if (originalExisted) DhOfflineFiles.requireInactive(target);
            Path rollback = uniqueRollbackPath(target);
            entries.add(new ClientRecoveryJournal.Entry(
                    dimension.dimension(),
                    target.toString(),
                    work.toString(),
                    rollback.toString(),
                    originalExisted
            ));
        }

        ClientRecoveryJournal.write(previousProfile.serverAddress(), ClientRecoveryJournal.Phase.PREPARED,
                previousProfile, entries);

        try {
            for (ClientRecoveryJournal.Entry entry : entries) {
                Path target = Path.of(entry.targetPath());
                Path work = Path.of(entry.workPath());
                Path rollback = Path.of(entry.rollbackPath());

                if (entry.originalExisted()) {
                    ClientRecoveryJournal.atomicReplace(target, rollback);
                }
                ClientRecoveryJournal.atomicReplace(work, target);
                DhSqliteSnapshotter.verify(target);
            }

            ClientRecoveryJournal.write(previousProfile.serverAddress(), ClientRecoveryJournal.Phase.TARGETS_INSTALLED,
                    previousProfile, entries);

            Map<String, ClientSyncState.DimensionState> dimensions =
                    new LinkedHashMap<>(previousProfile.dimensions());
            for (PreparedDimension dimension : prepared) {
                ClientSyncState.DimensionState old = dimensions.get(dimension.dimension());
                if (old == null) throw new IOException("Prepared dimension is not registered: " + dimension.dimension());
                dimensions.put(dimension.dimension(), new ClientSyncState.DimensionState(
                        old.databasePath(),
                        dimension.newServerBaselineSha256()
                ));
            }

            ClientSyncState.ServerProfile updated = new ClientSyncState.ServerProfile(
                    previousProfile.serverAddress(),
                    previousProfile.worldId(),
                    previousProfile.manifestUrl(),
                    Map.copyOf(dimensions)
            );
            ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(), updated);
            ClientRecoveryJournal.write(previousProfile.serverAddress(), ClientRecoveryJournal.Phase.STATE_COMMITTED,
                    previousProfile, entries);
            ClientRecoveryJournal.delete(previousProfile.serverAddress());

            List<Path> rollbacks = entries.stream()
                    .filter(ClientRecoveryJournal.Entry::originalExisted)
                    .map(e -> Path.of(e.rollbackPath()))
                    .toList();
            return new CommitResult(updated, rollbacks);
        } catch (Exception failure) {
            try {
                ClientRecoveryJournal.recoverIfPresent(previousProfile.serverAddress());
            } catch (Exception recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        }
    }

    private static Path uniqueRollbackPath(Path target) throws IOException {
        String prefix = target.getFileName() + ".gabcon-rollback-" + Instant.now().toEpochMilli();
        Path parent = target.getParent();
        for (int suffix = 0; suffix <= 10_000; suffix++) {
            String name = suffix == 0 ? prefix : prefix + "-" + suffix;
            Path candidate = parent.resolve(name);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("Unable to allocate rollback file for " + target);
    }

    private ClientDatabaseCommitter() {}
}
