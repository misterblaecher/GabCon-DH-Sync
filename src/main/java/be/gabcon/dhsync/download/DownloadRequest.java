package be.gabcon.dhsync.download;

import java.net.URI;
import java.nio.file.Path;

public record DownloadRequest(URI uri, Path targetDirectory, String fileName, long expectedSize, String expectedSha256, long maxBytes, boolean allowHttpForTests) {}
