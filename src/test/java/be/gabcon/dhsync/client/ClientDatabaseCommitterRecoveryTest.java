package be.gabcon.dhsync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientDatabaseCommitterRecoveryTest {
    private static final String OLD_BASELINE = "a".repeat(64);
    private static final String NEW_BASELINE = "b".repeat(64);

    @TempDir Path temp;

    @Test
    void crashAfterPreparedRestoresPreviousStateAndLeavesOriginalTarget() throws Exception {
        Scenario s = scenario("prepared");
        assertThrows(ClientDatabaseCommitter.SimulatedCrash.class, () ->
                ClientDatabaseCommitter.commit(s.previous, s.prepared, temp, new ClientDatabaseCommitter.FaultInjector() {
                    @Override
                    public void afterPrepared(List<ClientRecoveryJournal.Entry> entries) {
                        throw new ClientDatabaseCommitter.SimulatedCrash("after prepared");
                    }
                })
        );

        assertEquals(1, value(s.target));
        assertTrue(ClientRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(1, value(s.target));
        assertPreviousState(s.previous);
        assertFalse(Files.exists(s.work));
        assertFalse(ClientRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
    }

    @Test
    void crashAfterTargetInstallRollsBackInstalledDatabase() throws Exception {
        Scenario s = scenario("installed");
        assertThrows(ClientDatabaseCommitter.SimulatedCrash.class, () ->
                ClientDatabaseCommitter.commit(s.previous, s.prepared, temp, new ClientDatabaseCommitter.FaultInjector() {
                    @Override
                    public void afterTargetInstalled(int index, ClientRecoveryJournal.Entry entry) {
                        throw new ClientDatabaseCommitter.SimulatedCrash("after target install");
                    }
                })
        );

        assertEquals(2, value(s.target));
        assertTrue(ClientRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(1, value(s.target));
        assertPreviousState(s.previous);
    }

    @Test
    void crashAfterTargetsInstalledRollsBackBeforeStateCommit() throws Exception {
        Scenario s = scenario("targets-installed");
        assertThrows(ClientDatabaseCommitter.SimulatedCrash.class, () ->
                ClientDatabaseCommitter.commit(s.previous, s.prepared, temp, new ClientDatabaseCommitter.FaultInjector() {
                    @Override
                    public void afterTargetsInstalled(List<ClientRecoveryJournal.Entry> entries) {
                        throw new ClientDatabaseCommitter.SimulatedCrash("after targets installed");
                    }
                })
        );

        assertEquals(2, value(s.target));
        assertTrue(ClientRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(1, value(s.target));
        assertPreviousState(s.previous);
    }

    @Test
    void crashAfterClientStateWriteRollsBackDatabaseAndProfile() throws Exception {
        Scenario s = scenario("state-written");
        assertThrows(ClientDatabaseCommitter.SimulatedCrash.class, () ->
                ClientDatabaseCommitter.commit(s.previous, s.prepared, temp, new ClientDatabaseCommitter.FaultInjector() {
                    @Override
                    public void afterStateWritten(ClientSyncState.ServerProfile updated) {
                        throw new ClientDatabaseCommitter.SimulatedCrash("after state write");
                    }
                })
        );

        assertEquals(2, value(s.target));
        assertEquals(NEW_BASELINE, currentBaseline(s.previous.serverAddress()));

        assertTrue(ClientRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(1, value(s.target));
        assertPreviousState(s.previous);
    }

    @Test
    void crashAfterStateCommittedKeepsNewDatabaseAndNewProfile() throws Exception {
        Scenario s = scenario("state-committed");
        assertThrows(ClientDatabaseCommitter.SimulatedCrash.class, () ->
                ClientDatabaseCommitter.commit(s.previous, s.prepared, temp, new ClientDatabaseCommitter.FaultInjector() {
                    @Override
                    public void afterStateCommitted(List<ClientRecoveryJournal.Entry> entries) {
                        throw new ClientDatabaseCommitter.SimulatedCrash("after state committed");
                    }
                })
        );

        assertEquals(2, value(s.target));
        assertEquals(NEW_BASELINE, currentBaseline(s.previous.serverAddress()));

        assertTrue(ClientRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(2, value(s.target));
        assertEquals(NEW_BASELINE, currentBaseline(s.previous.serverAddress()));
        assertFalse(ClientRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
    }

    @Test
    void crashAfterFirstOfTwoTargetsRestoresBothDimensions() throws Exception {
        Path target1 = temp.resolve("overworld.sqlite");
        Path target2 = temp.resolve("nether.sqlite");
        Path work1 = temp.resolve("overworld.work.sqlite");
        Path work2 = temp.resolve("nether.work.sqlite");
        createDb(target1, 1);
        createDb(target2, 10);
        createDb(work1, 2);
        createDb(work2, 20);

        var previous = new ClientSyncState.ServerProfile(
                "example.org:25565",
                "gabcon-main",
                "https://example.org/manifest.json",
                Map.of(
                        "minecraft:overworld", new ClientSyncState.DimensionState(target1.toString(), OLD_BASELINE),
                        "minecraft:the_nether", new ClientSyncState.DimensionState(target2.toString(), OLD_BASELINE)
                )
        );
        ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(temp), previous);
        var prepared = List.of(
                new ClientDatabaseCommitter.PreparedDimension("minecraft:overworld", target1, work1, NEW_BASELINE),
                new ClientDatabaseCommitter.PreparedDimension("minecraft:the_nether", target2, work2, NEW_BASELINE)
        );

        assertThrows(ClientDatabaseCommitter.SimulatedCrash.class, () ->
                ClientDatabaseCommitter.commit(previous, prepared, temp, new ClientDatabaseCommitter.FaultInjector() {
                    @Override
                    public void afterTargetInstalled(int index, ClientRecoveryJournal.Entry entry) {
                        if (index == 0) throw new ClientDatabaseCommitter.SimulatedCrash("after first target");
                    }
                })
        );

        assertEquals(2, value(target1));
        assertEquals(10, value(target2));
        assertTrue(ClientRecoveryJournal.recoverIfPresent(temp, previous.serverAddress()));
        assertEquals(1, value(target1));
        assertEquals(10, value(target2));
        assertEquals(OLD_BASELINE, currentBaseline(previous.serverAddress()));
    }

    private Scenario scenario(String stem) throws Exception {
        Path target = temp.resolve(stem + "-target.sqlite");
        Path work = temp.resolve(stem + "-work.sqlite");
        createDb(target, 1);
        createDb(work, 2);

        var previous = new ClientSyncState.ServerProfile(
                "example.org:25565",
                "gabcon-main",
                "https://example.org/manifest.json",
                Map.of("minecraft:overworld",
                        new ClientSyncState.DimensionState(target.toString(), OLD_BASELINE))
        );
        ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(temp), previous);
        var prepared = List.of(
                new ClientDatabaseCommitter.PreparedDimension(
                        "minecraft:overworld", target, work, NEW_BASELINE
                )
        );
        return new Scenario(previous, prepared, target, work);
    }

    private void assertPreviousState(ClientSyncState.ServerProfile previous) throws Exception {
        assertEquals(OLD_BASELINE, currentBaseline(previous.serverAddress()));
    }

    private String currentBaseline(String server) throws Exception {
        return ClientSyncStateStore.find(ClientSyncStateStore.defaultPath(temp), server)
                .orElseThrow()
                .dimensions()
                .get("minecraft:overworld")
                .serverBaselineSha256();
    }

    private static void createDb(Path path, int value) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            c.createStatement().execute("CREATE TABLE Sample(Id INTEGER PRIMARY KEY, Value INTEGER NOT NULL)");
            c.createStatement().execute("INSERT INTO Sample VALUES (1," + value + ")");
        }
    }

    private static int value(Path path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
             var rs = c.createStatement().executeQuery("SELECT Value FROM Sample WHERE Id=1")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private record Scenario(
            ClientSyncState.ServerProfile previous,
            List<ClientDatabaseCommitter.PreparedDimension> prepared,
            Path target,
            Path work
    ) {}
}
