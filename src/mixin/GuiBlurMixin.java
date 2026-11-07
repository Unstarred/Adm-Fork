package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiBlurMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("HEAD"))
    private void ec$openBlurWindow(DeltaTracker deltaTracker, boolean renderLevel, boolean renderGui, CallbackInfo ci) {
        RandomStuff.INSTANCE.setBuildingGui(true);
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("RETURN"))
    private void ec$closeBlurWindow(DeltaTracker deltaTracker, boolean renderLevel, boolean renderGui, CallbackInfo ci) {
        RandomStuff.INSTANCE.setBuildingGui(false);
    }
}
