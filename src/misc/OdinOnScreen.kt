package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.features.ModuleManager

object OdinOnScreen {

    private const val MIN_LIST = 72

    @JvmStatic
    fun panelMaxY(odinMax: Int): Int = (odinMax - MIN_LIST).coerceAtLeast(0)

    private var saved = IntArray(0)

    @JvmStatic
    fun hudsOnScreen(draw: Runnable) {
        val settings = ModuleManager.hudSettingsCache
        val count = settings.size
        if (saved.size < count * 2) saved = IntArray(count * 2)
        val width = mc.window.guiScaledWidth
        val height = mc.window.guiScaledHeight
        for (i in 0 until count) {
            val hud = settings[i].hud
            saved[2 * i] = hud.x; saved[2 * i + 1] = hud.y
            if (width > 0 && height > 0) {
                hud.x = hud.x.coerceIn(0, (width - hud.scaledWidth).coerceAtLeast(0))
                hud.y = hud.y.coerceIn(0, (height - hud.scaledHeight).coerceAtLeast(0))
            }
        }
        try {
            draw.run()
        } finally {
            for (i in 0 until minOf(count, settings.size)) {
                val hud = settings[i].hud
                hud.x = saved[2 * i]; hud.y = saved[2 * i + 1]
            }
        }
    }
}
