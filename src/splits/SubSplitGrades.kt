package com.engineerclient.splits

object SubSplitGrades {

    enum class Clock { TICKS, REAL }

    class Bands(val clock: Clock, val limits: LongArray?, val floor: Long, val fromStart: Boolean = false, val lateAfter: Long? = null)

    @JvmStatic var byTime: () -> Boolean = { true }

    @JvmStatic var showBest: () -> Boolean = { false }

    val COLOURS = listOf("§2", "§a", "§e", "§c", "§4", "§0")
    const val BEST = "§6"
    const val FILLER = "§7"

    private fun ticks(floor: Long, vararg l: Long) = Bands(Clock.TICKS, l, floor)
    private fun real(floor: Long, vararg l: Long) = Bands(Clock.REAL, l, floor)
    private fun filler() = Bands(Clock.TICKS, null, 0)

    private fun stepped(clock: Clock, floor: Long, onTime: Long, step: Long, fromStart: Boolean = false) =
        Bands(clock, longArrayOf(onTime, onTime, onTime + step, onTime + 2 * step, onTime + 3 * step), floor, fromStart)

    val BANDS: Map<String, Bands> = mapOf(
        "watcher.dialogue" to ticks(229, 255, 294, 307, 324, 355),
        "watcher.wait" to ticks(45, 63, 78, 114, 155, 200),
        "watcher.camp" to ticks(485, 520, 560, 600, 640, 670),
        "watcher.clear" to ticks(0, 2, 6, 10, 16, 25),

        "maxor.crystals" to stepped(Clock.TICKS, 107, 110, 10),
        "maxor.lure" to stepped(Clock.TICKS, 124, 128, 10, fromStart = true),
        "maxor.cooldown" to stepped(Clock.TICKS, 83, 85, 10),
        "maxor.kill" to ticks(0, 1, 3, 5, 7, 17),
        "maxor.animation" to filler(),

        "storm.opening" to filler(),
        "storm.crush1" to stepped(Clock.TICKS, 10, 13, 20),
        "storm.pin" to ticks(0, 1, 2, 3, 6, 14),
        "storm.flight" to ticks(78, 86, 90, 93, 99, 168),
        "storm.crush2" to stepped(Clock.TICKS, 0, 20, 20),
        "storm.kill" to ticks(1, 2, 3, 5, 18, 24),
        "storm.animation" to filler(),

        "terms.s1" to real(8100, 8700, 9500, 11000, 13200, 17200),
        "terms.s2" to real(6700, 7700, 9400, 12600, 16100, 21800),
        "terms.s3" to real(6650, 7800, 11400, 13000, 16700, 18800),
        "terms.s4" to real(4450, 5000, 7600, 8800, 12600, 25100),

        "goldor.leaps" to ticks(7, 9, 15, 21, 29, 43),
        "goldor.kill" to ticks(77, 85, 96, 108, 124, 134),

        "necron.intro" to filler(),
        "necron.trip1" to ticks(11, 12, 14, 16, 18, 26),
        "necron.lock1" to Bands(Clock.TICKS, null, 0, lateAfter = 275),
        "necron.kill" to stepped(Clock.TICKS, 140, 155, 20),

        "split.open" to ticks(100, 160, 280, 380, 500, 640),
        "split.blood" to ticks(907, 920, 1000, 1100, 1200, 1400),
        "split.portal" to ticks(40, 76, 88, 100, 120, 160),
        "split.enter" to ticks(1100, 1240, 1400, 1560, 1700, 1900),
        "split.maxor" to ticks(263, 272, 280, 300, 340, 400),
        "split.storm" to ticks(820, 824, 840, 870, 960, 1080),
        "split.terms" to real(25000, 28000, 31000, 37000, 41000, 48000),
        "split.goldor" to ticks(96, 100, 126, 140, 160, 180),
        "split.necron" to ticks(409, 425, 436, 447, 490, 540),
    )

    val BANDS_M7: Map<String, Bands> = mapOf(
        "necron.kill" to Bands(Clock.TICKS, longArrayOf(106, 128, 150, 172, 192), 99),
        "necron.relics" to ticks(89, 101, 111, 121, 150, 212),
        "split.necron" to ticks(457, 482, 510, 533, 570, 633),
        "split.dragons" to ticks(523, 533, 547, 566, 591, 623),
    )

    fun bands(id: String, floor: String?): Bands? = when (floor) {
        "F7" -> BANDS[id]
        "M7" -> BANDS_M7[id]
        else -> null
    }

    private fun anyBands(id: String, floor: String?) = bands(id, floor) ?: BANDS[id] ?: BANDS_M7[id]

    val MAIN_SPLITS = mapOf(
        "Blood Open" to "split.open", "Blood Clear" to "split.blood", "Portal Entry" to "split.portal", "Boss Entry" to "split.enter", "Maxor" to "split.maxor", "Storm" to "split.storm",
        "Terminals" to "split.terms", "Goldor" to "split.goldor", "Necron" to "split.necron",
        "Cleared" to "split.dragons",
    )

    fun value(id: String, realMs: Long, ticks: Long, fromStartTicks: Long = ticks): Long {
        val b = BANDS[id] ?: return ticks
        return when {
            b.clock == Clock.REAL -> realMs
            b.fromStart || b.lateAfter != null -> fromStartTicks
            else -> ticks
        }
    }

    fun clock(id: String): Clock = BANDS[id]?.clock ?: Clock.TICKS

    fun colour(id: String, value: Long, finished: Boolean, best: Long?, floor: String?, fallback: String): String {
        if (!byTime()) return fallback
        val banded = bands(id, floor) != null
        val b = anyBands(id, floor) ?: return fallback
        b.lateAfter?.let { return if (finished && value > it) COLOURS[4] else FILLER }
        val limits = b.limits ?: return FILLER
        if (showBest() && finished && best != null && value <= best && value >= b.floor) return BEST
        if (!banded) return fallback
        val i = limits.indexOfFirst { value <= it }
        return COLOURS[if (i < 0) COLOURS.lastIndex else i]
    }

    fun canBeBest(id: String, value: Long, floor: String? = "F7"): Boolean {
        val b = anyBands(id, floor) ?: return false
        return b.limits != null && value >= b.floor
    }

    fun bestsView(text: String): Map<String, Long> {
        lastBests?.let { (t, m) -> if (t === text) return m }
        val m: Map<String, Long> = text.split(',').mapNotNull { e ->
            val (k, v) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            v.trim().toLongOrNull()?.let { k.trim() to it }
        }.toMap(HashMap())
        lastBests = text to m
        return m
    }
    @Volatile private var lastBests: Pair<String, Map<String, Long>>? = null

    fun parseBests(text: String): MutableMap<String, Long> = HashMap(bestsView(text))

    fun formatBests(bests: Map<String, Long>): String = bests.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
}
