package com.engineerclient.mixin;

import com.engineerclient.features.misc.RandomStuff;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public class GuiRenderStateBlurMixin {
    @Inject(method = "reset", at = @At("TAIL"))
    private void ec$blurBehindGui(CallbackInfo ci) {
        if (RandomStuff.INSTANCE.blursAfterReset()) ((GuiRenderState) (Object) this).blurBeforeThisStratum();
    }
}
