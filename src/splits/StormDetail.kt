package com.engineerclient.splits

import com.engineerclient.storm.StormCrush
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

class StormDetail {

    class Moment(val tick: Int, val text: String)

    private val moments = mutableListOf<Moment>()

    var start: Int? = null; private set
    private var happy: Int? = null
    private var strikeShown = false
    private var lightning: Int? = null
    private var enraged: Int? = null
    private var leftSpot = false
    private var reachedYellow = false
    private var crushes = 0
    private var m7 = false
    private var ended = false
    private var dead = false

    private var last: Triple<Double, Double, Double>? = null

    private val stepStart = HashMap<StormCrush.Pillar, Int>()
    private val reads = HashMap<StormCrush.Pillar, Int>()

    fun reset() {
        moments.clear(); start = null; happy = null; strikeShown = false; lightning = null; enraged = null
        leftSpot = false; reachedYellow = false; crushes = 0; ended = false; dead = false; crushAt = null; last = null
        stepStart.clear(); reads.clear()
    }

    val active get() = start != null && !ended

    val checking get() = active && lightning != null && !dead

    fun phase(tick: Int): Int? = start?.let { tick - it }

    fun moments(): List<Moment> = moments.sortedBy { it.tick }

    fun onChat(msg: String, tick: Int, isM7: Boolean) {
        if (msg == STORM_START) { reset(); start = tick - 1; m7 = isM7; return }
        val t = phase(tick) ?: return
        if (ended) return
        when (msg) {
            HAPPY -> if (happy == null) happy = tick
            in LIGHTNING -> if (lightning == null) {
                lightning = t
                add(t, "§eLightning line")
            }
            in CRUSHED -> onCrush(t)
            ENRAGED -> if (enraged == null) { enraged = t; add(t, "§bBroke free §8(pinned " + secs(t - (crushAt ?: t)) + ")") }
            STORM_DEAD -> { dead = true; add(t, "§aDead") }
            GOLDOR_START -> ended = true
        }
    }

    private var crushAt: Int? = null

    private fun onCrush(t: Int) {
        crushes++
        crushAt = t
        val pillar = last?.let { (x, _, z) -> StormCrush.nearest(x, z) }
        val check = Math.floorDiv(t + CHECK_SLACK, StormCrush.CHECK_PERIOD) * StormCrush.CHECK_PERIOD
        val earliest = when (crushes) {
            1 -> lightning?.let { firstCheck(it + PURPLE_AFTER_LIGHTNING) }
            2 -> enraged?.let { firstCheck(it + if (m7) YELLOW_AFTER_ENRAGE_M7 else YELLOW_AFTER_ENRAGE) }
            else -> null
        }
        val timing = earliest?.let { e ->
            val late = (check - e) / StormCrush.CHECK_PERIOD
            when {
                late <= 0 -> " §a(first check)"
                late == 1 -> " §e(1 check late)"
                else -> " §c($late checks late)"
            }
        }.orEmpty()
        val by = pillar?.let { " by " + it.colour + it.name } ?: ""
        add(t, "§6Crushed$by §8t$check$timing")
    }

    fun onServerTick(tick: Int) {
        val t = phase(tick) ?: return
        if (ended || strikeShown) return
        val strike = happy?.let { it - start!! + STRIKE_AFTER_HAPPY }
        val at = strike ?: LIGHTNING_STRIKE
        if (t >= at) {
            strikeShown = true
            add(at, "§eGiga Lightning" + if (strike == null) " §8(~t$LIGHTNING_STRIKE)" else "")
        }
    }

    fun onPosition(tick: Int, x: Double, y: Double, z: Double) {
        val t = phase(tick) ?: return
        last = Triple(x, y, z)
        if (ended) return
        if (!leftSpot && lightning != null && hypot(x - SPOT_X, z - SPOT_Z) > 0.5) {
            leftSpot = true
            add(t, "§bLeft his spot §8(" + secs(t - lightning!!) + " after the line)")
        }
        if (!reachedYellow && enraged != null && hypot(x - YELLOW_X, z - YELLOW_Z) <= YELLOW_REACHED) {
            reachedYellow = true
            add(t, "§eReached Yellow")
        }
    }

    fun onPillarStep(pillar: StormCrush.Pillar, tick: Int) {
        val t = phase(tick) ?: return
        if (ended) return
        val prev = stepStart[pillar]
        if (prev != null && tick - prev < STEP_SPAN) return
        stepStart[pillar] = tick
        val n = (reads[pillar] ?: 0) + 1
        reads[pillar] = n
        add(t, pillar.colour + pillar.name + " pad §7read " + n)
    }

    fun onCheck(tick: Int, v: StormCrush.Verdict, armed: Boolean) {
        val t = phase(tick) ?: return
        if (ended) return
        val side = (if (v.inset >= 0) "§a" else "§c") + fmt(abs(v.inset)) + if (v.inset >= 0) " in" else " out"
        val head = v.head?.let { (if (it >= 0) "§a" else "§c") + "head " + (if (it >= 0) "+" else "-") + fmt(abs(it)) } ?: "§7no pillar"
        add(t, "§8check " + v.pillar.colour + v.pillar.name + " " + side + " §7· " + head + if (armed) "" else " §8(not lowered)")
    }

    private fun add(t: Int, text: String) { moments += Moment(t, text) }

    private fun firstCheck(t: Int) = Math.floorDiv(t + StormCrush.CHECK_PERIOD - 1, StormCrush.CHECK_PERIOD) * StormCrush.CHECK_PERIOD

    companion object {
        const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
        const val HAPPY = "[BOSS] Storm: I'd be happy to show you what that's like!"
        const val ENRAGED = "⚠ Storm is enraged! ⚠"
        const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
        const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
        val LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
        val CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")

        const val LIGHTNING_STRIKE = 520
        const val STRIKE_AFTER_HAPPY = 33
        const val PURPLE_AFTER_LIGHTNING = 109
        const val YELLOW_AFTER_ENRAGE = 87
        const val YELLOW_AFTER_ENRAGE_M7 = 84
        const val CHECK_SLACK = 5
        const val STEP_SPAN = 18

        const val SPOT_X = 102.375
        const val SPOT_Z = 52.375
        const val YELLOW_X = 46.0
        const val YELLOW_Z = 65.0
        const val YELLOW_REACHED = 2.4

        fun secs(ticks: Int): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)
        private fun fmt(d: Double) = String.format(Locale.ROOT, "%.2f", d)
    }
}
