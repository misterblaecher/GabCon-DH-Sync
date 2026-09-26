package be.gabcon.dhsync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class DhSqliteSnapshotterTest {
    @TempDir Path temp;

    @Test
    void createsVerifiedBackup() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path source = temp.resolve("source.sqlite");
        Path destination = temp.resolve("snapshot.sqlite");

        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + source);
             var statement = conn.createStatement()) {
            statement.execute("CREATE TABLE FullData (DetailLevel INTEGER, PosX INTEGER, PosZ INTEGER, Data BLOB, PRIMARY KEY(DetailLevel, PosX, PosZ))");
            try (var prepared = conn.prepareStatement("INSERT INTO FullData VALUES (?,?,?,?)")) {
                prepared.setInt(1, 0);
                prepared.setInt(2, 12);
                prepared.setInt(3, -7);
                prepared.setBytes(4, new byte[]{1,2,3,4});
                prepared.executeUpdate();
            }
        }

        var result = DhSqliteSnapshotter.backup(source, destination);
        assertEquals(destination.toAbsolutePath().normalize(), result.path());
        assertTrue(result.size() > 0);
        assertEquals(64, result.sha256().length());
        assertFalse(Files.exists(destination.resolveSibling(destination.getFileName() + ".part")));

        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + destination);
             var rs = conn.createStatement().executeQuery("SELECT COUNT(*), MAX(PosX), MIN(PosZ) FROM FullData")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(12, rs.getInt(2));
            assertEquals(-7, rs.getInt(3));
        }
    }

    @Test
    void rejectsSourceAsDestination() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path source = temp.resolve("same.sqlite");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            // Create the database file.
        }
        assertThrows(Exception.class, () -> DhSqliteSnapshotter.backup(source, source));
    }

    @Test
    void safeStemProducesPortableNames() {
        assertEquals("minecraft_overworld", DhSnapshotService.safeStem("minecraft:overworld"));
        assertEquals("custom_dimension_name", DhSnapshotService.safeStem("custom/dimension:name"));
        assertEquals("unknown", DhSnapshotService.safeStem("...___"));
    }
}
