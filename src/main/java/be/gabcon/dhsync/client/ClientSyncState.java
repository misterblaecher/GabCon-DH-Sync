package be.gabcon.dhsync.client;

import java.util.Map;

public record ClientSyncState(
        int schemaVersion,
        Map<String, ServerProfile> servers
) {
    public static final int SCHEMA_VERSION = 1;

    public record ServerProfile(
            String serverAddress,
            String worldId,
            String manifestUrl,
            Map<String, DimensionState> dimensions
    ) {}

    public record DimensionState(
            String databasePath,
            String serverBaselineSha256
    ) {}
}
