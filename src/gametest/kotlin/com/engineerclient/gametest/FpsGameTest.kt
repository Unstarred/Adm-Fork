package com.engineerclient.gametest

import com.engineerclient.EngineerClient
import com.engineerclient.features.misc.RandomStuff
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.core.SimWorld
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.SimonSays
import com.odtheking.odin.features.impl.boss.TerminalSolver
import com.odtheking.odin.features.impl.boss.TerminalTimes
import com.odtheking.odin.features.impl.boss.TickTimers
import com.odtheking.odin.features.impl.dungeon.InvincibilityTimer
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.features.impl.dungeon.map.DungeonMap
import com.odtheking.odin.features.impl.skyblock.Splits
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.InactivityFpsLimit
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.util.profiling.jfr.Environment
import net.minecraft.util.profiling.jfr.JvmProfiler
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class FpsGameTest : FabricClientGameTest {
    private val modules: List<Module> get() = EngineerClient.MODULES + listOf(
        LeapMenu, Splits, DungeonMap, TerminalSolver, TerminalTimes, SimonSays, TickTimers, InvincibilityTimer,
    )

    private val perFrameOptions = listOf(RandomStuff to "Clean Scoreboard")

    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("ADM_BENCH") != "1") return
        context.onClient { mc -> prepare(mc) }
        context.waitTicks(20)
        context.onClient { SimWorld.open() }
        context.waitFor({ mc -> P3Sim.inSim && mc.player != null && mc.gui.screen() == null }, 20 * 180)
        context.waitTicks(20 * WARM_UP_S)
        context.onClient { JvmProfiler.INSTANCE.start(Environment.CLIENT) }
        context.waitTicks(20 * 3)
        val boss = sample(context, PHASE_S)
        val leapOpened = openLeap(context)
        val leap = sample(context, PHASE_S)
        context.onClient { mc -> mc.player?.closeContainer() }
        val after = sample(context, PHASE_S / 2)
        val jfr = context.onClient { JvmProfiler.INSTANCE.stop() }
        val leapName = if (leapOpened) "leap-menu" else "leap-menu(NOT OPENED)"
        report(context, listOf("boss" to boss, leapName to leap, "boss-after" to after), jfr.toAbsolutePath().toString())
        context.leaveAds()
    }

    private fun prepare(mc: Minecraft) {
        mc.options.framerateLimit().set(260)
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED)
        mc.options.enableVsync().set(false)
        for (module in modules) if (!module.enabled) module.toggle()
        for ((module, name) in perFrameOptions) (module.settings[name] as BooleanSetting).value = true
        P3Sim.autoStartS.value = true
    }

    private fun openLeap(context: ClientGameTestContext): Boolean {
        val slot = context.onClient { mc ->
            val inventory = mc.player?.inventory
            (0 until 9).firstOrNull { inventory != null && SimItems.idOf(inventory.getItem(it)) == "INFINITE_SPIRIT_LEAP" } ?: -1
        }
        if (slot < 0) return false
        context.getInput().pressKey { options -> options.keyHotbarSlots[slot] }
        context.waitTicks(2)
        context.getInput().pressKey { options -> options.keyUse }
        return runCatching {
            context.waitFor({ mc -> (mc.gui.screen() as? AbstractContainerScreen<*>)?.title?.string == "Spirit Leap" }, 20 * 10)
        }.isSuccess
    }

    private fun sample(context: ClientGameTestContext, seconds: Int): IntArray {
        val out = IntArray(seconds)
        for (i in 0 until seconds) {
            context.waitTicks(20)
            out[i] = context.onClient { mc -> mc.fps }
        }
        return out
    }

    private fun report(context: ClientGameTestContext, phases: List<Pair<String, IntArray>>, jfr: String) {
        val lines = mutableListOf("adm fps bench ${LocalDateTime.now()}", "jfr: $jfr")
        for ((name, fps) in phases) {
            val sorted = fps.sorted()
            val limited = if (sorted.last() <= 60) "  LIMITED: window minimized or hidden? discard this run" else ""
            lines += "%-24s mean %6.1f  p50 %4d  min %4d  max %4d  n %d  %s%s".format(
                name, fps.average(), sorted[sorted.size / 2], sorted.first(), sorted.last(), fps.size, fps.joinToString(","), limited)
        }
        val dir = context.onClient { mc -> mc.gameDirectory.toPath().resolve("fps") }
        Files.createDirectories(dir)
        val file = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt")
        Files.write(file, lines)
        lines.forEach { log.info("[fps] {}", it) }
        log.info("[fps] written to {}", file.toAbsolutePath())
    }

    private companion object {
        const val WARM_UP_S = 15
        const val PHASE_S = 20
    }
}
