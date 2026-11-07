package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public class BlurRadiusMixin {

    @Redirect(method = "extractOptions()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getMenuBackgroundBlurriness()I"))
    private int ec$blurRadius(Options options) {
        if (RandomStuff.INSTANCE.blursGui()) return RandomStuff.INSTANCE.blurRadius();
        return options.getMenuBackgroundBlurriness();
    }
}
