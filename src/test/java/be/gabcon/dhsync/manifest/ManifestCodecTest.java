package be.gabcon.dhsync.manifest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManifestCodecTest {
    private static final String HASH="a".repeat(64);
    private static String manifest(String worldId,int schema){ return """
        {"schemaVersion":%d,"worldId":"%s","minecraftVersion":"1.21.1","neoforgeVersion":"21.1.251","distantHorizonsVersion":"3.3.1","baseVersion":1,"latestDelta":2,"dimensions":{"minecraft:overworld":{"bootstrap":{"version":1,"fileName":"base.gcdh","size":4,"sha256":"%s","url":"https://example.invalid/base.gcdh","dimension":"minecraft:overworld","requiresBaseVersion":1},"deltas":[{"version":2,"fileName":"delta-2.gcdh","size":4,"sha256":"%s","url":"https://example.invalid/delta-2.gcdh","dimension":"minecraft:overworld","requiresBaseVersion":1}]}}}
        """.formatted(schema,worldId,HASH,HASH); }
    @Test void parsesValidManifest() throws Exception { SyncManifest p=ManifestCodec.parseAndValidate(manifest("world-a",1),"world-a",1024); assertEquals(1,p.baseVersion()); assertEquals(2,p.latestDelta()); }
    @Test void rejectsWrongWorldId(){ ManifestException ex=assertThrows(ManifestException.class,()->ManifestCodec.parseAndValidate(manifest("world-a",1),"world-b",1024)); assertTrue(ex.getMessage().contains("worldId")); }
    @Test void rejectsUnsupportedSchemaVersion(){ assertThrows(ManifestException.class,()->ManifestCodec.parseAndValidate(manifest("world-a",2),"world-a",1024)); }
    @Test void rejectsPathTraversal(){ String json=manifest("world-a",1).replace("delta-2.gcdh","../evil.gcdh"); assertThrows(ManifestException.class,()->ManifestCodec.parseAndValidate(json,"world-a",1024)); }
    @Test void rejectsHttpUrl(){ String json=manifest("world-a",1).replace("https://example.invalid/base.gcdh","http://example.invalid/base.gcdh"); assertThrows(ManifestException.class,()->ManifestCodec.parseAndValidate(json,"world-a",1024)); }
}
