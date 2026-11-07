package com.engineerclient.misc

import com.engineerclient.DeadPlayers
import com.engineerclient.practice.TermInfo
import com.engineerclient.storm.StormCrush
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.level.block.Blocks
import java.util.Locale

object Timers : Module(
    name = "Timers",
    category = Category.custom("ADM", 860, 10),
    description = "Countdowns through a run: clear, exit core, Maxor moving, crystals, Purple's pad, terminal section times, and Necron becoming damageable (Goldor's death tick: Odin's Goldor Hud).",
    key = null,
) {

    private val clearHud by HUD("Clear Countdown", "From blood opening, counts down 54 s to the boss (50 s camp, 4 s portal): green, yellow under 30, red under 20. Hidden if blood isn't done by 50 s; set to 4 s when the portal spawns.", true, 420, 200, 2f) { example ->
        val left = if (example) 41.3 else bossTicksLeft()?.let { it / 20.0 } ?: return@HUD 0 to 0
        val colour = when { left > 30 -> "§a"; left > 20 -> "§e"; else -> "§c" }
        draw(colour + String.format(Locale.ROOT, "%.1f", left))
    }
    private val portalHud by HUD("Portal Text", "\"PORTAL\" in pink on screen for 1.8 s when the portal spawns.", true, 400, 160, 4f) { example ->
        if (!example && (!enabled || DungeonUtils.inBoss || portalUntil - serverTicks <= 0)) return@HUD 0 to 0
        draw("§d§lPORTAL")
    }
    private val exitCoreHud by HUD("Exit Core Text", "\"EXIT CORE\" in pink on screen for 0.8 s once the core has opened and everyone has leapt in.", true, 400, 160, 4f) { example ->
        if (!example && (!enabled || exitCoreUntil - serverTicks <= 0)) return@HUD 0 to 0
        draw("§d§lEXIT CORE")
    }

    private val maxorHud by HUD("Maxor Move", "Counts down to Maxor starting to move, 4.25 s after his first line.", true, 10, 100, 1.5f) { example ->
        if (example) timer("Maxor", 100, MAXOR_MOVE, true) else left(maxorMoveAt, MAXOR_MOVE)?.let { timer("Maxor", it, MAXOR_MOVE) } ?: (0 to 0)
    }
    private val crystalHud by HUD("Crystal Spawn", "Counts down to the top crystals coming back after a laser hit, 2 s.", true, 10, 115, 1.5f) { example ->
        if (example) timer("Crystals", 25, CRYSTALS_BACK, true) else left(crystalsAt, CRYSTALS_BACK)?.let { timer("Crystals", it, CRYSTALS_BACK) } ?: (0 to 0)
    }
    private val purplePadHud by HUD("Purple Pad", "Storm: counts down to the pad read to be on Purple's pad for (t 599, then t 619 if that one's missed), so he's crushed the moment he gets there. Gone once the pillar comes down.", true, 10, 160, 1.5f) { example ->
        if (example) timer("Purple", 30, PAD_TOTAL, true) else padLeft(purpleReads())?.let { timer("Purple", it, PAD_TOTAL) } ?: (0 to 0)
    }
    private val sectionHud: HudElement by HUD("Section Time", "When a terminal section is done, how long it took, in purple. S1 from Goldor's first line, the rest from the last section's end.", true, 420, 330, 5f) { example ->
        draw(if (example) "§514.35" else TermInfo.sectionTime(sectionSeconds) ?: return@HUD 0 to 0)
    }
    private val sectionSeconds: Double by NumberSetting("Section Time Seconds", 1.5, 0.5..10.0, 0.5, desc = "How long a section's time stays up.", unit = "s").withDependency { sectionHud.enabled }
    private val necronHud by HUD("Necron DPS", "Counts down to Necron first taking damage, 4.45 s after his first line (\"You went further than any human before, congratulations.\").", true, 10, 175, 1.5f) { example ->
        if (example) timer("Necron", 35, NECRON_DPS, true) else left(necronDpsAt, NECRON_DPS)?.let { timer("Necron", it, NECRON_DPS) } ?: (0 to 0)
    }

    private const val CAMP_TICKS = 50 * 20
    private const val PORTAL_TICKS = 4 * 20
    private const val PORTAL_TEXT = 36
    private const val EXIT_CORE_TEXT = 16
    private const val MAXOR_MOVE = 85
    private const val CRYSTALS_BACK = 40
    private const val NECRON_DPS = 89
    private const val PAD_LEAD = 41
    private const val PURPLE_AFTER_LIGHTNING = 109
    private const val PAD_TOTAL = 60
    private const val DAMAGEABLE = 500f
    private const val HIT_DEBOUNCE = 30

    private const val WATCHER = "[BOSS] The Watcher: "
    private const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
    private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
    private val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPENING = "The Core entrance is opening!"
    private val NECRON_START = setOf(
        "[BOSS] Necron: You went further than any human before, congratulations.",
        "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
    )

    private var serverTicks = 0
    private var bossAt: Int? = null
    private var bloodSeen = false
    private var portalOpen = false
    private var portalUntil = 0
    private var coreOpen = false
    private var exitCoreUntil = 0
    private var maxorMoveAt: Int? = null
    private var inMaxor = false
    private var crystalsAt: Int? = null
    private var lastHit = Int.MIN_VALUE / 2
    private var maxorHealth = 0f
    private var stormStart: Int? = null
    private var stormDead = false
    private var lightningAt: Int? = null
    private var crushes = 0
    private var purpleStepAt: Int? = null
    private var necronDpsAt: Int? = null

    private fun bossTicksLeft(): Int? {
        if (!enabled || DungeonUtils.inBoss) return null
        val at = bossAt ?: return null
        return (at - serverTicks).takeIf { it > 0 }
    }

    private fun left(at: Int?, total: Int): Int? {
        if (at == null) return null
        return (at - serverTicks).takeIf { it in 1..total }
    }

    private fun checkFrom(t: Int): Int? {
        val start = stormStart ?: return null
        return start + Math.ceilDiv(t - start, StormCrush.CHECK_PERIOD) * StormCrush.CHECK_PERIOD
    }

    private fun purpleReads(): List<Int> {
        val light = lightningAt ?: return emptyList()
        if (crushes > 0 || stormDead) return emptyList()
        val check = checkFrom(light + PURPLE_AFTER_LIGHTNING) ?: return emptyList()
        val reads = listOf(check - PAD_LEAD - StormCrush.CHECK_PERIOD, check - PAD_LEAD)
        if ((purpleStepAt ?: Int.MIN_VALUE) > reads[0]) return emptyList()
        return reads
    }

    private fun padLeft(reads: List<Int>): Int? {
        if (!enabled) return null
        return reads.firstOrNull { it >= serverTicks }?.let { it - serverTicks }
    }

    private fun colour(left: Int, total: Int): String = when {
        left >= total * 0.66f -> "§a"
        left >= total * 0.33f -> "§6"
        else -> "§c"
    }

    private fun time(ticks: Int): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)

    private fun GuiGraphicsExtractor.timer(label: String, left: Int, total: Int, example: Boolean = false): Pair<Int, Int> =
        draw((if (example) "§7$label: " else "") + colour(left, total) + time(left))

    private fun GuiGraphicsExtractor.draw(s: String): Pair<Int, Int> {
        text(s, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(s) to 9
    }

    private fun chat(message: String) {
        when {
            message == WATCHER_DONE -> {
                bloodSeen = true
                portalOpen = true
                bossAt = serverTicks + PORTAL_TICKS
                portalUntil = serverTicks + PORTAL_TEXT
            }
            message.startsWith(WATCHER) && !bloodSeen -> {
                bloodSeen = true
                bossAt = serverTicks + CAMP_TICKS + PORTAL_TICKS
            }
            message == MAXOR_START -> { inMaxor = true; maxorMoveAt = serverTicks + MAXOR_MOVE; maxorHealth = 0f }
            message == STORM_START -> {
                inMaxor = false; crystalsAt = null; stormStart = serverTicks - 1; stormDead = false
                lightningAt = null; crushes = 0; purpleStepAt = null
            }
            message == STORM_DEAD -> stormDead = true
            message in STORM_LIGHTNING -> if (stormStart != null && lightningAt == null) lightningAt = serverTicks
            message in STORM_CRUSHED -> if (stormStart != null) crushes++
            message == GOLDOR_START -> stormStart = null
            message == CORE_OPENING -> coreOpen = true
            message in NECRON_START -> if (necronDpsAt == null) necronDpsAt = serverTicks + NECRON_DPS
        }
    }

    private fun watchMaxor() {
        if (!inMaxor) return
        val level = mc.level ?: return
        val maxor = level.entitiesForRendering().firstOrNull { it is WitherBoss && Witherborn.isBoss(it) } as? WitherBoss ?: return
        val hp = maxor.health
        if (hp >= DAMAGEABLE && maxorHealth < DAMAGEABLE && serverTicks - lastHit >= HIT_DEBOUNCE) {
            lastHit = serverTicks
            crystalsAt = serverTicks + CRYSTALS_BACK
        }
        maxorHealth = hp
    }

    private fun watchCore() {
        if (!coreOpen) return
        val level = mc.level ?: return
        val me = mc.player ?: return
        if (!DeadPlayers.isDead(me.name.string) && !inCore(me.x, me.y, me.z)) return
        for (mate in DungeonUtils.dungeonTeammates) {
            if (DeadPlayers.isDead(mate) || mate.name == me.name.string) continue
            val p = mate.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == mate.name } ?: return
            val at = p.positionCodec.base
            if (!inCore(at.x, at.y, at.z)) return
        }
        coreOpen = false
        exitCoreUntil = serverTicks + EXIT_CORE_TEXT
    }

    private fun inCore(x: Double, y: Double, z: Double) = x >= 39 && x < 71 && y >= 110 && y < 155.5 && z >= 54 && z < 118

    private fun reset() {
        bossAt = null; bloodSeen = false; portalOpen = false; portalUntil = 0; coreOpen = false; exitCoreUntil = 0
        maxorMoveAt = null; inMaxor = false; crystalsAt = null; lastHit = Int.MIN_VALUE / 2; maxorHealth = 0f
        stormStart = null; stormDead = false; necronDpsAt = null
        lightningAt = null; crushes = 0; purpleStepAt = null
    }

    init {
        clearHud.enabled = true; portalHud.enabled = true
        maxorHud.enabled = true; crystalHud.enabled = true
        necronHud.enabled = true; purplePadHud.enabled = true; sectionHud.enabled = true

        on<MessageEvent.Chat> { chat(message) }

        on<TickEvent.Server> {
            serverTicks++
            val at = bossAt ?: return@on
            if (!portalOpen && at - serverTicks <= PORTAL_TICKS) bossAt = null
            else if (at - serverTicks <= 0) bossAt = null
        }

        on<TickEvent.End> { watchMaxor(); watchCore() }

        on<BlockUpdateEvent> {
            if (stormStart == null || updated.block != Blocks.MOVING_PISTON || pos.y !in StormCrush.FLOOR..StormCrush.TOP) return@on
            val purple = StormCrush.PILLARS[0]
            if (pos.x == purple.columnX && pos.z == purple.columnZ) purpleStepAt = serverTicks
        }

        on<LevelEvent.Load> { reset() }
    }
}
