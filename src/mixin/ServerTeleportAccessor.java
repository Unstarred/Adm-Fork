package com.engineerclient.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerTeleportAccessor {
    @Accessor("awaitingPositionFromClient")
    Vec3 ec_getAwaitingPosition();

    @Accessor("awaitingTeleport")
    int ec_getAwaitingTeleport();
}
