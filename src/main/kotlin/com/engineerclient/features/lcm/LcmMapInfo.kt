package com.engineerclient.features.lcm

import com.engineerclient.EngineerClient
import com.engineerclient.core.OdinCompat
import com.engineerclient.features.mapprediction.FloorRules
import com.engineerclient.util.setting
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.features.impl.dungeon.MapInfo
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.dungeon.PuzzleStatus
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil
import kotlin.math.floor

object LcmMapInfo {

    private val style = BooleanSetting("LCM Style", false,
        desc = "Draws the Full Hud like LCM's map (dtMap) draws its score, with dtMap's score calculator: secrets found, missing and total, the unknown secrets (in rooms nobody in the party has walked into yet, teammates' from Odin's map websocket), the score, then what's still missing from the bonus (deaths, Mimic, Prince, bats, crypts). Added by ADM.")
        .withDependency { MapInfo.setting<HUDSetting>("Full Hud")?.value?.enabled ?: true }

    private var failed = false

    fun install() {
        MapInfo.registerSetting(style)
    }

    @JvmStatic
    fun active(): Boolean = style.value

    @JvmStatic
    fun render(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        if (!example && (!DungeonUtils.inDungeons || (disableInBoss() && DungeonUtils.inBoss))) return 0 to 0
        return try {
            if (example) centred(g, listOf("§b10§7-§e49§7-§c55     §a300", "§7D: §c1  §7M: §c✖  §7P: §c✖  §7B: §c✖  §c0§7/§a5"), 2)
            else centred(g, lines(), 0)
        } catch (t: Throwable) {
            if (!failed) {
                failed = true
                EngineerClient.logger.error("[ec] dtMap score failed, Odin draws its Full Hud", t)
            }
            null
        }
    }

    private fun disableInBoss(): Boolean = MapInfo.setting<BooleanSetting>("Disable in boss")?.value ?: true

    private fun lines(): List<String> {
        val score = calculateScore()
        val totalSecrets = calculateTotalSecrets()
        val secrets = "§b${DungeonUtils.secretCount}§7-§e${calculateMinimumSecrets()}§7-§c$totalSecrets"
        val scoreText = "§${if (score < 270) "c" else if (score < 300) "e" else "a"}$score"
        val unknown = if (totalSecrets != 0) {
            val unknown = (totalSecrets - knownRoomSecrets()).coerceAtLeast(0)
            "${if (unknown >= calculateNeededSecrets()) "§a" else "§6"}$unknown   "
        } else ""
        val line1 = "$secrets   $unknown$scoreText"
        val line2 = buildString {
            if (DungeonUtils.deathCount > 0) append("§7D: §c${DungeonUtils.deathCount}   ")
            if (!DungeonUtils.mimicKilled && floorNumber() >= 6) append("§7M: §c✖   ")
            if (!DungeonUtils.princeKilled && princeLeft()) append("§7P: §c✖   ")
            if (OdinCompat.batsKilled() < batsPossible()) append("§7B: §c${OdinCompat.batsKilled().coerceAtMost(5)}   ")
            if (DungeonUtils.cryptCount < 5) append("§c${DungeonUtils.cryptCount}§7/§a5")
        }.trim()
        val rows = LcmStyle.mapSize(false).second
        val mapWidth = rows * 16 + (rows - 1) * 4 + 10
        return if (mc.font.width("$line1   $line2") < mapWidth) listOf("$secrets   ${if (line2.isNotEmpty()) "$line2   " else ""}$unknown$scoreText")
            else listOf(line1, line2)
    }

    private fun knownRoomSecrets(): Int = DungeonScan.rooms.sumOf { if (LcmStyle.knownName(it) != null) it.data?.maxSecrets ?: 0 else 0 }

    private fun batsPossible(): Int = DungeonUtils.dungeonTeammates.size.takeIf { it > 0 }?.coerceAtMost(5) ?: 5

