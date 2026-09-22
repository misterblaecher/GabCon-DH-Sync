package be.gabcon.dhsync.server;

import be.gabcon.dhsync.GabConDhSync;
import be.gabcon.dhsync.command.GabConCommands;
import be.gabcon.dhsync.config.ServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

public final class ServerEvents {
    @SubscribeEvent public void onChunkSave(ChunkDataEvent.Save event) {
        if (!ServerConfig.ENABLED.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        ServerState.CHANGED_REGIONS.markChunkSaved(level.dimension().location().toString(), pos.x, pos.z);
    }
    @SubscribeEvent public void onRegisterCommands(RegisterCommandsEvent event) { GabConCommands.register(event.getDispatcher()); }
    @SubscribeEvent public void onServerStarted(ServerStartedEvent event) {
        DhCompatibility.Status status = DhCompatibility.detect();
        GabConDhSync.LOGGER.info("[GabConDHSync] Distant Horizons: present={}, mod={}, api={}, compatible={} ({})", status.present(), status.modVersion(), status.apiVersion(), status.compatible(), status.detail());
    }
}
