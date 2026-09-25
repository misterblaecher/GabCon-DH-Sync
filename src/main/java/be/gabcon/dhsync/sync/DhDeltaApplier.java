package be.gabcon.dhsync.sync;

import be.gabcon.dhsync.server.DhSqliteSnapshotter;
import be.gabcon.dhsync.util.Hashes;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DhDeltaApplier {
    private static final String DRIVER_CLASS = "dh_sqlite.JDBC";
    private static final String JDBC_PREFIX = "jdbc:dh_sqlite:";
    private static final List<String> DATA_TABLES = List.of("FullData", "ChunkHash", "BeaconBeam");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public record TableApplyStats(String table, long deletes, long upserts) {}
    public record ApplyResult(
            Path database,
            Path rollbackBackup,
            long size,
            String nextServerBaselineSha256,
            List<TableApplyStats> tables
    ) {}

    public record WorkingApplyResult(
            String nextServerBaselineSha256,
            List<TableApplyStats> tables
    ) {}

    private record Column(String name, String type, boolean notNull, String defaultValue, int pkOrder) {}

    public static ApplyResult applyOffline(Path targetDatabase, Path deltaDatabase) throws Exception {
        Path target = targetDatabase.toAbsolutePath().normalize();
        Path delta = deltaDatabase.toAbsolutePath().normalize();

        requireRegularFile(target, "Target DH database");
        requireRegularFile(delta, "Delta database");
        refuseActiveSidecars(target);
        DhSqliteSnapshotter.verify(target);
        DhSqliteSnapshotter.verify(delta);

        DeltaMeta meta = readDeltaMeta(delta);
        String targetHash = Hashes.sha256(target);
        if (!targetHash.equalsIgnoreCase(meta.oldSha256())) {
            throw new IllegalStateException(
                    "Delta baseline mismatch: target SHA-256 " + targetHash
                            + " != expected " + meta.oldSha256()
            );
        }

        return applyVerified(target, delta, meta);
    }

    public static ApplyResult applyOfflineChained(
            Path targetDatabase,
            Path deltaDatabase,
            String currentServerBaselineSha256
    ) throws Exception {
        Path target = targetDatabase.toAbsolutePath().normalize();
        Path delta = deltaDatabase.toAbsolutePath().normalize();

        requireRegularFile(target, "Target DH database");
        requireRegularFile(delta, "Delta database");
        refuseActiveSidecars(target);
        DhSqliteSnapshotter.verify(target);
        DhSqliteSnapshotter.verify(delta);
        requireSha256(currentServerBaselineSha256, "currentServerBaselineSha256");

        DeltaMeta meta = readDeltaMeta(delta);
        if (!currentServerBaselineSha256.equalsIgnoreCase(meta.oldSha256())) {
            throw new IllegalStateException(
                    "Delta chain mismatch: current server baseline "
                            + currentServerBaselineSha256 + " != expected " + meta.oldSha256()
            );
        }

        return applyVerified(target, delta, meta);
    }

    public static WorkingApplyResult applyToWorkingCopy(
            Path workDatabase,
            Path deltaDatabase,
            String currentServerBaselineSha256
    ) throws Exception {
        Path work = workDatabase.toAbsolutePath().normalize();
        Path delta = deltaDatabase.toAbsolutePath().normalize();
        requireRegularFile(work, "Working DH database");
        requireRegularFile(delta, "Delta database");
        DhSqliteSnapshotter.verify(work);
        DhSqliteSnapshotter.verify(delta);
        requireSha256(currentServerBaselineSha256, "currentServerBaselineSha256");

        DeltaMeta meta = readDeltaMeta(delta);
        if (!currentServerBaselineSha256.equalsIgnoreCase(meta.oldSha256())) {
            throw new IllegalStateException(
                    "Delta chain mismatch: current server baseline "
                            + currentServerBaselineSha256 + " != expected " + meta.oldSha256()
            );
        }

        List<TableApplyStats> stats = applyIntoWorkingCopy(work, delta);
        DhSqliteSnapshotter.verify(work);
        return new WorkingApplyResult(meta.newSha256(), List.copyOf(stats));
    }

    private static ApplyResult applyVerified(Path target, Path delta, DeltaMeta meta) throws Exception {
        Path parent = target.getParent();
        String base = target.getFileName().toString();
        Path work = parent.resolve(base + ".gabcon-work");
        Path rollback = uniqueRollbackPath(parent, base);
        Files.deleteIfExists(work);

        try {
            copyOffline(target, work);
            DhSqliteSnapshotter.verify(work);

            List<TableApplyStats> stats = applyIntoWorkingCopy(work, delta);
            DhSqliteSnapshotter.verify(work);

            createRollbackCopy(target, rollback);
            replaceTarget(work, target);

            DhSqliteSnapshotter.verify(target);
            return new ApplyResult(
                    target,
                    rollback,
                    Files.size(target),
                    meta.newSha256(),
                    List.copyOf(stats)
            );
        } catch (Exception e) {
            Files.deleteIfExists(work);
            throw e;
        }
    }

    static List<TableApplyStats> applyIntoWorkingCopy(Path workDatabase, Path deltaDatabase) throws Exception {
        Class.forName(DRIVER_CLASS);
        try (Connection conn = DriverManager.getConnection(JDBC_PREFIX + workDatabase.toAbsolutePath().normalize());
             Statement statement = conn.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("ATTACH DATABASE '" + sqliteQuote(deltaDatabase.toAbsolutePath().normalize().toString()) + "' AS delta");

            validateTargetAndDelta(conn, statement);

            conn.setAutoCommit(false);
            try {
                List<TableApplyStats> stats = new ArrayList<>();
                for (String table : DATA_TABLES) {
                    stats.add(applyTable(conn, statement, table));
                }
                conn.commit();
                statement.execute("DETACH DATABASE delta");
                return List.copyOf(stats);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static TableApplyStats applyTable(Connection conn, Statement statement, String table) throws SQLException {
        List<Column> targetColumns = columns(conn, "main", table);
        if (targetColumns.isEmpty()) throw new SQLException("Missing target table: " + table);

        List<Column> primaryKey = targetColumns.stream()
                .filter(c -> c.pkOrder() > 0)
                .sorted(Comparator.comparingInt(Column::pkOrder))
                .toList();
        if (primaryKey.isEmpty()) throw new SQLException("Target table has no primary key: " + table);

        List<String> upsertColumns = columnNames(conn, "delta", table + "Upsert");
        List<String> deleteColumns = columnNames(conn, "delta", table + "Delete");
        List<String> expectedColumns = targetColumns.stream().map(Column::name).toList();
        List<String> expectedPkColumns = primaryKey.stream().map(Column::name).toList();

        if (!expectedColumns.equals(upsertColumns)) {
            throw new SQLException("Delta upsert columns do not match target table " + table);
        }
        if (!expectedPkColumns.equals(deleteColumns)) {
            throw new SQLException("Delta delete keys do not match target table " + table);
        }

        String qt = qident(table);
        String qDelete = qident(table + "Delete");
        String qUpsert = qident(table + "Upsert");

        String keyMatch = primaryKey.stream()
                .map(c -> "d." + qident(c.name()) + " = main." + qt + "." + qident(c.name()))
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();

        long deletes = statement.executeUpdate(
                "DELETE FROM main." + qt
                        + " WHERE EXISTS (SELECT 1 FROM delta." + qDelete + " d WHERE " + keyMatch + ")"
        );

        String allColumns = targetColumns.stream()
                .map(c -> qident(c.name()))
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        String conflictColumns = primaryKey.stream()
                .map(c -> qident(c.name()))
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();

        List<Column> nonPk = targetColumns.stream().filter(c -> c.pkOrder() == 0).toList();
        String updateClause;
        if (nonPk.isEmpty()) {
            updateClause = "DO NOTHING";
        } else {
            String updates = nonPk.stream()
                    .map(c -> qident(c.name()) + " = excluded." + qident(c.name()))
                    .reduce((a, b) -> a + ", " + b)
                    .orElseThrow();
            updateClause = "DO UPDATE SET " + updates;
        }

        long upserts = statement.executeUpdate(
                "INSERT INTO main." + qt + " (" + allColumns + ")"
                        + " SELECT " + allColumns + " FROM delta." + qUpsert
                        + " WHERE true ON CONFLICT (" + conflictColumns + ") " + updateClause
        );

        return new TableApplyStats(table, deletes, upserts);
    }

    private static void validateTargetAndDelta(Connection conn, Statement statement) throws SQLException {
        String format = scalarText(statement, "SELECT Value FROM delta.DeltaMeta WHERE Key='format'");
        if (!"gabcon-dh-delta-v1".equals(format)) {
            throw new SQLException("Unsupported delta format: " + format);
        }

        long legacyRows = scalarLong(statement, "SELECT COUNT(*) FROM main.Legacy_FullData_V1");
        if (legacyRows != 0) {
            throw new SQLException("Target Legacy_FullData_V1 contains rows; refusing apply");
        }

        for (String table : DATA_TABLES) {
            if (columns(conn, "main", table).isEmpty()) {
                throw new SQLException("Target missing DH table: " + table);
            }
            if (columnNames(conn, "delta", table + "Upsert").isEmpty()) {
                throw new SQLException("Delta missing upsert table for " + table);
            }
            if (columnNames(conn, "delta", table + "Delete").isEmpty()) {
                throw new SQLException("Delta missing delete table for " + table);
            }
        }
    }

    public record DeltaMetadata(String oldSha256, String newSha256) {}

    private record DeltaMeta(String oldSha256, String newSha256) {}

    public static DeltaMetadata inspectDelta(Path deltaDatabase) throws Exception {
        Path delta = deltaDatabase.toAbsolutePath().normalize();
        requireRegularFile(delta, "Delta database");
        DhSqliteSnapshotter.verify(delta);
        DeltaMeta meta = readDeltaMeta(delta);
        return new DeltaMetadata(meta.oldSha256(), meta.newSha256());
    }

    private static DeltaMeta readDeltaMeta(Path delta) throws Exception {
        Class.forName(DRIVER_CLASS);
        try (Connection conn = DriverManager.getConnection(JDBC_PREFIX + delta);
             Statement statement = conn.createStatement()) {
            String format = scalarText(statement, "SELECT Value FROM DeltaMeta WHERE Key='format'");
            if (!"gabcon-dh-delta-v1".equals(format)) {
                throw new SQLException("Unsupported delta format: " + format);
            }
            String oldSha = scalarText(statement, "SELECT Value FROM DeltaMeta WHERE Key='oldSha256'");
            String newSha = scalarText(statement, "SELECT Value FROM DeltaMeta WHERE Key='newSha256'");
            requireSha256(oldSha, "oldSha256");
            requireSha256(newSha, "newSha256");
            return new DeltaMeta(oldSha, newSha);
        }
    }

    private static List<Column> columns(Connection conn, String schema, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        String sql = "PRAGMA " + qident(schema) + ".table_info(" + qident(table) + ")";
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(new Column(
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getInt("notnull") != 0,
                        rs.getString("dflt_value"),
                        rs.getInt("pk")
                ));
            }
        }
        return List.copyOf(columns);
    }

    private static List<String> columnNames(Connection conn, String schema, String table) throws SQLException {
        return columns(conn, schema, table).stream().map(Column::name).toList();
    }

    private static long scalarLong(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no row: " + sql);
            return rs.getLong(1);
        }
    }

    private static String scalarText(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no row: " + sql);
            String value = rs.getString(1);
            if (value == null) throw new SQLException("Query returned null: " + sql);
            return value;
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid delta " + field);
        }
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException(label + " not found: " + path);
    }

    private static void refuseActiveSidecars(Path target) throws IOException {
        Path wal = target.resolveSibling(target.getFileName() + "-wal");
        Path shm = target.resolveSibling(target.getFileName() + "-shm");
        if ((Files.exists(wal) && Files.size(wal) > 0) || Files.exists(shm)) {
            throw new IOException(
                    "Target DH database appears active or uncheckpointed; close DH/Minecraft before applying a delta"
            );
        }
    }

    private static Path uniqueRollbackPath(Path parent, String base) throws IOException {
        String prefix = base + ".gabcon-rollback-" + STAMP.format(Instant.now());
        Path candidate = parent.resolve(prefix);
        if (!Files.exists(candidate)) return candidate;
        for (int suffix = 2; suffix <= 10_000; suffix++) {
            candidate = parent.resolve(prefix + "-" + suffix);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("Unable to allocate unique rollback path for " + base);
    }

    private static void createRollbackCopy(Path source, Path rollback) throws Exception {
        if (Files.exists(rollback)) throw new IOException("Rollback path already exists: " + rollback);
        copyOffline(source, rollback);
        DhSqliteSnapshotter.verify(rollback);
    }

    private static void copyOffline(Path source, Path destination) throws IOException {
        Files.copy(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        );
    }

    private static void replaceTarget(Path work, Path target) throws IOException {
        try {
            Files.move(work, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(work, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String qident(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String sqliteQuote(String value) {
        return value.replace("'", "''");
    }

    private DhDeltaApplier() {}
}
