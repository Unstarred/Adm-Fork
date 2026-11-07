package com.engineerclient.mixin;

import com.engineerclient.ads.core.SimServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class PortalSimMixin {
    @Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
    private void ec$noPortalsInSim(Portal portal, BlockPos pos, CallbackInfo ci) {
        if (SimServer.isSimLevel(((Entity) (Object) this).level())) ci.cancel();
    }
}
