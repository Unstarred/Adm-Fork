package com.engineerclient.p3sim

import com.engineerclient.dungeonpack.pack.runstate.LagTier
import com.google.gson.JsonObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

data class LagEvent(val gapMs: Double, val missedTicks: Int, val recoveredTicks: Int) {
    val lostTicks: Int get() = missedTicks - recoveredTicks
}

class LagParams(
    val exceedPerHour: List<Pair<Double, Double>>,
    val recovery: List<Pair<Double, Double>>,
    val within5sFrac: Double,
    val randomWithin5s: Double,
    val maxGapMs: Double = 12_000.0,
) {
    fun recovered(missed: Double): Double {
        if (missed <= 0) return 0.0
        val pts = recovery
        if (pts.isEmpty()) return 0.0
        if (missed <= pts.first().first) return pts.first().second * missed / pts.first().first.coerceAtLeast(1e-9)
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            if (missed <= x1) return (y0 + (y1 - y0) * (missed - x0) / (x1 - x0).coerceAtLeast(1e-9)).coerceAtMost(missed)
        }
        return pts.last().second.coerceAtMost(missed)
    }

    companion object {
        val DEFAULTS = LagParams(
            exceedPerHour = listOf(100.0 to 1230.25, 250.0 to 111.24, 500.0 to 36.09, 1000.0 to 23.1, 2000.0 to 16.33, 5000.0 to 0.72),
            recovery = listOf(5.6 to 0.0, 12.2 to 6.4, 38.6 to 9.9, 78.2 to 8.6),
            within5sFrac = 0.313, randomWithin5s = 0.167,
        )

        fun fromPack(lag: JsonObject?): LagParams {
            val f = lag?.get("freezes")?.takeIf { it.isJsonObject }?.asJsonObject ?: return DEFAULTS
            val exceed = f.get("exceedPerHour")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val g = o.num("gapMs") ?: return@mapNotNull null; val h = o.num("perHour") ?: return@mapNotNull null
                g to h
            }?.sortedBy { it.first }?.takeIf { it.size >= 2 } ?: DEFAULTS.exceedPerHour
            val by = f.get("catchup")?.takeIf { it.isJsonObject }?.asJsonObject?.get("byGap")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val m = o.num("missedTicks") ?: return@mapNotNull null; val r = o.num("recoveredWithin5s") ?: return@mapNotNull null
                m to r.coerceAtLeast(0.0)
            }?.sortedBy { it.first }?.takeIf { it.isNotEmpty() } ?: DEFAULTS.recovery
            val c = f.get("clustering")?.takeIf { it.isJsonObject }?.asJsonObject
            return LagParams(exceed, by, c?.num("within5sFrac") ?: DEFAULTS.within5sFrac, c?.num("randomExpected") ?: DEFAULTS.randomWithin5s)
        }

        private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    }
}

class LagGenerator(val params: LagParams, val lossPerMinute: Double, private val random: Random = Random.Default) {
    private class Bin(val lo: Double, val hi: Double, val weight: Double)

    private val bins: List<Bin> = run {
        val e = params.exceedPerHour
        List(e.size) { i ->
            val hi = if (i + 1 < e.size) e[i + 1].first else params.maxGapMs
            Bin(e[i].first, hi, (e[i].second - (if (i + 1 < e.size) e[i + 1].second else 0.0)).coerceAtLeast(0.0))
        }
    }
    private val small = bins.filter { it.lo < CLUSTER_FROM_MS }
    private val big = bins.filter { it.lo >= CLUSTER_FROM_MS }
    private val totalPerHour = bins.sumOf { it.weight }

    private val meanLost: Double
    val meanMissed: Double
    init {
        var lost = 0.0; var missed = 0.0
        for (b in bins) {
            val n = 400
            var l = 0.0; var m = 0.0
            for (k in 0 until n) {
                val gap = exp(ln(b.lo) + (ln(b.hi) - ln(b.lo)) * (k + 0.5) / n)
                val miss = missedFor(gap)
                l += miss - Math.round(params.recovered(miss.toDouble())).coerceIn(0, miss.toLong()); m += miss
            }
            lost += b.weight * l / n; missed += b.weight * m / n
        }
        meanLost = if (totalPerHour > 0) lost / totalPerHour else 0.0
        meanMissed = if (totalPerHour > 0) missed / totalPerHour else 0.0
    }

