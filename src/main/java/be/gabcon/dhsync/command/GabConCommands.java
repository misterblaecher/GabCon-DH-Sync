package be.gabcon.dhsync.command;

import be.gabcon.dhsync.config.ServerConfig;
import be.gabcon.dhsync.server.DhCompatibility;
import be.gabcon.dhsync.server.ServerState;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class GabConCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gabcondhsync")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("snapshot").executes(ctx -> blocked(ctx.getSource(), "snapshot")))
                .then(Commands.literal("publish").executes(ctx -> blocked(ctx.getSource(), "publish")))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        DhCompatibility.Status dh = DhCompatibility.detect();
        source.sendSuccess(() -> Component.literal("[GabConDHSync] enabled=" + ServerConfig.ENABLED.get()
                + ", worldId=" + ServerConfig.WORLD_ID.get()
                + ", pendingBuckets=" + ServerState.CHANGED_REGIONS.pendingCount()
                + ", DH=" + dh.modVersion()
                + ", API=" + dh.apiVersion()
                + ", compatible=" + dh.compatible()), false);
        return 1;
    }

    private static int blocked(CommandSourceStack source, String operation) {
        source.sendFailure(Component.literal("[GabConDHSync] " + operation
                + " is intentionally disabled in the MVP: no verified safe DH 3.3.1 snapshot/import adapter exists yet. Active DistantHorizons.sqlite will not be copied or modified."));
        return 0;
    }

    private static int reload(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[GabConDHSync] NeoForge owns config reload; current worldId=" + ServerConfig.WORLD_ID.get()), false);
        return 1;
    }

    private GabConCommands() {}
}
