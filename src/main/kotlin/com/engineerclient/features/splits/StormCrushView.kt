package com.engineerclient.features.splits

import com.engineerclient.EngineerClient.mc
import com.engineerclient.features.misc.Witherborn
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.abs

class StormCrushView {

    private val arena = AABB(0.0, 150.0, 0.0, 140.0, 215.0, 145.0)

    private val lastStep = HashMap<StormCrush.Pillar, Int>()

    private class Snapshot(val box: AABB, val verdict: StormCrush.Verdict, val bottom: Int?, val armed: Boolean, val at: Long)
    private val snapshots = ArrayList<Snapshot>()

    fun reset() { lastStep.clear(); snapshots.clear() }

    fun inArena(): Boolean = mc.player?.let { arena.contains(it.position()) } == true

    fun onStep(pillar: StormCrush.Pillar, tick: Int) { lastStep[pillar] = tick }

    fun check(now: Int): Pair<StormCrush.Verdict, Boolean>? {
        val level = mc.level ?: return null
        val storm = level.entitiesForRendering().filterIsInstance<WitherBoss>()
            .filter { it.isAlive && arena.contains(it.position()) && Witherborn.isBoss(it) }
            .minByOrNull { it.distanceToSqr(70.0, 180.0, 53.0) } ?: return null
        val at = storm.positionCodec.base.takeIf { it != Vec3.ZERO } ?: storm.position()
        val pillar = StormCrush.nearest(at.x, at.z)
        if (StormCrush.distance(pillar, at.x, at.z) > RANGE) return null
        val bottom = bottomOf(level, pillar)
        val verdict = StormCrush.judge(pillar, at.x, at.y, at.z, bottom)
        val armed = StormCrush.armed(lastStep[pillar], now)
        val box = AABB(at.x, at.y, at.z, at.x + 1, at.y + StormCrush.HEAD, at.z + 1)
        snapshots += Snapshot(box, verdict, bottom, armed, System.currentTimeMillis())
        return verdict to armed
    }

    fun RenderExtractEvent.render(seconds: Int, margins: Boolean, outline: Boolean, insideColor: Color, outsideColor: Color) {
        if (snapshots.isEmpty()) return
        val now = System.currentTimeMillis()
        val life = seconds * 1000L
        snapshots.removeIf { now - it.at > life }
        if (!inArena()) return
        for (s in snapshots) {
            val fade = ((life - (now - s.at)) / 1000f).coerceIn(0f, 1f)
            val colour = if (s.verdict.inside) insideColor else outsideColor
            drawFilledBox(s.box, colour.withAlpha(colour.alphaFloat * fade), depth = false)
            drawWireFrameBox(s.box, colour.withAlpha(fade), depth = false)
            val p = s.verdict.pillar
            if (outline && s.bottom != null) {
                val y = s.bottom.toDouble()
                val square = AABB(p.minX.toDouble(), y, p.minZ.toDouble(), (p.minX + StormCrush.PILLAR).toDouble(), y + 0.02, (p.minZ + StormCrush.PILLAR).toDouble())
                drawWireFrameBox(square, colour.withAlpha(0.6f * fade), depth = false)
            }
            if (margins) drawText(label(s), Vec3((s.box.minX + s.box.maxX) / 2, s.box.maxY + 0.5, (s.box.minZ + s.box.maxZ) / 2), 1f, false)
        }
    }

    private fun bottomOf(level: ClientLevel, p: StormCrush.Pillar): Int? {
        val pos = BlockPos.MutableBlockPos(p.columnX, StormCrush.TOP, p.columnZ)
        if (level.getBlockState(pos).isAir) return null
        var y = StormCrush.TOP
        while (y > StormCrush.FLOOR && !level.getBlockState(pos.setY(y - 1)).isAir) y--
        return y
    }

    private fun label(s: Snapshot): String {
        val v = s.verdict
        val side = (if (v.inset >= 0) "§a" else "§c") + fmt(abs(v.inset)) + if (v.inset >= 0) " in" else " out"
        val head = v.head?.let { (if (it >= 0) "§a" else "§c") + "head " + (if (it >= 0) "+" else "-") + fmt(abs(it)) } ?: "§7no pillar"
        return v.pillar.colour + v.pillar.name + " §7· " + side + " §7· " + head + if (s.armed) "" else " §8(not lowered)"
    }

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.2f", d)

    companion object {
        const val RANGE = 5.0
    }
}
