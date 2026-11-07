package com.engineerclient.mixin;

import com.engineerclient.EngineerClient;
import com.engineerclient.features.partyfinder.PartyFinderStats;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(AbstractContainerScreen.class)
public class ContainerTooltipMixin {

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void ec$partyFinderStats(ItemStack itemStack, CallbackInfoReturnable<List<Component>> cir) {
        List<Component> original = cir.getReturnValue();
        if (original == null) return;
        List<Component> replaced;
        try {
            replaced = PartyFinderStats.INSTANCE.decorate((AbstractContainerScreen<?>) (Object) this, itemStack, original);
        } catch (Throwable t) {
            EngineerClient.warnOnce("party finder stats tooltip", t);
            return;
        }
        if (replaced != original) cir.setReturnValue(replaced);
    }
}
