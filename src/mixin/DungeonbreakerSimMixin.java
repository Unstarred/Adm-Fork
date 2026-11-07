package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class DungeonbreakerSimMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void ec$dungeonbreakerInSim(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (SimItems.clientHitBlock(pos, direction)) cir.setReturnValue(false);
    }
}
