package com.engineerclient.features.splits

import com.engineerclient.util.noFormatting
import java.util.Locale

object EngineerLook {

    data class Row(val name: String, val ms: Long, val ticks: Long, val current: Boolean)

    enum class Place { FLOOR7, DUNGEON, OTHER }

    data class Options(val bossEntry: Boolean, val show0: Boolean, val showTicks: Boolean, val enterAfterEntry: Boolean = false,
                       val showPace: Boolean = true, val showLag: Boolean = true)

    data class Line(val label: String, val colour: String, val time: String, val ticks: String?)

    val TARGET_SPLITS = listOf("Blood Open", "Blood Clear", "Portal Entry", "Maxor", "Storm", "Terminals", "Goldor", "Necron", "Cleared")

    fun targetLabels(master: Boolean) = listOf("Open", "Blood", "Portal", "Maxor", "Storm", "Terms", "Goldor", "Necron") + if (master) listOf("Dragons") else emptyList()

    private val DUNGEON_NAMES = mapOf("Blood Open" to "§aOpen", "Blood Clear" to "§cBlood", "Portal Entry" to "§dPortal", "Boss Entry" to "§9Enter")
    private val FLOOR7_NAMES = mapOf("Maxor" to "§5Maxor", "Storm" to "§bStorm", "Terminals" to "§6Terms", "Goldor" to "§eGoldor", "Necron" to "§cNecron")
    private const val PACE = "§3Pace"
    private const val BOSS_ENTRY = "§9Boss Entry"
    private const val LAG = "§8Lag"

    private fun strip(s: String) = s.noFormatting().trim()

    fun label(odinName: String, place: Place, master: Boolean): String {
        val name = strip(odinName)
        if (place != Place.OTHER) DUNGEON_NAMES[name]?.let { return it }
        if (place == Place.FLOOR7) {
            FLOOR7_NAMES[name]?.let { return it }
            if (name == "Cleared" && master) return "§dDragons"
        }
        return if (odinName.startsWith("§")) odinName else "§f$odinName"
    }

    private fun colourOf(label: String) = if (label.length >= 2 && label[0] == '§') label.take(2) else "§f"

    fun lines(rows: List<Row>, opts: Options, place: Place, master: Boolean, targets: List<Double?>?,
              grade: ((String, Long, Long, Boolean) -> String?)? = null,
              pace: Pair<Long, Long>? = null,
              lagMs: Long? = null): List<Line> {
        if (rows.isEmpty()) return emptyList()
        val segments = segments(rows, place, master)
        val out = mutableListOf<Line>()

        val current = segments.indexOfFirst { it.current }
        val started = current >= 0 || rows.any { it.ms > 0 }
        if (opts.showLag && lagMs != null && started) out += Line(LAG, lagColour(lagMs), SplitFormat.seconds(lagMs), null)
        if (opts.showPace && (started || opts.show0 || targets != null)) {
            var ms = 0L; var ticks = 0L
            segments.forEachIndexed { i, s ->
                val target = targetFor(s.name, targets)
                when {
                    !started || (current >= 0 && i > current) -> { ms += secondsMs(target); ticks += secondsTicks(target) }
                    i == current -> { ms += maxOf(s.ms, secondsMs(target)); ticks += maxOf(s.ticks, secondsTicks(target)) }
                    else -> { ms += s.ms; ticks += s.ticks }
                }
            }
            out += if (pace != null) Line(PACE, colourOf(PACE), SplitPace.mss(pace.first), SplitPace.mss(pace.second * 50))
            else line(PACE, ms, ticks, opts, SplitFormat::minutes)
        }

        segments.forEachIndexed { i, s ->
            if (s.ms != 0L || opts.show0) {
                val l = line(label(s.name, place, master), s.ms, s.ticks, opts, SplitFormat::seconds)
                val over = current < 0 || i < current
                out += grade?.invoke(s.name, s.ms, s.ticks, over && s.ms != 0L)?.let { l.copy(colour = it) } ?: l
            }
            val entered = current > 2 || (current == -1 && started)
            if (opts.bossEntry && i == 2 && rows.size > 3 && (entered || !opts.enterAfterEntry)) {
                val ms = segments.take(3).sumOf { it.ms }
                val ticks = segments.take(3).sumOf { it.ticks }
                if (ms != 0L || opts.show0) {
                    val l = line(label(BOSS_ENTRY, place, master), ms, ticks, opts, SplitFormat::minutes)
                    out += grade?.invoke(BOSS_ENTRY, ms, ticks, entered && ms != 0L)?.let { l.copy(colour = it) } ?: l
                }
            }
        }
        return out
    }

    fun allLabels(rows: List<Row>, opts: Options, place: Place, master: Boolean): List<String> {
        val segments = segments(rows, place, master)
        return listOfNotNull(PACE.takeIf { opts.showPace }, LAG.takeIf { opts.showLag }) + segments.map { label(it.name, place, master) } +
            (if (opts.bossEntry && rows.size > 3) listOf(label(BOSS_ENTRY, place, master)) else emptyList())
    }

    fun currentLine(rows: List<Row>, opts: Options, place: Place, master: Boolean): Line? {
        val s = segments(rows, place, master).firstOrNull { it.current } ?: return null
        return line(label(s.name, place, master), s.ms, s.ticks, opts, SplitFormat::seconds)
    }

    private fun segments(rows: List<Row>, place: Place, master: Boolean) =
        rows.dropLast(1).filterNot { place == Place.FLOOR7 && !master && strip(it.name) == "Cleared" }

    private fun lagColour(ms: Long) = when {
        ms < 1000 -> "§7"
        ms <= 3000 -> "§e"
        else -> "§c"
    }

    fun text(l: Line): String = "${l.label} §b> ${l.colour}${l.time}" + (l.ticks?.let { " §8(§7$it§8)" } ?: "")

    private fun line(label: String, ms: Long, ticks: Long, opts: Options, format: (Long) -> String) =
        Line(label, colourOf(label), format(ms), if (opts.showTicks) format(ticks * 50L) else null)

    private fun targetFor(odinName: String, targets: List<Double?>?): Double? {
        targets ?: return null
        val i = TARGET_SPLITS.indexOf(strip(odinName))
        return if (i < 0) null else targets.getOrNull(i)
    }

    private fun secondsMs(s: Double?) = if (s == null) 0L else Math.round(s * 1000)
    private fun secondsTicks(s: Double?) = if (s == null) 0L else Math.round(s * 20)

    private val MINUTES = Regex("""^(\d+)\s*[:m]\s*(\d+(?:\.\d+)?)?\s*s?$""")
    private val SECONDS = Regex("""^(\d+(?:\.\d+)?)\s*s?$""")

    fun parseSeconds(text: String): Double? {
        val t = text.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        SECONDS.matchEntire(t)?.let { return it.groupValues[1].toDouble() }
        MINUTES.matchEntire(t)?.let { m ->
            val sec = m.groupValues[2].ifEmpty { "0" }.toDouble()
            if (t.contains(':') && m.groupValues[2].isEmpty()) return null
            return m.groupValues[1].toDouble() * 60 + sec
        }
        return null
    }

    fun formatSeconds(s: Double): String =
        String.format(Locale.ROOT, "%.2f", s).trimEnd('0').trimEnd('.')
}
