package be.gabcon.dhsync.manifest;

public record Asset(
        long version,
        String fileName,
        long size,
        String sha256,
        String url,
        String dimension,
        long requiresBaseVersion
) {}
