package com.engineerclient.gametest

import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.core.SimWorld
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.LocalDateTime

internal val log = LoggerFactory.getLogger("adm-gametest")

/** [ClientGameTestContext.computeOnClient] with its exception type fixed, which Kotlin can't infer from a lambda. */
internal fun <T> ClientGameTestContext.onClient(block: (Minecraft) -> T): T =
    computeOnClient<T, RuntimeException> { mc -> block(mc) }

/** A command as if typed in chat: Fabric's client commands (ADM's) take it before the server sees it. */
internal fun ClientGameTestContext.command(cmd: String) = onClient { mc -> mc.player!!.connection.sendCommand(cmd) }

/** Into ADS, the way its title button goes, until you stand in the world with no screen up. */
internal fun ClientGameTestContext.enterAds() {
    onClient { P3Sim.autoStartS.value = true; SimWorld.open() }
    waitFor({ mc -> P3Sim.inSim && mc.player != null && mc.gui.screen() == null }, 20 * 180)
}

/**
 * Out of ADS and back on the title screen. The harness fails a test that ends with a server still running, or off the
 * title screen, and it defers the disconnect: so the level first, time for the server thread to stop, then the title
 * screen put up here.
 */
internal fun ClientGameTestContext.leaveAds() {
    onClient { SimWorld.leave() }
    waitFor({ mc -> mc.level == null }, 20 * 60)
    waitTicks(20 * 10)
    setScreen { TitleScreen() }
    waitForScreen(TitleScreen::class.java)
}

/**
 * Soft checks for one test: every phase runs, whatever an earlier one found, and each records what failed, ADM's
 * logged problems included. [finish] writes the report (`gametest-report/<test>.txt` in the game folder) and fails the
 * test with every failure at once.
 */
internal class Checks(private val context: ClientGameTestContext, private val test: String) {
    private val lines = mutableListOf("$test ${LocalDateTime.now()}")
    private val failures = mutableListOf<String>()

    /** Runs [body] as a phase: a throw fails it, and so does anything ADM logged during it. */
    fun phase(name: String, body: () -> Unit) {
        val mark = ProblemLog.mark()
        val error = runCatching(body).exceptionOrNull()
        val problems = ProblemLog.since(mark)
        val ok = error == null && problems.isEmpty()
        lines += (if (ok) "PASS " else "FAIL ") + name
        if (error != null) fail("$name: threw $error")
        for (p in problems) fail("$name: $p")
    }

    /** A failure unless [condition]. */
    fun expect(what: String, condition: Boolean) {
        if (!condition) fail(what)
    }

    fun note(text: String) { lines += "     $text" }

    private fun fail(what: String) {
        failures += what
        lines += "     ! $what"
        log.error("[gametest] {}", what)
    }

    fun finish() {
        val dir = context.onClient { mc -> mc.gameDirectory.toPath().resolve("gametest-report") }
        Files.createDirectories(dir)
        Files.write(dir.resolve("$test.txt"), lines + "" + (if (failures.isEmpty()) "ALL PASSED" else "${failures.size} FAILED"))
        lines.forEach { log.info("[gametest] {}", it) }
        all += failures.map { "$test: $it" }
    }

    companion object {
        /** Every test's failures: a throw would end the run there, so [VerdictGameTest] fails it at the end instead. */
        val all = mutableListOf<String>()
    }
}
