package com.engineerclient.mixin;

import com.engineerclient.waypoints.BrWaypoints2;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class WandScrollMixin {

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void ec$wandScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (BrWaypoints2.onScroll(yOffset)) ci.cancel();
    }
}
