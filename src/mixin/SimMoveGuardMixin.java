package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class SimMoveGuardMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean receivedPositionThisTick;
    @Shadow private Vec3 awaitingPositionFromClient;
    @Shadow private int awaitingTeleport;

    private boolean ec$simServer() {
        if (this.player == null) return false;
        return SimServer.INSTANCE.getServer() != null && this.player.level().getServer() == SimServer.INSTANCE.getServer();
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void ec$allowSecondMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (ec$simServer()) this.receivedPositionThisTick = false;
    }

    @Inject(method = "handleAcceptTeleportPacket", at = @At("HEAD"), cancellable = true)
    private void ec$dropStaleAccept(ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        if (ec$simServer() && packet.id() == this.awaitingTeleport && this.awaitingPositionFromClient == null) ci.cancel();
    }

    @WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean ec$noWrongMoveSnap(ServerLevel level, Entity entity, AABB box, Operation<Boolean> original) {
        return !ec$simServer() && original.call(level, entity, box);
    }

    @WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z"))
    private boolean ec$noNewCollisionSnap(ServerGamePacketListenerImpl self, LevelReader level, Entity entity, AABB box, double x, double y, double z, Operation<Boolean> original) {
        return !ec$simServer() && original.call(self, level, entity, box, x, y, z);
    }
}
