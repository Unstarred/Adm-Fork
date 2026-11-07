package com.engineerclient.mixin.odin;

import com.engineerclient.features.practice.InfNumbersSim;
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TerminalHandler.class, remap = false)
public class LeapTermsimProtMixin {
    @Inject(method = "shouldProtect", at = @At("HEAD"), cancellable = true)
    private void ec$noProt(CallbackInfoReturnable<Boolean> cir) {
        if (InfNumbersSim.active()) cir.setReturnValue(false);
    }
}
