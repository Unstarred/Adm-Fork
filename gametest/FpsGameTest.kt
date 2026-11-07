package com.engineerclient.gametest

import com.engineerclient.EngineerClient
import com.engineerclient.misc.RandomStuff
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.SimItems
import com.engineerclient.p3sim.SimWorld
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
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.util.profiling.jfr.Environment
import net.minecraft.util.profiling.jfr.JvmProfiler
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The FPS bench: ADS's F7 boss with the frame-heavy ADM and Odin modules on, measured in three phases, with a JFR
 * recording of the measured part (Minecraft's own profiler, so the recording carries its per-second FPS events and
 * `Analyze.java` can split it by frame phase).
 *
 * Each phase samples `Minecraft.fps` once a second. The results go to `fps/<time>.txt` in the gametest's game folder,
 * with the recording's path. The same run before and after a change is the comparison: absolute numbers depend on
 * the window size the harness gives the client, on the card, and on anything else using the GPU at the time.
 */
class FpsGameTest : FabricClientGameTest {
    private val log = LoggerFactory.getLogger("adm-gametest")

    /** The modules this bench measures, switched on whatever the gametest config says: all of ADM's, and Odin's it adds to. */
    private val modules: List<Module> get() = EngineerClient.MODULES + listOf(
        LeapMenu, Splits, DungeonMap, TerminalSolver, TerminalTimes, SimonSays, TickTimers, InvincibilityTimer,
    )

    /**
     * Options off by default that add work to every frame, on for the bench. Blur In GUI stays off: it is vanilla's own
     * blur, a picture the player chooses, and it would swamp everything else in the leap menu phase.
     */
    private val perFrameOptions = listOf(RandomStuff to "Clean Scoreboard")

    override fun runTest(context: ClientGameTestContext) {
        context.onClient { mc -> prepare(mc) }
        context.waitTicks(20)
        context.onClient { SimWorld.open() }
        context.waitFor({ mc -> P3Sim.inSim && mc.player != null && mc.gui.screen() == null }, 20 * 180)
        // P3 starts on join (Start On Join); the first seconds are world loading, chunk builds and a cold JIT.
        context.waitTicks(20 * WARM_UP_S)
        context.onClient { JvmProfiler.INSTANCE.start(Environment.CLIENT) }
        // Starting JFR instruments its event classes: a burst of allocation and a GC that is the profiler's, not ADM's.
        context.waitTicks(20 * 3)
        val boss = sample(context, PHASE_S)
        val leapOpened = openLeap(context)
        val leap = sample(context, PHASE_S)
        context.onClient { mc -> mc.player?.closeContainer() }
        val after = sample(context, PHASE_S / 2)
        val jfr = context.onClient { JvmProfiler.INSTANCE.stop() }
        val leapName = if (leapOpened) "leap-menu" else "leap-menu(NOT OPENED)"
        report(context, listOf("boss" to boss, leapName to leap, "boss-after" to after), jfr.toAbsolutePath().toString())
        // The harness fails a test that ends with a server still running, or off the title screen. It defers the
        // disconnect, so ADS's own TitleScreen goes up before it and "Leaving ADS" is left showing; and the client can
        // keep its reference to the stopping server well after the level is gone. So: the level, time for the server
        // thread to stop, then the title screen put up here.
        context.onClient { SimWorld.leave() }
        context.waitFor({ mc -> mc.level == null }, 20 * 60)
        context.waitTicks(20 * 10)
        context.setScreen { TitleScreen() }
        context.waitForScreen(TitleScreen::class.java)
    }

    /** [ClientGameTestContext.computeOnClient] with its exception type fixed, which Kotlin can't infer from a lambda. */
    private fun <T> ClientGameTestContext.onClient(block: (Minecraft) -> T): T =
        computeOnClient<T, RuntimeException> { mc -> block(mc) }

    /** What every run measures against: no frame cap, no vsync, no AFK limit, the modules on, P3 on join. */
    private fun prepare(mc: Minecraft) {
        mc.options.framerateLimit().set(260)
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED)
        mc.options.enableVsync().set(false)
        for (module in modules) if (!module.enabled) module.toggle()
        for ((module, name) in perFrameOptions) (module.settings[name] as BooleanSetting).value = true
        P3Sim.autoStartS.value = true
    }

    /** Opens the leap menu the way a player does: the Infinileap's hotbar key, then use. False if it never opened. */
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

    /** One FPS reading a second for [seconds]: `Minecraft.fps` is the count of the second before. */
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
            // A phase that never passed 60 measured a limiter, not the game: the window minimized, hidden or out of
            // focus during the run (one run read a flat 20 the whole way through).
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
