package com.engineerclient.splits

object SplitPace {

    data class Clocks(val ms: Long, val ticks: Long) {
        operator fun plus(o: Clocks) = Clocks(ms + o.ms, ticks + o.ticks)
    }

    val ORDER = listOf(
        SplitTracker.OPEN, SplitTracker.BLOOD, SplitTracker.PORTAL, SplitTracker.MAXOR, SplitTracker.STORM,
        SplitTracker.TERMS, SplitTracker.GOLDOR, SplitTracker.NECRON,
    )

    private val REAL_SPLITS = setOf(SplitTracker.TERMS)

    val SPLIT_IDS = mapOf(
        SplitTracker.OPEN to "split.open", SplitTracker.BLOOD to "split.blood", SplitTracker.PORTAL to "split.portal",
        SplitTracker.MAXOR to "split.maxor", SplitTracker.STORM to "split.storm", SplitTracker.TERMS to "split.terms",
        SplitTracker.GOLDOR to "split.goldor", SplitTracker.NECRON to "split.necron", SplitTracker.DRAGONS to "split.dragons",
    )

    val ALL = ORDER + SplitTracker.DRAGONS

    private val SUB_PREFIX = mapOf(
        SplitTracker.BLOOD to "watcher.", SplitTracker.MAXOR to "maxor.", SplitTracker.STORM to "storm.",
        SplitTracker.TERMS to "terms.", SplitTracker.GOLDOR to "goldor.", SplitTracker.NECRON to "necron.",
    )

    private val FIXED_SUBS = setOf("maxor.animation", "storm.opening", "storm.animation", "necron.intro", "necron.lock1")

    private val SPLIT_REFS: Map<String, Clocks> = mapOf(
        SplitTracker.OPEN to ticks(160),
        SplitTracker.BLOOD to ticks(920),
        SplitTracker.PORTAL to ticks(76),
        SplitTracker.MAXOR to ticks(272),
        SplitTracker.STORM to ticks(824),
        SplitTracker.TERMS to real(28_000),
        SplitTracker.GOLDOR to ticks(100),
        SplitTracker.NECRON to ticks(425),
    )

    private val SUB_REFS: Map<String, Clocks> = mapOf(
        "watcher.dialogue" to ticks(255), "watcher.wait" to ticks(63), "watcher.camp" to ticks(520), "watcher.clear" to ticks(2),
        "maxor.crystals" to ticks(110), "maxor.lure" to ticks(18), "maxor.cooldown" to ticks(85), "maxor.kill" to ticks(1),
        "maxor.animation" to ticks(62),
        "storm.opening" to ticks(647), "storm.crush1" to ticks(13), "storm.pin" to ticks(1), "storm.flight" to ticks(86),
        "storm.crush2" to ticks(20), "storm.kill" to ticks(2), "storm.animation" to ticks(62),
        "terms.s1" to real(8_700), "terms.s2" to real(7_700), "terms.s3" to real(7_800), "terms.s4" to real(5_000),
        "goldor.leaps" to ticks(9), "goldor.kill" to ticks(85),
        "necron.intro" to ticks(82), "necron.trip1" to ticks(12), "necron.lock1" to ticks(176), "necron.kill" to ticks(155),
    )

    fun ref(label: String): Clocks? = SPLIT_REFS[label]
    fun subRef(id: String): Clocks? = SUB_REFS[id]

    private fun ticks(t: Long) = Clocks(t * 50, t)
    private fun real(ms: Long) = Clocks(ms, ms / 50)

    fun clocks(id: String, value: Long): Clocks =
        if (SubSplitGrades.clock(id) == SubSplitGrades.Clock.REAL) real(value) else ticks(value)

    data class Sub(val id: String, val split: Split)

    fun pace(splits: List<Split>, subs: (String) -> List<Sub>, now: Stamp, target: (String) -> Clocks? = { null }): Clocks {
        var total = Clocks(0, 0)
        for (label in ORDER) {
            val ref = target(label) ?: SPLIT_REFS[label] ?: continue
            val split = splits.firstOrNull { it.label == label }
            total += when {
                split == null -> ref
                split.stop != null -> length(split, now)
                else -> running(label, split, ref, subs(label), now)
            }
        }
        return total
    }

    private fun running(label: String, split: Split, ref: Clocks, subs: List<Sub>, now: Stamp): Clocks {
        val prefix = SUB_PREFIX[label] ?: return atLeast(ref, length(split, now))
        val all = SUB_REFS.filterKeys { it.startsWith(prefix) }
        val fixed = all.filterKeys { it in FIXED_SUBS }.values.fold(Clocks(0, 0), Clocks::plus)
        val varying = all.filterKeys { it !in FIXED_SUBS }.values.fold(Clocks(0, 0), Clocks::plus)
        val kMs = if (varying.ms > 0) (ref.ms - fixed.ms).coerceAtLeast(0).toDouble() / varying.ms else 1.0
        val kTicks = if (varying.ticks > 0) (ref.ticks - fixed.ticks).coerceAtLeast(0).toDouble() / varying.ticks else 1.0
        var est = ref
        for (s in subs) {
            val subRef = SUB_REFS[s.id] ?: continue
            val share = if (s.id in FIXED_SUBS) subRef else Clocks(Math.round(subRef.ms * kMs), Math.round(subRef.ticks * kTicks))
            val len = length(s.split, now)
            val diff = Clocks(len.ms - share.ms, len.ticks - share.ticks)
            est += if (s.split.stop != null) diff else Clocks(maxOf(diff.ms, 0), maxOf(diff.ticks, 0))
        }
        return atLeast(est, length(split, now))
    }

    fun lag(splits: List<Split>, now: Stamp): Long =
        splits.filter { it.label !in REAL_SPLITS }.sumOf { length(it, now).let { c -> c.ms - c.ticks * 50 } }.coerceAtLeast(0)

    private fun length(s: Split, now: Stamp): Clocks {
        val stop = s.stop ?: now
        return Clocks(stop.realMs - s.start.realMs, (stop.tick - s.start.tick).toLong())
    }

    private fun atLeast(a: Clocks, b: Clocks) = Clocks(maxOf(a.ms, b.ms), maxOf(a.ticks, b.ticks))

    fun mss(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
