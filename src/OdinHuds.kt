package com.engineerclient

import com.engineerclient.misc.HealthMana
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Practice
import com.engineerclient.splits.OdinSplitsLook
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.clickgui.settings.impl.drawAtHud
import net.minecraft.client.gui.GuiGraphicsExtractor

object OdinHuds {

    class Draw(private val block: (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int>) : (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int> {
        var hud: HudElement? = null
        var practiceHidden = false

        override fun invoke(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
            val h = hud ?: return block(g, example)
            if (!example && HealthMana.hidesOdinHud(h)) return 0 to 0
            if (!example && practiceHidden && Practice.active && P3Sim.inSim) return 0 to 0
            return OdinSplitsLook.render(h, g, example) ?: block(g, example)
        }
    }

    private val PRACTICE_HIDDEN = setOf("Term Info", "Goldor Hud")

    private val draws = HashMap<HUDSetting, Draw>()
    private var pending: Draw? = null

    @JvmStatic
    fun wrap(block: (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int>): (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int> =
        Draw(block).also { pending = it }

    @JvmStatic
    fun made(setting: HUDSetting) {
        val draw = pending ?: return
        pending = null
        draw.hud = setting.value
        draw.practiceHidden = setting.name in PRACTICE_HIDDEN
        draws[setting] = draw
    }

    fun redraw(g: GuiGraphicsExtractor, setting: HUDSetting) {
        val draw = draws[setting] ?: return
        g.drawAtHud(setting.value) { draw(this, false) }
    }
}
