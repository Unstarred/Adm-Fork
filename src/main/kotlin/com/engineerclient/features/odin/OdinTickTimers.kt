package com.engineerclient.features.odin

import com.engineerclient.f7.F7Chat.GOLDOR_START
import com.engineerclient.util.setting
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.TickTimers

object OdinTickTimers {

    private const val TICK_PREFIX = "§7Tick:"

    private val countUp = BooleanSetting("Goldor Count Up", false, desc = "The Goldor Hud's Tick timer counts up from Goldor's first line like a split, past 3 s; the colour still goes green / yellow / red with each tick. Added by ADM.")
        .withDependency { TickTimers.setting<HUDSetting>("Goldor Hud")?.value?.enabled ?: true }

    private var serverTicks = 0
    private var goldorStart: Int? = null

    @JvmStatic
    fun tick(time: Int, max: Int, prefix: String, format: (Int, Int, String, String?) -> String): String {
        if (!countUp.value || prefix != TICK_PREFIX) return format(time, max, prefix, null)
        val colour = when {
            time >= max * 0.66f -> "§a"
            time >= max * 0.33f -> "§6"
            else -> "§c"
        }
        val elapsed = goldorStart?.let { serverTicks - it } ?: 70
        return format(elapsed, max, prefix, colour)
    }

    fun stopAll() {
        goldorStart = null
        for (f in listOf("goldorTickTime", "goldorStartTime", "padTickTime", "lightningTickTime", "pyTickTime", "stormTick", "necronTime", "fireFreezeTime"))
            runCatching { TickTimers::class.java.getDeclaredField(f).apply { isAccessible = true }.setInt(TickTimers, -1) }
        runCatching { TickTimers::class.java.getDeclaredField("pyTriggered").apply { isAccessible = true }.setBoolean(TickTimers, false) }
    }

    fun install() {
        TickTimers.registerSetting(countUp)
        on<TickEvent.Server> { serverTicks++ }
        on<MessageEvent.Chat> { if (message == GOLDOR_START) goldorStart = serverTicks }
        on<LevelEvent.Load> { goldorStart = null }
        EventBus.subscribe(this)
    }
}
