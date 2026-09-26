package be.gabcon.dhsync.server;

import be.gabcon.dhsync.sync.DhDeltaApplier;
import be.gabcon.dhsync.sync.DhReverseDeltaBuilder;
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

class DhReverseDeltaBuilderTest {
    @TempDir Path temp;

    @Test
    void reverseDeltaRestoresOldLogicalStateAfterForwardApply() throws Exception {
        Fixture fixture = fixture("restore");

        Path client = temp.resolve("client.sqlite");
        Files.copy(fixture.oldDb(), client);
        Path reverse = temp.resolve("reverse.sqlite");

        var built = DhReverseDeltaBuilder.build(
                client,
                fixture.deltaDb(),
                reverse,
                Hashes.sha256(fixture.oldDb())
        );
        assertTrue(Files.isRegularFile(built.rollbackDelta()));

        var forward = DhDeltaApplier.applyToWorkingCopy(
                client,
                fixture.deltaDb(),
                Hashes.sha256(fixture.oldDb())
        );
        assertEquals(Hashes.sha256(fixture.newDb()), forward.nextServerBaselineSha256());
        assertSemanticEquality(client, fixture.newDb());

        var restored = DhDeltaApplier.applyToWorkingCopy(
                client,
                reverse,
                forward.nextServerBaselineSha256()
        );
        assertEquals(Hashes.sha256(fixture.oldDb()), restored.nextServerBaselineSha256());
        assertSemanticEquality(client, fixture.oldDb());
    }

    @Test
    void reverseDeltaIsSafeWhenForwardDeltaNeverCommitted() throws Exception {
        Fixture fixture = fixture("idempotent");

        Path client = temp.resolve("unchanged-client.sqlite");
        Files.copy(fixture.oldDb(), client);
        Path reverse = temp.resolve("idempotent-reverse.sqlite");

        var built = DhReverseDeltaBuilder.build(
                client,
                fixture.deltaDb(),
                reverse,
                Hashes.sha256(fixture.oldDb())
        );

        var restored = DhDeltaApplier.applyToWorkingCopy(
                client,
                built.rollbackDelta(),
                built.baselineAfter()
        );

        assertEquals(built.baselineBefore(), restored.nextServerBaselineSha256());
        assertSemanticEquality(client, fixture.oldDb());
    }

    @Test
    void reverseDeltaIsMuchSmallerThanUntouchedRows() throws Exception {
        Fixture fixture = fixture("compact");

        Path client = temp.resolve("compact-client.sqlite");
        Files.copy(fixture.oldDb(), client);
        Path reverse = temp.resolve("compact-reverse.sqlite");

        var built = DhReverseDeltaBuilder.build(
                client,
                fixture.deltaDb(),
                reverse,
                Hashes.sha256(fixture.oldDb())
        );

        long operations = built.tables().stream()
                .mapToLong(t -> t.restoreRows() + t.deleteRows())
                .sum();
        assertEquals(6, operations);
        assertTrue(Files.size(reverse) < 1024 * 1024);
    }

    private Fixture fixture(String name) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path oldDir = temp.resolve(name + "-old");
        Path newDir = temp.resolve(name + "-new");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        Path oldDb = oldDir.resolve("minecraft_overworld.sqlite");
        Path newDb = newDir.resolve("minecraft_overworld.sqlite");
        createSchema(oldDb);
        createSchema(newDb);
        seedOld(oldDb);
        seedNew(newDb);

        writeManifest(oldDir, "2026-09-22T14:01:32Z", oldDb);
        writeManifest(newDir, "2026-09-22T14:15:55Z", newDb);

        var delta = DhDeltaBuilder.build(
                oldDir,
                newDir,
                "gabcon-main",
                temp.resolve(name + "-deltas")
        );
        assertEquals(1, delta.files().size());
        Path deltaDb = delta.directory().resolve(delta.files().getFirst().fileName());
        return new Fixture(oldDb, newDb, deltaDb);
    }

    private record Fixture(Path oldDb, Path newDb, Path deltaDb) {}

    private static void createSchema(Path db) throws Exception {
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

    private static long scalar(Connection c, String sql) throws Exception {
        try (var rs = c.createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
