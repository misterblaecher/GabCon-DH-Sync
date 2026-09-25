package be.gabcon.dhsync;

import be.gabcon.dhsync.client.ClientBootstrap;
import be.gabcon.dhsync.config.ClientConfig;
import be.gabcon.dhsync.config.ServerConfig;
import be.gabcon.dhsync.server.ServerEvents;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.DistExecutor;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(GabConDhSync.MOD_ID)
public final class GabConDhSync {
    public static final String MOD_ID = "gabcondhsync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GabConDhSync(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        NeoForge.EVENT_BUS.register(new ServerEvents());
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::init);
        LOGGER.info("[GabConDHSync] Loaded: safe server snapshots/deltas and managed client pre-connect sync are available.");
    }
}
