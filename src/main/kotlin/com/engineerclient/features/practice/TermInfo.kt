package com.engineerclient.features.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.arena.Station
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.plan.Practice
import com.engineerclient.f7.F7Chat.CORE_OPEN
import com.engineerclient.f7.F7Chat.GOLDOR_START
import com.engineerclient.util.noFormatting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

object TermInfo {
    fun drawInfo(g: GuiGraphicsExtractor, example: Boolean, simple: Boolean): Pair<Int, Int> {
        if (example) return lines(g, if (simple) listOf("§c2/7") else listOf("§eTerms: 3/4", "§aLevers: 2/2", "§aDevice: §l✔", "§cGate: §l✘"))
        val s = current() ?: return 0 to 0
        return lines(g, if (simple) listOf("${if (s.gateDestroyed) "§a" else "§c"}${s.termsDone + s.leversDone + (if (s.deviceDone) 1 else 0)}/${s.terms + 3}")
        else listOf(
            "${colourFor(s.termsDone, s.terms)}Terms: ${s.termsDone}/${s.terms}",
            "${colourFor(s.leversDone, 2)}Levers: ${s.leversDone}/2",
            if (s.deviceDone) "§aDevice: §l✔" else "§cDevice: §l✘",
            if (s.gateDestroyed) "§aGate: §l✔" else "§cGate: §l✘",
        ))
    }

    fun sectionTime(seconds: Double): String? {
        if (P3Sim.inSim && Practice.active) return null
        val (text, at) = shownTime ?: return null
        return text.takeIf { System.currentTimeMillis() - at <= seconds * 1000 }
    }

