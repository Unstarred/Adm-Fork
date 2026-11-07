package com.engineerclient.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraAccessor {

    @Accessor("eyeHeight")
    float ec$getEyeHeight();

    @Accessor("eyeHeight")
    void ec$setEyeHeight(float eyeHeight);

    @Accessor("eyeHeightOld")
    float ec$getEyeHeightOld();

    @Accessor("eyeHeightOld")
    void ec$setEyeHeightOld(float eyeHeightOld);

    @Accessor("capturedFrustum")
    Frustum ec$getCapturedFrustum();

    @Accessor("capturedFrustum")
    void ec$setCapturedFrustum(Frustum frustum);
}
