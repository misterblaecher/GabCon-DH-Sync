package be.gabcon.dhsync;

import be.gabcon.dhsync.config.ClientConfig;
import be.gabcon.dhsync.config.ServerConfig;
import be.gabcon.dhsync.server.ServerEvents;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
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

        if (FMLEnvironment.dist == Dist.CLIENT) {
            initPhysicalClient();
        }

        LOGGER.info("[GabConDHSync] Loaded: safe server snapshots/deltas and managed client pre-connect sync are available.");
    }

    private static void initPhysicalClient() {
        try {
            Class<?> bootstrap = Class.forName("be.gabcon.dhsync.client.ClientBootstrap");
            bootstrap.getMethod("init").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to initialize GabCon DH Sync client", e);
        }
    }
}