    val naturalLossPerMinute: Double = totalPerHour * meanLost / 60.0

    val freezesPerMinute: Double = if (meanLost > 0) lossPerMinute / meanLost else 0.0

    private val bigShare = if (totalPerHour > 0) big.sumOf { it.weight } / totalPerHour else 0.0

    private val follow: Double = run {
        val perMinute = freezesPerMinute * bigShare
        val r = 1.0 - exp(-perMinute * 5.0 / 60.0)
        ((params.within5sFrac - r) / (1.0 - r)).coerceIn(0.0, 0.9)
    }
    private val pSmall: Double = freezesPerMinute * (1.0 - bigShare) / (60.0 * TICKS_PER_SECOND)
    private val pBig: Double = freezesPerMinute * bigShare * (1.0 - follow) / (60.0 * TICKS_PER_SECOND)

    private var chainIn = -1
    var ticks = 0L
        private set
    var lost = 0L
        private set
    var events = 0
        private set

    fun nextTick(): LagEvent? {
        ticks++
        var ev: LagEvent? = null
        if (chainIn > 0) chainIn--
        else if (chainIn == 0) { chainIn = -1; ev = draw(big) }
        if (ev == null && pBig > 0 && random.nextDouble() < pBig) ev = draw(big)
        if (ev != null) {
            if (chainIn < 0 && random.nextDouble() < follow) chainIn = (1 + random.nextInt(5 * TICKS_PER_SECOND) - ev.missedTicks).coerceAtLeast(0)
            return count(ev)
        }
        if (pSmall > 0 && random.nextDouble() < pSmall) return count(draw(small))
        return null
    }

    private fun count(ev: LagEvent): LagEvent { events++; lost += ev.lostTicks; return ev }

    private fun draw(from: List<Bin>): LagEvent {
        val pool = from.ifEmpty { bins }
        var u = random.nextDouble() * pool.sumOf { it.weight }
        var bin = pool.last()
        for (b in pool) { if (u < b.weight) { bin = b; break }; u -= b.weight }
        val gap = exp(ln(bin.lo) + (ln(bin.hi) - ln(bin.lo)) * random.nextDouble())
        val missed = missedFor(gap)
        return LagEvent(gap, missed, Math.round(params.recovered(missed.toDouble())).toInt().coerceIn(0, missed))
    }

    companion object {
        const val TICK_MS = 50.0
        const val TICKS_PER_SECOND = 20
        const val CLUSTER_FROM_MS = 250.0

        fun missedFor(gapMs: Double): Int = Math.round(gapMs / TICK_MS - 1).toInt().coerceAtLeast(1)
    }
}

object LagPresets {
    fun lossPerMinute(preset: LagPreset, tiers: Map<String, LagTier>?): Double {
        if (preset == LagPreset.OFF) return 0.0
        val t = tiers?.get("F7") ?: return preset.lossPerMinute
        return when (preset) {
            LagPreset.GOOD -> t.good
            LagPreset.TYPICAL -> t.typical
            LagPreset.BAD -> t.bad
            LagPreset.HIGH_SLOT -> t.slotOver200 ?: preset.lossPerMinute
            LagPreset.OFF -> 0.0
        }
    }
}

object LagBurst {
    const val DEFAULT_RATE = 2.0

    fun sleepMs(sinceLastMs: Double, rate: Double): Double = (LagGenerator.TICK_MS / rate.coerceAtLeast(1.0) - sinceLastMs).coerceAtLeast(0.0)

    fun ticksFor(recovered: Int, rate: Double): Int = if (rate <= 1.0001) recovered else Math.ceil(recovered * rate / (rate - 1.0)).toInt()
}

enum class LagPreset(val label: String, val lossPerMinute: Double) {
    OFF("Off", 0.0), GOOD("Good", 14.5), TYPICAL("Typical", 30.3), BAD("Bad", 56.6),
    HIGH_SLOT("High slot", 54.6);
    override fun toString() = label
}
