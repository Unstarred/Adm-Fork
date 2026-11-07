package com.engineerclient.features.practice

import com.engineerclient.mixin.ContainerScreenAccessor
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.GuiEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.boss.TerminalSimulator
import com.odtheking.odin.features.impl.boss.TerminalSolver
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils

object TermsimExtras {
    private val hoverTerms = BooleanSetting("Hover Terms", false, desc = "Hovering the slot to click clicks it (numbers: the next number). Termsim only. Added by ADM.")

    fun install() {
        TerminalSimulator.registerSetting(hoverTerms)
        ModuleManager.loadConfigurations()
        EventBus.subscribe(this)
    }

    init {
        on<GuiEvent.Render> {
            if (!hoverTerms.value) return@on
            val screen = screen as? TermSimGUI ?: return@on
            val term = TerminalUtils.currentTerm ?: return@on
            if (term.type == TerminalTypes.MELODY) return@on
            val acc = screen as ContainerScreenAccessor
            val mx = mouseX - acc.adrLeftPos(); val my = mouseY - acc.adrTopPos()
            val slot = screen.menu.slots.firstOrNull { mx >= it.x - 1 && mx < it.x + 17 && my >= it.y - 1 && my < it.y + 17 }?.index ?: return@on
            if (slot in term.solution && term.canClick(slot, 0)) term.click(slot, 0, TerminalSolver.clickPrediction)
        }
    }
}
