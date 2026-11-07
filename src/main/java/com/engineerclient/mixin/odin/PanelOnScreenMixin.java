package com.engineerclient.mixin.odin;

import com.engineerclient.features.odin.OdinOnScreen;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.odtheking.odin.clickgui.ui.PanelKt", remap = false)
public class PanelOnScreenMixin {
    @ModifyReturnValue(method = "maxY", at = @At("RETURN"), remap = false)
    private static int ec$listOnScreen(int original) {
        try {
            return OdinOnScreen.panelMaxY(original);
        } catch (Throwable t) {
            return original;
        }
    }
}
