package com.engineerclient.features.odin

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.features.ModuleManager

/**
 * Odin's GUIs never off screen (PanelOnScreenMixin, HudOnScreenMixin).
 *
 *  - Click GUI: Odin keeps a panel's header on screen (it clamps every panel to the screen as it lays it out, and while
 *    you drag it), but the header could sit on the very bottom edge with its open module list all below the screen. The
 *    lowest a header may sit is raised by [MIN_LIST]: Odin's own floor for a panel's scrolled list, so at least that much
 *    of the list always shows and the rest scrolls up into it.
 *  - HUDs: Odin's HUD editor pulls the HUDs onto the screen when it opens and as you move or resize one, but in play each
 *    is drawn where it was saved, so a smaller window, a bigger GUI scale or a HUD grown wider could leave it off screen
 *    until the editor was opened. Each is drawn clamped to the screen (as the editor would put it), its saved place left
 *    as it is, the way Odin's Click GUI treats panels: back where it was once there's room.
 */
object OdinOnScreen {

    /** Odin's MIN_VISIBLE: the least of a panel's module list it keeps showing when scrolled. */
    private const val MIN_LIST = 72

    /** The lowest a Click GUI panel's header may sit ([odinMax]: Odin's, the screen's height less the header's). */
    @JvmStatic
    fun panelMaxY(odinMax: Int): Int = (odinMax - MIN_LIST).coerceAtLeast(0)

    /**
     * Each HUD's saved place while [hudsOnScreen] draws, x then y, kept between frames so a frame allocates nothing.
     * Render thread only.
     */
    private var saved = IntArray(0)

    /**
     * Runs [draw] (Odin drawing the HUDs in play) with each HUD inside the screen, then puts the saved places back.
     *
     * Every HUD is clamped, drawn or not: one that is off doesn't draw, and asking would read its enabled flag, which
     * in Odin 0.4.0 is Compose state (a snapshot lookup per HUD per frame). Positions and sizes are plain fields. This
     * runs every frame in play: the filtered and mapped lists it used to build were ~2.5% of the render thread in the
     * FPS bench (gametest/FpsGameTest, 2026-11-06).
     */
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
            // A minimised window has no size: leave every HUD where it is.
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
