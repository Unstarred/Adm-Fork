package com.engineerclient.mixin.odin;

import com.engineerclient.EngineerClient;
import com.engineerclient.misc.OdinClickLimit;
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TerminalHandler.class, remap = false)
public class TerminalClickLimitMixin {
    @Inject(method = "click", at = @At("HEAD"), cancellable = true)
    private void ec$clickLimit(int slotIndex, int button, boolean simulateClick, CallbackInfo ci) {
        try {
            if (OdinClickLimit.blocks((TerminalHandler) (Object) this, slotIndex, button)) ci.cancel();
        } catch (Throwable t) {
            EngineerClient.warnOnce("terminal click limit", t);
        }
    }
}
