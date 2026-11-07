package com.engineerclient.ads.core

import net.minecraft.client.Minecraft
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.party.PartyClient
import com.engineerclient.ads.plan.P3Plan
import com.engineerclient.ads.plan.Roles

object Stats {
    private var from = 1
    private var practice = false
    private val sections = IntArray(5) { -1 }
    private val mine = ConcurrentHashMap<String, Int>()
    private val deaths = ConcurrentHashMap<String, Int>()

    fun reset(from: Int, practice: Boolean = false) { this.from = from; this.practice = practice; sections.fill(-1); mine.clear(); deaths.clear() }

    private fun s(ticks: Int) = "%.2fs".format(Locale.ROOT, ticks / 20.0)

    fun done(by: String) { mine.merge(by, 1, Int::plus) }

    fun section(s: Int, ticks: Int) {
        if (s !in 1..4 || sections[s] >= 0) return
        sections[s] = ticks
        val best = if (practice) "" else best("S$s", ticks)
        for (p in Sim.players()) Sim.asPlayer(p) { if (P3Sim.showTimes) Sim.note("§fS$s§7 ${s(ticks)}" + if (Sim.isHost(p)) best else "") }
        PartyClient.send("sectionTime", ticks)
    }

    private val bestFile get() = File(Minecraft.getInstance().gameDirectory, "config/adm/p3sim-pb.properties")
    private val bests: Properties by lazy { Properties().also { p -> runCatching { bestFile.inputStream().use { p.load(it) } } } }

    fun best(what: String, ticks: Int): String {
        val key = "$what @ ${P3Plan.skillName()} ${Roles.label(P3Sim.ownClass)}" + if (P3Plan.skill == P3Plan.RANDOM) " ${P3Plan.botMin}-${P3Plan.botMax}s" else ""
        val old = bests.getProperty(key)?.toIntOrNull()
        if (old != null && ticks >= old) return " §8PB ${s(old)}"
        bests.setProperty(key, ticks.toString())
        runCatching { bestFile.parentFile.mkdirs(); bestFile.outputStream().use { bests.store(it, "ADS personal bests, server ticks") } }
        return if (old == null) " §6PB" else " §6§lNEW PB §8(was ${s(old)})"
    }

    fun deathTick(by: String) { deaths.merge(by, 1, Int::plus) }

    fun p3(n: Int) {
        val parts = (1..4).filter { sections[it] >= 0 }.joinToString(" §8|§7 ") { "S$it ${s(sections[it])}" }
        val best = if (from == 1 && !practice && P3Sim.showTimesS.value) best("P3", n) else ""
        val total = if (from == 1) " §8|§f P3 ${s(n)}" else ""
        for (p in Sim.players()) Sim.asPlayer(p) {
            if (!P3Sim.showTimes) return@asPlayer
            Sim.note("$parts$total" + if (Sim.isHost(p)) best else "")
            val n = p.gameProfile.name
            Sim.note("You did §f${mine[n] ?: 0}§7 of the jobs; death ticks taken: §f${deaths[n] ?: 0}")
        }
    }

    var runStart = -1

    fun runTicks() = if (runStart >= 0) Fight.serverTick - runStart else 0
}
