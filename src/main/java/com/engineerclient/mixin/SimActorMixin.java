package com.engineerclient.mixin;

import com.engineerclient.ads.core.Sim;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class SimActorMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "/^handle/", at = @At("HEAD"), require = 0)
    private void ec$simActorIn(CallbackInfo ci) {
        Sim.enterPacket(this.player);
    }

    @Inject(method = "/^handle/", at = @At("RETURN"), require = 0)
    private void ec$simActorOut(CallbackInfo ci) {
        Sim.leavePacket(this.player);
    }
}
