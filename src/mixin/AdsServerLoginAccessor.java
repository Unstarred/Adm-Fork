package com.engineerclient.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLoginPacketListenerImpl.class)
public interface AdsServerLoginAccessor {
    @Accessor("connection")
    Connection ec$connection();

    @Accessor("authenticatedProfile")
    void ec$setAuthenticatedProfile(GameProfile profile);
}
