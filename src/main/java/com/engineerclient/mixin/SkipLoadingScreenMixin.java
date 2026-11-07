package com.engineerclient.mixin;

import com.engineerclient.features.misc.RandomStuff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class SkipLoadingScreenMixin {
    @Inject(method = "startWaitingForNewLevel", at = @At("TAIL"))
    private void ec$skipLoadingScreen(LocalPlayer player, ClientLevel level, LevelLoadingScreen.Reason reason, CallbackInfo ci) {
        if (!RandomStuff.INSTANCE.skipsLoadingScreen()) return;
        if (Minecraft.getInstance().gui.screen() instanceof LevelLoadingScreen screen) screen.onClose();
    }
}
