package com.engineerclient.pov

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.render.text
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.locale.Language
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.FormattedCharSequence
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket

object PovPreviews : Module(
    name = "POV Previews",
    category = Category.custom("ADM", 860, 10),
    description = "Renders each teammate's first-person view into their quarter of Odin's Spirit Leap menu.",
    key = null,
) {

    val previewsPerFrame by NumberSetting("Previews Per Frame", 1, 1..4, 1, desc = "Feeds refreshed each frame; the rest keep their last image. Higher is smoother and costs more.")

    val resolution by NumberSetting("Resolution", 0.75f, 0.25..1.0, 0.05f, desc = "Render scale of each feed, as a fraction of its quadrant. Lower is cheaper and softer.", unit = "x")

    val recullTerrain by BooleanSetting("Re-cull Terrain", true, desc = "Re-runs Sodium's terrain cull from the teammate's eyes. Off is cheaper but shows only the chunks already visible to you.")

    val opacity by NumberSetting("Opacity", 1f, 0.1..1.0, 0.05f, desc = "How opaque the previews are. Below 1 you can see your own game through them (your own view keeps rendering, so this costs a world render).", unit = "x")

    val leapBoxScale by NumberSetting("Leap Box Size", 0.5f, 0.3..1.0, 0.05f, desc = "Shrinks Odin's leap boxes toward the centre while the previews are showing.", unit = "x")
    val leapBoxOpacity by NumberSetting("Leap Box Opacity", 0.2f, 0.1..1.0, 0.05f, desc = "Fades the background of Odin's leap boxes (not the head or name) while the previews are showing.", unit = "x")

    val skipOwnView by BooleanSetting("Skip Own View", false, desc = "Skips rendering your own view while the previews cover the screen. Saves a full world render per frame.")

    enum class HeadSmoothing { VANILLA, RAW, CUSTOM }

    val headSmoothing by SelectorSetting("Head Smoothing", HeadSmoothing.VANILLA, desc = "How a teammate's head movement is interpolated for their preview.")

    val smoothingTicks by NumberSetting("Smoothing Ticks", 3, 0..6, 1, desc = "How far in the past Custom renders the pose, in ticks (50 ms each). 0 is the same as Raw.")
        .withDependency { headSmoothing.ordinal == 2 }

    private val keepHuds by DropdownSetting("Keep HUDs On Top", desc = "")
    private val keepAll by BooleanSetting("Keep All HUDs", true, desc = "Redraws every HUD and GUI that is on (Odin's and EC's) over the previews. Off: only the ones ticked below.").withDependency { keepHuds }
    private val keepTickTimers by BooleanSetting("Keep Tick Timers", true, desc = "Redraws Odin's Necron / Goldor / Storm / Secrets tick timers over the previews.").withDependency { keepHuds && !keepAll }
    private val keepInvincibility by BooleanSetting("Keep Invincibility Timer", true, desc = "Redraws Odin's Invincibility Timer HUD over the previews.").withDependency { keepHuds && !keepAll }
    private val keepMelody by BooleanSetting("Keep Melody Display", true, desc = "Redraws Odin's Melody progress GUI over the previews.").withDependency { keepHuds && !keepAll }
    private val keepRoleHud by BooleanSetting("Keep Role HUD", true, desc = "Redraws EC's own role HUD over the previews.").withDependency { keepHuds && !keepAll }
    private val keepTermInfo by BooleanSetting("Keep Term Info", true, desc = "Redraws the Term Info HUD (Odin's, and Coffee Client's) and Timers' Section Time over the previews.").withDependency { keepHuds && !keepAll }

    private val TICK_TIMER_HUDS = listOf(
        "Necron Hud", "Goldor Hud", "Storm Pad Hud", "Storm Lightning Hud", "Storm PY Hud", "Storm Tick Hud", "Secrets Hud",
    )

    fun keepsHud(name: String): Boolean = keepAll || name in keptHudNames()

    private fun keptHudNames(): Set<String> {
        val names = HashSet<String>()
        if (keepTickTimers) names += TICK_TIMER_HUDS
        if (keepInvincibility) names += "Invincibility Timer"
        if (keepMelody) names += "Progress GUI"
        if (keepRoleHud) names += "Your Role"
        if (keepTermInfo) names += listOf("Term Info", "Section Time")
        return names
    }

    private val onlyBloodRush by BooleanSetting("Only In Blood Rush", false, desc = "Shows the previews on blood rush: from the dungeon starting until the blood door opens. Adds to the other Only In places; none ticked is everywhere.")
    private val onlyBoss by BooleanSetting("Only In Boss", false, desc = "Shows the previews in boss. Adds to the other Only In places; none ticked is everywhere.")
    private val onlyGoldor by BooleanSetting("Only In Goldor", false, desc = "Shows the previews in Goldor (F7/M7 phase 3). Adds to the other Only In places; none ticked is everywhere.")

    private fun places(): List<ShowIn.Place> = buildList {
        if (onlyBloodRush) add(ShowIn.Place.BLOOD_RUSH)
        if (onlyBoss) add(ShowIn.Place.BOSS)
        if (onlyGoldor) add(ShowIn.Place.GOLDOR)
    }

    val showCost by BooleanSetting("Show Cost", true, desc = "HUD line with the milliseconds the previews added to the last frame.")

    val mode: PovPose.Mode
        get() = when (headSmoothing.ordinal) {
            1 -> PovPose.Mode.RAW
            2 -> PovPose.Mode.CUSTOM
            else -> PovPose.Mode.VANILLA
        }

    private val costHud by HUD("POV Cost", "Milliseconds the POV previews added to the last frame, and how many feeds are live.", x = 491, y = 415, scale = 4f) { example ->
        val line = if (example) "§7POV §f1.8§7ms  §f4§7/4" else PovCapture.costLine() ?: return@HUD 0 to 0
        drawLine(this, line)
    }

    private var laidOut: String? = null
    private var laidOutText: FormattedCharSequence? = null
    private var laidOutWidth = 0

    private fun drawLine(gfx: GuiGraphicsExtractor, line: String): Pair<Int, Int> {
        val laid = laidOutText?.takeIf { line == laidOut } ?: Language.getInstance().getVisualOrder(FormattedText.of(line)).also {
            laidOut = line
            laidOutText = it
            laidOutWidth = Minecraft.getInstance().font.width(it)
        }
        gfx.text(laid, 0, 0, Colors.WHITE)
        return laidOutWidth to 9
    }

    @JvmStatic var overlayScale = 1f
    @JvmStatic var overlayAlpha = 1f

    @JvmStatic
    fun fade(argb: Int): Int {
        if (overlayAlpha >= 1f) return argb
        val a = ((argb ushr 24) * overlayAlpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    init {
        costHud.enabled = true
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            overlayScale = 1f
            overlayAlpha = 1f
            EngineerClient.safely("pov gui") {
                if (wants()) { overlayScale = leapBoxScale; overlayAlpha = leapBoxOpacity }
                PovCapture.onScreenExtract(guiGraphics)
            }
        }

        on<TickEvent.End> { PovPose.onClientTick() }

        ClientTickEvents.END_CLIENT_TICK.register {
            EngineerClient.safely("pov release") { if (!wants()) PovCapture.releaseNow() }
        }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (!overlay) ShowIn.onChat(content.string)
        }

        on<LevelEvent.Load> {
            ShowIn.reset()
            PovPose.reset()
            PovCapture.onWorldChange()
        }
    }

    override fun onDisable() {
        overlayScale = 1f
        overlayAlpha = 1f
        PovPose.reset()
        PovCapture.requestFree()
        super.onDisable()
    }

    fun wants(): Boolean {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return false
        if (!enabled || PovCapture.disabledForSession) return false
        if (!LeapMenu.enabled) return false
        if (!ShowIn.allows(places())) return false
        return isLeapScreen(screen)
    }

    private var titledScreen: AbstractContainerScreen<*>? = null
    private var titledLeap = false

    private fun isLeapScreen(screen: AbstractContainerScreen<*>): Boolean {
        if (screen !== titledScreen) {
            titledScreen = screen
            titledLeap = screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")
        }
        return titledLeap
    }
}
