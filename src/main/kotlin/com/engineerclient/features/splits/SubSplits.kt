package com.engineerclient.features.splits

import java.util.Arrays
import kotlin.math.hypot

class SubSplitTracker {

    private class Step(val split: String, val label: String?, val id: String = "")

    private val steps = SEQUENCE
    private val starts = arrayOfNulls<Stamp>(SEQUENCE.size)

    private var current = -1

    private var ticks = 0
    private var lightning: Stamp? = null

    private var bloodMobs = 0

    private var gateBlown = false
    private var gateWaiting = false

    var watchingCore = false
        private set

    val s4Start: Stamp? get() = starts[T_S4]

    fun reset() {
        Arrays.fill(starts, null)
        current = -1; ticks = 0; lightning = null; bloodMobs = 0
        gateBlown = false; gateWaiting = false; watchingCore = false
    }

    fun forSplit(split: String): List<Split> {
        val out = mutableListOf<Split>()
        steps.forEachIndexed { i, step ->
            if (step.split != split || step.label == null) return@forEachIndexed
            val start = starts[i] ?: return@forEachIndexed
            val stop = (i + 1 until starts.size).firstNotNullOfOrNull { starts[it] }
            out += Split(step.label, start, stop)
        }
        return out
    }

    fun idsForSplit(split: String): List<String> =
        steps.indices.filter { steps[it].split == split && steps[it].label != null && starts[it] != null }.map { steps[it].id }

    fun onServerTick() {
        ticks++
        val from = lightning ?: return
        if (current == S_OPENING && ticks >= STORM_LEAVES) {
            lightning = null
            jumpTo(S_CRUSH1, from.plus(STORM_LEAVES))
        }
    }

    fun onWatcherMoved(at: Stamp) {
        if (current == W_WAIT) jumpTo(W_CAMP, at)
    }

    fun onBloodMobSpawn(at: Stamp) {
        if (current !in W_DIALOGUE..W_CAMP) return
        if (++bloodMobs >= BLOOD_MOBS) jumpTo(W_CLEAR, at)
    }

    fun onMaxorKilled(at: Stamp) {
        if (current in M_CRYSTALS until M_ANIMATION) jumpTo(M_ANIMATION, at)
    }

    fun onMaxorDead(at: Stamp) {
        if (current == M_KILL) jumpTo(M_ANIMATION, maxOf(at.minus(MAXOR_DESPAWN), starts[M_KILL]!!))
    }

    fun onTopCrystal(at: Stamp) {
        val hit = at.minus(CRYSTALS_BACK)
        when (current) {
            M_LURE -> jumpTo(M_COOLDOWN, maxOf(hit, starts[current]!!))
            M_COOLDOWN -> if (hit.tick - starts[M_COOLDOWN]!!.tick >= MIN_HIT_GAP)
                jumpTo(M_KILL, maxOf(hit, starts[current]!!))
        }
    }

    fun onStormPosition(at: Stamp, x: Double, y: Double, z: Double) {
        when (current) {
            S_OPENING -> if (lightning != null && hypot(x - STORM_SPOT_X, z - STORM_SPOT_Z) > 0.5) {
                lightning = null
                jumpTo(S_CRUSH1, at)
            }
            S_FLIGHT -> if (hypot(x - YELLOW_X, z - YELLOW_Z) <= YELLOW_REACHED) jumpTo(S_CRUSH2, at)
        }
    }

    fun onNecronPosition(at: Stamp, fromMid: Double) {
        when (current) {
            N_INTRO -> if (fromMid > OFF_MID) jumpTo(N_TRIP1, at)
            N_TRIP1 -> if (fromMid < ON_MID) jumpTo(N_LOCK1, at)
        }
    }

    fun onNecronDeath(at: Stamp) {
        if (current in N_INTRO until N_RELICS) jumpTo(N_RELICS, at)
    }

    fun onSectionDoor(at: Stamp, section: Int) {
        val step = T_S1 + section - 1
        if (current == step) jumpTo(step + 1, at)
    }

    fun startTerms(section: Int, starts: List<Stamp>) {
        reset()
        for (s in 1..section.coerceAtMost(4)) jumpTo(T_S1 + s - 1, starts[s - 1])
    }

