package be.gabcon.dhsync.client;

import be.gabcon.dhsync.config.ClientConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClientCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gabcondhsyncclient")
                .then(Commands.literal("register").executes(ctx -> registerCurrent(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("forget").executes(ctx -> forgetCurrent(ctx.getSource()))));
    }

    private static int registerCurrent(CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (server == null || minecraft.level == null) {
            source.sendFailure(Component.literal("[GabConDHSync] Join the GabCon server first, then run this command once."));
            return 0;
        }

        try {
            Map<String, Path> databases = DhClientLocator.locateLoadedDatabases();
            String serverKey = ClientServerKeys.normalize(server.ip);

            Map<String, ClientSyncState.DimensionState> dimensions = new LinkedHashMap<>();
            var existing = ClientSyncStateStore.find(ClientSyncStateStore.defaultPath(), serverKey);
            if (existing.isPresent()) dimensions.putAll(existing.get().dimensions());

            for (Map.Entry<String, Path> entry : databases.entrySet()) {
                ClientSyncState.DimensionState old = dimensions.get(entry.getKey());
                String baseline = old == null ? null : old.serverBaselineSha256();
                // A newly discovered dimension intentionally starts without a baseline and will bootstrap once.
                dimensions.put(entry.getKey(), new ClientSyncState.DimensionState(entry.getValue().toString(), baseline));
            }

            ClientSyncState.ServerProfile profile = new ClientSyncState.ServerProfile(
                    serverKey,
                    ClientConfig.WORLD_ID.get(),
                    ClientConfig.defaultManifestUrl(),
                    Map.copyOf(dimensions)
            );
            ClientSyncStateStore.upsert(ClientSyncStateStore.defaultPath(), profile);
            source.sendSuccess(() -> Component.literal(
                    "[GabConDHSync] Registered " + serverKey + " as " + profile.worldId()
                            + " with " + dimensions.size() + " DH database(s). "
                            + "The next connection will run pre-connect sync."
            ), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("[GabConDHSync] Registration failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            return 0;
        }
    }

    private static int status(CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (server == null) {
            source.sendFailure(Component.literal("[GabConDHSync] Not connected to a multiplayer server."));
            return 0;
        }
        String key = ClientServerKeys.normalize(server.ip);
        try {
            var profile = ClientSyncStateStore.find(ClientSyncStateStore.defaultPath(), key);
            if (profile.isEmpty()) {
                source.sendSuccess(() -> Component.literal("[GabConDHSync] " + key + " is not registered."), false);
                return 1;
            }
            long knownBaselines = profile.get().dimensions().values().stream()
                    .filter(d -> d.serverBaselineSha256() != null && !d.serverBaselineSha256().isBlank())
                    .count();
            source.sendSuccess(() -> Component.literal(
                    "[GabConDHSync] managed=" + key
                            + ", worldId=" + profile.get().worldId()
                            + ", dimensions=" + profile.get().dimensions().size()
                            + ", baselines=" + knownBaselines
                            + ", manifest=" + profile.get().manifestUrl()
            ), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("[GabConDHSync] Cannot read client state: " + e.getMessage()));
            return 0;
        }
    }

    private static int forgetCurrent(CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (server == null) {
            source.sendFailure(Component.literal("[GabConDHSync] Not connected to a multiplayer server."));
            return 0;
        }
        String key = ClientServerKeys.normalize(server.ip);
        try {
            ClientSyncStateStore.remove(ClientSyncStateStore.defaultPath(), key);
            source.sendSuccess(() -> Component.literal("[GabConDHSync] Removed managed profile for " + key + "."), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("[GabConDHSync] Cannot update client state: " + e.getMessage()));
            return 0;
        }
    }

    private ClientCommands() {}
}
