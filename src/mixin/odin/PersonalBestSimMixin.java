package com.engineerclient.mixin.odin;

import com.engineerclient.p3sim.P3Sim;
import com.engineerclient.practice.OdinTermTimes;
import com.odtheking.odin.clickgui.settings.impl.MapSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.odtheking.odin.utils.PersonalBest", remap = false)
public class PersonalBestSimMixin {
    @Shadow(remap = false) @SuppressWarnings("rawtypes") private MapSetting mapSetting;

    @ModifyArg(method = "time(Ljava/lang/String;FLjava/lang/String;Ljava/lang/String;Z)V",
        at = @At(value = "INVOKE", target = "Lcom/odtheking/odin/utils/ChatUtilsKt;modMessage$default(Ljava/lang/Object;Ljava/lang/String;Lnet/minecraft/network/chat/Style;ILjava/lang/Object;)V"),
        index = 0, remap = false)
    private Object ec$twoDecimals(Object message) {
        String name = mapSetting == null ? "" : mapSetting.getName();
        return "TerminalPBs".equals(name) || "TermsimPBs".equals(name) ? OdinTermTimes.format(message) : message;
    }

    @Inject(method = "set(Ljava/lang/String;F)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$notInSim(CallbackInfo ci) {
        if (P3Sim.getInSim()) ci.cancel();
    }

    @ModifyVariable(method = "time(Ljava/lang/String;FLjava/lang/String;Ljava/lang/String;Z)V", at = @At("STORE"), ordinal = 0, remap = false)
    private float ec$noNewPb(float old, String name, float time) {
        return P3Sim.getInSim() ? Math.min(old, time) : old;
    }
}
