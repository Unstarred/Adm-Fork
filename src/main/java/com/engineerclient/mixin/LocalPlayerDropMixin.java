package com.engineerclient.mixin;

import com.engineerclient.features.brwaypoints.BrWaypoints2;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class LocalPlayerDropMixin {

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void ec$wandDrop(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (BrWaypoints2.onDrop()) ci.cancel();
    }
}
