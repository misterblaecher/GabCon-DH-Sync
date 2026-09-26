package be.gabcon.dhsync.manifest;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeltaSelectorTest {
    private static Asset asset(long v){ return new Asset(v,"d"+v+".gcdh",1,"a".repeat(64),"https://example.invalid/d"+v,"minecraft:overworld",1); }
    private static DimensionManifest dimension(){ return new DimensionManifest(new Asset(1,"base.gcdh",1,"a".repeat(64),"https://example.invalid/base","minecraft:overworld",1),List.of(asset(125),asset(126),asset(127),asset(128))); }
    @Test void selectsOnlyMissingDeltas(){ var s=DeltaSelector.select(dimension(),1,128,new DeltaSelector.ClientState(1,124)); assertFalse(s.bootstrapRequired()); assertEquals(List.of(125L,126L,127L,128L),s.assets().stream().map(Asset::version).toList()); }
    @Test void alreadyUpToDateSelectsNothing(){ var s=DeltaSelector.select(dimension(),1,128,new DeltaSelector.ClientState(1,128)); assertFalse(s.bootstrapRequired()); assertTrue(s.assets().isEmpty()); }
    @Test void baseMismatchAddsBootstrapAndDeltas(){ var s=DeltaSelector.select(dimension(),1,128,new DeltaSelector.ClientState(0,127)); assertTrue(s.bootstrapRequired()); assertEquals(5,s.assets().size()); assertEquals("base.gcdh",s.assets().getFirst().fileName()); }
}
