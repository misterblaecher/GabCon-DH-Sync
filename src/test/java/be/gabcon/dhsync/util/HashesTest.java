package be.gabcon.dhsync.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HashesTest {
    @TempDir Path temp;
    @Test void computesSha256() throws Exception { Path p=temp.resolve("hello.txt"); Files.writeString(p,"hello"); assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",Hashes.sha256(p)); }
}
