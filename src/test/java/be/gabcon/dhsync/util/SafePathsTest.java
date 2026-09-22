package be.gabcon.dhsync.util;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SafePathsTest {
    @Test void allowsSimpleFileName(){ assertTrue(SafePaths.resolveAsset(Path.of("target"),"delta-001.gcdh").endsWith("delta-001.gcdh")); }
    @Test void rejectsTraversalAndSubdirectories(){ assertThrows(IllegalArgumentException.class,()->SafePaths.resolveAsset(Path.of("target"),"../evil")); assertThrows(IllegalArgumentException.class,()->SafePaths.resolveAsset(Path.of("target"),"sub/evil")); assertThrows(IllegalArgumentException.class,()->SafePaths.resolveAsset(Path.of("target"),"sub\\evil")); }
}
