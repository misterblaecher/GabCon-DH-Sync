package be.gabcon.dhsync.mixin.client;

import be.gabcon.dhsync.client.ClientPreConnectController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void gabcon$preConnect(
            Screen parent,
            Minecraft minecraft,
            ServerAddress serverAddress,
            ServerData serverData,
            boolean isQuickPlay,
            TransferState transferState,
            CallbackInfo ci
    ) {
        if (ClientPreConnectController.intercept(
                parent, minecraft, serverAddress, serverData, isQuickPlay, transferState
        )) {
            ci.cancel();
        }
    }
}
