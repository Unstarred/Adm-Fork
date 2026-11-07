package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public class P3SimLevelInfoMixin {
    @Inject(method = "isFlat", at = @At("HEAD"), cancellable = true)
    private void ec$simNotFlat(CallbackInfoReturnable<Boolean> cir) {
        if (SimServer.INSTANCE.isSimLevel((ServerLevel) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "getSeaLevel", at = @At("HEAD"), cancellable = true)
    private void ec$simSeaLevel(CallbackInfoReturnable<Integer> cir) {
        if (SimServer.INSTANCE.isSimLevel((ServerLevel) (Object) this)) cir.setReturnValue(63);
    }
}
