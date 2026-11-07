package com.engineerclient.features.mapprediction

import java.util.Arrays
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class TreeRule(val alpha: Double = 0.5, val beta: Double = 0.5, val mu: Double = 0.2) {
    companion object {
        val DEFAULT = TreeRule()
    }
}

internal class Whole(val owner: ByteArray, val shape: ByteArray)

internal class Stored(val whole: Whole, val parent: ByteArray, val w: Double, val src: Int, val batch: Int) {
    var names = false
    var totals: IntArray = NO_TOTALS

    companion object { val NO_TOTALS = IntArray(0) }
}

internal class Part(val tally: Tally, val kept: Int, val w1: Double, val w2: Double, val s2: Double = 0.0) {
    val batches: Double get() = if (w2 > 0) w1 * w1 / w2 else 0.0
    val samples: Double get() = if (s2 > 0) tally.weight * tally.weight / s2 else 0.0
}

internal class GameSampler(
    private val view: MapView,
    private val cells: IntArray,
    private val tileIdx: IntArray,
    private val entries: IntArray,
    private val entryIndex: IntArray,
    private val entryDoor: ByteArray,
    private val pinKeys: IntArray,
    private val pinBefore: IntArray,
    private val secrets: SecretNames?,
    secretsOn: Boolean,
    private val unnamedKnown: IntArray,
    private val poolSecrets: DoubleArray = MapStats.meanSecrets(),
    private val rule: TreeRule = TreeRule.DEFAULT,
) {
    private val w = view.w
    private val h = view.h
    private val n = w * h
    private val rules = view.rules
    private val gw = rules.w
    private val gh = rules.h
    private val nb = Array(n) { t ->
        val x = t % w; val z = t / w; val o = ArrayList<Int>(4)
        if (inMain(t)) { if (x + 1 < gw) o += t + 1; if (x > 0) o += t - 1; if (z + 1 < gh) o += t + w; if (z > 0) o += t - w }
        o.toIntArray()
    }
    private val nKnown = view.kinds.size
    private val mainTiles = (0 until n).filter { inMain(it) }.toIntArray()
    private val mainCells = cells.filter { inMain(it) }.toIntArray()
    private val colTiles = if (rules.puzzleColumn && gw < w) IntArray(gh) { it * w + gw } else IntArray(0)
    private val colSide = IntArray(colTiles.size)
    private val colKnown = IntArray(4)
    private val entCell: Int
    private val inward: Int
    private val doorIn = BooleanArray(n)
    private val touchesDoor = BooleanArray(n)
    private val shownA: IntArray
    private val shownB: IntArray
    private val shownKind: ByteArray
    private val wallA: IntArray
    private val wallB: IntArray
    private val knownBig = IntArray(5)
    private val knownSpecial = IntArray(4)
    private var knownBlood = -1
    private var knownFairy = -1
    private val puzzles = view.puzzles
    private val ty = (if (rules.trap) 1 else 0) + 1
    private val specials = puzzles + ty
    private val topUp = colTiles.isNotEmpty() && specials <= rules.deadEndTarget
    private val deadMin = if (topUp) 1 else specials + 1
    private val deadMax = if (topUp || puzzles < 5) specials + 1 else specials + MAX_RARES + 1
    private val mixes = ArrayList<IntArray>()
    private val mixW: DoubleArray
    private val mixTotal: Double
    private val spots: Array<Array<IntArray>>
    private val sideBeta = DoubleArray(5) { exp(-rule.beta * it) }
    private val sideMu: DoubleArray
    private val calibrate: ((FloatArray) -> FloatArray)? = rules.gameCalibration?.let { cal -> { p: FloatArray -> MapPredictor.calibrate(p, cal, nKnown) } }

    val possible: Boolean
    var secretsOn = secretsOn; private set

    val tally = Tally(cells.size, entries.size)
    var kept = 0; private set
    var tries = 0L; private set
    private var batchW1 = 0.0
    private var batchW2 = 0.0
    private var sampleW2 = 0.0
    private var emptyFrom = 0L
    private var namesTried = false
    var done = false; private set
    internal var triesCap = MAX_TRIES
    internal var store: ArrayList<Stored>? = null
    internal var srcId = 0
    internal var storeLimit = Int.MAX_VALUE
    private var batchNo = 0

    init {
        val ent = view.kinds.indexOf(Kind.ENTRANCE)
        entCell = if (ent < 0) -1 else view.roomTiles[ent][0]
        inward = if (entCell < 0 || !inMain(entCell)) -1 else nb[entCell].firstOrNull { Edge.isDoor(view.edge(entCell, it)) } ?: run {
            val off = when {
                entCell / w == 0 -> entCell + w
                entCell / w == gh - 1 -> entCell - w
                entCell % w == 0 -> entCell + 1
                else -> entCell - 1
            }
            if (inMain(off) && view.edge(entCell, off) != Edge.NONE) off else nb[entCell].firstOrNull { view.edge(entCell, it) != Edge.NONE } ?: -1
        }
        val a = ArrayList<Int>(); val b = ArrayList<Int>(); val k = ArrayList<Byte>()
        val wa = ArrayList<Int>(); val wb = ArrayList<Int>()
        shownEdges(a, b, k, wa, wb)
        shownA = a.toIntArray(); shownB = b.toIntArray(); shownKind = k.toByteArray()
        wallA = wa.toIntArray(); wallB = wb.toIntArray()
        colSides()
        countKnown()
        val ws = ArrayList<Double>()
        for (i in rules.gameMixes.indices) {
            val x = if (i < rules.gameMixRate.size) mixWeight(i, knownBig, rules.gameMixes, rules.gameMixRate) else 0.0
            if (x > 0) { mixes += rules.gameMixes[i]; ws += x }
        }
        mixW = ws.toDoubleArray(); mixTotal = mixW.sum()
        spots = Array(5) { s ->
            val out = ArrayList<IntArray>()
            for (offs in MapPredictor.PLACEMENTS[s]) for (t0 in 0 until n) {
                val x0 = t0 % w; val z0 = t0 / w
                val cs = IntArray(offs.size / 2); var ok = true
                for (c in cs.indices) {
                    val x = x0 + offs[c * 2]; val z = z0 + offs[c * 2 + 1]
                    if (x >= gw || z >= gh) { ok = false; break }
                    cs[c] = z * w + x
                }
                if (ok) out += cs
            }
            out.toTypedArray()
        }
        sideMu = DoubleArray(n) { t -> if (entCell < 0) 1.0 else exp(-rule.mu * (abs(t % w - entCell % w) + abs(t / w - entCell / w))) }
        possible = gw <= w && gh <= h && inward >= 0 && mixTotal > 0 && cells.isNotEmpty()
        if (!possible) done = true
    }

    private fun shownEdges(a: ArrayList<Int>, b: ArrayList<Int>, k: ArrayList<Byte>, wa: ArrayList<Int>, wb: ArrayList<Int>) {
        for (t in 0 until n) for (u in nb[t]) {
            val e = view.edge(t, u)
            if (e == Edge.NONE && u > t) { wa += t; wb += u }
            if (!Edge.isDoor(e)) continue
            touchesDoor[t] = true
            if (u > t) { a += t; b += u; k += e }
            if (view.roomAt[t] < 0 && view.roomAt[u] >= 0) doorIn[t] = true
        }
    }

    private fun colSides() {
        for (k in colTiles.indices) {
            val c = colTiles[k]
            colSide[k] = when { view.roomAt[c] >= 0 || Edge.isDoor(view.edge(c - 1, c)) -> 1; view.edge(c - 1, c) == Edge.NONE -> 0; else -> -1 }
        }
    }

    private fun countKnown() {
        for (i in 0 until nKnown) {
            val shape = view.shapes[i]
            if (!inMain(view.roomTiles[i][0])) {
                colKnown[when (view.kinds[i]) { Kind.TRAP -> T; Kind.YELLOW -> Y; Kind.PUZZLE -> P; else -> R }]++
                continue
            }
            if (shape != Shape.ONE) knownBig[shape.ordinal]++
            when (view.kinds[i]) {
                Kind.TRAP -> knownSpecial[T]++
                Kind.YELLOW -> knownSpecial[Y]++
                Kind.PUZZLE -> knownSpecial[P]++
                Kind.RARE -> knownSpecial[R]++
                Kind.BLOOD -> if (knownBlood < 0) knownBlood = i
                Kind.FAIRY -> if (knownFairy < 0) knownFairy = i
                Kind.NORMAL -> if (shape == Shape.ONE && doorsOf(view.roomTiles[i][0]) == 1) knownSpecial[R]++
                Kind.ENTRANCE -> {}
            }
        }
    }

    private fun doorsOf(t: Int) = nb[t].count { Edge.isDoor(view.edge(t, it)) }

    private fun inMain(t: Int) = t % w < gw && t / w < gh

    fun effectiveBatches(): Double = if (batchW2 > 0) batchW1 * batchW1 / batchW2 else 0.0

    val accepted: Int get() = if (done) kept else 0

    val failed: Boolean get() = done && effectiveBatches() < MIN_SHOW

    fun run(samples: Int, maxTries: Int, rnd: Random): Int {
        val k0 = kept; val t0 = tries
        val budget = minOf(maxTries.toLong(), CALL_TRIES)
        while (!done && kept - k0 < samples && tries - t0 < budget) {
            batch(rnd)
            if (kept >= MIN_SAMPLES && effectiveBatches() >= MIN_BATCHES || tries >= triesCap) done = true
            else if (kept == 0 && tries - emptyFrom >= EMPTY_TRIES) {
                if (secretsOn && namesTried) { secretsOn = false; emptyFrom = tries } else done = true
            }
        }
        return kept - k0
    }

    fun prediction(): Prediction? =
        if (kept == 0 || !done && effectiveBatches() < MIN_SHOW) null
        else tally.prediction(w, h, cells, entries, entryDoor, kept, 0, calibrate)

    internal fun runBatches(maxTries: Int, rnd: Random, until: () -> Boolean = { false }) {
        val t0 = tries
        while (tries - t0 < maxTries && !until()) batch(rnd)
    }

    internal fun part(): Part = Part(tally, kept, batchW1, batchW2, sampleW2)

    internal fun dropNames() { secretsOn = false }

    internal val namesWereTried: Boolean get() = namesTried

    internal fun predictionOf(tally: Tally, samples: Int): Prediction? =
        tally.prediction(w, h, cells, entries, entryDoor, samples, 0, calibrate)

    internal fun predictionOf(parts: List<Part>, weights: DoubleArray): Prediction? {
        var live = 0; var only = -1; var samples = 0
        for (i in parts.indices) if (parts[i].kept > 0 && parts[i].tally.weight > 0 && weights[i] > 0) { live++; only = i; samples += parts[i].kept }
        if (live == 0) return null
        if (live == 1) return predictionOf(parts[only].tally, samples)
        val sum = Tally(cells.size, entries.size)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.kept > 0 && p.tally.weight > 0 && weights[i] > 0) sum.addScaled(p.tally, weights[i] / p.tally.weight)
        }
        return predictionOf(sum, samples)
    }

    internal class Reused(val survivors: ArrayList<Stored>, val part: Part, val anyNames: Boolean, val minRooms: Int)

    internal val finishSignature: String by lazy {
        view.structureKey() + "|" + view.kinds.indices.sortedBy { view.roomTiles[it].min() }.joinToString(",") { "${view.roomTiles[it].min()}k${view.kinds[it].ordinal}" } +
            "|" + pinKeys.contentToString() + pinBefore.contentToString() + "|" + view.puzzles
    }

    internal val namesUpTo: Int get() = if (secrets != null && secretsOn) SecretNames.MAX_ROOMS - unnamedKnown.size + nKnown else -1

    internal val scale: Double
        get() { var k = mixTotal; for (s in 0 until 5) repeat(knownBig[s]) { k /= spots[s].size }; return k }

    private val cntBuf = IntArray(64)
    private val idBuf = IntArray(64)

    internal fun reuse(from: List<Stored>): Reused {
        val t = Tally(cells.size, entries.size)
        val out = ArrayList<Stored>()
        if (namesBlocked(from)) return Reused(out, Part(t, 0, 0.0, 0.0), false, Int.MAX_VALUE)
        val totals = view.secretTotals
        var count = 0; var w1 = 0.0; var w2 = 0.0; var bw = 0.0; var s2 = 0.0
        var anyNames = false; var minRooms = Int.MAX_VALUE
        var cur = -1L
        for (s in from) {
            val key = (s.src.toLong() shl 32) or (s.batch.toLong() and 0xffffffffL)
            if (key != cur) { w1 += bw; w2 += bw * bw; bw = 0.0; cur = key }
            val pt = rebuild(s) ?: continue
            val fin = finish(pt) ?: continue
            val wgt = s.w * fin.f
            if (!(wgt > 0) || wgt.isInfinite()) continue
            add(t, pt, fin, wgt); count++
            bw += wgt; s2 += wgt * wgt
            val names = fin.names != null
            s.names = names; s.totals = if (names) totals else Stored.NO_TOTALS
            if (names) anyNames = true
            if (pt.rooms.size < minRooms) minRooms = pt.rooms.size
            out += s
        }
        w1 += bw; w2 += bw * bw
        return Reused(out, Part(t, count, w1, w2, s2), anyNames, minRooms)
    }

    internal fun namesBlocked(from: List<Stored>): Boolean {
        val totals = view.secretTotals
        for (s in from) if (s.names && !(totals.isNotEmpty() && totals.all { it in s.totals })) return true
        return false
    }

    private val noBools = BooleanArray(0)
    private val noInts = IntArray(0)

    private fun rebuild(s: Stored): Particle? {
        val par = s.parent; val wo = s.whole.owner; val sh = s.whole.shape
        for (e in shownA.indices) if (par[shownA[e]].toInt() != shownB[e] && par[shownB[e]].toInt() != shownA[e]) return null
        for (e in wallA.indices) if (par[wallA[e]].toInt() == wallB[e] || par[wallB[e]].toInt() == wallA[e]) return null
        val nr = sh.size
        val cnt = cntBuf; val id = idBuf
        Arrays.fill(cnt, 0, nr, 0); Arrays.fill(id, 0, nr, -1)
        for (t in 0 until n) { val o = wo[t].toInt(); if (o >= 0) cnt[o]++ }
        for (i in 0 until nKnown) {
            val ts = view.roomTiles[i]
            if (!inMain(ts[0])) continue
            val r = wo[ts[0]].toInt()
            if (r < 0 || cnt[r] != ts.size || sh[r].toInt() != view.shapes[i].ordinal) return null
            for (tile in ts) if (wo[tile].toInt() != r) return null
            id[r] = i
        }
        var next = nKnown
        for (r in 0 until nr) if (id[r] < 0 && cnt[r] > 0) id[r] = next++
        val owner = IntArray(n) { val o = wo[it].toInt(); if (o < 0) -1 else id[o] }
        val shape = IntArray(next); val size = IntArray(next)
        for (i in 0 until nKnown) shape[i] = view.shapes[i].ordinal
        for (r in 0 until nr) if (id[r] >= 0) { shape[id[r]] = sh[r].toInt(); size[id[r]] = cnt[r] }
        val rooms = Array(next) { r -> if (r < nKnown) view.roomTiles[r] else IntArray(size[r]) }
        val at = IntArray(next)
        for (tile in 0 until n) { val r = owner[tile]; if (r >= nKnown) rooms[r][at[r]++] = tile }
        val parent = IntArray(n) { par[it].toInt() }
        val depth = IntArray(n) { -1 }
        for (tile in 0 until n) depthOf(parent, depth, tile)
        val rentry = IntArray(next) { -1 }
        for (tile in mainTiles) { val p = parent[tile]; if (p < 0 || owner[p] != owner[tile]) rentry[owner[tile]] = tile }
        return Particle(owner, rooms, shape, noBools, noBools, parent, depth, noBools, rentry, noInts, noInts, 0, s.w, true)
    }

    private fun depthOf(parent: IntArray, depth: IntArray, t: Int): Int {
        if (depth[t] >= 0) return depth[t]
        val p = parent[t]
        val d = if (p < 0) 0 else depthOf(parent, depth, p) + 1
        depth[t] = d
        return d
    }

    private class Particle(
        val owner: IntArray,
        val rooms: Array<IntArray>,
        val shape: IntArray,
        val shown: BooleanArray,
        val inT: BooleanArray,
        val parent: IntArray,
        val depth: IntArray,
        val entered: BooleanArray,
        val rentry: IntArray,
        val rdist: IntArray,
        val nodes: IntArray,
        var nNodes: Int,
        var w: Double,
        var done: Boolean,
    ) {
        fun copy(w: Double) = Particle(owner, rooms, shape, shown, inT.copyOf(), parent.copyOf(), depth.copyOf(), entered.copyOf(),
            rentry.copyOf(), rdist.copyOf(), nodes.copyOf(), nNodes, w, done)
        fun big(r: Int) = shape[r] != Shape.ONE.ordinal
    }

    private val fitBuf = IntArray(128)

    private fun newParticle(rnd: Random): Particle? {
        var r0 = rnd.nextDouble() * mixTotal; var mi = 0
        while (r0 > mixW[mi] && mi < mixW.size - 1) { r0 -= mixW[mi]; mi++ }
        val mix = mixes[mi]
        val todo = ArrayList<Int>(7)
        for (s in 0 until 5) repeat(mix[s] - knownBig[s]) { todo += s }
        todo.shuffle(rnd)
        val owner = IntArray(n) { if (inMain(it)) view.roomAt[it] else -1 }
        val rooms = ArrayList<IntArray>(24); val shape = ArrayList<Int>(24)
        for (i in 0 until nKnown) { rooms += view.roomTiles[i]; shape += view.shapes[i].ordinal }
        var wt = 1.0
        for (s in todo) {
            val all = spots[s]; var nFit = 0
            for (j in all.indices) if (fits(all[j], owner)) fitBuf[nFit++] = j
            if (nFit == 0) return null
            wt *= nFit.toDouble() / all.size
            val cs = all[fitBuf[rnd.nextInt(nFit)]]
            for (t in cs) owner[t] = rooms.size
            rooms += cs; shape += s
        }
        for (t in mainCells) if (owner[t] < 0) { owner[t] = rooms.size; rooms += intArrayOf(t); shape += Shape.ONE.ordinal }
        val nr = rooms.size
        val roomArr = rooms.toTypedArray()
        val shown = BooleanArray(nr) { r -> roomArr[r].any { touchesDoor[it] } }
        val pt = Particle(owner, roomArr, shape.toIntArray(), shown, BooleanArray(n), IntArray(n) { -1 }, IntArray(n), BooleanArray(nr),
            IntArray(nr) { -1 }, IntArray(n), IntArray(nr), 0, wt, false)
        pt.inT[entCell] = true; pt.entered[owner[entCell]] = true; pt.rentry[owner[entCell]] = entCell
        if (!allowed(pt, entCell, inward)) return null
        addCell(pt, entCell, inward)
        return pt
    }

    private fun fits(cs: IntArray, owner: IntArray): Boolean {
        var doors = 0
        for (t in cs) { if (owner[t] != -1) return false; if (doorIn[t]) doors++ }
        return doors <= 1
    }

    private fun addCell(pt: Particle, u: Int, v: Int) {
        val r = pt.owner[v]
        pt.inT[v] = true; pt.parent[v] = u; pt.depth[v] = pt.depth[u] + 1
        pt.entered[r] = true; pt.rentry[r] = v
        val cs = pt.rooms[r]
        if (cs.size > 1) {
            pt.rdist[v] = 0
            val q = IntArray(cs.size); var qh = 0; var qt = 0; q[qt++] = v
            while (qh < qt) {
                val a = q[qh++]
                for (b in nb[a]) if (pt.owner[b] == r && !pt.inT[b]) {
                    pt.inT[b] = true; pt.parent[b] = a; pt.depth[b] = pt.depth[a] + 1; pt.rdist[b] = pt.rdist[a] + 1
                    q[qt++] = b
                }
            }
        }
        pt.nodes[pt.nNodes++] = r
    }

    private fun allowed(pt: Particle, u: Int, v: Int): Boolean {
        if (view.edge(u, v) == Edge.NONE) return false
        val owner = pt.owner; val s = owner[v]
        if (s == owner[u] || !pt.shown[s]) return true
        for (c in pt.rooms[s]) for (x in nb[c]) {
            val rx = owner[x]
            if (rx == s || !pt.entered[rx] || c == v && x == u) continue
            if (Edge.isDoor(view.edge(c, x))) return false
        }
        return true
    }

    private val sideU = IntArray(4 * n)
    private val sideV = IntArray(4 * n)
    private val sideP = DoubleArray(4 * n)

    private fun nodeWeight(sw: Double) = when (rule.alpha) { 0.5 -> sqrt(sw); 1.0 -> sw; 0.0 -> 1.0; else -> sw.pow(rule.alpha) }

    private fun grow(pt: Particle, rnd: Random) {
        var ns = 0; var z = 0.0
        for (i in 0 until pt.nNodes) {
            val r = pt.nodes[i]; val s0 = ns; var sw = 0.0
            for (u in pt.rooms[r]) {
                if (u == entCell) continue
                for (v in nb[u]) if (!pt.inT[v]) {
                    val x = sideBeta[minOf(pt.rdist[u], 4)] * sideMu[v]
                    sideU[ns] = u; sideV[ns] = v; sideP[ns] = x; ns++; sw += x
                }
            }
            if (ns > s0) { val nw = nodeWeight(sw); for (k in s0 until ns) sideP[k] *= nw / sw; z += nw }
        }
        if (ns == 0) { pt.done = true; return }
        var c = 0.0
        for (k in 0 until ns) if (allowed(pt, sideU[k], sideV[k])) { sideP[k] /= z; c += sideP[k] } else sideP[k] = 0.0
        if (c <= 0) { pt.w = 0.0; return }
        pt.w *= c
        var r = rnd.nextDouble() * c; var pick = -1
        for (k in 0 until ns) {
            if (sideP[k] <= 0) continue
            pick = k
            if (r < sideP[k]) break
            r -= sideP[k]
        }
        addCell(pt, sideU[pick], sideV[pick])
    }

    private fun ess(ps: List<Particle>): Double {
        var a = 0.0; var b = 0.0
        for (p in ps) { a += p.w; b += p.w * p.w }
        return if (b > 0) a * a / b else 0.0
    }

    private fun resample(ps: ArrayList<Particle>, rnd: Random): ArrayList<Particle> {
        val pos = ps.filter { it.w > 0 }
        val tot = pos.sumOf { it.w }
        if (pos.isEmpty() || tot <= 0) return ps
        val m = ps.size; val step = tot / m
        val out = ArrayList<Particle>(m)
        val u = rnd.nextDouble() * step; var c = 0.0; var i = 0
        for (k in 0 until m) {
            val target = u + k * step
            while (c + pos[i].w < target && i < pos.size - 1) { c += pos[i].w; i++ }
            out += pos[i].copy(step)
        }
        return out
    }

    private fun batch(rnd: Random) {
        var ps = ArrayList<Particle>(PARTICLES)
        repeat(PARTICLES) { tries++; newParticle(rnd)?.let { ps += it } }
        if (ps.isEmpty()) return
        while (ps.size < PARTICLES) ps += ps[rnd.nextInt(ps.size)].copy(0.0)
        if (ess(ps) < PARTICLES / 2.0) ps = resample(ps, rnd)
        for (step in 0 until n - 2) {
            for (pt in ps) if (pt.w > 0 && !pt.done) grow(pt, rnd)
            if (ps.none { it.w > 0 && !it.done }) break
            if (ess(ps) < PARTICLES / 2.0) ps = resample(ps, rnd)
        }
        var bw = 0.0
        val sink = store?.takeIf { it.size < storeLimit }
        val wholes = if (sink != null) IdentityHashMap<IntArray, Whole>() else null
        for (pt in ps) {
            if (!(pt.w > 0) || mainTiles.any { !pt.inT[it] }) continue
            val fin = finish(pt) ?: continue
            val wgt = pt.w * fin.f
            if (!(wgt > 0) || wgt.isInfinite()) continue
            add(tally, pt, fin, wgt); kept++
            bw += wgt; sampleW2 += wgt * wgt
            if (sink != null) sink += keep(pt, fin, wholes!!)
        }
        batchW1 += bw; batchW2 += bw * bw
        batchNo++
    }

    private fun keep(pt: Particle, fin: Finish, wholes: IdentityHashMap<IntArray, Whole>): Stored {
        val whole = wholes.getOrPut(pt.owner) { Whole(ByteArray(n) { pt.owner[it].toByte() }, ByteArray(pt.shape.size) { pt.shape[it].toByte() }) }
        val s = Stored(whole, ByteArray(n) { pt.parent[it].toByte() }, pt.w, srcId, batchNo)
        if (fin.names != null) { s.names = true; s.totals = view.secretTotals }
        return s
    }

    private class Inside(val parent: IntArray, val depth: IntArray, val p: Double)

    private fun insides(pt: Particle): List<Inside> {
        var sets = listOf(IntArray(0) to 1.0)
        for (r in pt.rooms.indices) {
            if (pt.shape[r] != Shape.BOX.ordinal) continue
            val v = pt.rentry[r]; val cs = pt.rooms[r]
            val ab = nb[v].filter { pt.owner[it] == r }
            val a = ab[0]; val b = ab[1]; val d = cs.first { it != v && it != a && it != b }
            val opts = listOf(
                intArrayOf(a, v, b, v, d, a) to 3.0 / 8, intArrayOf(a, v, b, v, d, b) to 3.0 / 8,
                intArrayOf(a, v, d, a, b, d) to 1.0 / 8, intArrayOf(b, v, d, b, a, d) to 1.0 / 8,
            )
            sets = sets.flatMap { (set, p) -> opts.map { (o, q) -> (set + o) to p * q } }
        }
        if (sets.size == 1) return listOf(Inside(pt.parent, pt.depth, 1.0))
        return sets.map { (set, p) ->
            val parent = pt.parent.copyOf()
            for (i in set.indices step 2) parent[set[i]] = set[i + 1]
            val depth = IntArray(n) { -1 }; depth[entCell] = 0
            fun dep(c: Int): Int { if (depth[c] < 0) depth[c] = dep(parent[c]) + 1; return depth[c] }
            for (c in mainTiles) dep(c)
            Inside(parent, depth, p)
        }
    }

    private class Finish(
        val f: Double,
        val dist: Array<DoubleArray?>,
        val onPath: DoubleArray,
        val blood: DoubleArray,
        val fairy: DoubleArray,
        val rParent: IntArray,
        val rEntry: IntArray,
        val names: HashMap<Long, Double>?,
        val col: Array<DoubleArray?>,
    )

    private class Col(val w: Double, val pIn: Double, val ok: BooleanArray)

    private fun colFor(pt: Particle, deg: IntArray, entR: Int, fr: Int, c: Int): Col? {
        val ok = BooleanArray(colTiles.size)
        var el = 0; var forced = 0; var free = 0
        for (k in colTiles.indices) {
            val r = pt.owner[colTiles[k] - 1]
            ok[k] = r != entR && r != fr && (r >= nKnown || view.kinds[r] == Kind.NORMAL) && (pt.big(r) || deg[r] >= 2)
            if (ok[k]) el++
            when (colSide[k]) { 1 -> if (ok[k]) forced++ else return null; -1 -> if (ok[k]) free++ }
        }
        if (c > el || forced > c || c - forced > free) return null
        return Col(MapPredictor.choose(free, c - forced) / MapPredictor.choose(el, c), if (free > 0) (c - forced).toDouble() / free else 0.0, ok)
    }

    private fun colPin(col: Col, k: Int): Double = when {
        view.roomAt[colTiles[k]] >= 0 -> 0.0
        colSide[k] == 1 -> 1.0
        colSide[k] == -1 && col.ok[k] -> col.pIn
        else -> 0.0
    }

    private fun finish(pt: Particle): Finish? {
        val owner = pt.owner; val nr = pt.rooms.size; val parent = pt.parent
        for (i in shownA.indices) { val a = shownA[i]; val b = shownB[i]; if (parent[a] != b && parent[b] != a) return null }
        val deg = IntArray(nr); val rParent = IntArray(nr) { -1 }; val rEntry = IntArray(nr) { -1 }
        for (v in mainTiles) {
            val u = parent[v]
            if (u < 0) { rEntry[owner[v]] = v; continue }
            if (owner[u] != owner[v]) { deg[owner[u]]++; deg[owner[v]]++; rParent[owner[v]] = owner[u]; rEntry[owner[v]] = v }
        }
        val entR = owner[entCell]
        val dead = (0 until nr).filter { it != entR && !pt.big(it) && deg[it] == 1 }
        if (dead.size < deadMin || dead.size > deadMax) return null
        val d = dead.size - 1
        val c = if (topUp) maxOf(0, specials - d) else 0
        val rares = d + c - specials
        if (rares < 0 || rares > 0 && puzzles < 5) return null
        val both = c > 0 && d < ty
        val need = intArrayOf(ty - 1, 1, if (both) puzzles else d - ty - rares, rares)
        if (need[P] < 0 || !both && colKnown[T] + colKnown[Y] + colKnown[R] > 0) return null
        val left = IntArray(4) { need[it] - knownSpecial[it] - (if (both) colKnown[it] else 0) }
        if (left.any { it < 0 }) return null
        val m = left.sum()
        val deadU = dead.filter { it >= nKnown }
        val colRooms = c - colKnown.sum()
        if (deadU.size - (if (knownBlood >= 0) 0 else 1) + (if (both) colRooms else 0) != m) return null
        val slots = if (both) d + c else d
        val wType = (fact(m) / (fact(left[T]) * fact(left[Y]) * fact(left[P]) * fact(left[R]))) /
            (fact(slots) / (fact(need[T]) * fact(need[Y]) * fact(need[P]) * fact(need[R])))

        val walk = rules.bloodWalk; val back = rules.fairyBeforeBlood
        val pb = ArrayList<Int>(); val pf = ArrayList<Int>(); val pw = ArrayList<Double>(); val pc = ArrayList<Col?>()
        bloodFairyPicks(pt, dead, walk, back, owner, entR, rParent, rEntry, parent, deg, c, pb, pf, pw, pc)
        if (pb.isEmpty()) return null

        val names = secretsOn && secrets != null && !both && unnamedKnown.size + nr - nKnown + colRooms <= SecretNames.MAX_ROOMS
        if (names) namesTried = true
        return if (names) finishNamed(pt, deadU, left, pb, pf, pw, pc, colRooms, rParent, rEntry, wType)
            else finishSummed(pt, deadU, left, pb, pf, pw, pc, both, rParent, rEntry, wType)
    }

    private fun bloodFairyPicks(pt: Particle, dead: List<Int>, walk: Int, back: Int, owner: IntArray, entR: Int, rParent: IntArray, rEntry: IntArray,
                                parent: IntArray, deg: IntArray, c: Int, pb: ArrayList<Int>, pf: ArrayList<Int>, pw: ArrayList<Double>, pc: ArrayList<Col?>) {
        val fitsPath = HashMap<Int, Boolean>()
        val cols = HashMap<Int, Col?>()
        for (va in insides(pt)) {
            var nAll = 0; val start = pb.size
            for (b in dead) {
                val t = pt.rooms[b][0]; val dep = va.depth[t]
                if (dep != walk && dep != walk + 1) continue
                var f = t; repeat(back) { f = va.parent[f] }
                val fr = owner[f]
                if (fr == entR || pt.big(fr)) continue
                nAll++
                if (b < nKnown && view.kinds[b] != Kind.BLOOD || fr < nKnown && view.kinds[fr] != Kind.FAIRY) continue
                if (knownBlood >= 0 && b != knownBlood || knownFairy >= 0 && fr != knownFairy) continue
                if (!fitsPath.getOrPut(b) { pathFits(b, rParent, rEntry, parent) }) continue
                var col: Col? = null
                if (colTiles.isNotEmpty()) {
                    if (fr !in cols) cols[fr] = colFor(pt, deg, entR, fr, c)
                    col = cols[fr] ?: continue
                }
                pb += b; pf += fr; pw += va.p * (col?.w ?: 1.0); pc += col
            }
            for (i in start until pb.size) pw[i] = pw[i] / nAll
        }
    }

    private fun addColumn(dist: Array<DoubleArray?>, col: Col?, q: Double, both: Boolean, left: IntArray, m: Int) {
        if (col == null || both && m == 0) return
        for (k in colTiles.indices) {
            val pin = colPin(col, k); if (pin <= 0) continue
            val dk = dist[k] ?: DoubleArray(Cls.COUNT).also { dist[k] = it }
            if (!both) dk[Cls.PUZZLE] += q * pin
            else for (c in 0 until 4) if (left[c] > 0) dk[CLS[c]] += q * pin * left[c] / m
        }
    }

    private fun finishSummed(pt: Particle, deadU: List<Int>, left: IntArray, pb: List<Int>, pf: List<Int>, pw: List<Double>, pc: List<Col?>,
                             both: Boolean, rParent: IntArray, rEntry: IntArray, wType: Double): Finish? {
        val nr = pt.rooms.size; val m = left.sum()
        val pins = pinKeys.isNotEmpty()
        val pinCache = HashMap<Int, PinShare?>()
        val pinOf = { b: Int -> if (!pins) null else pinCache.getOrPut(b) { pinShare(pt, deadU.filter { it != b }, left[P]) } }
        var fsum = 0.0
        val ws = DoubleArray(pb.size)
        for (i in pb.indices) {
            val share = if (pins) pinOf(pb[i])?.fit ?: 0.0 else 1.0
            ws[i] = pw[i] * share; fsum += ws[i]
        }
        if (!(fsum > 0)) return null
        val dist = arrayOfNulls<DoubleArray>(nr)
        val at = { r: Int -> dist[r] ?: DoubleArray(Cls.COUNT).also { dist[r] = it } }
        val onPath = DoubleArray(nr); val blood = DoubleArray(nr); val fairy = DoubleArray(nr)
        val col = arrayOfNulls<DoubleArray>(colTiles.size)
        val entR = pt.owner[entCell]
        for (i in pb.indices) {
            val q = ws[i] / fsum; if (q <= 0) continue
            val b = pb[i]; val fr = pf[i]
            if (b >= nKnown) at(b)[Cls.BLOOD] += q
            if (fr >= nKnown) at(fr)[Cls.FAIRY] += q
            blood[b] += q; fairy[fr] += q
            var x = b; while (x >= 0 && x != entR) { onPath[x] += q; x = rParent[x] }
            addColumn(col, pc[i], q, both, left, m)
            if (m == 0) continue
            val pin = pinOf(b)
            for (r in deadU) {
                if (r == b) continue
                val dr = at(r)
                if (pin == null) { for (c in 0 until 4) if (left[c] > 0) dr[CLS[c]] += q * left[c] / m; continue }
                val pp = pin.puzzleChance[r] ?: 0.0
                dr[Cls.PUZZLE] += q * pp
                val others = m - left[P]
                if (others > 0) for (c in intArrayOf(T, Y, R)) if (left[c] > 0) dr[CLS[c]] += q * (1 - pp) * left[c] / others
            }
        }
        return Finish(fsum * wType, dist, onPath, blood, fairy, rParent, rEntry, null, col)
    }

    private class PinShare(val fit: Double, val puzzleChance: Map<Int, Double>)

    private fun pinShare(pt: Particle, slots: List<Int>, puzzlesLeft: Int): PinShare? {
        val stretch = Array(pinKeys.size + 1) { ArrayList<Int>() }
        for (r in slots) stretch[stretchOf(pt.rooms[r][0])] += r
        var before = 0; var ways = 1.0
        val chance = HashMap<Int, Double>()
        for (g in stretch.indices) {
            val c = (if (g < pinKeys.size) pinBefore[g] else puzzlesLeft) - before
            if (c < 0 || c > stretch[g].size) return null
            ways *= MapPredictor.choose(stretch[g].size, c)
            for (r in stretch[g]) chance[r] = c.toDouble() / stretch[g].size
            before += c
        }
        val all = MapPredictor.choose(slots.size, puzzlesLeft)
        return if (all > 0) PinShare(ways / all, chance) else null
    }

    private fun orderKey(t: Int): Int = (t % w) * h + t / w

    private fun finishNamed(pt: Particle, deadU: List<Int>, left: IntArray, pb: List<Int>, pf: List<Int>, pw: List<Double>, pc: List<Col?>,
                            colRooms: Int, rParent: IntArray, rEntry: IntArray, wType: Double): Finish? {
        val sn = secrets ?: return null
        val nr = pt.rooms.size; val m = left.sum()
        val classes = ArrayList<Int>(m); for (c in 0 until 4) repeat(left[c]) { classes += CLS[c] }
        val perms = permutations(classes)
        val masks = LongArray(nr) { r -> var x = 0L; for (t in pt.rooms[r]) x = x or (1L shl t); x }
        val dist = arrayOfNulls<DoubleArray>(nr)
        val at = { r: Int -> dist[r] ?: DoubleArray(Cls.COUNT).also { dist[r] = it } }
        val onPath = DoubleArray(nr); val blood = DoubleArray(nr); val fairy = DoubleArray(nr)
        val col = arrayOfNulls<DoubleArray>(colTiles.size)
        val names = HashMap<Long, Double>()
        val entR = pt.owner[entCell]
        val cls = IntArray(nr)
        val pools = IntArray(unnamedKnown.size + nr - nKnown + colRooms) { MapStats.POOL_PUZZLE }
        for (i in unnamedKnown.indices) pools[i] = unnamedKnown[i]
        var fsum = 0.0
        for (i in pb.indices) {
            val b = pb[i]; val fr = pf[i]
            val slots = deadU.filter { it != b }
            for (perm in perms) {
                if (pinKeys.isNotEmpty() && !pinsFit(pt, slots, perm, left[P])) continue
                for (r in nKnown until nr) cls[r] = if (pt.big(r)) Cls.BIG else Cls.NORMAL
                if (b >= nKnown) cls[b] = Cls.BLOOD
                if (fr >= nKnown) cls[fr] = Cls.FAIRY
                slots.forEachIndexed { j, r -> cls[r] = perm[j] }
                for (r in nKnown until nr) pools[unnamedKnown.size + r - nKnown] = MapPredictor.poolOfCls(cls[r], Shape.entries[pt.shape[r]])
                val res = sn.solve(pools)
                val lik = res?.likelihood ?: 1.0
                val q = pw[i] / perms.size * lik
                if (!(q > 0)) continue
                fsum += q
                for (r in nKnown until nr) if (!pt.big(r) && cls[r] != Cls.NORMAL) at(r)[cls[r]] += q
                blood[b] += q; fairy[fr] += q
                var x = b; while (x >= 0 && x != entR) { onPath[x] += q; x = rParent[x] }
                addColumn(col, pc[i], q, false, left, m)
                if (res != null) for (r in nKnown until nr) {
                    val pool = MapPredictor.poolOfCls(cls[r], Shape.entries[pt.shape[r]])
                    res.byPool[pool]?.forEachIndexed { k, share ->
                        if (share > 0) names.merge(masks[r] * 256 + MapStats.NAMES.indexOf(sn.avail[pool][k]), q * share, Double::plus)
                    }
                }
            }
        }
        if (!(fsum > 0)) return null
        for (r in 0 until nr) dist[r]?.let { for (c in it.indices) it[c] /= fsum }
        for (d in col) d?.let { for (c in it.indices) it[c] /= fsum }
        for (r in 0 until nr) { onPath[r] /= fsum; blood[r] /= fsum; fairy[r] /= fsum }
        for (k in names.keys) names[k] = names.getValue(k) / fsum
        return Finish(fsum * wType, dist, onPath, blood, fairy, rParent, rEntry, names, col)
    }

    private fun pinsFit(pt: Particle, slots: List<Int>, perm: IntArray, puzzlesLeft: Int): Boolean {
        val inStretch = IntArray(pinKeys.size + 1)
        slots.forEachIndexed { j, r -> if (perm[j] == Cls.PUZZLE) inStretch[stretchOf(pt.rooms[r][0])]++ }
        var before = 0
        for (g in inStretch.indices) {
            val upTo = if (g < pinKeys.size) pinBefore[g] else puzzlesLeft
            if (inStretch[g] != upTo - before) return false
            before = upTo
        }
        return true
    }

    private fun stretchOf(t: Int): Int {
        val k = orderKey(t); var g = 0
        while (g < pinKeys.size && pinKeys[g] < k) g++
        return g
    }

    private fun permutations(items: List<Int>): List<IntArray> {
        if (items.isEmpty()) return listOf(IntArray(0))
        val out = ArrayList<IntArray>()
        fun go(rest: List<Int>, acc: List<Int>) {
            if (rest.isEmpty()) { out += acc.toIntArray(); return }
            for (i in rest.indices) go(rest.filterIndexed { j, _ -> j != i }, acc + rest[i])
        }
        go(items, emptyList())
        return out
    }

    private fun pathFits(b: Int, rParent: IntArray, rEntry: IntArray, parent: IntArray): Boolean {
        val path = ArrayList<Int>()
        var r = b; while (r >= 0) { path += r; r = rParent[r] }
        path.reverse()
        val kind = HashMap<Int, Byte>()
        for (i in 0 until path.size - 1) {
            val v = rEntry[path[i + 1]]; val u = parent[v]
            kind[minOf(u, v) * n + maxOf(u, v)] = if (i == 0) Edge.DOOR else if (i == path.size - 2) Edge.BLOOD else Edge.WITHER
        }
        for (i in shownA.indices) if ((kind[shownA[i] * n + shownB[i]] ?: Edge.DOOR) != shownKind[i]) return false
        return true
    }

    private fun add(t: Tally, pt: Particle, fin: Finish, wgt: Double) {
        t.weight += wgt
        val p = DoubleArray(Cls.COUNT)
        for (r in nKnown until pt.rooms.size) {
            val cs = pt.rooms[r]
            p.fill(0.0)
            if (pt.big(r)) p[Cls.BIG] = 1.0
            else {
                val d = fin.dist[r]
                var rest = 1.0
                if (d != null) for (c in 0 until Cls.COUNT) { p[c] = d[c]; rest -= d[c] }
                if (rest > 1e-12) p[Cls.NORMAL] += rest
            }
            for (tile in cs) { val row = t.counts[tileIdx[tile]]; for (c in 0 until Cls.COUNT) if (p[c] > 0) row[c] += wgt * p[c] }
            var x = r; while (fin.rParent[x] >= nKnown) x = fin.rParent[x]
            val e = entryIndex[fin.rEntry[x]]
            if (e >= 0) {
                t.splitRooms[e] += wgt; t.splitTiles[e] += wgt * cs.size
                for (c in 0 until Cls.COUNT) t.splitCls[e][c] += wgt * p[c]
                t.splitSecrets[e] += wgt * if (pt.big(r)) poolSecrets[MapView.shapeOf(cs, w)?.ordinal ?: 0] else oneSecrets(p)
            }
            if (pt.big(r)) { var mask = 0L; for (tile in cs) mask = mask or (1L shl tile); t.bigCounts.merge(mask, wgt, Double::plus) }
            val u = fin.rParent[r]
            if (u >= nKnown) {
                val a = fin.rEntry[r]; val b = pt.parent[a]
                val dc = t.doorCounts.getOrPut(minOf(a, b) * n + maxOf(a, b)) { DoubleArray(4) }
                dc[0] += wgt
                dc[1] += wgt * maxOf(0.0, fin.onPath[r] - fin.blood[r])
                dc[2] += wgt * fin.blood[r]
                dc[3] += wgt * (fin.fairy[r] + fin.fairy[u])
            }
        }
        for (k in colTiles.indices) {
            val d = fin.col[k] ?: continue
            val ct = colTiles[k]; val i = tileIdx[ct]
            if (i < 0) continue
            var pin = 0.0
            val row = t.counts[i]
            for (c in 0 until Cls.COUNT) if (d[c] > 0) { row[c] += wgt * d[c]; pin += d[c] }
            if (pin <= 0) continue
            val host = pt.owner[ct - 1]
            val e = if (host < nKnown) entryIndex[ct] else { var x = host; while (fin.rParent[x] >= nKnown) x = fin.rParent[x]; entryIndex[fin.rEntry[x]] }
            if (e >= 0) {
                t.splitRooms[e] += wgt * pin; t.splitTiles[e] += wgt * pin
                for (c in 0 until Cls.COUNT) t.splitCls[e][c] += wgt * d[c]
                t.splitSecrets[e] += wgt * oneSecrets(d)
            }
            if (host >= nKnown) t.doorCounts.getOrPut((ct - 1) * n + ct) { DoubleArray(4) }[0] += wgt * pin
        }
        fin.names?.forEach { (k, s) -> t.nameCounts.merge(k, wgt * s, Double::plus) }
    }

    private fun oneSecrets(p: DoubleArray): Double {
        var s = 0.0
        for (c in 1 until Cls.COUNT) if (p[c] > 0) s += p[c] * poolSecrets[MapPredictor.poolOfCls(c, Shape.ONE)]
        return s
    }

    companion object {
        const val PARTICLES = 200
        const val MIN_SAMPLES = 2000
        const val MIN_BATCHES = 120.0
        const val MIN_SHOW = 5.0
        const val CALL_TRIES = 2_000L
        const val MAX_TRIES = 400_000L
        const val EMPTY_TRIES = 20_000L
        val CALIBRATION = arrayOf(
            doubleArrayOf(0.8123, 0.8378, 0.8816, 0.9570, 0.9706, 0.9312, 0.9158, 1.0328),
            doubleArrayOf(0.0656, -0.1019, -0.0325, 0.0128, 0.0441, 0.0454, 0.0341, -0.0677),
            doubleArrayOf(-0.0236, 0.0047, 0.0020, -0.0030, -0.0076, 0.0283, 0.0005, -0.0014),
        )

        const val MAX_RARES = 3

        private const val T = 0
        private const val Y = 1
        private const val P = 2
        private const val R = 3
        private val CLS = intArrayOf(Cls.TRAP, Cls.YELLOW, Cls.PUZZLE, Cls.RARE)

        private fun fact(k: Int): Double { var r = 1.0; for (i in 2..k) r *= i; return r }

        internal fun mixWeight(i: Int, knownBig: IntArray, mixes: Array<IntArray> = GameStats.MIXES, rate: DoubleArray = GameStats.MIX_RATE): Double {
            val c = mixes[i]
            var mult = 1.0
            for (s in 0 until 5) for (j in 0 until knownBig[s]) mult *= (c[s] - j).toDouble()
            return if (mult > 0) c[5] / rate[i] * mult else 0.0
        }
    }
}
