package com.engineerclient.misc

import net.minecraft.network.chat.Component
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard

object ScoreboardLines {

    @JvmField var hideLines: Boolean = false

    enum class Kind {
        BLANK, DATE, SEASON, CLOCK, LOCATION_CATACOMBS, LOCATION_OTHER, PURSE, BITS, OBJECTIVE,
        OBJECTIVE_TASK, HYPE, PROTOTYPE_LOBBY, FOOTER, STARTING, TEAMMATE_LOBBY, TEAMMATE_RUN, SOLO,
        TIME_ELAPSED, KEYS, CLEARED,
    }

    @JvmField var hideCatacombsLocation: Boolean = false
    @JvmField var hideTimeElapsed: Boolean = true
    @JvmField var hideCleared: Boolean = true

    private val ALWAYS_HIDDEN = setOf(
        Kind.BLANK, Kind.DATE, Kind.SEASON, Kind.CLOCK, Kind.LOCATION_OTHER, Kind.OBJECTIVE,
        Kind.OBJECTIVE_TASK, Kind.HYPE, Kind.PROTOTYPE_LOBBY, Kind.FOOTER, Kind.STARTING, Kind.SOLO,
        Kind.KEYS,
    )

    private const val NUM = """\d[\d,.]*"""

    private val KINDS: List<Pair<Kind, Regex>> = listOf(
        Kind.DATE to Regex("""^\d{2}/\d{2}/\d{2}\s+\S+.*$"""),
        Kind.SEASON to Regex("""^(?:Early |Late )?(?:Spring|Summer|Autumn|Winter) \d{1,2}(?:st|nd|rd|th)$"""),
        Kind.CLOCK to Regex("""^\d{1,2}:\d{2}(?:am|pm)(?: [☀☽])?$"""),
        Kind.LOCATION_CATACOMBS to Regex("""^\W*The Catacombs \((?:[FM][1-7]|E)\)$"""),
        Kind.LOCATION_OTHER to Regex("""^\uE067 .+$"""),
        Kind.PURSE to Regex("""^(?:Purse|Piggy): $NUM(?: \(\+$NUM\))?$"""),
        Kind.BITS to Regex("""^Bits: $NUM(?: \(\+$NUM\))?$"""),
        Kind.OBJECTIVE to Regex("""^Objective(?: \S)?$"""),
        Kind.HYPE to Regex("""^Hype: $NUM/$NUM$"""),
        Kind.PROTOTYPE_LOBBY to Regex("""^(?:Games in this lobby are|under heavy development!|Report bugs and leave|feedback at|hypixel\.net/ptl)$"""),
        Kind.FOOTER to Regex("""^www\.hypixel\.net$"""),
        Kind.STARTING to Regex("""^(?:Starting in|Auto-closing in): .+$"""),
        Kind.TEAMMATE_LOBBY to Regex("""^\[[MHABT]\] \w{1,16} \[Lv\d*\]?$"""),
        Kind.TEAMMATE_RUN to Regex("""^\[[MHABT]\] \w{1,16} $NUM❤?$"""),
        Kind.SOLO to Regex("""^Solo$"""),
        Kind.TIME_ELAPSED to Regex("""^Time Elapsed: .+$"""),
        Kind.KEYS to Regex("""^Keys: .+$"""),
        Kind.CLEARED to Regex("""^Cleared: $NUM% \($NUM\)$"""),
    )

    internal fun kindOf(text: String, previous: Kind?): Kind? {
        val t = text.trim()
        if (t.isEmpty()) return Kind.BLANK
        KINDS.firstOrNull { it.second.matches(t) }?.let { return it.first }
        return if (previous == Kind.OBJECTIVE) Kind.OBJECTIVE_TASK else null
    }

    internal fun hidesKind(kind: Kind?): Boolean = when (kind) {
        null -> false
        Kind.LOCATION_CATACOMBS -> hideCatacombsLocation
        Kind.TIME_ELAPSED -> hideTimeElapsed
        Kind.CLEARED -> hideCleared
        else -> kind in ALWAYS_HIDDEN
    }

    private val DISPLAY_ORDER = compareByDescending<PlayerScoreEntry> { it.value() }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }

    internal fun lineText(scoreboard: Scoreboard, entry: PlayerScoreEntry): Component =
        PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())

    fun shouldHide(line: Component): Boolean = hideLines && hides(plain(line))

    fun hidesTitle(): Boolean = hideLines

    internal fun hides(raw: String, previous: Kind? = null): Boolean =
        hidesKind(kindOf(raw, previous))

    fun visibleEntries(scoreboard: Scoreboard, objective: Objective): Collection<PlayerScoreEntry> {
        val all = scoreboard.listPlayerScores(objective)
        if (!hideLines) return all
        val hidden = HashSet<PlayerScoreEntry>()
        var previous: Kind? = null
        for (entry in all.filter { !it.isHidden }.sortedWith(DISPLAY_ORDER)) {
            val kind = contextFreeKind(lineText(scoreboard, entry)) ?: if (previous == Kind.OBJECTIVE) Kind.OBJECTIVE_TASK else null
            if (hidesKind(kind)) hidden += entry
            previous = kind
        }
        return all.filter { it !in hidden }
    }

    private fun contextFreeKind(line: Component): Kind? {
        val raw = line.string
        if (kindMemo.containsKey(raw)) return kindMemo[raw]
        if (kindMemo.size >= KIND_MEMO_MAX) kindMemo.clear()
        return kindOf(raw.replace(FORMATTING, "").trim(), null).also { kindMemo[raw] = it }
    }

    private val kindMemo = HashMap<String, Kind?>()
    private const val KIND_MEMO_MAX = 512

    internal fun plain(component: Component): String = component.string.replace(FORMATTING, "").trim()

    private val FORMATTING = Regex("§.")
}
