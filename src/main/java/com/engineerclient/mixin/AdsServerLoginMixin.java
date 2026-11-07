package com.engineerclient.mixin;

import com.engineerclient.ads.party.AdsServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLoginPacketListenerImpl.class)
public class AdsServerLoginMixin {
    @Redirect(method = "handleHello", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean ec$proxyChecksAccounts(MinecraftServer server) {
        return !AdsServer.forwards(server) && server.usesAuthentication();
    }
}
