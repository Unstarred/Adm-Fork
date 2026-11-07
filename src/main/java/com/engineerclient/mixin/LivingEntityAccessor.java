package com.engineerclient.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("lerpYHeadRot")
    double ec_getLerpYHeadRot();

    @Accessor("lerpHeadSteps")
    int ec_getLerpHeadSteps();
}