    fun onEveryoneInCore(at: Stamp) {
        if (!watchingCore) return
        watchingCore = false
        if (current == G_LEAPS) jumpTo(G_KILL, at)
    }

    fun onChat(msg: String, at: Stamp) {
        when {
            msg.startsWith(WATCHER) && current < W_DIALOGUE -> { reset(); jumpTo(W_DIALOGUE, at) }
            msg == WATCHER_HANDLE -> if (current == W_DIALOGUE) jumpTo(W_WAIT, at)
            msg == WATCHER_DONE -> if (current in W_DIALOGUE..W_CLEAR) jumpTo(W_END, at)

            msg == MAXOR_START -> jumpTo(M_CRYSTALS, at)
            msg == STORM_START -> { lightning = null; jumpTo(S_OPENING, at) }
            msg == GOLDOR_START -> { gateBlown = false; gateWaiting = false; jumpTo(T_S1, at) }
            msg == CORE_OPENING -> if (current in T_S1..T_S4) { jumpTo(G_LEAPS, at); watchingCore = true }
            msg in NECRON_START -> { watchingCore = false; jumpTo(N_INTRO, at) }
            SplitTracker.EXTRA_STATS.matches(msg) -> if (current in N_INTRO until N_END) jumpTo(N_END, at)
            msg == SplitTracker.RELICS_PLACED -> if (current in N_INTRO until N_END) jumpTo(N_END, at)

            current < 0 -> return

            msg == LASER_CHARGING -> if (current == M_CRYSTALS) jumpTo(M_LURE, at)
            msg in MAXOR_STUN -> when (current) {
                M_CRYSTALS, M_LURE -> jumpTo(M_COOLDOWN, at)
                M_COOLDOWN -> if (at.tick - starts[M_COOLDOWN]!!.tick >= MIN_HIT_GAP) jumpTo(M_KILL, at)
            }

            msg in STORM_LIGHTNING -> if (current == S_OPENING && lightning == null) { lightning = at; ticks = 0 }
            msg in STORM_CRUSHED -> when (current) {
                S_OPENING, S_CRUSH1 -> jumpTo(S_PIN, at)
                S_CRUSH2 -> jumpTo(S_KILL, at)
                S_FLIGHT -> { jumpTo(S_CRUSH2, at); jumpTo(S_KILL, at) }
            }
            msg == STORM_ENRAGED -> if (current == S_PIN) jumpTo(S_FLIGHT, at)
            msg == STORM_DEAD -> if (current in S_OPENING until S_ANIMATION) jumpTo(S_ANIMATION, at)

            msg == NECRON_ARGH -> if (current in N_INTRO..N_LOCK1) jumpTo(N_KILL, at)

            msg == GATE_DESTROYED -> if (current in T_S1..T_S3) {
                if (gateWaiting) jumpTo(current + 1, at) else gateBlown = true
            }
            else -> {
                if (current !in T_S1..T_S4) return
                val m = SECTION_DONE.find(msg) ?: return
                if (m.groupValues[2] != m.groupValues[3]) return
                when {
                    current == T_S4 -> { jumpTo(G_LEAPS, at); watchingCore = true }
                    gateBlown -> jumpTo(current + 1, at)
                    else -> gateWaiting = true
                }
            }
        }
    }

    private fun jumpTo(step: Int, at: Stamp) {
        ticks = 0
        gateBlown = false
        gateWaiting = false
        current = step
        starts[step] = at
    }

    private fun Stamp.plus(n: Int) = Stamp(realMs + n * 50L, tick + n)
    private fun Stamp.minus(n: Int) = Stamp(realMs - n * 50L, tick - n)
    private fun maxOf(a: Stamp, b: Stamp) = if (a.tick >= b.tick) a else b

