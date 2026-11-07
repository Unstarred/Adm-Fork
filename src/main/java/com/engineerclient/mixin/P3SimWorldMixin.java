package com.engineerclient.mixin;

import com.engineerclient.ads.arena.Arena;
import com.engineerclient.ads.core.SimServer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Mixin(FlatLevelSource.class)
public class P3SimWorldMixin {

    @Inject(method = "buildTerrain", at = @At("HEAD"))
    private void ec$arena(ChunkAccess chunk, Blender blender, RandomState random, StructureManager structures, BiomeManager biomes,
                          WorldGenRegion region, Set<Holder<Biome>> possibleBiomes, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (SimServer.INSTANCE.getServer() != null) Arena.INSTANCE.fill(chunk);
    }
}
