package com.engineerclient.mixin.odin;

import com.engineerclient.features.pov.PovPreviews;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu$2", remap = false)
public class LeapMenuRenderMixin {

    private static final String RENDER = "invoke(Lcom/odtheking/odin/events/ScreenEvent$Render;)Ljava/lang/Object;";

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;"), index = 0, remap = false)
    private float ec$scaleX(float x) {
        return x * PovPreviews.getOverlayScale();
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;"), index = 1, remap = false)
    private float ec$scaleY(float y) {
        return y * PovPreviews.getOverlayScale();
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/odtheking/odin/utils/render/RoundedRectKt;roundedRect(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIF)V"), index = 5, remap = false)
    private int ec$fadeBackground(int argb) {
        return PovPreviews.fade(argb);
    }
}
