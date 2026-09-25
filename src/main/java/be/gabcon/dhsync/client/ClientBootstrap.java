package be.gabcon.dhsync.client;

import be.gabcon.dhsync.GabConDhSync;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ClientBootstrap {
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onRegisterClientCommands);
        try {
            int recovered = ClientRecoveryJournal.recoverAll();
            if (recovered > 0) {
                GabConDhSync.LOGGER.warn("[GabConDHSync] Recovered {} interrupted client database transaction(s).", recovered);
            }
        } catch (Exception e) {
            GabConDhSync.LOGGER.error("[GabConDHSync] Client recovery scan failed; managed sync will remain blocked until repaired.", e);
        }
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ClientCommands.register(event.getDispatcher());
    }

    private ClientBootstrap() {}
}
