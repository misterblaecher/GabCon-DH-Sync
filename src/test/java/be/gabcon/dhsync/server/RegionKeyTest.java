package be.gabcon.dhsync.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionKeyTest {
    @Test void handlesNegativeCoordinatesWithFloorDivision(){ assertEquals(new RegionKey("minecraft:overworld",-1,-1),RegionKey.fromChunk("minecraft:overworld",-1,-1)); assertEquals(new RegionKey("minecraft:overworld",-2,-2),RegionKey.fromChunk("minecraft:overworld",-33,-33)); assertEquals(new RegionKey("minecraft:overworld",0,0),RegionKey.fromChunk("minecraft:overworld",31,31)); assertEquals(new RegionKey("minecraft:overworld",1,1),RegionKey.fromChunk("minecraft:overworld",32,32)); }
}
