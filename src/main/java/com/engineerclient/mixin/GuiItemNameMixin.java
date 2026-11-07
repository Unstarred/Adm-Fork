package com.engineerclient.mixin;

import com.engineerclient.features.misc.RandomStuff;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class GuiItemNameMixin {

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void ec$hideSelectedItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (RandomStuff.INSTANCE.hidesItemNames()) ci.cancel();
    }
}
