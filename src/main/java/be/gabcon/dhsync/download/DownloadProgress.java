package be.gabcon.dhsync.download;

public record DownloadProgress(long downloadedBytes, long totalBytes, double bytesPerSecond, long etaSeconds) {}
