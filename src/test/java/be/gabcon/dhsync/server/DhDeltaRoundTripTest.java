package be.gabcon.dhsync.server;

import be.gabcon.dhsync.sync.DhDeltaApplier;
import be.gabcon.dhsync.util.Hashes;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DhDeltaRoundTripTest {
    @TempDir Path temp;

    @Test
    void oldSnapshotPlusDeltaBecomesLogicallyEqualToNewSnapshot() throws Exception {
        Class.forName("org.sqlite.JDBC");

        Path oldDir = temp.resolve("old");
        Path newDir = temp.resolve("new");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        Path oldDb = oldDir.resolve("minecraft_overworld.sqlite");
        Path newDb = newDir.resolve("minecraft_overworld.sqlite");
        createDhSchema(oldDb);
        createDhSchema(newDb);

        seedOld(oldDb);
        seedNew(newDb);

        writeManifest(oldDir, "2026-09-22T14:01:32Z", oldDb);
        writeManifest(newDir, "2026-09-22T14:15:55Z", newDb);

        var delta = DhDeltaBuilder.build(oldDir, newDir, "gabcon-main", temp.resolve("deltas"));
        assertEquals(1, delta.files().size());

        Path clientDb = temp.resolve("client.sqlite");
        Files.copy(oldDb, clientDb);

        var applied = DhDeltaApplier.applyOffline(
                clientDb,
                delta.directory().resolve(delta.files().getFirst().fileName())
        );

        assertTrue(Files.isRegularFile(applied.rollbackBackup()));
        assertSemanticEquality(applied.rollbackBackup(), oldDb);
        assertSemanticEquality(clientDb, newDb);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + clientDb)) {
            assertEquals(3, count(c, "FullData"));
            assertEquals(2, count(c, "ChunkHash"));
            assertEquals(1, count(c, "BeaconBeam"));
        }
    }


    @Test
    void appliesASecondDeltaUsingTheServerBaselineChain() throws Exception {
        Class.forName("org.sqlite.JDBC");

        Path oldDir = temp.resolve("chain-old");
        Path midDir = temp.resolve("chain-mid");
        Path newDir = temp.resolve("chain-new");
        Files.createDirectories(oldDir);
        Files.createDirectories(midDir);
        Files.createDirectories(newDir);

        Path oldDb = oldDir.resolve("minecraft_overworld.sqlite");
        Path midDb = midDir.resolve("minecraft_overworld.sqlite");
        Path newDb = newDir.resolve("minecraft_overworld.sqlite");
        createDhSchema(oldDb);
        createDhSchema(midDb);
        createDhSchema(newDb);
        seedOld(oldDb);
        seedNew(midDb);
        seedFinal(newDb);

        writeManifest(oldDir, "2026-09-22T14:01:32Z", oldDb);
        writeManifest(midDir, "2026-09-22T14:15:55Z", midDb);
        writeManifest(newDir, "2026-09-22T14:30:00Z", newDb);

        var delta1 = DhDeltaBuilder.build(oldDir, midDir, "gabcon-main", temp.resolve("chain-delta-1"));
        var delta2 = DhDeltaBuilder.build(midDir, newDir, "gabcon-main", temp.resolve("chain-delta-2"));

        Path clientDb = temp.resolve("chain-client.sqlite");
        Files.copy(oldDb, clientDb);

        var first = DhDeltaApplier.applyOffline(
                clientDb,
                delta1.directory().resolve(delta1.files().getFirst().fileName())
        );
        assertEquals(Hashes.sha256(midDb), first.nextServerBaselineSha256());

        var second = DhDeltaApplier.applyOfflineChained(
                clientDb,
                delta2.directory().resolve(delta2.files().getFirst().fileName()),
                first.nextServerBaselineSha256()
        );
        assertEquals(Hashes.sha256(newDb), second.nextServerBaselineSha256());
        assertSemanticEquality(clientDb, newDb);
    }

    @Test
    void refusesWrongPhysicalBaselineBeforeChangingTarget() throws Exception {
        Class.forName("org.sqlite.JDBC");

        Path oldDir = temp.resolve("baseline-old");
        Path newDir = temp.resolve("baseline-new");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        Path oldDb = oldDir.resolve("minecraft_overworld.sqlite");
        Path newDb = newDir.resolve("minecraft_overworld.sqlite");
        createDhSchema(oldDb);
        createDhSchema(newDb);
        seedOld(oldDb);
        seedNew(newDb);

        writeManifest(oldDir, "2026-09-22T14:01:32Z", oldDb);
        writeManifest(newDir, "2026-09-22T14:15:55Z", newDb);

        var delta = DhDeltaBuilder.build(oldDir, newDir, "gabcon-main", temp.resolve("baseline-deltas"));
        Path clientDb = temp.resolve("wrong-client.sqlite");
        Files.copy(oldDb, clientDb);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + clientDb)) {
            c.createStatement().execute("UPDATE ChunkHash SET ChunkHash=999999 WHERE ChunkPosX=1 AND ChunkPosZ=1");
        }
        String before = Hashes.sha256(clientDb);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> DhDeltaApplier.applyOffline(
                        clientDb,
                        delta.directory().resolve(delta.files().getFirst().fileName())
                )
        );
        assertTrue(ex.getMessage().contains("baseline mismatch"));
        assertEquals(before, Hashes.sha256(clientDb));
    }

    private static void createDhSchema(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
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
            c.createStatement().execute("""
                    CREATE TABLE Legacy_FullData_V1(
                        DhSectionPos TEXT NOT NULL PRIMARY KEY,
                        MigrationFailed INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            c.createStatement().execute("""
                    CREATE TABLE Schema(
                        SchemaVersionId INTEGER PRIMARY KEY NOT NULL,
                        ScriptName TEXT NOT NULL UNIQUE,
                        AppliedDateTime TEXT NOT NULL
                    )
                    """);
            c.createStatement().execute("INSERT INTO Schema VALUES (1,'test-schema','2026-09-22T00:00:00Z')");
        }
    }

    private static void seedOld(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.createStatement().execute("INSERT INTO FullData VALUES (0,1,1,0,10,X'01',100,50)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,2,2,0,20,X'02',100,50)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,3,3,0,30,X'03',100,50)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (1,1,111,100,50)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (2,2,222,100,50)");
            c.createStatement().execute("INSERT INTO BeaconBeam VALUES (1,64,1,255,0,0,100,50)");
        }
    }

    private static void seedNew(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.createStatement().execute("INSERT INTO FullData VALUES (0,1,1,0,10,X'01',100,50)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,2,2,0,21,X'99',200,50)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,4,4,0,40,X'04',200,200)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (1,1,111,100,50)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (3,3,333,200,200)");
            c.createStatement().execute("INSERT INTO BeaconBeam VALUES (1,64,1,0,255,0,200,50)");
        }
    }


    private static void seedFinal(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.createStatement().execute("INSERT INTO FullData VALUES (0,1,1,0,10,X'01',100,50)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,2,2,0,22,X'AA',300,50)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,4,4,0,40,X'04',200,200)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,5,5,0,50,X'05',300,300)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (1,1,112,300,50)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (3,3,333,200,200)");
            c.createStatement().execute("INSERT INTO BeaconBeam VALUES (1,64,1,0,255,0,200,50)");
        }
    }

    private static void writeManifest(Path dir, String createdAt, Path db) throws Exception {
        var file = new DhSnapshotService.SnapshotFile(
                "minecraft:overworld",
                "minecraft:overworld",
                db.getFileName().toString(),
                Files.size(db),
                Hashes.sha256(db)
        );
        var manifest = new DhSnapshotService.SnapshotManifest(
                1,
                "gabcon-main",
                Instant.parse(createdAt).toString(),
                "3.3.2",
                "7.2.0",
                List.of(file)
        );
        Files.writeString(
                dir.resolve("snapshot.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest)
        );
    }

    private static void assertSemanticEquality(Path actual, Path expected) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + actual)) {
            c.createStatement().execute("ATTACH DATABASE '" + expected.toAbsolutePath() + "' AS expected");
            for (String table : List.of("FullData", "ChunkHash", "BeaconBeam", "Schema", "Legacy_FullData_V1")) {
                long diff = scalar(c,
                        "SELECT COUNT(*) FROM (SELECT * FROM main." + table
                                + " EXCEPT SELECT * FROM expected." + table + ")")
                        + scalar(c,
                        "SELECT COUNT(*) FROM (SELECT * FROM expected." + table
                                + " EXCEPT SELECT * FROM main." + table + ")");
                assertEquals(0, diff, "semantic mismatch in " + table);
            }
        }
    }

    private static long count(Connection c, String table) throws Exception {
        return scalar(c, "SELECT COUNT(*) FROM " + table);
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (var rs = c.createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
