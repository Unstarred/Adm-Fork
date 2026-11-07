package com.engineerclient.features.practice

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.boss.TerminalTimes
import java.util.Locale

object OdinTermTimes {
    private val twoDecimals = BooleanSetting("Two Decimals", false, desc = "Times in chat as 5.12s, not 5.123s (the PBs are still kept exact). Added by ADM.")

    fun install() {
        TerminalTimes.registerSetting(twoDecimals)
        ModuleManager.loadConfigurations()
    }

    private val DECIMAL = Regex("\\d+\\.\\d+")

    @JvmStatic
    fun format(message: Any?): Any? {
        if (!twoDecimals.value || message !is String) return message
        return DECIMAL.replace(message) { String.format(Locale.ROOT, "%.2f", it.value.toDouble()) }
    }
}
