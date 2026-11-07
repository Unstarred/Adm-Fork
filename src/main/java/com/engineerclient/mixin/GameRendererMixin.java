package com.engineerclient.mixin;

import com.engineerclient.features.pov.PovCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel()V", at = @At("HEAD"), cancellable = true)
    private void ec$beforeLevelRender(CallbackInfo ci) {
        if (PovCapture.INSTANCE.beforeLevelRender(Minecraft.getInstance().getDeltaTracker())) ci.cancel();
    }

    @Inject(method = "renderLevel()V", at = @At("RETURN"))
    private void ec$afterLevelRender(CallbackInfo ci) {
        PovCapture.INSTANCE.afterLevelRender(Minecraft.getInstance().getDeltaTracker());
    }
}
