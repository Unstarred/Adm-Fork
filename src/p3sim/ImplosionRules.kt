package com.engineerclient.p3sim

import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random

object ImplosionRules {
    const val REACH = 6.0
    const val REACH_UP = 7.0
    const val REACH_DOWN = 6.0

    class Profile(val floor: String, val median: Double, val byKind: Boolean = false) {
        fun roll(random: Random, multiplier: Double = 1.0): Double = median * multiplier * (0.9 + random.nextDouble() * 0.25)

        fun castBase(random: Random, multiplier: Double = 1.0): Double =
            if (byKind) median * multiplier * (0.7 + random.nextDouble() * 0.6) else roll(random, multiplier)

        fun perMob(kind: String, miniboss: Boolean, maxHp: Double, hpScale: Double, base: Double, random: Random): Double {
            if (!byKind) return base
            val raw = base * factor(kind) * (0.97 + random.nextDouble() * 0.06)
            if (!miniboss && kind != "shadow_assassin") return raw
            val unscaled = if (hpScale > 0.0) maxHp / hpScale else maxHp
            return minOf(raw, 0.75 * unscaled * (0.95 + random.nextDouble() * 0.10))
        }
    }

    private val F7 = Profile("F7", 36_000_000.0, byKind = true)
    private val M7 = Profile("M7", 56_600_000.0)

    private val FACTORS = mapOf(
        "super_tank_zombie" to 0.06, "angry_archaeologist" to 0.015, "lost_adventurer" to 0.09, "frozen_adventurer" to 0.18,
        "withermancer" to 0.37, "crypt_undead" to 0.54, "mimic" to 0.62, "prince" to 0.6, "crypt_souleater" to 1.14,
        "wither_miner" to 1.4, "skeleton_master" to 1.9, "crypt_lurker" to 2.6, "zombie_knight" to 2.8, "zombie_soldier" to 1.45,
        "sniper" to 1.3,
    )

    fun factor(kind: String): Double = FACTORS[kind] ?: 1.0

    fun profile(floor: String): Profile = if (floor.startsWith("M")) M7 else F7

    fun castsNeeded(hp: Double, perCast: Double): Int = if (hp <= 0.0) 0 else if (perCast <= 0.0) Int.MAX_VALUE else ceil(hp / perCast).toInt()

    class Hitbox(val x: Double, val y: Double, val z: Double, val width: Double, val height: Double) {
        val minX get() = x - width / 2; val maxX get() = x + width / 2
        val minZ get() = z - width / 2; val maxZ get() = z + width / 2
        val minY get() = y; val maxY get() = y + height
    }

    fun size(kind: String): Pair<Double, Double> = when {
        kind == "fels" -> 0.6 to 2.9
        kind.startsWith("withermancer") || kind.contains("wither_skeleton") -> 0.7 to 2.4
        kind.contains("skeleton") || kind == "super_archer" || kind == "sniper" -> 0.6 to 1.99
        else -> 0.6 to 1.95
    }

    fun castBox(ex: Double, ey: Double, ez: Double): DoubleArray =
        doubleArrayOf(ex - REACH, ey - REACH_DOWN, ez - REACH, ex + REACH, ey + REACH_UP, ez + REACH)

    fun touches(h: Hitbox, box: DoubleArray): Boolean =
        h.maxX >= box[0] && h.minX <= box[3] && h.maxY >= box[1] && h.minY <= box[4] && h.maxZ >= box[2] && h.minZ <= box[5]

    class Candidate(val id: UUID, val hitbox: Hitbox, val alive: Boolean, val untargetable: Boolean)

    fun select(box: DoubleArray, mobs: Collection<Candidate>): List<UUID> =
        mobs.filter { it.alive && !it.untargetable && touches(it.hitbox, box) }.map { it.id }

    fun line(count: Int, total: Double): String {
        val shown = "%,.1f".format(Locale.ROOT, total).removeSuffix(".0")
        return "§7Your Implosion hit §r§c$count §r§7${if (count == 1) "enemy" else "enemies"} for §r§c$shown §r§7damage."
    }
}
