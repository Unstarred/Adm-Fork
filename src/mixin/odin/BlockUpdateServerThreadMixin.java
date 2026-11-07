package com.engineerclient.mixin.odin;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.odtheking.odin.events.BlockUpdateEvent", remap = false)
public class BlockUpdateServerThreadMixin {
    @Inject(method = "postAndCatch()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$clientOnly(CallbackInfoReturnable<Boolean> cir) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null && server.isSameThread()) cir.setReturnValue(false);
    }
}
