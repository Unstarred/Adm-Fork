package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import net.minecraft.SharedConstants
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraft.world.Difficulty
import net.minecraft.world.level.DataPackConfig
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.levelgen.structure.StructureSet
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.zip.CRC32

object SimWorld {
    const val NAME = "p3sim"
    private const val MARKER = "p3sim-arena.txt"
    private const val WORLD_VERSION = 3

    val arenaVersion: String by lazy {
        val crc = CRC32()
        for (f in listOf("arena.bin", "arena-ext.bin", "arena-fixes.json")) Arena::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$f")?.use { crc.update(it.readAllBytes()) }
        java.lang.Long.toHexString(crc.value) + "-" + WORLD_VERSION
    }

    fun open() {
        mc.execute {
            if (P3Sim.inSim) return@execute
            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Opening ADS"))
            opening = true
            try { openOrCreate() } catch (t: Throwable) { failed("open", t) }
        }
    }

    private fun openOrCreate() {
        val source = mc.levelSource
        if (Files.isDirectory(source.baseDir.resolve(NAME))) source.createAccess(NAME).use { it.deleteLevel() }
        create()
    }

    fun leave() {
        mc.execute {
            if (!P3Sim.inSim) return@execute
            mc.disconnectFromWorld(Component.literal("Leaving ADS"))
            mc.gui.setScreen(TitleScreen())
        }
    }

    fun rebuild() {
        mc.execute {
            if (P3Sim.inSim && !P3Sim.localSim) { mc.player?.sendSystemMessage(Component.literal("§8[§6ADS§8] §7Only the host can rebuild the world.")); return@execute }
            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Rebuilding ADS"))
            opening = true
            try {
                mc.levelSource.createAccess(NAME).use { it.deleteLevel() }
                create()
            } catch (t: Throwable) { failed("rebuild", t) }
        }
    }

    private fun create() {
        mc.gui.setScreen(GenericMessageScreen(Component.literal("Building the F7 boss...")))
        HypixelPack.loadThen { try { build() } catch (t: Throwable) { failed("build", t) } }
    }

    private fun build() {
        val envPack = installEnvPack()
        val dataConfiguration = if (!envPack) WorldDataConfiguration.DEFAULT
            else WorldDataConfiguration(DataPackConfig(listOf("vanilla", WorldEnv.PACK_ID), emptyList()), WorldDataConfiguration.DEFAULT.enabledFeatures())
        val settings = LevelSettings(
            NAME, GameType.SURVIVAL,
            LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true),
            true, dataConfiguration,
        )
        mc.createWorldOpenFlows().createFreshLevel(NAME, settings, WorldOptions(0L, false, false), { provider ->
            val biomes = provider.lookupOrThrow(Registries.BIOME)
            val plains = biomes.getOrThrow(Biomes.PLAINS)
            val flat = FlatLevelGeneratorSettings(Optional.of<HolderSet<StructureSet>>(HolderSet.empty()), plains, emptyList())
            provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions().replaceOverworldGenerator(provider, FlatLevelSource(flat))
        }, TitleScreen())
    }

    private fun installEnvPack(): Boolean = try {
        val vanilla = SimWorld::class.java.getResourceAsStream("/data/minecraft/dimension_type/overworld.json")?.use { it.readAllBytes().toString(Charsets.UTF_8) }
            ?: error("the game's overworld dimension type was not found")
        val format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)
        WorldEnv.installPack(mc.levelSource.baseDir.resolve(NAME), vanilla, format.major(), format.minor())
        true
    } catch (t: Throwable) {
        EngineerClient.logger.warn("[ec] ADS world data pack not written; the world keeps the vanilla sky", t)
        false
    }

    private fun failed(what: String, t: Throwable) {
        EngineerClient.logger.error("[ec] p3sim $what failed", t)
        opening = false
        mc.gui.setScreen(TitleScreen())
    }

    @Volatile var opening = false

    fun isBuilt(worldDir: Path) = runCatching { Files.readString(worldDir.resolve(MARKER)).trim() == arenaVersion }.getOrDefault(false)

    fun markBuilt(worldDir: Path) {
        EngineerClient.safely("p3sim marker") { Files.writeString(worldDir.resolve(MARKER), arenaVersion) }
    }
}
