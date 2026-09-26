package be.gabcon.dhsync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientSyncStateStoreTest {
    @TempDir Path temp;

    @Test
    void savesLoadsAndReplacesManagedProfileAtomically() throws Exception {
        Path state = temp.resolve("client-state.json");
        var first = profile("Example.Org:25565", null);
        ClientSyncStateStore.upsert(state, first);

        var loaded = ClientSyncStateStore.find(state, "example.org:25565").orElseThrow();
        assertEquals("gabcon-main", loaded.worldId());
        assertNull(loaded.dimensions().get("minecraft:overworld").serverBaselineSha256());

        String baseline = "a".repeat(64);
        var second = profile("example.org:25565", baseline);
        ClientSyncStateStore.upsert(state, second);
        var updated = ClientSyncStateStore.find(state, "EXAMPLE.ORG:25565").orElseThrow();
        assertEquals(baseline, updated.dimensions().get("minecraft:overworld").serverBaselineSha256());
        assertFalse(Files.exists(state.resolveSibling("client-state.json.part")));
    }

    @Test
    void rejectsInsecureManifestUrl() {
        var profile = new ClientSyncState.ServerProfile(
                "example.org:25565", "gabcon-main", "http://example.org/manifest.json",
                Map.of("minecraft:overworld",
                        new ClientSyncState.DimensionState(temp.resolve("db.sqlite").toString(), null))
        );
        assertThrows(Exception.class, () -> ClientSyncStateStore.validateProfile(profile));
    }

    private ClientSyncState.ServerProfile profile(String server, String baseline) {
        return new ClientSyncState.ServerProfile(
                server,
                "gabcon-main",
                "https://example.org/manifest.json",
                Map.of("minecraft:overworld",
                        new ClientSyncState.DimensionState(temp.resolve("DistantHorizons.sqlite").toString(), baseline))
        );
    }
}
