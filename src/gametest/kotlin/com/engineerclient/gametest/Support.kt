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

internal fun <T> ClientGameTestContext.onClient(block: (Minecraft) -> T): T =
    computeOnClient<T, RuntimeException> { mc -> block(mc) }

internal fun ClientGameTestContext.command(cmd: String) = onClient { mc -> mc.player!!.connection.sendCommand(cmd) }

internal fun ClientGameTestContext.enterAds() {
    onClient { P3Sim.autoStartS.value = true; SimWorld.open() }
    waitFor({ mc -> P3Sim.inSim && mc.player != null && mc.gui.screen() == null }, 20 * 180)
}

internal fun ClientGameTestContext.leaveAds() {
    onClient { SimWorld.leave() }
    waitFor({ mc -> mc.level == null }, 20 * 60)
    waitTicks(20 * 10)
    setScreen { TitleScreen() }
    waitForScreen(TitleScreen::class.java)
}

internal class Checks(private val context: ClientGameTestContext, private val test: String) {
    private val lines = mutableListOf("$test ${LocalDateTime.now()}")
    private val failures = mutableListOf<String>()

    fun phase(name: String, body: () -> Unit) {
        val mark = ProblemLog.mark()
        val error = runCatching(body).exceptionOrNull()
        val problems = ProblemLog.since(mark)
        val ok = error == null && problems.isEmpty()
        lines += (if (ok) "PASS " else "FAIL ") + name
        if (error != null) fail("$name: threw $error")
        for (p in problems) fail("$name: $p")
    }

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
        val all = mutableListOf<String>()
    }
}
