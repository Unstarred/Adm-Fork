package com.engineerclient.features.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.arena.Station
import com.google.gson.JsonParser
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import kotlin.math.abs

object TerminalHitboxes : Module(
    name = "Terminal Hitboxes",
    key = null,
    category = Category.custom("ADM", 860, 10),
    description = "Outlines each P3 terminal's clickable box (both its stands) while terminals run, in F7/M7 and the P3 Sim: by whether it's this section's, and whether you're aiming at it, it's in your reach or it's out of it. Done ones aren't drawn.",
    toggled = true,
) {
    private val aimColor by ColorSetting("Section Aiming", Color(0, 255, 0, 1f), true, desc = "This section's, your crosshair on it: a click opens it.")
    private val readyColor by ColorSetting("Section In Range", Color(0, 140, 0, 1f), true, desc = "This section's and in your reach (part of the box under 3 blocks from your eyes with nothing in front of it), crosshair elsewhere.")
    private val farColor by ColorSetting("Section Out Of Range", Color(255, 255, 0, 1f), true, desc = "This section's but out of your reach: a click there never gets sent.")
    private val idleAimColor by ColorSetting("Other Section Aiming", Color(255, 0, 0, 1f), true, desc = "Another section's, your crosshair on it: a click gets \"This Terminal doesn't seem to be responsive at the moment.\".")
    private val idleNearColor by ColorSetting("Other Section In Range", Color(140, 0, 0, 1f), true, desc = "Another section's, in your reach, crosshair elsewhere.")
    private val idleColor by ColorSetting("Other Section Out Of Range", Color(255, 128, 0, 1f), true, desc = "Another section's, out of your reach.")
    private val fill by BooleanSetting("Terminal Fill", false, desc = "Fills the boxes too, faintly.")
    private val walls by BooleanSetting("Terminals Through Walls", true, desc = "Drawn over blocks in the way.")
    private var adopted by BooleanSetting("Adopted Term Info Settings", false, desc = "").hide()

    private val DOOR_PROBES = listOf(BlockPos(100, 115, 122), BlockPos(18, 115, 132), BlockPos(8, 115, 50))

    private val TERMINALS by lazy { Station.all().filter { it.kind == Station.Kind.TERMINAL } }
    private val doneSeen = BooleanArray(17)
    @Volatile private var lastClicked = -1
    @Volatile private var openedTerm = -1
    private val TERM_TITLE = Regex("^(Click in order!|Select all the .+ items!|What starts with: '.+'\\?|Correct all the panes!|Change all to same color!|Click the button on time!)$")
    private val OWN_TASK = Regex("^(\\w{1,16}) activated a terminal! \\(\\d+/\\d+\\)$")

    fun reset() {
        doneSeen.fill(false); lastClicked = -1; openedTerm = -1
    }

    fun opened(title: String) {
        if (!TERM_TITLE.matches(title)) return
        mc.execute { openedTerm = lastClicked }
    }

    fun chat(msg: String) {
        val m = OWN_TASK.matchEntire(msg) ?: return
        if (m.groupValues[1] == mc.player?.name?.string && openedTerm >= 0) doneSeen[openedTerm] = true
    }

    private fun doorSection(level: Level): Int {
        var s = 1
        for (p in DOOR_PROBES) {
            if (!level.isLoaded(p) || level.getBlockState(p).`is`(Blocks.BARRIER)) break
            s++
        }
        return s
    }

    private fun terminalOf(e: Entity): Int {
        if (e !is ArmorStand) return -1
        return TERMINALS.indexOfFirst { abs(e.x - it.at.x) < 0.01 && abs(e.z - it.at.z) < 0.01 && e.y > it.at.y - 0.5 && e.y < it.at.y + 0.1 }
    }

    private class Scan(val boxes: Array<AABB>, val done: BooleanArray, val live: Int)

    private var lastScan: Scan? = null

    private fun scan(): Scan? {
        val sim = P3Sim.localSim
        if (!P3Sim.inSim && !(DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 7)) return null
        val level = mc.level ?: return null
        val simStations = if (sim) (Fight.phase as? GoldorPhase)?.stations else null
        val stands = TERMINALS.mapIndexed { i, t ->
            level.getEntitiesOfClass(ArmorStand::class.java,
                AABB(t.at.x - 0.1, t.at.y - 0.5, t.at.z - 0.1, t.at.x + 0.1, t.at.y + 0.1, t.at.z + 0.1)) { terminalOf(it) == i }
        }
        val done = BooleanArray(TERMINALS.size) { i ->
            val t = TERMINALS[i]
            if (sim) simStations?.firstOrNull { it.kind == t.kind && it.section == t.section && it.label == t.label }?.done == true
            else {
                val s = stands[i]
                if (s.size >= 2 && s.maxOf { it.y } - s.minOf { it.y } < 0.1 || s.any { it.customName?.string?.contains("Terminal Active") == true }) doneSeen[i] = true
                doneSeen[i]
            }
        }
        val live = if (sim) TermInfo.liveSection() else {
            val chat = TermInfo.liveSection()
            val doneBound = TERMINALS.indices.filter { done[it] }.maxOfOrNull { TERMINALS[it].section } ?: 0
            if (TermInfo.coreOpened || chat == 0 && doneBound == 0) 0 else maxOf(chat, doneBound, doorSection(level))
        }
        if (live == 0) return null
        val boxes = Array(TERMINALS.size) { i ->
            val at = TERMINALS[i].at
            stands[i].map { it.boundingBox }.reduceOrNull { a, b -> a.minmax(b) }
                ?: AABB(at.x - 0.25, at.y - 0.355, at.z - 0.25, at.x + 0.25, at.y + 1.995, at.z + 0.25)
        }
        return Scan(boxes, done, live)
    }

    private fun RenderExtractEvent.drawTerminals() {
        val scan = lastScan ?: return
        val level = mc.level ?: return
        val player = mc.player ?: return
        val live = scan.live
        val eye = player.getEyePosition(mc.deltaTracker.getGameTimeDeltaPartialTick(false))
        val reach = player.entityInteractionRange()
        val aimedAt = (mc.hitResult as? EntityHitResult)?.entity?.let { terminalOf(it) } ?: -1
        TERMINALS.forEachIndexed { i, t ->
            if (scan.done[i] || t.section < live) return@forEachIndexed
            val box = scan.boxes[i]
            val aimed = aimedAt == i
            val near = aimed || inReach(level, player, eye, box, reach)
            val color = if (t.section == live) { if (aimed) aimColor else if (near) readyColor else farColor }
                else { if (aimed) idleAimColor else if (near) idleNearColor else idleColor }
            if (fill) drawFilledBox(box, color.withAlpha(color.alphaFloat * 0.2f), depth = !walls)
            drawWireFrameBox(box, color, depth = !walls)
        }
    }

    private fun inReach(level: Level, player: Player, eye: Vec3, box: AABB, reach: Double): Boolean {
        if (box.contains(eye)) return true
        val r2 = reach * reach
        val near = Vec3(eye.x.coerceIn(box.minX, box.maxX), eye.y.coerceIn(box.minY, box.maxY), eye.z.coerceIn(box.minZ, box.maxZ))
        if (near.distanceToSqr(eye) >= r2) return false
        fun clear(p: Vec3): Boolean {
            val hit = level.clip(ClipContext(eye, p, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            return hit.type == HitResult.Type.MISS || hit.location.distanceToSqr(eye) >= p.distanceToSqr(eye) - 1e-6
        }
        if (clear(near)) return true
        val e = 1e-3
        for (i in 0..2) for (j in 0..8) for (k in 0..2) {
            val p = Vec3(
                box.minX + e + (box.maxX - box.minX - 2 * e) * i / 2, box.minY + e + (box.maxY - box.minY - 2 * e) * j / 8, box.minZ + e + (box.maxZ - box.minZ - 2 * e) * k / 2)
            if (p.distanceToSqr(eye) < r2 && clear(p)) return true
        }
        return false
    }

    fun adoptTermInfoSettings() {
        if (adopted) return
        adopted = true
        val file = mc.gameDirectory.toPath().resolve("config/odin/addons/adm.json")
        val saved = if (Files.exists(file)) JsonParser.parseString(Files.readString(file)) else null
        val old = saved?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull { it.asJsonObject.get("name")?.asString == "Term Info" }?.asJsonObject?.getAsJsonObject("settings")
        if (old != null) {
            for ((name, element) in old.entrySet()) {
                if (!element.isJsonPrimitive) continue
                runCatching {
                    when (val setting = settings[name]) {
                        is ColorSetting -> setting.value = Color(element.asString.removePrefix("#"))
                        is BooleanSetting -> if (name != "Adopted Term Info Settings") setting.value = element.asBoolean
                        else -> {}
                    }
                }
            }
            if (old.get("Terminal Hitboxes")?.asBoolean == false && enabled) toggle()
        }
    }

    init {
        on<TickEvent.End> {
            lastScan = null
            EngineerClient.safely("terminal hitboxes scan") { lastScan = scan() }
        }
        on<RenderExtractEvent> { EngineerClient.safely("terminal hitboxes") { drawTerminals() } }
        UseEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide) terminalOf(entity).let { if (it >= 0) lastClicked = it }
            InteractionResult.PASS
        }
        AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide) terminalOf(entity).let { if (it >= 0) lastClicked = it }
            InteractionResult.PASS
        }
    }
}
