package com.engineerclient.mixin;

import com.engineerclient.features.misc.RandomStuff;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({CuboidItemModelWrapper.class, SpecialModelWrapper.class})
public class ItemFoilMixin {

    private static final String EC_GLINT_HIDDEN = "engineerclient:glint_hidden";

    @Redirect(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z")
    )
    private boolean ec$noItemGlint(
        ItemStack foilStack,
        ItemStackRenderState renderState,
        ItemStack stack,
        ItemModelResolver resolver,
        ItemDisplayContext displayContext,
        ClientLevel level,
        ItemOwner owner,
        int seed
    ) {
        if (RandomStuff.INSTANCE.hidesGlint(displayContext)) {
            renderState.appendModelIdentityElement(EC_GLINT_HIDDEN);
            return false;
        }
        return foilStack.hasFoil();
    }
}
