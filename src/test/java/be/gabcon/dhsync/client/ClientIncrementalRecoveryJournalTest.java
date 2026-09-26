package be.gabcon.dhsync.client;

import be.gabcon.dhsync.sync.DhDeltaApplier;
import be.gabcon.dhsync.sync.DhReverseDeltaBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientIncrementalRecoveryJournalTest {
    private static final String OLD_BASELINE = "a".repeat(64);
    private static final String NEW_BASELINE = "b".repeat(64);

    @TempDir Path temp;

    @Test
    void applyingJournalReversesCommittedForwardDeltaAndRestoresProfile() throws Exception {
        Scenario s = scenario("apply");
        applyForwardWithJournal(s, ClientIncrementalRecoveryJournal.Phase.APPLYING);

        assertEquals(20, checksum(s.target));
        assertTrue(ClientIncrementalRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));

        assertEquals(10, checksum(s.target));
        assertEquals(OLD_BASELINE, currentBaseline(s.previous.serverAddress()));
        assertFalse(Files.exists(s.reverse));
        assertFalse(ClientIncrementalRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
    }

    @Test
    void applyingJournalAlsoRollsBackWhenClientStateWasAlreadyWritten() throws Exception {
        Scenario s = scenario("state-written");
        applyForwardWithJournal(s, ClientIncrementalRecoveryJournal.Phase.APPLYING);

        var updated = profile(s.target, NEW_BASELINE);
        ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(temp), updated);
        assertEquals(NEW_BASELINE, currentBaseline(s.previous.serverAddress()));

        assertTrue(ClientIncrementalRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(10, checksum(s.target));
        assertEquals(OLD_BASELINE, currentBaseline(s.previous.serverAddress()));
    }

    @Test
    void stateCommittedJournalKeepsForwardResultAndOnlyCleansRecoveryArtifacts() throws Exception {
        Scenario s = scenario("committed");
        applyForwardWithJournal(s, ClientIncrementalRecoveryJournal.Phase.STATE_COMMITTED);
        ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(temp), profile(s.target, NEW_BASELINE));

        assertTrue(ClientIncrementalRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(20, checksum(s.target));
        assertEquals(NEW_BASELINE, currentBaseline(s.previous.serverAddress()));
        assertFalse(Files.exists(s.reverse));
        assertFalse(Files.exists(ClientIncrementalRecoveryJournal.pathFor(temp, s.previous.serverAddress())));
    }

    @Test
    void missingReverseDeltaFailsSafeAndKeepsJournal() throws Exception {
        Scenario s = scenario("missing");
        var entry = new ClientIncrementalRecoveryJournal.Entry(
                "minecraft:overworld",
                s.target.toString(),
                s.reverse.toString(),
                OLD_BASELINE,
                NEW_BASELINE
        );
        ClientIncrementalRecoveryJournal.write(
                temp,
                s.previous.serverAddress(),
                ClientIncrementalRecoveryJournal.Phase.APPLYING,
                s.previous,
                List.of(entry)
        );

        Exception ex = assertThrows(Exception.class,
                () -> ClientIncrementalRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertTrue(ex.getMessage().contains("missing"));
        assertTrue(Files.exists(ClientIncrementalRecoveryJournal.pathFor(temp, s.previous.serverAddress())));
        assertEquals(10, checksum(s.target));
    }

    @Test
    void corruptJournalFailsWithoutTouchingDatabase() throws Exception {
        Scenario s = scenario("corrupt");
        Path journal = ClientIncrementalRecoveryJournal.pathFor(temp, s.previous.serverAddress());
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "{ definitely-not-json");

        assertThrows(Exception.class,
                () -> ClientIncrementalRecoveryJournal.recoverIfPresent(temp, s.previous.serverAddress()));
        assertEquals(10, checksum(s.target));
        assertTrue(Files.exists(journal));
    }

    @Test
    void multiDimensionRecoveryRunsReverseOrderAndRestoresBoth() throws Exception {
        Path overworld = temp.resolve("multi-overworld.sqlite");
        Path nether = temp.resolve("multi-nether.sqlite");
        Path delta1 = temp.resolve("multi-overworld.delta.sqlite");
        Path delta2 = temp.resolve("multi-nether.delta.sqlite");
        createTarget(overworld, 10);
        createTarget(nether, 30);
        createForwardDelta(delta1, OLD_BASELINE, NEW_BASELINE, 20);
        createForwardDelta(delta2, OLD_BASELINE, NEW_BASELINE, 40);

        var previous = new ClientSyncState.ServerProfile(
                "example.org:25565",
                "gabcon-main",
                "https://example.org/manifest.json",
                Map.of(
                        "minecraft:overworld", new ClientSyncState.DimensionState(overworld.toString(), OLD_BASELINE),
                        "minecraft:the_nether", new ClientSyncState.DimensionState(nether.toString(), OLD_BASELINE)
                )
        );
        ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(temp), previous);

        Path reverse1 = ClientIncrementalRecoveryJournal.reverseDeltaPath(
                temp, previous.serverAddress(), "minecraft:overworld", 0);
        Path reverse2 = ClientIncrementalRecoveryJournal.reverseDeltaPath(
                temp, previous.serverAddress(), "minecraft:the_nether", 1);
        var r1 = DhReverseDeltaBuilder.build(overworld, delta1, reverse1, OLD_BASELINE);
        var r2 = DhReverseDeltaBuilder.build(nether, delta2, reverse2, OLD_BASELINE);

        var entries = List.of(
                new ClientIncrementalRecoveryJournal.Entry(
                        "minecraft:overworld", overworld.toString(), reverse1.toString(),
                        r1.baselineBefore(), r1.baselineAfter()),
                new ClientIncrementalRecoveryJournal.Entry(
                        "minecraft:the_nether", nether.toString(), reverse2.toString(),
                        r2.baselineBefore(), r2.baselineAfter())
        );
        ClientIncrementalRecoveryJournal.write(
                temp, previous.serverAddress(),
                ClientIncrementalRecoveryJournal.Phase.APPLYING,
                previous, entries
        );

        DhDeltaApplier.applyToPrecheckedWorkingCopy(overworld, delta1, OLD_BASELINE);
        DhDeltaApplier.applyToPrecheckedWorkingCopy(nether, delta2, OLD_BASELINE);
        assertEquals(20, checksum(overworld));
        assertEquals(40, checksum(nether));

        assertTrue(ClientIncrementalRecoveryJournal.recoverIfPresent(temp, previous.serverAddress()));
        assertEquals(10, checksum(overworld));
        assertEquals(30, checksum(nether));
    }

    private Scenario scenario(String stem) throws Exception {
        Path target = temp.resolve(stem + "-target.sqlite");
        Path delta = temp.resolve(stem + "-forward.sqlite");
        createTarget(target, 10);
        createForwardDelta(delta, OLD_BASELINE, NEW_BASELINE, 20);

        var previous = profile(target, OLD_BASELINE);
        ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(temp), previous);
        Path reverse = ClientIncrementalRecoveryJournal.reverseDeltaPath(
                temp, previous.serverAddress(), "minecraft:overworld", 0
        );
        return new Scenario(previous, target, delta, reverse);
    }

    private void applyForwardWithJournal(
            Scenario s,
            ClientIncrementalRecoveryJournal.Phase phase
    ) throws Exception {
        DhReverseDeltaBuilder.Result reverse =
                DhReverseDeltaBuilder.build(s.target, s.delta, s.reverse, OLD_BASELINE);
        var entry = new ClientIncrementalRecoveryJournal.Entry(
                "minecraft:overworld",
                s.target.toString(),
                s.reverse.toString(),
                reverse.baselineBefore(),
                reverse.baselineAfter()
        );
        ClientIncrementalRecoveryJournal.write(
                temp,
                s.previous.serverAddress(),
                phase,
                s.previous,
                List.of(entry)
        );
        DhDeltaApplier.applyToPrecheckedWorkingCopy(s.target, s.delta, OLD_BASELINE);
    }

    private ClientSyncState.ServerProfile profile(Path target, String baseline) {
        return new ClientSyncState.ServerProfile(
                "example.org:25565",
                "gabcon-main",
                "https://example.org/manifest.json",
                Map.of("minecraft:overworld", new ClientSyncState.DimensionState(target.toString(), baseline))
        );
    }

    private String currentBaseline(String server) throws Exception {
        return ClientSyncStateStore.find(ClientSyncStateStore.defaultPath(temp), server)
                .orElseThrow()
                .dimensions()
                .get("minecraft:overworld")
                .serverBaselineSha256();
    }

    private static void createTarget(Path path, int checksum) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            c.createStatement().execute("""
                    CREATE TABLE FullData(
                        DetailLevel INTEGER NOT NULL,
                        PosX INTEGER NOT NULL,
                        PosZ INTEGER NOT NULL,
                        MinY INTEGER NOT NULL,
                        DataChecksum INTEGER NOT NULL,
                        Data BLOB,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
                        CreatedUnixDateTime INTEGER NOT NULL,
                        PRIMARY KEY(DetailLevel,PosX,PosZ)
                    )
                    """);
            c.createStatement().execute("""
                    CREATE TABLE ChunkHash(
                        ChunkPosX INTEGER NOT NULL,
                        ChunkPosZ INTEGER NOT NULL,
                        ChunkHash INTEGER NOT NULL,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
                        CreatedUnixDateTime INTEGER NOT NULL,
                        PRIMARY KEY(ChunkPosX,ChunkPosZ)
                    )
                    """);
            c.createStatement().execute("""
                    CREATE TABLE BeaconBeam(
                        BlockPosX INTEGER NOT NULL,
                        BlockPosY INTEGER NOT NULL,
                        BlockPosZ INTEGER NOT NULL,
                        ColorR INTEGER NOT NULL,
                        ColorG INTEGER NOT NULL,
                        ColorB INTEGER NOT NULL,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
                        CreatedUnixDateTime INTEGER NOT NULL,
                        PRIMARY KEY(BlockPosX,BlockPosY,BlockPosZ)
                    )
                    """);
            c.createStatement().execute(
                    "INSERT INTO FullData VALUES (0,1,1,0," + checksum + ",X'01',100,50)"
            );
        }
    }

    private static void createForwardDelta(
            Path path,
            String oldBaseline,
            String newBaseline,
            int checksum
    ) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            c.createStatement().execute("CREATE TABLE DeltaMeta(Key TEXT PRIMARY KEY NOT NULL, Value TEXT NOT NULL)");
            c.createStatement().execute("INSERT INTO DeltaMeta VALUES ('format','gabcon-dh-delta-v1')");
            c.createStatement().execute("INSERT INTO DeltaMeta VALUES ('oldSha256','" + oldBaseline + "')");
            c.createStatement().execute("INSERT INTO DeltaMeta VALUES ('newSha256','" + newBaseline + "')");

            c.createStatement().execute("""
                    CREATE TABLE FullDataUpsert(
                        DetailLevel INTEGER NOT NULL,
                        PosX INTEGER NOT NULL,
                        PosZ INTEGER NOT NULL,
                        MinY INTEGER NOT NULL,
                        DataChecksum INTEGER NOT NULL,
                        Data BLOB,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
                        CreatedUnixDateTime INTEGER NOT NULL
                    )
                    """);
            c.createStatement().execute(
                    "INSERT INTO FullDataUpsert VALUES (0,1,1,0," + checksum + ",X'02',200,50)"
            );
            c.createStatement().execute(
                    "CREATE TABLE FullDataDelete(DetailLevel INTEGER NOT NULL, PosX INTEGER NOT NULL, PosZ INTEGER NOT NULL)"
            );

            c.createStatement().execute("""
                    CREATE TABLE ChunkHashUpsert(
                        ChunkPosX INTEGER NOT NULL,
                        ChunkPosZ INTEGER NOT NULL,
                        ChunkHash INTEGER NOT NULL,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
                        CreatedUnixDateTime INTEGER NOT NULL
                    )
                    """);
            c.createStatement().execute(
                    "CREATE TABLE ChunkHashDelete(ChunkPosX INTEGER NOT NULL, ChunkPosZ INTEGER NOT NULL)"
            );

            c.createStatement().execute("""
                    CREATE TABLE BeaconBeamUpsert(
                        BlockPosX INTEGER NOT NULL,
                        BlockPosY INTEGER NOT NULL,
                        BlockPosZ INTEGER NOT NULL,
                        ColorR INTEGER NOT NULL,
                        ColorG INTEGER NOT NULL,
                        ColorB INTEGER NOT NULL,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
                        CreatedUnixDateTime INTEGER NOT NULL
                    )
                    """);
            c.createStatement().execute(
                    "CREATE TABLE BeaconBeamDelete(BlockPosX INTEGER NOT NULL, BlockPosY INTEGER NOT NULL, BlockPosZ INTEGER NOT NULL)"
            );
        }
    }

    private static int checksum(Path path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
             var rs = c.createStatement().executeQuery(
                     "SELECT DataChecksum FROM FullData WHERE DetailLevel=0 AND PosX=1 AND PosZ=1"
             )) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private record Scenario(
            ClientSyncState.ServerProfile previous,
            Path target,
            Path delta,
            Path reverse
    ) {}
}
