package com.engineerclient.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererInvoker {

    @Invoker("extractWindow")
    void ec$extractWindow();

    @Invoker("extractOptions")
    void ec$extractOptions();

    @Invoker("extractCamera")
    void ec$extractCamera(DeltaTracker deltaTracker, float worldPartialTicks);

    @Mutable
    @Accessor("mainRenderTarget")
    void ec$setMainRenderTarget(RenderTarget target);

    @Accessor("hud3DTarget")
    RenderTarget ec$getHud3DTarget();

    @Mutable
    @Accessor("hud3DTarget")
    void ec$setHud3DTarget(RenderTarget target);
}
