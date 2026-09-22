package be.gabcon.dhsync.server;

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

class DhDeltaBuilderTest {
    @TempDir Path temp;

    @Test
    void buildsExactUpsertAndDeleteSets() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path oldDir = temp.resolve("old");
        Path newDir = temp.resolve("new");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        Path oldDb = oldDir.resolve("minecraft_overworld.sqlite");
        Path newDb = newDir.resolve("minecraft_overworld.sqlite");
        createSchema(oldDb);
        createSchema(newDb);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + oldDb)) {
            c.createStatement().execute("INSERT INTO FullData VALUES (0,1,1,10,X'01',100)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,2,2,20,X'02',100)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,3,3,30,X'03',100)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (1,1,111,100)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (2,2,222,100)");
            c.createStatement().execute("INSERT INTO BeaconBeam VALUES (1,64,1,255,0,0,100)");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + newDb)) {
            c.createStatement().execute("INSERT INTO FullData VALUES (0,1,1,10,X'01',100)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,2,2,21,X'99',200)");
            c.createStatement().execute("INSERT INTO FullData VALUES (0,4,4,40,X'04',200)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (1,1,111,100)");
            c.createStatement().execute("INSERT INTO ChunkHash VALUES (3,3,333,200)");
            c.createStatement().execute("INSERT INTO BeaconBeam VALUES (1,64,1,0,255,0,200)");
        }

        writeManifest(oldDir, "2026-09-22T14:01:32Z", oldDb, Hashes.sha256(oldDb));
        writeManifest(newDir, "2026-09-22T14:15:55Z", newDb, Hashes.sha256(newDb));

        DhDeltaBuilder.DeltaResult result =
                DhDeltaBuilder.build(oldDir, newDir, "gabcon-main", temp.resolve("deltas"));

        assertEquals(1, result.files().size());
        var file = result.files().getFirst();
        assertEquals("minecraft:overworld", file.dimension());
        assertEquals(6, file.tables().stream().mapToLong(t -> t.upserts() + t.deletes()).sum());

        Path deltaDb = result.directory().resolve(file.fileName());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + deltaDb)) {
            assertEquals(2, count(c, "FullDataUpsert"));
            assertEquals(1, count(c, "FullDataDelete"));
            assertEquals(1, count(c, "ChunkHashUpsert"));
            assertEquals(1, count(c, "ChunkHashDelete"));
            assertEquals(1, count(c, "BeaconBeamUpsert"));
            assertEquals(0, count(c, "BeaconBeamDelete"));

            try (var rs = c.createStatement().executeQuery(
                    "SELECT DataChecksum, hex(Data) FROM FullDataUpsert WHERE PosX=2 AND PosZ=2")) {
                assertTrue(rs.next());
                assertEquals(21, rs.getInt(1));
                assertEquals("99", rs.getString(2));
            }
        }
    }

    @Test
    void differentSnapshotHashesCanYieldZeroLogicalDelta() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path oldDir = temp.resolve("same-old");
        Path newDir = temp.resolve("same-new");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        Path oldDb = oldDir.resolve("minecraft_overworld.sqlite");
        Path newDb = newDir.resolve("minecraft_overworld.sqlite");
        createSchema(oldDb);
        Files.copy(oldDb, newDb);

        writeManifest(oldDir, "2026-09-22T14:01:32Z", oldDb, "a".repeat(64));
        writeManifest(newDir, "2026-09-22T14:15:55Z", newDb, "b".repeat(64));

        DhDeltaBuilder.DeltaResult result =
                DhDeltaBuilder.build(oldDir, newDir, "gabcon-main", temp.resolve("zero-deltas"));

        assertTrue(result.files().isEmpty());
        assertTrue(Files.isRegularFile(result.manifest()));
    }

    private static void createSchema(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.createStatement().execute("""
                    CREATE TABLE FullData(
                        DetailLevel INTEGER NOT NULL,
                        PosX INTEGER NOT NULL,
                        PosZ INTEGER NOT NULL,
                        DataChecksum INTEGER NOT NULL,
                        Data BLOB,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
                        PRIMARY KEY(DetailLevel,PosX,PosZ)
                    )
                    """);
            c.createStatement().execute("""
                    CREATE TABLE ChunkHash(
                        ChunkPosX INTEGER NOT NULL,
                        ChunkPosZ INTEGER NOT NULL,
                        ChunkHash INTEGER NOT NULL,
                        LastModifiedUnixDateTime INTEGER NOT NULL,
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
                        PRIMARY KEY(BlockPosX,BlockPosY,BlockPosZ)
                    )
                    """);
        }
    }

    private static void writeManifest(Path dir, String createdAt, Path db, String hash) throws Exception {
        var file = new DhSnapshotService.SnapshotFile(
                "minecraft:overworld",
                "minecraft:overworld",
                db.getFileName().toString(),
                Files.size(db),
                hash
        );
        var manifest = new DhSnapshotService.SnapshotManifest(
                1,
                "gabcon-main",
                Instant.parse(createdAt).toString(),
                "3.3.2",
                "7.2.0",
                List.of(file)
        );
        Files.writeString(dir.resolve("snapshot.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
    }

    private static long count(Connection c, String table) throws Exception {
        try (var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
