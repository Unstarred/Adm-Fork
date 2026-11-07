package com.engineerclient.splits

import java.util.Locale

data class Stamp(val realMs: Long, val tick: Int)

data class Split(val label: String, val start: Stamp, val stop: Stamp?)

object SplitFormat {

    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f", ms / 1000.0) + "s"

    fun minutes(ms: Long): String {
        val tenths = Math.round(ms / 100.0)
        return "${tenths / 600}m " + String.format(Locale.ROOT, "%.1f", tenths % 600 / 10.0) + "s"
    }
}

class SplitTracker {

    private var start: Stamp? = null
    private val starts = arrayOfNulls<Stamp>(PHASES.size)
    private var end: Stamp? = null

    fun reset() {
        start = null; end = null
        starts.fill(null)
        pending = null
    }

    private class Pending(val phase: Int, val before: (String) -> Clock, val head: Clock)

    data class Clock(val ms: Long, val ticks: Int)

    private var pending: Pending? = null

    fun startAt(label: String, before: (String) -> Clock, now: Stamp? = null, head: Clock = Clock(0, 0)) {
        reset()
        val i = PHASES.indexOfFirst { it.label == label }.takeIf { it >= 0 } ?: return
        pending = Pending(i, before, head)
        if (now != null) fill(now)
    }

    private fun fill(at: Stamp) {
        val p = pending ?: return
        pending = null
        var s = Stamp(at.realMs - p.head.ms, at.tick - p.head.ticks)
        starts[p.phase] = s
        for (j in p.phase - 1 downTo 0) {
            val c = p.before(PHASES[j].label)
            s = Stamp(s.realMs - c.ms, s.tick - c.ticks)
            starts[j] = s
        }
        start = starts[0]
    }

    fun onChat(msg: String, at: Stamp) {
        pending?.let { p ->
            if (PHASES[p.phase].starts(msg)) fill(at)
            return
        }
        if (start == null) {
            if (msg == MORT) { start = at; starts[0] = at }
            return
        }
        if (end != null) return
        if (EXTRA_STATS.matches(msg)) { end = at; return }
        for (i in 1 until PHASES.size) {
            if (starts[i] == null && PHASES[i].starts(msg)) starts[i] = at
        }
    }

    fun splits(): List<Split> = PHASES.indices.mapNotNull { i ->
        val from = starts[i] ?: return@mapNotNull null
        val next = (i + 1 until PHASES.size).firstNotNullOfOrNull { starts[it] }
        Split(PHASES[i].label, from, next ?: end)
    }

    fun split(label: String): Split? = splits().firstOrNull { it.label == label }

    private class Phase(val label: String, val starts: (String) -> Boolean)

    companion object {
        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."

        const val OPEN = "&aOpen"
        const val BLOOD = "&cBlood"
        const val PORTAL = "&dPortal"
        const val MAXOR = "&5Maxor"
        const val STORM = "&bStorm"
        const val TERMS = "&6Terms"
        const val GOLDOR = "&eGoldor"
        const val NECRON = "&cNecron"
        const val DRAGONS = "&dDragons"

        const val RELICS_PLACED = "[BOSS] Wither King: You... again?"

        val EXTRA_STATS = Regex("^ +> EXTRA STATS <$")

        private val PHASES = listOf(
            Phase(OPEN) { false },
            Phase(BLOOD) { it.startsWith("[BOSS] The Watcher: ") },
            Phase(PORTAL) { it == "[BOSS] The Watcher: You have proven yourself. You may pass." },
            Phase(MAXOR) { it == "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" },
            Phase(STORM) { it == "[BOSS] Storm: Pathetic Maxor, just like expected." },
            Phase(TERMS) { it == "[BOSS] Goldor: Who dares trespass into my domain?" },
            Phase(GOLDOR) { it == "The Core entrance is opening!" },
            Phase(NECRON) { it == "[BOSS] Necron: You went further than any human before, congratulations." },
            Phase(DRAGONS) { it == RELICS_PLACED },
        )
    }
}