    private companion object {
        const val WATCHER = "[BOSS] The Watcher: "
        const val WATCHER_HANDLE = "[BOSS] The Watcher: Let's see how you can handle this."
        const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
        const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
        const val LASER_CHARGING = "The Energy Laser is charging up!"
        const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
        const val STORM_ENRAGED = "⚠ Storm is enraged! ⚠"
        const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
        const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
        const val GATE_DESTROYED = "The gate has been destroyed!"
        const val CORE_OPENING = "The Core entrance is opening!"
        const val NECRON_ARGH = "[BOSS] Necron: ARGH!"
        val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (?:terminal|device|lever)! \((\d+)/(\d+)\)$""")

        val MAXOR_STUN = setOf("[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!")
        val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
        val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
        val NECRON_START = setOf(
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
            "[BOSS] Necron: You went further than any human before, congratulations.",
        )

        const val BLOOD_MOBS = 19

        const val CRYSTALS_BACK = 41
        const val MAXOR_DESPAWN = 54
        const val MIN_HIT_GAP = 60

        const val STORM_LEAVES = 99
        const val STORM_SPOT_X = 102.375
        const val STORM_SPOT_Z = 52.375
        const val YELLOW_X = 46.0
        const val YELLOW_Z = 65.0
        const val YELLOW_REACHED = 2.4

        const val OFF_MID = 0.5
        const val ON_MID = 0.05

        const val W_DIALOGUE = 0; const val W_WAIT = 1; const val W_CAMP = 2; const val W_CLEAR = 3; const val W_END = 4
        const val M_CRYSTALS = 5; const val M_LURE = 6; const val M_COOLDOWN = 7; const val M_KILL = 8; const val M_ANIMATION = 9
        const val S_OPENING = 10; const val S_CRUSH1 = 11; const val S_PIN = 12; const val S_FLIGHT = 13
        const val S_CRUSH2 = 14; const val S_KILL = 15; const val S_ANIMATION = 16
        const val T_S1 = 17; const val T_S4 = 20; const val T_S3 = 19
        const val G_LEAPS = 21; const val G_KILL = 22
        const val N_INTRO = 23; const val N_TRIP1 = 24; const val N_LOCK1 = 25; const val N_KILL = 26; const val N_RELICS = 27; const val N_END = 28

        val SEQUENCE: List<Step> = listOf(
            Step(SplitTracker.BLOOD, "&7Dialogue", "watcher.dialogue"), Step(SplitTracker.BLOOD, "&5Wait", "watcher.wait"), Step(SplitTracker.BLOOD, "&cCamp", "watcher.camp"),
            Step(SplitTracker.BLOOD, "&aClear", "watcher.clear"), Step(SplitTracker.BLOOD, null),

            Step(SplitTracker.MAXOR, "&dCrystals", "maxor.crystals"), Step(SplitTracker.MAXOR, "&6Lure", "maxor.lure"), Step(SplitTracker.MAXOR, "&5Cooldown", "maxor.cooldown"),
            Step(SplitTracker.MAXOR, "&cKill", "maxor.kill"), Step(SplitTracker.MAXOR, "&dAnimation", "maxor.animation"),

            Step(SplitTracker.STORM, "&aOpening", "storm.opening"), Step(SplitTracker.STORM, "&6Crush", "storm.crush1"),
            Step(SplitTracker.STORM, "&cPin", "storm.pin"), Step(SplitTracker.STORM, "&bFlight", "storm.flight"),
            Step(SplitTracker.STORM, "&6Crush", "storm.crush2"), Step(SplitTracker.STORM, "&cKill", "storm.kill"),
            Step(SplitTracker.STORM, "&aAnimation", "storm.animation"),

            Step(SplitTracker.TERMS, "&6S1", "terms.s1"), Step(SplitTracker.TERMS, "&6S2", "terms.s2"),
            Step(SplitTracker.TERMS, "&6S3", "terms.s3"), Step(SplitTracker.TERMS, "&6S4", "terms.s4"),

            Step(SplitTracker.GOLDOR, "&5Leaps", "goldor.leaps"), Step(SplitTracker.GOLDOR, "&cKill", "goldor.kill"),

            Step(SplitTracker.NECRON, "&dIntro", "necron.intro"), Step(SplitTracker.NECRON, "&cTrip", "necron.trip1"),
            Step(SplitTracker.NECRON, "&aLock", "necron.lock1"), Step(SplitTracker.NECRON, "&cKill", "necron.kill"),
            Step(SplitTracker.NECRON, "&3Relics", "necron.relics"),
            Step(SplitTracker.NECRON, null),
        )
    }
}
