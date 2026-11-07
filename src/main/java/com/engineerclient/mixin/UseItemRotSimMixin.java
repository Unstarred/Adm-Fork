package com.engineerclient.mixin;

import com.engineerclient.ads.fight.Fight;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class UseItemRotSimMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void ec$priorRot(ServerboundUseItemPacket packet, CallbackInfo ci) {
        Fight.noteUseItem(this.player);
    }
}
