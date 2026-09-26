package be.gabcon.dhsync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DhOfflineFilesTest {
    @TempDir Path temp;

    @Test
    void copyToWorkReportsProgressAndPreservesDatabase() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path source = temp.resolve("DistantHorizons.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            connection.createStatement().execute("CREATE TABLE T(Id INTEGER PRIMARY KEY, Value TEXT NOT NULL)");
            connection.createStatement().execute("INSERT INTO T(Value) VALUES ('gabcon')");
        }

        AtomicLong lastCopied = new AtomicLong(-1);
        AtomicLong lastTotal = new AtomicLong(-1);
        Path work = DhOfflineFiles.copyToWork(source, (copied, total) -> {
            assertTrue(copied >= 0);
            assertTrue(total >= copied);
            assertTrue(copied >= lastCopied.get());
            lastCopied.set(copied);
            lastTotal.set(total);
        });

        assertEquals(Files.size(source), lastTotal.get());
        assertEquals(lastTotal.get(), lastCopied.get());
        assertTrue(Files.isRegularFile(work));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + work);
             var rs = connection.createStatement().executeQuery("SELECT Value FROM T WHERE Id=1")) {
            assertTrue(rs.next());
            assertEquals("gabcon", rs.getString(1));
        }
    }
}
