package be.gabcon.dhsync.sync;

import be.gabcon.dhsync.server.DhSqliteSnapshotter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DhReverseDeltaBuilder {
    private static final String DRIVER_CLASS = "dh_sqlite.JDBC";
    private static final String JDBC_PREFIX = "jdbc:dh_sqlite:";
    private static final List<String> DATA_TABLES = List.of("FullData", "ChunkHash", "BeaconBeam");

    public record TableStats(String table, long restoreRows, long deleteRows) {}
    public record Result(Path rollbackDelta, String baselineBefore, String baselineAfter, List<TableStats> tables) {}

    private record Column(String name, int pkOrder) {}

    public static Result build(
            Path targetDatabase,
            Path forwardDelta,
            Path rollbackDelta,
            String currentServerBaselineSha256
    ) throws Exception {
        Path target = targetDatabase.toAbsolutePath().normalize();
        Path forward = forwardDelta.toAbsolutePath().normalize();
        Path rollback = rollbackDelta.toAbsolutePath().normalize();

        if (!Files.isRegularFile(target)) throw new IOException("Target DH database not found: " + target);
        if (!Files.isRegularFile(forward)) throw new IOException("Forward delta not found: " + forward);
        DhSqliteSnapshotter.verify(target);
        DhSqliteSnapshotter.verify(forward);

        DhDeltaApplier.DeltaMetadata meta = DhDeltaApplier.inspectDelta(forward);
        if (!meta.oldSha256().equalsIgnoreCase(currentServerBaselineSha256)) {
            throw new IllegalStateException(
                    "Forward delta baseline mismatch: current=" + currentServerBaselineSha256
                            + ", expected=" + meta.oldSha256()
            );
        }

        Files.createDirectories(rollback.getParent());
        Path part = rollback.resolveSibling(rollback.getFileName() + ".part");
        Files.deleteIfExists(part);
        Files.deleteIfExists(rollback);

        Class.forName(DRIVER_CLASS);
        try {
            List<TableStats> stats = new ArrayList<>();
            try (Connection conn = DriverManager.getConnection(JDBC_PREFIX + part);
                 Statement statement = conn.createStatement()) {
                statement.execute("PRAGMA journal_mode=OFF");
                statement.execute("PRAGMA synchronous=OFF");
                statement.execute("ATTACH DATABASE '" + sqliteQuote(target.toString()) + "' AS targetdb");
                statement.execute("ATTACH DATABASE '" + sqliteQuote(forward.toString()) + "' AS forwarddb");

                statement.execute("CREATE TABLE DeltaMeta (Key TEXT PRIMARY KEY NOT NULL, Value TEXT NOT NULL)");
                statement.execute("INSERT INTO DeltaMeta VALUES ('format','gabcon-dh-delta-v1')");
                statement.execute("INSERT INTO DeltaMeta VALUES ('oldSha256','" + sqliteQuote(meta.newSha256()) + "')");
                statement.execute("INSERT INTO DeltaMeta VALUES ('newSha256','" + sqliteQuote(meta.oldSha256()) + "')");

                for (String table : DATA_TABLES) {
                    stats.add(buildTable(conn, statement, table));
                }

                statement.execute("DETACH DATABASE forwarddb");
                statement.execute("DETACH DATABASE targetdb");
            }

            DhSqliteSnapshotter.verify(part);
            atomicReplace(part, rollback);
            return new Result(
                    rollback,
                    meta.oldSha256(),
                    meta.newSha256(),
                    List.copyOf(stats)
            );
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    private static TableStats buildTable(Connection conn, Statement statement, String table) throws SQLException {
        List<Column> targetColumns = columns(conn, "targetdb", table);
        if (targetColumns.isEmpty()) throw new SQLException("Target missing table: " + table);

        List<Column> primaryKey = targetColumns.stream()
                .filter(c -> c.pkOrder() > 0)
                .sorted(Comparator.comparingInt(Column::pkOrder))
                .toList();
        if (primaryKey.isEmpty()) throw new SQLException("Target table has no primary key: " + table);

        String qt = qident(table);
        String qForwardUpsert = qident(table + "Upsert");
        String qForwardDelete = qident(table + "Delete");
        String qReverseUpsert = qident(table + "Upsert");
        String qReverseDelete = qident(table + "Delete");

        List<String> upsertColumns = columnNames(conn, "forwarddb", table + "Upsert");
        List<String> deleteColumns = columnNames(conn, "forwarddb", table + "Delete");
        List<String> expectedColumns = targetColumns.stream().map(Column::name).toList();
        List<String> expectedPkColumns = primaryKey.stream().map(Column::name).toList();
        if (!expectedColumns.equals(upsertColumns)) {
            throw new SQLException("Forward upsert columns do not match target table " + table);
        }
        if (!expectedPkColumns.equals(deleteColumns)) {
            throw new SQLException("Forward delete keys do not match target table " + table);
        }

        statement.execute("CREATE TABLE " + qReverseUpsert
                + " AS SELECT * FROM targetdb." + qt + " WHERE 0");

        String pkSelect = primaryKey.stream()
                .map(c -> qident(c.name()))
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        statement.execute("CREATE TABLE " + qReverseDelete
                + " AS SELECT " + pkSelect + " FROM targetdb." + qt + " WHERE 0");

        String upsertMatch = primaryKey.stream()
                .map(c -> "u." + qident(c.name()) + " = t." + qident(c.name()))
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();
        String deleteMatch = primaryKey.stream()
                .map(c -> "d." + qident(c.name()) + " = t." + qident(c.name()))
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();

        long restoreRows = statement.executeUpdate(
                "INSERT INTO " + qReverseUpsert
                        + " SELECT t.* FROM targetdb." + qt + " t"
                        + " WHERE EXISTS (SELECT 1 FROM forwarddb." + qForwardUpsert + " u WHERE " + upsertMatch + ")"
                        + " OR EXISTS (SELECT 1 FROM forwarddb." + qForwardDelete + " d WHERE " + deleteMatch + ")"
        );

        String newRowMatch = primaryKey.stream()
                .map(c -> "t." + qident(c.name()) + " = u." + qident(c.name()))
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();
        String newPkSelect = primaryKey.stream()
                .map(c -> "u." + qident(c.name()))
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        String missingTarget = "t." + qident(primaryKey.getFirst().name()) + " IS NULL";

        long deleteRows = statement.executeUpdate(
                "INSERT INTO " + qReverseDelete
                        + " SELECT " + newPkSelect
                        + " FROM forwarddb." + qForwardUpsert + " u"
                        + " LEFT JOIN targetdb." + qt + " t ON " + newRowMatch
                        + " WHERE " + missingTarget
        );

        return new TableStats(table, restoreRows, deleteRows);
    }

    private static List<Column> columns(Connection conn, String schema, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "PRAGMA " + qident(schema) + ".table_info(" + qident(table) + ")"
             )) {
            while (rs.next()) {
                columns.add(new Column(rs.getString("name"), rs.getInt("pk")));
            }
        }
        return List.copyOf(columns);
    }

    private static List<String> columnNames(Connection conn, String schema, String table) throws SQLException {
        return columns(conn, schema, table).stream().map(Column::name).toList();
    }

    private static String qident(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String sqliteQuote(String value) {
        return value.replace("'", "''");
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private DhReverseDeltaBuilder() {}
}
