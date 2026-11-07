package com.engineerclient.mixin.odin;

import com.engineerclient.features.odin.OdinHighlightLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.Highlight$2", remap = false)
public class HighlightRenderMixin {

    @Inject(method = "invoke(Lcom/odtheking/odin/events/RenderExtractEvent;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$bladeLook(CallbackInfo ci) {
        if (OdinHighlightLook.replacesOdin()) ci.cancel();
    }
}
