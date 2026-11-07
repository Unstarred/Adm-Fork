package com.engineerclient.features.odin

import com.engineerclient.EngineerClient
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.party.PartyClient
import com.engineerclient.util.setting
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.InvincibilityTimer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

object OdinMasksUsed {

    private val onlyUsed = BooleanSetting("Only Used", false, desc = "Only show the masks and Phoenix that have saved you this run. Added by ADM.")
        .withDependency { InvincibilityTimer.setting<DropdownSetting>("Displayed Items")?.value ?: true }

    private val PROCS = listOf(
        Regex("^Second Wind Activated! Your Spirit Mask saved your life!$"),
        Regex("^Your (?:. )?Bonzo's Mask saved your life!$"),
        Regex("^Your Phoenix Pet saved you from certain death!$"),
    )
    private val used = BooleanArray(3)

    @JvmStatic
    fun shows(index: Int, setting: Boolean): Boolean = setting && (!onlyUsed.value || used[index]) &&
        !(P3Sim.inSim && PartyClient.phase.isEmpty())

    fun resetTimers() {
        used.fill(false)
        EngineerClient.safely("odin invincibility reset") {
            val type = Class.forName("com.odtheking.odin.features.impl.dungeon.InvincibilityTimer\$InvincibilityType")
            val reset = type.getMethod("reset").apply { isAccessible = true }
            type.enumConstants.forEach { reset.invoke(it) }
        }
    }

    fun install() {
        InvincibilityTimer.registerSetting(onlyUsed)
        on<MessageEvent.Chat> {
            if (InvincibilityTimer.setting<BooleanSetting>("Only In Dungeons")?.value != false && !DungeonUtils.inDungeons) return@on
            PROCS.indexOfFirst { message.matches(it) }.takeIf { it >= 0 }?.let { used[it] = true }
        }
        on<LevelEvent.Load> { used.fill(false) }
        EventBus.subscribe(this)
    }
}
