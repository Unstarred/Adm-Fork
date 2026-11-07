package com.engineerclient.mixin.odin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.InvincibilityTimer$InvincibilityType", remap = false)
public class InvincibilityPhoenixMixin {
    @Shadow(remap = false) @Final @Mutable private int maxInvincibilityTime;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ec$phoenix(CallbackInfo ci) {
        if ("PHOENIX".equals(((Enum<?>) (Object) this).name())) maxInvincibilityTime = 60;
    }
}