    private fun centred(g: GuiGraphicsExtractor, lines: List<String>, gap: Int): Pair<Int, Int> {
        val width = lines.maxOf { mc.font.width(it) }
        for ((i, line) in lines.withIndex()) g.centeredText(mc.font, line, width / 2, i * (mc.font.lineHeight + gap), -1)
        return width to lines.size * (mc.font.lineHeight + gap) - gap
    }

    private fun floorNumber(): Int = DungeonUtils.floor?.floorNumber ?: 0

    private fun secretFactor(): Float = when (DungeonUtils.floor) {
        Floor.E, Floor.F1 -> 0.3f
        Floor.F2 -> 0.4f
        Floor.F3 -> 0.5f
        Floor.F4 -> 0.6f
        Floor.F5 -> 0.7f
        Floor.F6 -> 0.85f
        else -> 1f
    }

    private fun calculateTotalSecrets(): Int {
        val found = DungeonUtils.secretCount; val percent = DungeonUtils.secretPercentage
        return if (found == 0 || percent == 0f) 0 else floor(100 / percent * found + 0.5).toInt()
    }

    private fun calculateTotalRooms(): Int {
        val completed = DungeonUtils.completedRoomCount; val percent = DungeonUtils.percentCleared
        return if (completed == 0 || percent == 0) 0 else floor((completed / (percent * 0.01).toFloat()) + 0.4).toInt()
    }

    private fun deathPenalty(): Int = (DungeonUtils.deathCount * 2 - 1).coerceAtLeast(0)

    private fun calculateScore(): Int {
        val totalRooms = calculateTotalRooms()
        val completed = DungeonUtils.completedRoomCount + (if (!DungeonUtils.bloodDone) 1 else 0) + if (!DungeonUtils.inBoss) 1 else 0
        val total = if (totalRooms != 0) totalRooms else 36
        val totalSecrets = calculateTotalSecrets()

        val secretScore = if (totalSecrets > 0) {
            floor(DungeonUtils.secretCount.toDouble() / (totalSecrets.toDouble() * secretFactor()) * 40.0).toInt().coerceIn(0, 40)
        } else 0
        val exploration = secretScore + floor(completed.toFloat() / total * 60f).coerceIn(0f, 60f).toInt()

        val skillRooms = floor(completed.toFloat() / total * 80f).coerceIn(0f, 80f).toInt()
        val puzzlePenalty = (DungeonUtils.puzzleCount - DungeonUtils.puzzles.count { it.status == PuzzleStatus.Completed }) * 10

        return exploration + (20 + skillRooms - puzzlePenalty - deathPenalty()).coerceIn(20, 100) + 100 + calculateBonusScoreNoPaul() + calculatePaulScore()
    }

    private fun calculateBonusScoreNoPaul(): Int =
        (if (DungeonUtils.mimicKilled) 2 else 0) + (if (DungeonUtils.princeKilled) 1 else 0) + OdinCompat.batsKilled().coerceAtMost(5) + DungeonUtils.cryptCount.coerceAtMost(5)

    private fun calculatePaulScore(): Int = if (DungeonUtils.isPaul) 10 else 0

    private fun calculateNeededSecrets(): Int =
        ceil(calculateTotalSecrets() * secretFactor() * (40 - calculateBonusScoreNoPaul() + deathPenalty()) / 40f).toInt().coerceAtLeast(0)

    private fun calculateMinimumSecrets(): Int = (calculateNeededSecrets() - DungeonUtils.secretCount).coerceAtLeast(0)

    private fun princeLeft(): Boolean {
        val rooms = DungeonScan.rooms
        if (rooms.any { LcmStyle.opened(it) && it.name in LcmStyle.princeRooms }) return true
        val rules = FloorRules.of(floorNumber()) ?: return true
        for (z in 0 until rules.h) for (x in 0 until rules.w) {
            val room = DungeonScan.tiles.getOrNull(z * 6 + x)?.room ?: return true
            if (room.type != RoomType.BLOOD && !LcmStyle.opened(room)) return true
        }
        return false
    }
}
