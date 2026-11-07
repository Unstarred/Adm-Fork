package com.engineerclient.mixin.odin;

import com.engineerclient.features.practice.OdinSimonSays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.boss.SimonSays$4", remap = false)
public class SimonSaysTicksMixin {
    @Inject(method = "invoke(Lcom/odtheking/odin/events/TickEvent$Server;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$solver(CallbackInfo ci) {
        if (OdinSimonSays.active()) ci.cancel();
    }
}
