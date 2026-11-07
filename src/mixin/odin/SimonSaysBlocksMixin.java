package com.engineerclient.mixin.odin;

import com.engineerclient.practice.OdinSimonSays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.boss.SimonSays$3", remap = false)
public class SimonSaysBlocksMixin {
    @Inject(method = "invoke(Lcom/odtheking/odin/events/BlockUpdateEvent;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$solver(CallbackInfo ci) {
        if (OdinSimonSays.active()) ci.cancel();
    }
}
