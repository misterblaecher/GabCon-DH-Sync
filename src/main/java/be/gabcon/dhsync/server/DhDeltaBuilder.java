package be.gabcon.dhsync.server;

import be.gabcon.dhsync.util.Hashes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DhDeltaBuilder {
    private static final String DRIVER_CLASS = "dh_sqlite.JDBC";
    private static final String JDBC_PREFIX = "jdbc:dh_sqlite:";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final List<String> DATA_TABLES = List.of("FullData", "ChunkHash", "BeaconBeam");

    public record TableStats(String table, long upserts, long deletes) {}
    public record DeltaFile(String dimension, String fileName, long size, String sha256, List<TableStats> tables) {}
    public record DeltaManifest(
            int schemaVersion,
            String worldId,
            String fromCreatedAtUtc,
            String toCreatedAtUtc,
            String distantHorizonsVersion,
            String distantHorizonsApiVersion,
            List<DeltaFile> files
    ) {}
    public record DeltaResult(Path directory, Path manifest, List<DeltaFile> files) {}

    private record SnapshotRef(Path directory, DhSnapshotService.SnapshotManifest manifest) {}
    private record Column(String name, String type, boolean notNull, String defaultValue, int pkOrder) {}

    public static DeltaResult buildLatest(String worldId) throws Exception {
        Path snapshotRoot = Path.of("gabcondhsync", "snapshots", DhSnapshotService.safeStem(worldId))
                .toAbsolutePath().normalize();
        List<SnapshotRef> snapshots = loadSnapshots(snapshotRoot, worldId);
        if (snapshots.size() < 2) throw new IllegalStateException("At least two snapshots are required");

        SnapshotRef previous = snapshots.get(snapshots.size() - 2);
        SnapshotRef current = snapshots.get(snapshots.size() - 1);
        return build(previous, current, worldId, Path.of("gabcondhsync", "deltas"));
    }

    static DeltaResult build(Path previousDirectory, Path currentDirectory, String worldId) throws Exception {
        return build(previousDirectory, currentDirectory, worldId, Path.of("gabcondhsync", "deltas"));
    }

    static DeltaResult build(Path previousDirectory, Path currentDirectory, String worldId, Path deltaBase) throws Exception {
        return build(readSnapshot(previousDirectory, worldId), readSnapshot(currentDirectory, worldId), worldId, deltaBase);
    }

    private static DeltaResult build(SnapshotRef previous, SnapshotRef current, String worldId, Path deltaBase) throws Exception {
        validatePair(previous.manifest(), current.manifest(), worldId);

        Instant from = Instant.parse(previous.manifest().createdAtUtc());
        Instant to = Instant.parse(current.manifest().createdAtUtc());
        if (!to.isAfter(from)) throw new IllegalStateException("Current snapshot must be newer than previous snapshot");

        Path deltaRoot = deltaBase.resolve(DhSnapshotService.safeStem(worldId))
                .resolve(STAMP.format(from) + "--" + STAMP.format(to)).toAbsolutePath().normalize();
        Files.createDirectories(deltaRoot);

        Map<String, DhSnapshotService.SnapshotFile> oldByDimension = byDimension(previous.manifest().files());
        Map<String, DhSnapshotService.SnapshotFile> newByDimension = byDimension(current.manifest().files());
        List<DeltaFile> deltaFiles = new ArrayList<>();

        for (Map.Entry<String, DhSnapshotService.SnapshotFile> entry : newByDimension.entrySet()) {
            String dimension = entry.getKey();
            DhSnapshotService.SnapshotFile newer = entry.getValue();
            DhSnapshotService.SnapshotFile older = oldByDimension.get(dimension);

            if (older != null && older.sha256().equalsIgnoreCase(newer.sha256())) continue;
            if (older == null) {
                throw new IllegalStateException("New dimension without baseline is not supported yet: " + dimension);
            }

            Path oldDb = checkedChild(previous.directory(), older.fileName());
            Path newDb = checkedChild(current.directory(), newer.fileName());
            verifySnapshotFile(oldDb, older);
            verifySnapshotFile(newDb, newer);

            String sourceName = newer.fileName();
            String deltaName = sourceName.endsWith(".sqlite")
                    ? sourceName.substring(0, sourceName.length() - ".sqlite".length()) + ".delta.sqlite"
                    : sourceName + ".delta.sqlite";
            Path deltaDb = checkedChild(deltaRoot, deltaName);
            List<TableStats> stats = buildDimensionDelta(oldDb, newDb, deltaDb, older.sha256(), newer.sha256());

            // Even a zero-operation logical delta is retained when snapshot SHA tokens differ.
            // SQLite physical bytes are not a stable logical identity; this tiny delta advances the
            // server-baseline chain so the next real delta remains applicable.
            deltaFiles.add(new DeltaFile(
                    dimension,
                    deltaName,
                    Files.size(deltaDb),
                    Hashes.sha256(deltaDb),
                    stats
            ));
        }

        for (String oldDimension : oldByDimension.keySet()) {
            if (!newByDimension.containsKey(oldDimension)) {
                throw new IllegalStateException("Dimension disappeared between snapshots: " + oldDimension);
            }
        }

        DeltaManifest manifest = new DeltaManifest(
                1,
                worldId,
                previous.manifest().createdAtUtc(),
                current.manifest().createdAtUtc(),
                current.manifest().distantHorizonsVersion(),
                current.manifest().distantHorizonsApiVersion(),
                List.copyOf(deltaFiles)
        );
        Path manifestPath = deltaRoot.resolve("delta.json");
        Files.writeString(manifestPath, GSON.toJson(manifest));
        return new DeltaResult(deltaRoot, manifestPath, List.copyOf(deltaFiles));
    }

    private static List<TableStats> buildDimensionDelta(Path oldDb, Path newDb, Path output, String oldSha256, String newSha256) throws Exception {
        Files.createDirectories(output.getParent());
        Path part = output.resolveSibling(output.getFileName() + ".part");
        Files.deleteIfExists(part);
        Files.deleteIfExists(output);

        Class.forName(DRIVER_CLASS);
        try {
            List<TableStats> stats = new ArrayList<>();
            try (Connection conn = DriverManager.getConnection(JDBC_PREFIX + part);
                 Statement statement = conn.createStatement()) {
                statement.execute("PRAGMA journal_mode=OFF");
                statement.execute("PRAGMA synchronous=OFF");
                statement.execute("ATTACH DATABASE '" + sqliteQuote(oldDb.toString()) + "' AS olddb");
                statement.execute("ATTACH DATABASE '" + sqliteQuote(newDb.toString()) + "' AS newdb");

                statement.execute("CREATE TABLE DeltaMeta (Key TEXT PRIMARY KEY NOT NULL, Value TEXT NOT NULL)");
                statement.execute("INSERT INTO DeltaMeta VALUES ('format','gabcon-dh-delta-v1')");
                statement.execute("INSERT INTO DeltaMeta VALUES ('oldSha256','" + sqliteQuote(oldSha256) + "')");
                statement.execute("INSERT INTO DeltaMeta VALUES ('newSha256','" + sqliteQuote(newSha256) + "')");

                validateAttachedDatabases(statement);

                for (String table : DATA_TABLES) {
                    stats.add(diffTable(conn, statement, table));
                }

                statement.execute("DETACH DATABASE olddb");
                statement.execute("DETACH DATABASE newdb");
            }

            DhSqliteSnapshotter.verify(part);
            atomicReplace(part, output);
            return List.copyOf(stats);
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    private static TableStats diffTable(Connection conn, Statement statement, String table) throws SQLException {
        List<Column> oldColumns = columns(conn, "olddb", table);
        List<Column> newColumns = columns(conn, "newdb", table);
        if (oldColumns.isEmpty() || newColumns.isEmpty()) throw new SQLException("Missing table: " + table);
        if (!oldColumns.equals(newColumns)) throw new SQLException("Schema mismatch for table " + table);

        List<Column> primaryKey = newColumns.stream()
                .filter(c -> c.pkOrder() > 0)
                .sorted(Comparator.comparingInt(Column::pkOrder))
                .toList();
        if (primaryKey.isEmpty()) throw new SQLException("Table has no primary key: " + table);

        String qt = qident(table);
        String upsertTable = qident(table + "Upsert");
        String deleteTable = qident(table + "Delete");

        statement.execute("CREATE TABLE " + upsertTable + " AS SELECT * FROM newdb." + qt + " WHERE 0");

        String pkSelect = primaryKey.stream()
                .map(c -> qident(c.name()))
                .reduce((a,b) -> a + ", " + b)
                .orElseThrow();
        statement.execute("CREATE TABLE " + deleteTable + " AS SELECT " + pkSelect + " FROM newdb." + qt + " WHERE 0");

        String join = primaryKey.stream()
                .map(c -> "n." + qident(c.name()) + " = o." + qident(c.name()))
                .reduce((a,b) -> a + " AND " + b)
                .orElseThrow();
        String missingOld = "o." + qident(primaryKey.getFirst().name()) + " IS NULL";
        String missingNew = "n." + qident(primaryKey.getFirst().name()) + " IS NULL";

        List<Column> nonPk = newColumns.stream().filter(c -> c.pkOrder() == 0).toList();
        String changed = nonPk.stream()
                .map(c -> "n." + qident(c.name()) + " IS NOT o." + qident(c.name()))
                .reduce((a,b) -> a + " OR " + b)
                .orElse("0");

        long upserts = statement.executeUpdate(
                "INSERT INTO " + upsertTable
                        + " SELECT n.* FROM newdb." + qt + " n"
                        + " LEFT JOIN olddb." + qt + " o ON " + join
                        + " WHERE " + missingOld + " OR " + changed
        );

        String oldPkSelect = primaryKey.stream()
                .map(c -> "o." + qident(c.name()))
                .reduce((a,b) -> a + ", " + b)
                .orElseThrow();

        long deletes = statement.executeUpdate(
                "INSERT INTO " + deleteTable
                        + " SELECT " + oldPkSelect + " FROM olddb." + qt + " o"
                        + " LEFT JOIN newdb." + qt + " n ON " + join
                        + " WHERE " + missingNew
        );

        return new TableStats(table, upserts, deletes);
    }

    private static List<Column> columns(Connection conn, String schema, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        String sql = "PRAGMA " + qident(schema) + ".table_info(" + qident(table) + ")";
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) columns.add(new Column(
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getInt("notnull") != 0,
                    rs.getString("dflt_value"),
                    rs.getInt("pk")
            ));
        }
        return List.copyOf(columns);
    }

    private static void validateAttachedDatabases(Statement statement) throws SQLException {
        long oldLegacy = scalarLong(statement, "SELECT COUNT(*) FROM olddb.Legacy_FullData_V1");
        long newLegacy = scalarLong(statement, "SELECT COUNT(*) FROM newdb.Legacy_FullData_V1");
        if (oldLegacy != 0 || newLegacy != 0) {
            throw new SQLException("Legacy_FullData_V1 contains rows; refusing an incomplete/legacy migration state");
        }

        long schemaDiff = scalarLong(statement,
                "SELECT COUNT(*) FROM (SELECT * FROM olddb.Schema EXCEPT SELECT * FROM newdb.Schema)")
                + scalarLong(statement,
                "SELECT COUNT(*) FROM (SELECT * FROM newdb.Schema EXCEPT SELECT * FROM olddb.Schema)");
        if (schemaDiff != 0) throw new SQLException("DH Schema table changed between snapshots");
    }

    private static long scalarLong(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("Query returned no row: " + sql);
            return rs.getLong(1);
        }
    }

    private static List<SnapshotRef> loadSnapshots(Path root, String worldId) throws Exception {
        if (!Files.isDirectory(root)) throw new IllegalStateException("Snapshot directory does not exist: " + root);
        List<SnapshotRef> refs = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path manifest = dir.resolve("snapshot.json");
                if (!Files.isRegularFile(manifest)) continue;
                try {
                    refs.add(readSnapshot(dir, worldId));
                } catch (Exception ignored) {
                    // Ignore unrelated or incomplete directories; explicit build(Path,Path,...) remains strict.
                }
            }
        }
        refs.sort(Comparator.comparing(r -> Instant.parse(r.manifest().createdAtUtc())));
        return refs;
    }

    private static SnapshotRef readSnapshot(Path directory, String worldId) throws Exception {
        Path dir = directory.toAbsolutePath().normalize();
        Path manifestPath = checkedChild(dir, "snapshot.json");
        DhSnapshotService.SnapshotManifest manifest =
                GSON.fromJson(Files.readString(manifestPath), DhSnapshotService.SnapshotManifest.class);
        if (manifest == null) throw new IllegalStateException("Invalid snapshot manifest: " + manifestPath);
        if (manifest.schemaVersion() != 1) throw new IllegalStateException("Unsupported snapshot schema");
        if (!worldId.equals(manifest.worldId())) throw new IllegalStateException("Snapshot worldId mismatch");
        if (manifest.files() == null) throw new IllegalStateException("Snapshot files missing");
        Instant.parse(manifest.createdAtUtc());
        return new SnapshotRef(dir, manifest);
    }

    private static void validatePair(
            DhSnapshotService.SnapshotManifest oldManifest,
            DhSnapshotService.SnapshotManifest newManifest,
            String worldId
    ) {
        if (!worldId.equals(oldManifest.worldId()) || !worldId.equals(newManifest.worldId())) {
            throw new IllegalStateException("worldId mismatch between snapshots");
        }
        if (!oldManifest.distantHorizonsVersion().equals(newManifest.distantHorizonsVersion())) {
            throw new IllegalStateException("Distant Horizons version changed between snapshots");
        }
        if (!oldManifest.distantHorizonsApiVersion().equals(newManifest.distantHorizonsApiVersion())) {
            throw new IllegalStateException("Distant Horizons API version changed between snapshots");
        }
    }

    private static Map<String, DhSnapshotService.SnapshotFile> byDimension(List<DhSnapshotService.SnapshotFile> files) {
        Map<String, DhSnapshotService.SnapshotFile> result = new HashMap<>();
        for (DhSnapshotService.SnapshotFile file : files) {
            if (result.put(file.dimension(), file) != null) {
                throw new IllegalStateException("Duplicate dimension in snapshot: " + file.dimension());
            }
        }
        return result;
    }

    private static void verifySnapshotFile(Path path, DhSnapshotService.SnapshotFile file) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Snapshot database missing: " + path);
        if (Files.size(path) != file.size()) throw new IOException("Snapshot database size mismatch: " + path);
    }

    private static Path checkedChild(Path directory, String fileName) throws IOException {
        Path dir = directory.toAbsolutePath().normalize();
        Path child = dir.resolve(fileName).normalize();
        if (!child.getParent().equals(dir)) throw new IOException("Unsafe snapshot file name: " + fileName);
        return child;
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

    private DhDeltaBuilder() {}
}
