package com.engineerclient.features.splits

import java.util.Locale

class GoldorCore {
    class Entry(val tick: Int, val exact: Boolean, val beforeS4: Boolean, val died: Boolean = false)

    var startTick: Int? = null; private set
    var openTick: Int? = null; private set
    var goldorMoved: Int? = null; private set
    var pasted = false
    private val inside = LinkedHashMap<String, Entry>()
    private val seen = HashMap<String, Int>()
    private val deadAtStart = HashSet<String>()
    private var round = 0
    private var necron = false

    private var now = 0

    val watching get() = startTick != null && !necron && goldorMoved.let { g -> g == null || now < openTick!! + g + GRACE }
    val active get() = startTick != null && !necron

    fun reset() { startTick = null; openTick = null; goldorMoved = null; pasted = false; inside.clear(); seen.clear(); deadAtStart.clear(); round = 0; necron = false }

    fun onS4(tick: Int) { if (startTick == null) startTick = tick }

    fun onCoreOpen(tick: Int) {
        if (openTick != null) return
        if (startTick == null) startTick = tick
        openTick = tick
    }

    fun look(tick: Int): Boolean {
        now = tick
        if (!watching) return false
        round++
        return true
    }

    fun observe(name: String, inCore: Boolean?, tick: Int) {
        if (!watching || inCore == null) return
        val before = seen.put(name, round)
        if (!inCore || name in inside) return
        val first = round == 1
        inside[name] = Entry(tick, exact = first || before == round - 1, beforeS4 = first)
    }

    fun onDeath(name: String, tick: Int) {
        if (!watching || name in inside || name in deadAtStart) return
        if (round == 1) { deadAtStart += name; return }
        inside[name] = Entry(tick, exact = true, beforeS4 = false, died = true)
    }

    fun counted(alive: Collection<String>): List<String> = (alive + inside.filterValues { it.died }.keys).distinct()

    fun onGoldorMoved(tick: Int) {
        val open = openTick ?: return
        if (goldorMoved == null) goldorMoved = tick - open
    }

    fun onNecron() { if (startTick != null) necron = true }

    fun entry(name: String): Entry? = inside[name]

    fun lastIn(alive: Collection<String>): Pair<String, Entry>? {
        if (alive.isEmpty() || alive.any { it !in inside }) return null
        return alive.map { it to inside.getValue(it) }.maxByOrNull { it.second.tick }
    }

    private fun lastNames(alive: Collection<String>): List<String> {
        val t = lastIn(alive)?.second?.tick ?: return emptyList()
        return alive.filter { inside.getValue(it).tick == t }
    }

    private fun time(e: Entry): String {
        val open = openTick
        return when {
            e.beforeS4 -> "already in"
            open == null -> "in"
            else -> (if (e.exact) "" else "~") + secs(e.tick - open)
        }
    }

    fun lines(now: Int, me: String?, alive: Collection<String>): List<String> {
        if (startTick == null) return emptyList()
        val open = openTick
        val out = mutableListOf(if (open == null) "§eCore §8(S4)" else "§eCore")
        val last = lastIn(alive)
        val lastNames = lastNames(alive)
        for ((name, e) in inside) {
            val colour = when {
                name in lastNames -> "§6"
                !e.exact -> "§8"
                open == null || e.tick < open -> "§3"
                else -> "§a"
            }
            out += "${if (name == me) "§f" else "§7"}$name $colour${if (e.died) "☠ " else ""}${time(e)}"
        }
        for (name in alive) if (name !in inside) out += "${if (name == me) "§f" else "§7"}$name §8..."
        if (open != null) {
            val gap = goldorMoved?.let { g -> last?.let { " §8(" + signed(open + g - it.second.tick) + " after the last in)" } }.orEmpty()
            out += "§eGoldor moved " + (goldorMoved?.let { "§f" + secs(it) + gap } ?: "§8...")
        }
        return out
    }

    fun pasteLine(alive: Collection<String>): String? {
        val open = openTick ?: return null
        val e = lastIn(alive)?.second ?: return null
        if (alive.any { !inside.getValue(it).exact } || e.beforeS4) return null
        val t = e.tick - open
        return "Last in core: ${lastNames(alive).joinToString(" & ")} ${secs(t)}" + if (t < 0) " (before the core opened)" else ""
    }

    companion object {
        const val GRACE = 10
        fun secs(ticks: Int): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)
        private fun signed(ticks: Int) = (if (ticks >= 0) "+" else "-") + secs(Math.abs(ticks))
    }
}
