package be.gabcon.dhsync.util;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class SafePaths {
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,180}");

    public static Path resolveAsset(Path root, String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches() || fileName.equals(".") || fileName.equals("..")) throw new IllegalArgumentException("Unsafe asset file name");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(fileName).normalize();
        if (!resolved.getParent().equals(normalizedRoot)) throw new IllegalArgumentException("Asset escapes target directory");
        return resolved;
    }
    private SafePaths() {}
}
