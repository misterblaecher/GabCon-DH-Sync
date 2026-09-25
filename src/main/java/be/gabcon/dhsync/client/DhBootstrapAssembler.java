package be.gabcon.dhsync.client;

import be.gabcon.dhsync.distribution.DistributionManifest;
import be.gabcon.dhsync.server.DhSqliteSnapshotter;
import be.gabcon.dhsync.util.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class DhBootstrapAssembler {
    public static Path assemble(
            Path targetDatabase,
            DistributionManifest.BootstrapAsset bootstrap,
            Map<String, Path> downloadedParts
    ) throws Exception {
        Path target = targetDatabase.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Target database has no parent directory");
        DhOfflineFiles.requireInactive(target);
        DhOfflineFiles.requireUsableSpace(parent, bootstrap.totalSize());

        Path work = DhOfflineFiles.workPath(target);
        Files.deleteIfExists(work);

        long written = 0;
        try (OutputStream out = Files.newOutputStream(
                work,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            byte[] buffer = new byte[1024 * 1024];
            for (DistributionManifest.PartAsset part : bootstrap.parts()) {
                Path source = downloadedParts.get(part.fileName());
                if (source == null || !Files.isRegularFile(source)) {
                    throw new IOException("Missing downloaded bootstrap part: " + part.fileName());
                }
                if (Files.size(source) != part.size()) {
                    throw new IOException("Bootstrap part size mismatch: " + part.fileName());
                }
                if (!Hashes.sha256(source).equalsIgnoreCase(part.sha256())) {
                    throw new IOException("Bootstrap part SHA-256 mismatch: " + part.fileName());
                }

                try (InputStream in = Files.newInputStream(source)) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        written += read;
                        if (written > bootstrap.totalSize()) {
                            throw new IOException("Bootstrap exceeds declared total size");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(work);
            throw e;
        }

        if (written != bootstrap.totalSize()) {
            Files.deleteIfExists(work);
            throw new IOException("Bootstrap assembled size mismatch: " + written + " != " + bootstrap.totalSize());
        }
        String fullHash = Hashes.sha256(work);
        if (!fullHash.equalsIgnoreCase(bootstrap.databaseSha256())) {
            Files.deleteIfExists(work);
            throw new IOException("Bootstrap database SHA-256 mismatch");
        }
        DhSqliteSnapshotter.verify(work);
        return work;
    }

    private DhBootstrapAssembler() {}
}
