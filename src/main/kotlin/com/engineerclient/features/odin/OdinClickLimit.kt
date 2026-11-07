package com.engineerclient.features.odin

import com.engineerclient.EngineerClient
import com.engineerclient.util.noFormatting
import com.engineerclient.util.setting
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.impl.boss.TerminalSolver
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs

object OdinClickLimit {

    private const val LIMIT = 5
    private const val WINDOW = 500_000_000L
    private const val NO_PING_GRACE = 150_000_000L
    private const val OWED_NO_PING = 60_000_000L

    private val clickLimit = BooleanSetting("Click Limit", true, desc = "Hypixel takes at most 5 terminal clicks in any 500 ms and silently drops the rest. A click it would drop isn't sent, so the solver doesn't show it done; click again. Added by ADM.")
        .withDependency { TerminalSolver.setting<DropdownSetting>("Solver Functionality")?.value ?: true }

    private val COMPLETION = Regex("^(?:.{1,16} (?:activated a (?:terminal|lever)|completed a device)! \\(\\d+/\\d+\\)|The gate has been destroyed!|The Core entrance is opening!)$")

    private val waiting = ArrayDeque<Long>()
    private val taken = ArrayDeque<Long>()
    private val rtt = ArrayDeque<Long>()
    private val owed = ArrayDeque<Long>()
    private val pings = ArrayDeque<Long>()

    @JvmStatic
    fun blocks(handler: TerminalHandler, slot: Int, button: Int): Boolean {
        if (!clickLimit.value || EngineerClient.mc.gui.screen() is TermSimGUI) return false
        if (!handler.canClick(slot, button) || handler.shouldProtect()) return false
        val now = System.nanoTime()
        synchronized(this) {
            expire(now)
            val lands = now + (rtt.minOrNull() ?: 0L)
            return waiting.size + taken.count { lands - it < WINDOW } >= LIMIT
        }
    }

    @JvmStatic
    fun tap(packet: Packet<*>) {
        if (!clickLimit.value) return
        EngineerClient.safely("click limit tap") { read(packet, System.nanoTime(), false) }
    }

    private fun read(p: Packet<*>, now: Long, inBundle: Boolean) {
        when (p) {
            is ClientboundBundlePacket -> p.subPackets().forEach { read(it, now, true) }
            is ClientboundPingPacket -> if (p.id != 0 && !inBundle) synchronized(this) {
                pings.addLast(now)
                while (pings.size > 16) pings.removeFirst()
                owed.clear()
            }
            is ClientboundSystemChatPacket -> if (!p.overlay() && COMPLETION.matches(p.content().string.noFormatting().trim())) synchronized(this) {
                owed.addLast(now)
            }
            is ClientboundSoundPacket -> if (p.sound.value() == SoundEvents.NOTE_BLOCK_PLING.value() && p.volume >= 7.9f && abs(p.pitch - 4.047619f) < 0.01f) synchronized(this) {
                if (!pingsFlow()) while (owed.isNotEmpty() && now - owed.first() > OWED_NO_PING) owed.removeFirst()
                if (owed.isNotEmpty()) { owed.removeFirst(); return }
                val sent = waiting.removeFirstOrNull() ?: return
                taken.addLast(now); while (taken.size > 8) taken.removeFirst()
                rtt.addLast(now - sent); while (rtt.size > 8) rtt.removeFirst()
            }
            else -> {}
        }
    }

    private fun pingsFlow() = pings.isNotEmpty()

    private fun expire(now: Long) {
        val least = rtt.minOrNull() ?: 0L
        val most = rtt.maxOrNull() ?: 300_000_000L
        val flow = pingsFlow()
        while (waiting.isNotEmpty()) {
            val sent = waiting.first()
            val gone = if (flow) pings.count { it >= sent + least } >= 3 else now - sent > most + NO_PING_GRACE
            if (!gone) break
            waiting.removeFirst()
        }
    }

    private fun reset() = synchronized(this) { waiting.clear(); taken.clear(); rtt.clear(); owed.clear(); pings.clear() }

    fun install() {
        TerminalSolver.registerSetting(clickLimit)
        onSend<ServerboundContainerClickPacket> {
            if (!clickLimit.value || TerminalUtils.currentTerm == null || EngineerClient.mc.gui.screen() is TermSimGUI) return@onSend
            val now = System.nanoTime()
            synchronized(this@OdinClickLimit) { waiting.addLast(now); while (waiting.size > 16) waiting.removeFirst() }
        }
        on<LevelEvent.Load> { reset() }
        EventBus.subscribe(this)
    }
}