    private const val GATE = "The gate has been destroyed!"
    private val TASK = Regex("^(\\w+) (?:activated|completed) a (terminal|lever|device)! \\((\\d)/\\d\\)$")
    private val GATE_CORE_TITLE = Regex("gate (?:has been )?destroyed|core entrance is opening", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("^(\\w{1,16}) (?:activated a (?:terminal|lever)|completed a device)! \\(\\d+/\\d+\\)$")

    private class Section(val terms: Int, val number: Int) {
        var termsDone = 0
        var deviceDone = false
        var leversDone = 0
        var gateDestroyed = number == 4
        var lastIgn = ""
        var lastIndex = 0
        var lastType = ""
    }

    private val sections = listOf(Section(4, 1), Section(5, 2), Section(4, 3), Section(4, 4))
    @Volatile private var active = -1
    @Volatile private var serverTicks = 0
    @Volatile private var sectionStart = 0
    @Volatile private var shownTime: Pair<String, Long>? = null

    private fun current() = simSection() ?: if (P3Sim.localSim) null else sections.getOrNull(active)

    fun sectionNumber(): Int? = current()?.number

    fun deviceDone(n: Int): Boolean = sections.getOrNull(n - 1)?.deviceDone == true

    private fun simSection(): Section? {
        if (!P3Sim.localSim) return null
        val g = Fight.phase as? GoldorPhase ?: return null
        val n = g.section.takeIf { it in 1..4 } ?: return null
        val st = g.stations.filter { it.section == n }
        val kind = Station.Kind.TERMINAL
        return Section(st.count { it.kind == kind }, n).apply {
            termsDone = st.count { it.kind == kind && it.done }
            leversDone = st.count { it.kind == Station.Kind.LEVER && it.done }
            deviceDone = st.any { it.kind == Station.Kind.DEVICE && it.done }
            gateDestroyed = n == 4 || g.gateIsDown(n)
        }
    }

    private fun colourFor(n: Int, max: Int) = when (n) { max -> "§a"; 0 -> "§c"; else -> "§e" }

    private fun lines(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        lines.forEachIndexed { i, l -> gfx.text(l, 0, i * 10, Colors.WHITE, shadow = true) }
        return (lines.maxOfOrNull { mc.font.width(it) } ?: 0) to lines.size * 10
    }

    private fun reset() {
        for (s in sections) with(s) {
            termsDone = 0; deviceDone = false; leversDone = 0; gateDestroyed = number == 4
            lastIgn = ""; lastIndex = 0; lastType = ""
        }
        active = -1
        coreOpen = false
        TerminalHitboxes.reset()
    }

    private fun start() {
        reset()
        active = 0
        sectionStart = serverTicks
    }

    @JvmStatic
    fun simStart(section: Int) {
        reset()
        shownTime = null
        Recore.simStart(section)
        if (section !in 1..4) return
        for (i in 0 until section - 1) with(sections[i]) { termsDone = terms; leversDone = 2; deviceDone = true; gateDestroyed = true }
        active = section - 1
        sectionStart = serverTicks
    }

    @JvmStatic
    fun simClearTime() { shownTime = null }

    @JvmStatic
    fun simSectionTime(ticks: Int) {
        if (P3Sim.inSim && !Practice.active)
            shownTime = "§5${String.format(Locale.ROOT, "%.2f", ticks / 20.0)}" to System.currentTimeMillis()
    }

    @Volatile private var coreOpen = false
    val coreOpened: Boolean get() = coreOpen

    fun liveSection(): Int {
        if (P3Sim.localSim) {
            val g = Fight.phase as? GoldorPhase ?: return 0
            if (g.isHolding) return 0
            return simSection()?.number ?: 0
        }
        return if (active in 0..3) active + 1 else 0
    }

    private fun onTaskChat(msg: String) {
        val cur = current() ?: return
        if (msg == GATE) cur.gateDestroyed = true
        else {
            val match = TASK.matchEntire(msg) ?: return
            val ign = match.groupValues[1]
            val type = match.groupValues[2]
            val index = match.groupValues[3].toIntOrNull() ?: return
            if (index == cur.lastIndex) {
                if (ign == cur.lastIgn) return
                if (type == "device") {
                    when (cur.number) {
                        1 -> if (!sections[3].deviceDone) sections[3].deviceDone = true
                            else if (!sections[1].deviceDone) sections[1].deviceDone = true
                            else sections[2].deviceDone = true
                        2 -> if (!sections[2].deviceDone) sections[2].deviceDone = true
                            else sections[3].deviceDone = true
                        3 -> sections[3].deviceDone = true
                    }
                    return
                } else if (cur.lastType == "device") cur.deviceDone = false
            } else if (index == 2 && cur.lastIndex == 0) {
                cur.deviceDone = true
            } else if (index == 1 && type != "device") {
                cur.deviceDone = false
            }

            when (type) {
                "terminal" -> cur.termsDone++
                "lever" -> cur.leversDone++
                "device" -> {
                    if (cur.deviceDone && cur.number == 2) sections[3].deviceDone = true
                    cur.deviceDone = true
                }
            }
            cur.lastIgn = ign; cur.lastIndex = index; cur.lastType = type
        }

        if (cur.termsDone >= cur.terms && cur.leversDone >= 2 && cur.deviceDone && cur.gateDestroyed) {
            if (!P3Sim.inSim) shownTime = "§5${String.format(Locale.ROOT, "%.2f", (serverTicks - sectionStart) / 20.0)}" to System.currentTimeMillis()
            sectionStart = serverTicks
            active++
            if (active == 3) Recore.s3Done()
        }
    }

    @JvmStatic
    fun tap(packet: Packet<*>) {
        EngineerClient.safely("term info tap") {
            when (packet) {
                is ClientboundSystemChatPacket -> take(packet)
                is ClientboundOpenScreenPacket -> TerminalHitboxes.opened(packet.title.string)
                is ClientboundBundlePacket -> packet.subPackets().forEach {
                    if (it is ClientboundSystemChatPacket) take(it)
                    else if (it is ClientboundOpenScreenPacket) TerminalHitboxes.opened(it.title.string)
                }
                else -> {}
            }
        }
    }

    private val seenChat: MutableSet<ClientboundSystemChatPacket> = Collections.newSetFromMap(IdentityHashMap())
    private val seenChatOrder = ArrayDeque<ClientboundSystemChatPacket>()

    private fun take(p: ClientboundSystemChatPacket) {
        if (p.overlay()) return
        synchronized(seenChat) {
            if (!seenChat.add(p)) return
            seenChatOrder.addLast(p)
            while (seenChatOrder.size > 256) seenChat.remove(seenChatOrder.removeFirst())
        }
        val msg = p.content().string.noFormatting()
        mc.execute { EngineerClient.safely("term info chat") { onChat(msg) } }
    }

    private fun onChat(msg: String) {
        when {
            msg == GOLDOR_START -> if (active == -1) start()
            msg == CORE_OPEN -> { active = -1; coreOpen = true }
            else -> {
                if (active == -1 && TASK.matches(msg)) start()
                onTaskChat(msg)
                TerminalHitboxes.chat(msg)
            }
        }
    }

    fun init() {
        on<LevelEvent.Load> { reset(); shownTime = null }
        on<TickEvent.Server> { serverTicks++ }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) { take(this) }
        onReceive<ClientboundOpenScreenPacket>(priority = 1000, ignoreCancelled = true) { TerminalHitboxes.opened(title.string) }
        EventBus.subscribe(this)
    }

    fun isCompletionTitle(raw: String): Boolean {
        val text = raw.noFormatting()
        if (GATE_CORE_TITLE.containsMatchIn(text)) return true
        return (active in 0..3 || simSection() != null) && TITLE.matches(text)
    }
}
