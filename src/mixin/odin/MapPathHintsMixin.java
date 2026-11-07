package com.engineerclient.mixin.odin;

import com.engineerclient.mappred.OdinMapPrediction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.map.MapRendererKt", remap = false)
public class MapPathHintsMixin {

    @Inject(method = "renderPathHints(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/Collection;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ec$prediction(GuiGraphicsExtractor g, Collection<?> hints, CallbackInfo ci) {
        if (OdinMapPrediction.render(g)) ci.cancel();
    }

    @Inject(method = "renderPathHints(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/Collection;Ljava/util/Collection;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ec$predictionRooms(GuiGraphicsExtractor g, Collection<?> hints, Collection<?> rooms, CallbackInfo ci) {
        if (OdinMapPrediction.render(g)) ci.cancel();
    }
}
