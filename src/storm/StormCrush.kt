package com.engineerclient.storm

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object StormCrush {

    const val CHECK_PERIOD = 20

    const val HEAD = 3.0

    const val ARMED_TICKS = 60

    const val ZONE = 6
    const val PILLAR = 7

    data class Pillar(val name: String, val colour: String, val minX: Int, val minZ: Int) {
        val columnX get() = minX + 3
        val columnZ get() = minZ + 3
    }

    val PILLARS = listOf(
        Pillar("Purple", "§5", 97, 62),
        Pillar("Yellow", "§e", 43, 62),
        Pillar("Green", "§a", 43, 38),
        Pillar("Red", "§c", 97, 38),
    )

    const val TOP = 204
    const val FLOOR = 169

    fun inset(p: Pillar, x: Double, z: Double): Double =
        min(min(x - p.minX, p.minX + ZONE - x), min(z - p.minZ, p.minZ + ZONE - z))

    fun distance(p: Pillar, x: Double, z: Double): Double {
        val dx = max(max(p.minX - x, 0.0), x - (p.minX + ZONE))
        val dz = max(max(p.minZ - z, 0.0), z - (p.minZ + ZONE))
        return hypot(dx, dz)
    }

    fun nearest(x: Double, z: Double): Pillar = PILLARS.minBy { distance(it, x, z) }

    fun headAbove(y: Double, bottom: Int): Double = y + HEAD - bottom

    fun armed(lastStep: Int?, now: Int): Boolean = lastStep != null && now - lastStep in 0..ARMED_TICKS

    data class Verdict(val pillar: Pillar, val inset: Double, val head: Double?) {
        val inside: Boolean get() = inset >= 0 && head != null && head >= 0
    }

    fun judge(p: Pillar, x: Double, y: Double, z: Double, bottom: Int?): Verdict =
        Verdict(p, inset(p, x, z), bottom?.let { headAbove(y, it) })

    fun isCheck(ticks: Int): Boolean = ticks > 0 && ticks % CHECK_PERIOD == 0
}
