package com.engineerclient.mixin;

import com.engineerclient.EngineerClient;
import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class TerminalNumberMixin {

    @Unique
    private static Slot ec$slot;

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void ec$rememberSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ec$slot = slot;
    }

    @Redirect(
        method = "extractSlot",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"
        )
    )
    private void ec$hideTerminalCount(GuiGraphicsExtractor graphics, Font font, ItemStack itemStack, int x, int y, String countText) {
        try {
            Slot slot = ec$slot;
            if (slot != null && RandomStuff.hidesTerminalNumber(slot)) return;
        } catch (Throwable t) {
            EngineerClient.warnOnce("terminal number hiding", t);
        }
        graphics.itemDecorations(font, itemStack, x, y, countText);
    }
}
