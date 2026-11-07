package com.engineerclient.mixin;

import com.engineerclient.p3sim.AdsServer;
import com.engineerclient.p3sim.P3Party;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.InetAddress;

@Mixin(IntegratedServer.class)
public class SimPartyLanMixin {
    @ModifyArg(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"), index = 0)
    private InetAddress ec$loopbackOnly(InetAddress address) {
        return P3Party.getLoopbackOnly() || AdsServer.getActive() ? InetAddress.getLoopbackAddress() : address;
    }

    @Redirect(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/LanServerPinger;start()V"))
    private void ec$notAnnounced(LanServerPinger pinger) {
        if (!P3Party.getLoopbackOnly() && !AdsServer.getActive()) pinger.start();
    }
}
