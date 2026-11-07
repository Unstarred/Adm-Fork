package com.engineerclient.mixin;

import com.engineerclient.features.misc.RandomStuff;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenBlurMixin {

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void ec$skipVanillaBlurMarker(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        if (RandomStuff.INSTANCE.blursGui()) ci.cancel();
    }
}
