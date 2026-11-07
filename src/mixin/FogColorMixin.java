package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public class FogColorMixin {

    @Inject(method = "computeFogColor", at = @At("TAIL"))
    private void ec$blackSky(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenAmount, Vector4f dest, CallbackInfo ci, @Local FogType fogType) {
        if (fogType != FogType.ATMOSPHERIC || !RandomStuff.INSTANCE.blackSkyActive()) return;
        dest.set(0f, 0f, 0f, 1f);
    }
}
