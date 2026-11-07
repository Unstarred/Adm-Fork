package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GlyphRenderState.class)
public abstract class ClickGuiTextMixin {
    @Shadow public abstract Matrix3x2fc pose();

    @ModifyArg(method = "textureSetup", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/SamplerCache;getClampToEdge(Lcom/mojang/renderpearl/api/textures/FilterMode;)Lcom/mojang/renderpearl/api/textures/GpuSampler;"))
    private FilterMode ec$smoothInClickGui(FilterMode mode) {
        Matrix3x2fc m = pose();
        return RandomStuff.INSTANCE.smoothsGuiText(m.m00(), m.m01()) ? FilterMode.LINEAR : mode;
    }
}
