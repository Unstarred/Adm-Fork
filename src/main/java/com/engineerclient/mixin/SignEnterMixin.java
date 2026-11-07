package com.engineerclient.mixin;

import com.engineerclient.features.misc.RandomStuff;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractSignEditScreen.class)
public class SignEnterMixin {

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"), cancellable = true)
    private void ec$enterFinishesSign(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        if (!RandomStuff.INSTANCE.signEnterFinishes(keyEvent.key())) return;
        ((AbstractSignEditScreen) (Object) this).onClose();
        cir.setReturnValue(true);
    }
}
