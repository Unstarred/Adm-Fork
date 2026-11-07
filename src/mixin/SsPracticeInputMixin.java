package com.engineerclient.mixin;

import com.engineerclient.practice.SimonSaysPractice;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class SsPracticeInputMixin {

    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void ec$ssPick(float partialTicks, CallbackInfo ci) {
        SimonSaysPractice.afterPick(partialTicks);
    }

    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void ec$ssUse(CallbackInfo ci) {
        if (SimonSaysPractice.onUse()) ci.cancel();
    }

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$ssAttack(CallbackInfoReturnable<Boolean> cir) {
        if (SimonSaysPractice.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void ec$ssNoMining(boolean attacking, CallbackInfo ci) {
        if (SimonSaysPractice.blocksContinueAttack()) ci.cancel();
    }
}
