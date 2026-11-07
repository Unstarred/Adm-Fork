package com.engineerclient.mappred

import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

internal class PredictionSession(
    var threads: Int = 1,
    var ahead: Boolean = true,
    val seed: Long = System.nanoTime(),
    val config: Config = Config(),
) {
    class Config(
        val pool: Boolean = true,
        val cap: Int = 40_000,
        val aheadTries: Long = 1_500_000L,
        val roundTries: Int = 600,
        val samples: Int = GameSampler.MIN_SAMPLES,
        val batches: Double = GameSampler.MIN_BATCHES,
        val byInfo: Boolean = true,
        val reusedWeight: Double = 0.5,
        val freshBatches: Double = 0.0,
        val showSamples: Int = 0,
        val showBatches: Double = GameSampler.MIN_SHOW,
        val importChunk: Int = 3_000,
    )

    private class Group(val key: String, var stored: ArrayList<Stored>) {
        var part: Part? = null
        var sig = ""
        var anyNames = false
        var minRooms = Int.MAX_VALUE
        var slot = -1
        var next = 0
        var fitted = ArrayList<Stored>()
        var sum: Tally? = null
        var kept = 0; var w1 = 0.0; var w2 = 0.0; var s2 = 0.0
        var namesNow = false; var minNow = Int.MAX_VALUE
        var seen = 0
        var old: Part? = null
        val importing: Boolean get() = slot >= 0
        val size: Int get() = if (importing) fitted.size + stored.size - next else stored.size
    }

    class Reveal(
        val carried: Boolean,
        val groups: Int,
        val seen: Int,
        val fit: Int,
        val lastSeen: Int,
        val lastFit: Int,
        val lastShare: Double,
        val lastBatchesBefore: Double,
        val lastBatchesAfter: Double,
        val batches: Double,
        val kept: Int,
        val nanos: Long,
    )

    private class Stash(val view: MapView, val groups: ArrayList<Group>, val secrets: Boolean, val pins: Int)

    private enum class Mode { IDLE, GAME, CLASSIC }

    private var mode = Mode.IDLE
    private var view: MapView? = null
    private var viewKey = ""
    private var viewSig = ""
    private var viewSecrets = false
    private var viewPins = 0
    private var viewSeq = 0
    private var srcSeq = 0
    private var lanes: Array<GameSampler> = emptyArray()
    internal var rnds: Array<Random> = emptyArray()
    private val groups = ArrayList<Group>()
    private var stash: Stash? = null
    private var latest: Group? = null
    private var reused = ArrayList<Part>()
    private val sameLaw = HashSet<Part>()
    private val pending = ArrayList<Group>()
    private var revealCarried = false
    private var revealSeen = 0
    private var importNanos = 0L
    private var importSig = ""
    private var done = false
    private var emptyFrom = 0L
    private var rho = RHO0
    private var predictor: MapPredictor? = null
    private var classicRnd: Random = Random(seed)
    private var classicDone = false
    @Volatile private var cancelled = false
    private var helpers: ExecutorService? = null
    private var helperCount = 0

    var reveal: Reveal? = null; private set

    val importing: Boolean get() = pending.isNotEmpty()

    var fellBack = false; private set
    var error: Exception? = null; private set

    val usesGameProcess: Boolean get() = mode == Mode.GAME

    val isDone: Boolean get() = if (mode == Mode.GAME) done else classicDone

    val tries: Long get() = lanes.sumOf { it.tries }

    val kept: Int get() = reused.sumOf { it.kept } + lanes.sumOf { it.kept }

    val poolSize: Int get() = groups.sumOf { it.size } + lanes.sumOf { it.store?.size ?: 0 }

    val batches: Double get() = weights(freshCounts()).sum()

    fun cancel() { cancelled = true }

    fun clear() {
        groups.clear(); pending.clear(); stash = null; reused = ArrayList(); sameLaw.clear(); lanes = emptyArray(); view = null; predictor = null; mode = Mode.IDLE; latest = null
    }

    fun close() { helpers?.shutdownNow(); helpers = null; helperCount = 0; clear() }

    fun begin(v: MapView, game: Boolean = true) {
        cancelled = false
        settle()
        val before = view
        val beforeKey = viewKey
        latest = null
        if (mode == Mode.GAME) closeLanes(beforeKey)
        lanes = emptyArray(); reused = ArrayList(); sameLaw.clear(); predictor = null
        view = v; viewKey = v.structureKey(); fellBack = false; error = null; done = false; emptyFrom = 0L; classicDone = false
        viewSeq++
        val mp = try { MapPredictor(v, gameProcess = game) } catch (e: Exception) { error = e; null }
        val g0 = mp?.game
        if (mp == null || g0 == null) {
            stash = if (!game || !config.pool) null else if (before != null && groups.isNotEmpty()) Stash(before, ArrayList(groups), viewSecrets, viewPins) else stash
            groups.clear(); reveal = null
            mode = Mode.CLASSIC; predictor = mp ?: MapPredictor(v, gameProcess = false)
            classicRnd = Random(seed + viewSeq * 7_919L)
            return
        }
        mode = Mode.GAME
        var carried = config.pool && before != null && v.refines(before) && !(viewSecrets && !mp.secretsActive) && mp.pinCount >= viewPins
        if (!config.pool) groups.clear()
        else {
            val s = stash
            val back = s != null && v.refines(s.view) && !(s.secrets && !mp.secretsActive) && mp.pinCount >= s.pins
            if (carried) { if (back) { groups.addAll(0, s!!.groups); stash = null } }
            else if (back) { groups.clear(); groups.addAll(s!!.groups); stash = null; carried = true }
            else { stash = if (before != null && groups.isNotEmpty()) Stash(before, ArrayList(groups), viewSecrets, viewPins) else stash; groups.clear() }
        }
        viewSecrets = mp.secretsActive; viewPins = mp.pinCount
        val n = threads.coerceIn(1, MAX_THREADS)
        lanes = Array(n) { if (it == 0) g0 else mp.newGameLane()!! }
        rnds = Array(n) { Random(seed xor (viewSeq.toLong() shl 20) xor (it.toLong() shl 44)) }
        for (l in lanes) { l.store = if (config.pool) ArrayList() else null; l.srcId = ++srcSeq }
        if (n > 1) ensureHelpers(n - 1)
        try {
            startImport(carried)
            do importSome(config.importChunk * n) while (pending.isNotEmpty() && !canShow())
        } catch (e: Exception) { dropPool(e) }
        evaluate()
    }

    private fun canShow(): Boolean {
        val k = kept
        return k > 0 && (done || weights(freshCounts()).sum() >= config.showBatches && k >= config.showSamples)
    }

    private fun dropPool(e: Exception) {
        error = e; groups.clear(); pending.clear(); stash = null; reused = ArrayList(); sameLaw.clear(); latest = null
        reveal = Reveal(false, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0, 0L)
    }

    private fun settle() {
        for (grp in pending) {
            val rest = ArrayList<Stored>(grp.fitted.size + grp.stored.size - grp.next)
            rest.addAll(grp.fitted); rest.addAll(grp.stored.subList(grp.next, grp.stored.size))
            grp.stored = rest; grp.part = null
            endImport(grp)
        }
        pending.clear()
    }

    private fun endImport(grp: Group) {
        grp.slot = -1; grp.next = 0; grp.fitted = ArrayList(); grp.sum = null; grp.old = null
    }

    private fun closeLanes(key: String) {
        latest = null
        val part = fresh()
        if (part.batches >= RHO_BATCHES && part.samples > 0) rho = maxOf(1.0, part.samples / part.batches)
        viewSig = lanes.firstOrNull()?.finishSignature ?: viewSig
        if (!config.pool) return
        val st = ArrayList<Stored>()
        for (l in lanes) { l.store?.let { st += it }; l.store = null }
        if (st.isEmpty()) return
        var anyNames = false; var minRooms = Int.MAX_VALUE
        for (s in st) { if (s.names) anyNames = true; if (s.whole.shape.size < minRooms) minRooms = s.whole.shape.size }
        val g = groups.firstOrNull { it.key == key }
        if (g == null) {
            latest = Group(key, st).also { it.part = part; it.sig = viewSig; it.anyNames = anyNames; it.minRooms = minRooms }
            groups += latest!!
        } else {
            val old = g.part
            if (old != null && g.sig == viewSig) {
                val sum = Tally(old.tally.counts.size, old.tally.splitRooms.size)
                sum.addScaled(old.tally, 1.0); sum.addScaled(part.tally, 1.0)
                g.part = Part(sum, old.kept + part.kept, old.w1 + part.w1, old.w2 + part.w2, old.s2 + part.s2)
            } else g.part = null
            g.stored.addAll(st); g.anyNames = g.anyNames || anyNames; g.minRooms = minOf(g.minRooms, minRooms)
            latest = g
        }
    }

    private fun reuseAll(stored: List<Stored>): GameSampler.Reused {
        val n = lanes.size
        if (n == 1 || stored.size < PARALLEL_IMPORT) return lanes[0].reuse(stored)
        if (lanes[0].namesBlocked(stored)) return GameSampler.Reused(ArrayList(), Part(Tally(0, 0), 0, 0.0, 0.0), false, Int.MAX_VALUE)
        val cuts = IntArray(n + 1); cuts[n] = stored.size
        for (i in 1 until n) {
            var c = maxOf(cuts[i - 1], stored.size * i / n)
            while (c in 1 until stored.size && stored[c].src == stored[c - 1].src && stored[c].batch == stored[c - 1].batch) c++
            cuts[i] = c
        }
        val futures = (1 until n).map { i -> helpers!!.submit(Callable { lanes[i].reuse(stored.subList(cuts[i], cuts[i + 1])) }) }
        val first = lanes[0].reuse(stored.subList(cuts[0], cuts[1]))
        val survivors = first.survivors
        val sum = first.part.tally
        var kept = first.part.kept; var w1 = first.part.w1; var w2 = first.part.w2; var s2 = first.part.s2
        var anyNames = first.anyNames; var minRooms = first.minRooms
        for (f in futures) {
            val r = try { f.get() } catch (e: ExecutionException) { throw (e.cause as? Exception ?: e) }
            survivors.addAll(r.survivors); sum.addScaled(r.part.tally, 1.0)
            kept += r.part.kept; w1 += r.part.w1; w2 += r.part.w2; s2 += r.part.s2
            anyNames = anyNames || r.anyNames; minRooms = minOf(minRooms, r.minRooms)
        }
        return GameSampler.Reused(survivors, Part(sum, kept, w1, w2, s2), anyNames, minRooms)
    }

    private var revealFit = 0
    private var lastSeen = 0
    private var lastFit = 0
    private var lastShare = 0.0
    private var lastB0 = 0.0
    private var lastB1 = 0.0

    private fun startImport(carried: Boolean) {
        val t0 = threadCpuNanos()
        val g = lanes[0]
        val sig = g.finishSignature
        viewSig = sig; importSig = sig
        val namesUpTo = g.namesUpTo
        revealCarried = carried; revealSeen = 0; revealFit = 0; importNanos = 0L
        lastSeen = 0; lastFit = 0; lastShare = 0.0; lastB0 = 0.0; lastB1 = 0.0
        val it = groups.iterator()
        while (it.hasNext()) {
            val grp = it.next()
            val size = grp.stored.size
            val old = grp.part
            revealSeen += size
            if (grp === latest) { lastSeen = size; lastB0 = old?.batches ?: 0.0 }
            if (old != null && grp.sig == sig && !grp.anyNames && grp.minRooms > namesUpTo) {
                revealFit += size
                if (grp === latest) { lastFit = size; lastShare = 1.0; lastB1 = old.batches }
                reused += old
                if (grp.key == viewKey) sameLaw += old
                continue
            }
            if (g.namesBlocked(grp.stored)) { it.remove(); continue }
            grp.slot = reused.size; grp.next = 0; grp.fitted = ArrayList(); grp.sum = Tally(g.tally.counts.size, g.tally.splitRooms.size)
            grp.kept = 0; grp.w1 = 0.0; grp.w2 = 0.0; grp.s2 = 0.0; grp.namesNow = false; grp.minNow = Int.MAX_VALUE
            grp.seen = size; grp.old = old
            reused += Part(grp.sum!!, 0, 0.0, 0.0)
            pending += grp
        }
        pending.reverse()
        importNanos += threadCpuNanos() - t0
        if (pending.isEmpty()) finishImport() else reveal = makeReveal()
    }

    private fun importSome(budget: Int) {
        if (pending.isEmpty()) return
        val t0 = threadCpuNanos()
        var left = budget
        while (left > 0 && pending.isNotEmpty()) {
            val grp = pending[0]
            val st = grp.stored
            var end = minOf(st.size, grp.next + left)
            while (end in 1 until st.size && st[end].src == st[end - 1].src && st[end].batch == st[end - 1].batch) end++
            val r = reuseAll(st.subList(grp.next, end))
            left -= end - grp.next
            grp.next = end
            if (r.part.kept > 0) {
                grp.fitted.addAll(r.survivors); grp.sum!!.addScaled(r.part.tally, 1.0)
                grp.kept += r.part.kept; grp.w1 += r.part.w1; grp.w2 += r.part.w2; grp.s2 += r.part.s2
                grp.namesNow = grp.namesNow || r.anyNames; grp.minNow = minOf(grp.minNow, r.minRooms)
                revealFit += r.survivors.size
            }
            val part = Part(grp.sum!!, grp.kept, grp.w1, grp.w2, grp.s2)
            reused[grp.slot] = part
            if (grp === latest) {
                val old = grp.old
                lastFit = grp.fitted.size; lastB1 = part.batches
                lastShare = if (old != null && old.tally.weight > 0) part.tally.weight / old.tally.weight else 1.0
            }
            if (grp.next >= st.size) {
                pending.removeAt(0)
                if (grp.fitted.isEmpty()) groups.remove(grp)
                else { grp.stored = grp.fitted; grp.part = part; grp.sig = importSig; grp.anyNames = grp.namesNow; grp.minRooms = grp.minNow }
                endImport(grp)
            }
        }
        importNanos += threadCpuNanos() - t0
        if (pending.isEmpty()) finishImport() else reveal = makeReveal()
    }

    private fun finishImport() {
        trim()
        reveal = makeReveal()
    }

    private fun makeReveal() = Reveal(revealCarried, groups.size, revealSeen, revealFit, lastSeen, lastFit, lastShare, lastB0, lastB1, batches, kept, importNanos)

    private fun trim() {
        val room = if (groups.any { it.key == viewKey }) config.cap else (config.cap * OLD_SHARE).toInt()
        var extra = groups.sumOf { it.stored.size } - room
        var gi = 0
        while (extra > 0 && gi < groups.size) {
            val grp = groups[gi]
            val st = grp.stored
            var k = 0
            while (k < st.size && extra > 0) {
                val s = st[k]
                var j = k
                while (j < st.size && st[j].src == s.src && st[j].batch == s.batch) j++
                extra -= j - k; k = j
            }
            if (k >= st.size) { groups.removeAt(gi); continue }
            if (k > 0) { grp.stored = ArrayList(st.subList(k, st.size)); grp.part = null }
            gi++
        }
    }

    private fun ensureHelpers(n: Int) {
        if (helperCount == n && helpers != null) return
        helpers?.shutdownNow()
        helpers = Executors.newFixedThreadPool(n) { r -> Thread(r, "engineerclient-map-prediction-lane").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        helperCount = n
    }

    private fun fresh(): Part {
        if (lanes.isEmpty()) return Part(Tally(0, 0), 0, 0.0, 0.0)
        if (lanes.size == 1) return lanes[0].part()
        val parts = lanes.map { it.part() }
        val sum = Tally(lanes[0].tally.counts.size, lanes[0].tally.splitRooms.size)
        for (p in parts) sum.addScaled(p.tally, 1.0)
        return Part(sum, parts.sumOf { it.kept }, parts.sumOf { it.w1 }, parts.sumOf { it.w2 }, parts.sumOf { it.s2 })
    }

    private class Counts(val kept: Int, val w1: Double, val w2: Double, val s1: Double, val s2: Double) {
        val batches: Double get() = if (w2 > 0) w1 * w1 / w2 else 0.0
        val samples: Double get() = if (s2 > 0) s1 * s1 / s2 else 0.0
    }

    private fun freshCounts(): Counts {
        var k = 0; var w1 = 0.0; var w2 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (l in lanes) { val p = l.part(); k += p.kept; w1 += p.w1; w2 += p.w2; s1 += p.tally.weight; s2 += p.s2 }
        return Counts(k, w1, w2, s1, s2)
    }

    private fun worth(p: Part, rho: Double): Double =
        (if (config.byInfo) minOf(p.batches, p.samples / rho) else p.batches) * config.reusedWeight

    private fun weights(fresh: Counts): DoubleArray {
        val r = if (fresh.batches >= RHO_BATCHES && fresh.samples > 0) maxOf(1.0, fresh.samples / fresh.batches) else rho
        return DoubleArray(reused.size + 1) { if (it == reused.size) fresh.batches else if (reused[it] in sameLaw) reused[it].batches else worth(reused[it], r) }
    }

    private fun reached(): Boolean {
        val f = freshCounts()
        val b = weights(f).sum()
        val k = f.kept + reused.sumOf { it.kept }
        return k >= config.samples && b >= config.batches && f.batches >= config.freshBatches
    }

    private fun evaluate() {
        if (mode != Mode.GAME || done) return
        val k = kept
        val started = tries
        if (reached()) done = true
        else if (started >= lanes[0].triesCap) done = true
        else if (k == 0 && started - emptyFrom >= GameSampler.EMPTY_TRIES) {
            if (lanes.any { it.namesWereTried } && lanes[0].secretsOn) { for (l in lanes) l.dropNames(); emptyFrom = started } else done = true
        }
        if (done && batches < GameSampler.MIN_SHOW) fallBack(null)
    }

    private fun fallBack(e: Exception?) {
        val v = view ?: return
        error = e
        groups.clear(); pending.clear(); stash = null; reused = ArrayList(); sameLaw.clear(); lanes = emptyArray(); fellBack = true; latest = null
        mode = Mode.CLASSIC; predictor = MapPredictor(v, gameProcess = false)
        classicRnd = Random(seed + viewSeq * 7_919L)
        classicDone = false
    }

    fun step(tries: Int = config.roundTries): Boolean {
        if (cancelled) return false
        return when (mode) {
            Mode.GAME -> gameStep(tries)
            Mode.CLASSIC -> classicStep()
            Mode.IDLE -> false
        }
    }

    private fun gameStep(budget: Int): Boolean {
        if (pending.isNotEmpty()) {
            try { importSome(config.importChunk * lanes.size) } catch (e: Exception) { dropPool(unwrap(e)) }
            evaluate()
            return true
        }
        if (done && !(ahead && poolSize < config.cap && tries < config.aheadTries)) return false
        try { runLanes(budget) } catch (e: Exception) { fallBack(unwrap(e)); return true }
        if (poolSize > 2L * config.cap) for (l in lanes) l.storeLimit = l.store?.size ?: 0
        evaluate()
        return true
    }

    private fun unwrap(e: Exception): Exception = (e as? ExecutionException)?.cause as? Exception ?: e

    private fun runLanes(budget: Int) {
        val stop = { cancelled }
        if (lanes.size == 1) {
            lanes[0].runBatches(budget, rnds[0]) { cancelled || !done && reached() }
            return
        }
        val pool = helpers!!
        val futures = (1 until lanes.size).map { i -> pool.submit(Callable { lanes[i].runBatches(budget, rnds[i], stop) }) }
        var failure: Exception? = null
        try { lanes[0].runBatches(budget, rnds[0], stop) } catch (e: Exception) { failure = e }
        for (f in futures) try { f.get() } catch (e: Exception) { if (failure == null) failure = e }
        failure?.let { throw it }
    }

    private fun classicStep(): Boolean {
        val p = predictor ?: return false
        if (classicDone || p.accepted >= SAMPLES) { classicDone = true; return false }
        val k = p.run(BATCH, 40_000, classicRnd)
        if (k == 0 && (p.accepted > 0 || p.level > 2)) classicDone = true
        return true
    }

    fun prediction(): Prediction? = when (mode) {
        Mode.GAME -> gamePrediction()
        Mode.CLASSIC -> try { predictor?.prediction() } catch (e: Exception) { error = e; null }
        Mode.IDLE -> null
    }

    private fun gamePrediction(): Prediction? {
        val g = lanes.firstOrNull() ?: return null
        if (!canShow()) return null
        val parts = reused + fresh()
        val w = weights(freshCounts())
        return try { g.predictionOf(parts, w) } catch (e: Exception) { error = e; fallBack(e); null }
    }

    companion object {
        const val MAX_THREADS = 4
        const val OLD_SHARE = 0.75
        const val BYTES_PER_SAMPLE = 110
        const val RHO0 = 20.0
        const val RHO_BATCHES = 5.0
        const val PARALLEL_IMPORT = 3_000
        const val BATCH = 100
        const val SAMPLES = 2000

        fun autoThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_THREADS)

        private fun threadCpuNanos(): Long {
            val bean = ManagementFactory.getThreadMXBean()
            return if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime else System.nanoTime()
        }
    }
}
