package com.engineerclient.mixin;

import com.engineerclient.features.odin.OdinClickLimit;
import com.engineerclient.features.practice.TermInfo;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Connection.class, priority = 1)
public class TerminalTapMixin {

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD")
    )
    private void ec$terminalTap(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        TermInfo.tap(packet);
        OdinClickLimit.tap(packet);
    }
}
