package com.engineerclient.adr

import com.engineerclient.adr.UploadPacker.BlockReader
import com.engineerclient.adr.UploadPacker.ENTITY_KINDS
import com.engineerclient.adr.UploadPacker.HEAD
import com.engineerclient.adr.UploadPacker.ID
import com.engineerclient.adr.UploadPacker.Malformed
import com.engineerclient.adr.UploadPacker.SIB_MAX_CHARS
import com.engineerclient.adr.UploadPacker.SIB_MAX_IDS
import com.engineerclient.adr.UploadPacker.SIB_MAX_LINE
import com.engineerclient.adr.UploadPacker.SIB_MAX_LINES
import com.engineerclient.adr.UploadPacker.SIB_MAX_ROWS
import com.engineerclient.adr.UploadPacker.SIB_MAX_TICK
import com.engineerclient.adr.UploadPacker.Scan
import com.engineerclient.adr.UploadPacker.WORLD
import com.engineerclient.adr.UploadPacker.arr
import com.engineerclient.adr.UploadPacker.at
import com.engineerclient.adr.UploadPacker.int
import com.engineerclient.adr.UploadPacker.kindOf
import com.engineerclient.adr.UploadPacker.lines
import com.engineerclient.adr.UploadPacker.long
import com.engineerclient.adr.UploadPacker.obj
import com.engineerclient.adr.UploadPacker.str
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStream

/** A party member's upload, as far as leaving things out goes: whose it is, each mob's event count, when it had each player, its block changes, where it had each mob and player. */
internal class Sibling(
    val self: String, val startMs: Long, val counts: Map<Int, Int>, val players: Map<String, List<LongRange>>, val blocks: List<Pair<Long, String>>,
    val mobs: Map<Int, Track>, val lives: Map<Int, List<SibLife>>, val playerAt: Map<String, Track>,
    /** Ticks apart the viewer slides its mobs between rows (0: it holds them till the next). */
    val lerp: Int,
) {
    /** Where it had mob [id] at its tick [t] in life [life]. */
    fun mobAt(id: Int, life: SibLife, t: Long): DoubleArray? {
        val r = mobs[id]?.at(t, lerp)
        return if (r == null || r[0] < life.from) doubleArrayOf(t.toDouble(), life.x, life.y, life.z) else r
    }
}

/** A mob's life in a party member's upload, in its ticks: from its spawn to its gone, where it spawned and as what. */
internal class SibLife(val from: Long, var to: Long, val x: Double, val y: Double, val z: Double, val type: String?)

/** One mob's or player's rows in a recording (t, x, y, z), in order. */
internal class Track {
    private var t = LongArray(8); private var p = DoubleArray(24); var n = 0; private set
    fun add(tick: Long, x: Double, y: Double, z: Double) {
        if (n == t.size) { t = t.copyOf(n * 2); p = p.copyOf(n * 6) }
        t[n] = tick; p[3 * n] = x; p[3 * n + 1] = y; p[3 * n + 2] = z; n++
    }
    fun sort() {
        if ((1 until n).all { t[it - 1] <= t[it] }) return
        val order = (0 until n).sortedBy { t[it] }
        val t2 = LongArray(t.size); val p2 = DoubleArray(p.size)
        order.forEachIndexed { k, i -> t2[k] = t[i]; for (c in 0..2) p2[3 * k + c] = p[3 * i + c] }
        t = t2; p = p2
    }
    /** [t, x, y, z] at tick [at]: its last row, or slid toward the next one up to [lerp] ticks on (the viewer's way); null before the first. */
    fun at(at: Long, lerp: Int): DoubleArray? {
        var lo = 0; var hi = n - 1
        if (n == 0 || t[0] > at) return null
        while (lo < hi) { val m = (lo + hi + 1) / 2; if (t[m] <= at) lo = m else hi = m - 1 }
        val i = lo
        if (lerp > 0 && i + 1 < n && t[i + 1] - t[i] in 2..lerp.toLong()) {
            val f = (at - t[i]).toDouble() / (t[i + 1] - t[i])
            return doubleArrayOf(t[i].toDouble(), p[3 * i] + (p[3 * i + 3] - p[3 * i]) * f, p[3 * i + 1] + (p[3 * i + 4] - p[3 * i + 1]) * f, p[3 * i + 2] + (p[3 * i + 5] - p[3 * i + 2]) * f)
        }
        return doubleArrayOf(t[i].toDouble(), p[3 * i], p[3 * i + 1], p[3 * i + 2])
    }
}

private const val LIFE_MARGIN = 10
/** How far apart two recordings' views of a mob or player can be and still be the same (theirs are at a tenth of a block, slid). */
private const val AGREE_DIST = 1.5
private const val AGREE_SAMPLES = 30

private fun dist(a: DoubleArray, b: DoubleArray) = Math.sqrt((a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]) + (a[3] - b[3]) * (a[3] - b[3]))

/** Up to [AGREE_SAMPLES] of [rows], spread out. */
private fun sample(rows: List<DoubleArray>) = if (rows.size <= AGREE_SAMPLES) rows else List(AGREE_SAMPLES) { rows[it * rows.size / AGREE_SAMPLES] }

/**
 * Whether the party member's upload has this life of a mob - all of it (but a few ticks at its
 * ends), and where this recording saw it: nine in ten of its moves within [AGREE_DIST]. An
 * upload that has it somewhere else (or made up) has nothing left out for it.
 */
internal fun covered(life: MobPlan.Life, sib: Sibling, offset: Long, ticks: Int): Boolean {
    if (life.proj) return false
    val theirs = sib.lives[life.id] ?: return false
    val s = life.spawn.toLong(); val g = (life.gone ?: ticks).toLong()
    var from = s + LIFE_MARGIN; var to = g - LIFE_MARGIN
    if (from > to) { from = (s + g) / 2; to = from }
    val sl = theirs.firstOrNull { it.from + offset <= from && it.to + offset >= to } ?: return false
    if (sl.type != null && sl.type != life.type) return false
    val pts = sample(life.rows.filter { it[0] >= from && it[0] <= to }).ifEmpty { listOf(doubleArrayOf(from.toDouble(), life.at[0], life.at[1], life.at[2])) }
    val ok = pts.count { q -> sib.mobAt(life.id, sl, q[0].toLong() - offset)?.let { dist(it, q) <= AGREE_DIST } == true }
    return ok * 10 >= pts.size * 9
}

/**
 * Other players' lines left out while [sib] has them (see [UploadPacker]). Their rows go from "p"
 * lines; when that starts, a "pgone" so this recording's view of them doesn't hold still on the
 * last row it had; when it ends, the row they're at then, as the recording would have had it.
 */
internal class Players(private val scan: Scan, private val sib: Sibling, private val aligned: Pair<Long, Int>, private val ours: Map<String, List<DoubleArray>>) {
    /** Ticks (this recording's) the sibling surely has each player, where it has them as this one does. */
    private val covered = sib.players.mapValues { (name, list) ->
        val (offset, margin) = aligned
        list.mapNotNull { r -> val a = r.first + offset + margin; val b = r.last + offset - margin; if (a <= b && same(name, a, b, offset)) a..b else null }
    }

    /** Whether the sibling has [name] where this recording does, from tick [a] to [b] (nine in ten of its rows). */
    private fun same(name: String, a: Long, b: Long, offset: Long): Boolean {
        val pts = sample(ours[name]?.filter { it[0] >= a && it[0] <= b } ?: return true)
        val track = sib.playerAt[name] ?: return false
        // (slid between rows as the viewer does: their rows are thinned too)
        val ok = pts.count { q -> track.at(q[0].toLong() - offset, sib.lerp)?.let { dist(it, q) <= AGREE_DIST } == true }
        return ok * 10 >= pts.size * 9
    }
    private val dropping = HashMap<String, Long>() // name -> the end of the stretch it's left out in
    private val last = HashMap<String, JsonElement?>() // the row each one left out is at

    private fun coverEnd(name: String, t: Long): Long? =
        if (name == scan.self) null else covered[name]?.firstOrNull { t in it }?.last

    /** Lines about a player the sibling is: their skin, held head and equipment come from their own recording. */
    private fun theirs(line: String): Boolean {
        val kind = kindOf(line) ?: return false
        if (kind != "eq" && kind != "skin" && kind != "held") return false
        if (sib.self.isEmpty() || sib.self == scan.self) return false
        return line.contains("\"name\":" + JsonPrimitive(sib.self).toString() + ",")
    }

    fun keepsWorld(line: String) = !theirs(line)

    fun put(line: String, out: (String) -> Unit) {
        val head = HEAD.find(line) ?: return out(line)
        val kind = head.groupValues[1]
        val t = head.groupValues[2].toLong()
        // Stretches over: back to how this recording has them.
        if (dropping.isNotEmpty()) {
            val ended = dropping.filter { (_, end) -> t > end }.keys
            if (ended.isNotEmpty()) {
                val rows = JsonArray()
                for (name in ended) { dropping.remove(name); last.remove(name)?.let { rows.add(it) } }
                if (rows.size() > 0) out(JsonObject().apply { addProperty("k", "p"); addProperty("t", t); add("d", rows) }.toString())
            }
        }
        if (theirs(line)) return
        when (kind) {
            "p" -> {
                val l = JsonParser.parseString(line).asJsonObject
                val left = JsonArray()
                val started = ArrayList<String>()
                for (r in l.getAsJsonArray("d")) {
                    val name = r.asJsonArray[0].asString
                    val end = dropping[name] ?: coverEnd(name, t)?.also { dropping[name] = it; started += name }
                    if (end == null) left.add(r) else last[name] = r
                }
                if (left.size() == l.getAsJsonArray("d").size()) out(line)
                else if (left.size() > 0) { l.add("d", left); out(l.toString()) }
                for (name in started) out(JsonObject().apply { addProperty("k", "pgone"); addProperty("t", t); addProperty("name", name) }.toString())
            }
            "pgone" -> {
                val name = JsonParser.parseString(line).asJsonObject["name"]?.asString
                if (name != null && name in dropping) last[name] = null else out(line)
            }
            else -> out(line)
        }
    }
}

/**
 * What leaving things out needs from a party member's upload (or a saved run). Anyone can upload
 * anything, so every field is checked and everything is limited (see [SIB_MAX_CHARS] and on);
 * anything off throws, and the run goes up as if there were no sibling.
 */
internal fun readSibling(input: InputStream, cat: Catalogue.Blocks? = null, values: Catalogue.Values? = null, dict: Catalogue.Dict? = null): Sibling {
    val out = HashMap<Int, Int>()
    var self = ""
    var startMs = 0L
    var minT = Long.MAX_VALUE
    var maxT = -1L
    var rows = 0
    val seen = HashMap<String, MutableList<Long>>() // name -> ticks it had a row at, and pgone ticks as -(t + 1)
    val blocks = BlockReader(cat)
    val mobs = HashMap<Int, Track>()
    val playerAt = HashMap<String, Track>()
    val spawns = HashMap<Int, MutableList<DoubleArray>>() // id -> [t, life (or -1), x, y, z, back], and their types
    val types = HashMap<Long, String>() // (id shl 32 | spawn tick) -> type
    val gones = HashMap<Int, MutableList<Long>>()
    var lerp = 0
    var erel = false
    var tab2 = false
    var valuesOk = false
    val typeTable = ArrayList<String?>() // ("tab2": spawn types by number, see Stretch.tables)
    val relative = ArrayList<DoubleArray>() // ("erel": mob rows from where the mob spawned) id, t, x, y, z, till the spawns are in
    fun mob(id: Int, t: Long, x: Double?, y: Double?, z: Double?) {
        if (x == null || y == null || z == null) return
        if (++rows > SIB_MAX_ROWS) throw Malformed("too many rows")
        if (erel) relative += doubleArrayOf(id.toDouble(), t.toDouble(), x, y, z) else mobs.getOrPut(id) { Track() }.add(t, x, y, z)
    }
    fun player(name: String, t: Long, x: Double?, y: Double?, z: Double?) { if (x == null || y == null || z == null) return; playerAt.getOrPut(name) { Track() }.add(t, x, y, z) }
    fun tick(t: Long): Long { if (t !in 0..SIB_MAX_TICK) throw Malformed("tick $t"); if (t < minT) minT = t; if (t > maxT) maxT = t; return t }
    fun row(name: String, t: Long) { if (++rows > SIB_MAX_ROWS) throw Malformed("too many player rows"); seen.getOrPut(name) { ArrayList() } += t }
    fun count(id: Int, n: Int) {
        out.merge(id, n) { a, b -> minOf(a.toLong() + b, Int.MAX_VALUE.toLong()).toInt() }
        if (out.size > SIB_MAX_IDS) throw Malformed("too many mobs")
    }
    lines(input, dict).use { r ->
        for (line in capped(r)) {
            val kind = kindOf(line) ?: continue
            HEAD.find(line)?.let { if (kind !in WORLD) tick(it.groupValues[2].toLongOrNull() ?: throw Malformed("tick")) }
            blocks.read(kind, line)
            when (kind) {
                "meta" -> obj(line).let { m ->
                    self = runCatching { str(m["self"]) }.getOrDefault("").takeIf { it.length <= 16 } ?: ""
                    startMs = runCatching { long(m["startMs"]) }.getOrDefault(0L)
                    lerp = runCatching { int(m["lerp"]) }.getOrDefault(0).coerceIn(0, 200)
                    erel = runCatching { int(m["erel"]) }.getOrDefault(0) == 1
                    tab2 = runCatching { int(m["tab2"]) }.getOrDefault(0) == 1
                    valuesOk = values != null && runCatching { int((m["cat"] as? JsonObject)?.get("values")) == values.version }.getOrDefault(false)
                }
                "p" -> { val l = obj(line); val t = tick(long(l["t"])); for (x in arr(l["d"])) { val r = arr(x); val name = str(at(r, 0)); row(name, t); if (r.size() >= 4) player(name, t, num(r[1]), num(r[2]), num(r[3])) } }
                "e" -> { val l = obj(line); val t = tick(long(l["t"])); for (x in arr(l["d"])) { val r = arr(x); val id = int(at(r, 0)); count(id, 1); if (r.size() >= 4) mob(id, t, num(r[1]), num(r[2]), num(r[3])) } }
                "pgone" -> { val l = obj(line); row(str(l["name"]), -(tick(long(l["t"])) + 1)) }
                "cols" -> {
                    val l = obj(line)
                    var acc = 0L
                    val ticks = arr(l["t"]).map { acc += long(it); tick(acc) }
                    // Which of the line's ticks each mob (player) has a row in: each one once, in order -
                    // so a mob's count is at most the lines there are, not however many times it is listed.
                    fun inLines(a: JsonArray): List<Int> {
                        var li = 0L
                        return arr(at(a, 1)).mapIndexed { k, d ->
                            val step = long(d)
                            if (step < 0 || (k > 0 && step == 0L)) throw Malformed("line order")
                            li += step
                            if (li >= ticks.size) throw Malformed("line $li of ${ticks.size}")
                            li.toInt()
                        }
                    }
                    val byId = l.has("kd")
                    var id = 0L
                    // Each one's first three columns (x, y, z: every row has them).
                    fun xyz(a: JsonArray, n: Int): List<List<Double?>> { val cols = arr(at(a, 3)); return (0..2).map { c -> if (c < cols.size()) nums(cols[c], n) else emptyList() } }
                    if (l.has("flat")) {
                        val of = l["of"]?.let(::str)
                        for (f in flatRows(l, ticks.size)) {
                            if (of == "e") {
                                id = if (byId) id + long(f.key) else long(f.key)
                                val mid = id.toInt().takeIf { i -> i.toLong() == id } ?: throw Malformed("id")
                                count(mid, f.lines.size)
                                f.lines.forEachIndexed { k, li -> mob(mid, ticks[li], f.x[k], f.y[k], f.z[k]) }
                            } else if (of == "p") {
                                val name = str(f.key)
                                f.lines.forEachIndexed { k, li -> row(name, ticks[li]); player(name, ticks[li], f.x[k], f.y[k], f.z[k]) }
                            }
                        }
                        continue
                    }
                    when (l["of"]?.let(::str)) {
                        "e" -> for (t in arr(l["tr"])) arr(t).let {
                            id = if (byId) id + long(at(it, 0)) else long(at(it, 0))
                            val mid = id.toInt().takeIf { i -> i.toLong() == id } ?: throw Malformed("id")
                            val lis = inLines(it); count(mid, lis.size)
                            val (xs, ys, zs) = xyz(it, lis.size)
                            lis.forEachIndexed { k, li -> mob(mid, ticks[li], xs.getOrNull(k), ys.getOrNull(k), zs.getOrNull(k)) }
                        }
                        "p" -> for (tr in arr(l["tr"])) {
                            val a = arr(tr); val name = str(at(a, 0)); val lis = inLines(a)
                            val (xs, ys, zs) = xyz(a, lis.size)
                            lis.forEachIndexed { k, li -> row(name, ticks[li]); player(name, ticks[li], xs.getOrNull(k), ys.getOrNull(k), zs.getOrNull(k)) }
                        }
                    }
                }
                "grp" -> {
                    // Mob events as columns (Stretch): each one's id, and its tick.
                    val l = obj(line)
                    val of = str(l["of"])
                    val n = int(l["n"]).also { if (it !in 0..SIB_MAX_ROWS) throw Malformed("rows") }
                    val c = l["c"] as? JsonObject ?: throw Malformed("columns")
                    c["t"]?.let { col -> for (t in longs(col, n)) tick(t) }
                    if (of in ENTITY_KINDS || of == "eq") c["id"]?.let { col -> for (id in longs(col, n)) count(id.toInt().takeIf { it.toLong() == id } ?: throw Malformed("id"), 1) }
                    if (of == "pgone") {
                        val ts = longs(c["t"] ?: throw Malformed("t"), n); val names = strs(c["name"] ?: throw Malformed("name"), n)
                        for (k in ts.indices) names.getOrNull(k)?.let { row(it, -(tick(ts[k]) + 1)) }
                    }
                    // (arrows sent already stuck: "pj" rows marked "spawn", a spawn each - see Stretch.stuckArrows)
                    if (of == "pj" && c["spawn"] != null) {
                        val ts = nums(c["t"] ?: throw Malformed("t"), n); val ids = nums(c["id"] ?: throw Malformed("id"), n)
                        val sp = nums(c["spawn"]!!, n).map { it == 1.0 }; val g = c["g"]?.let { nums(it, n) }
                        val xs = c["x"]?.let { nums(it, n) }; val ys = c["y"]?.let { nums(it, n) }; val zs = c["z"]?.let { nums(it, n) }
                        for (k in ts.indices) {
                            if (sp.getOrNull(k) != true) continue
                            val t = ts[k] ?: continue; val i = ids.getOrNull(k)?.toInt() ?: continue
                            count(i, 1)
                            spawns.getOrPut(i) { ArrayList() } += doubleArrayOf(t, g?.getOrNull(k) ?: -1.0, xs?.getOrNull(k) ?: Double.NaN, ys?.getOrNull(k) ?: Double.NaN, zs?.getOrNull(k) ?: Double.NaN, 0.0)
                            types[(i.toLong() shl 32) or t.toLong()] = "minecraft:arrow"
                            if (spawns.size > SIB_MAX_IDS) throw Malformed("too many mobs")
                        }
                    }
                    // Lives: spawns (and how long, "life") and gone.
                    if (of == "spawn" || of == "gone") {
                        val ts = nums(c["t"] ?: throw Malformed("t"), n); val ids = nums(c["id"] ?: throw Malformed("id"), n)
                        if (of == "gone") ts.indices.forEach { k -> val t = ts[k]?.toLong() ?: return@forEach; val i = ids.getOrNull(k)?.toInt() ?: return@forEach; gones.getOrPut(i) { ArrayList() } += t }
                        else {
                            val life = c["life"]?.let { nums(it, n) }; val back = c["b"]?.let { nums(it, n) }; val v = c["v"]?.let { present(it, n) }
                            val xs = c["x"]?.let { nums(it, n) }; val ys = c["y"]?.let { nums(it, n) }; val zs = c["z"]?.let { nums(it, n) }
                            val ty = c["type"]?.let { if (tab2) tabled(it, n, typeTable, if (valuesOk) values?.lists?.get("type") else null) else strs(it, n) }
                            for (k in ts.indices) {
                                val t = ts[k] ?: continue; val i = ids.getOrNull(k)?.toInt() ?: continue
                                if (v?.getOrNull(k) == true) continue // (a projectile)
                                spawns.getOrPut(i) { ArrayList() } += doubleArrayOf(t, life?.getOrNull(k) ?: -1.0, xs?.getOrNull(k) ?: Double.NaN, ys?.getOrNull(k) ?: Double.NaN, zs?.getOrNull(k) ?: Double.NaN, back?.getOrNull(k) ?: 0.0)
                                ty?.getOrNull(k)?.let { types[(i.toLong() shl 32) or t.toLong()] = it }
                                if (spawns.size > SIB_MAX_IDS) throw Malformed("too many mobs")
                            }
                        }
                    }
                }
                "proj" -> {
                    val l = obj(line)
                    val n = 1 + (l["arcs"]?.let { arr(it).size() } ?: 0) + (if (l.has("s")) 1 else 0) + (if (l.has("g")) 1 else 0)
                    count(int(l["id"]), n)
                }
                else -> if (kind in ENTITY_KINDS || (kind == "eq" && line.contains("\"id\":")))
                    ID.find(line)?.groupValues?.get(1)?.let { count(it.toIntOrNull() ?: throw Malformed("id"), 1) }
            }
        }
    }
    if (maxT < 0) maxT = 0
    // A mob can't have more than about one line a tick: a count past the ticks the recording
    // spans is made up, and capped there so it can't win every mob.
    val span = if (minT > maxT) 0L else maxT - minT + 1
    val counts = out.mapValues { (_, n) -> minOf(n.toLong(), span).toInt() }
    // When it had each player: from a row until their "pgone" (rows only come when something changed).
    val players = seen.mapValues { (_, events) ->
        val ranges = ArrayList<LongRange>()
        var from: Long? = null
        for (e in events.sortedBy { if (it < 0) -it - 1 else it }) {
            if (e >= 0) { if (from == null) from = e }
            else { val t = -e - 1; from?.let { ranges += it until t }; from = null }
        }
        from?.let { ranges += it..maxT }
        ranges
    }
    // Each mob's lives: from a spawn till its "life" ran out, its gone or the next spawn.
    val lives = HashMap<Int, List<SibLife>>()
    for ((id, list) in spawns) {
        val gs = gones[id]?.sorted() ?: emptyList()
        var type: String? = null
        lives[id] = list.sortedBy { it[0] }.let { ss -> ss.mapIndexed { k, s ->
            val t = s[0].toLong()
            type = types[(id.toLong() shl 32) or t] ?: if (s[5] > 0) type else null
            val next = ss.getOrNull(k + 1)?.get(0)?.toLong() ?: Long.MAX_VALUE
            val end = if (s[1] > 0) t + s[1].toLong() - 1 else gs.firstOrNull { it >= t && it <= next } ?: minOf(next, maxT)
            SibLife(t, end, s[2], s[3], s[4], type)
        } }
    }
    // Relative rows: from their life's spawn (to a fifth of a block, as MobPlan sends them).
    if (relative.isNotEmpty()) {
        val sorted = spawns.mapValues { (_, l) -> l.sortedBy { it[0] } }
        for (r in relative) {
            val s = sorted[r[0].toInt()]?.lastOrNull { it[0] <= r[1] } ?: continue
            if (s[2].isNaN() || s[3].isNaN() || s[4].isNaN()) continue
            mobs.getOrPut(r[0].toInt()) { Track() }.add(r[1].toLong(), r[2] + MobPlan.fifthOf(s[2]), r[3] + MobPlan.fifthOf(s[3]), r[4] + MobPlan.fifthOf(s[4]))
        }
    }
    for (tr in mobs.values) tr.sort()
    for (tr in playerAt.values) tr.sort()
    return Sibling(self, startMs, counts, players, blocks.changes(), mobs, lives, playerAt, lerp)
}

/** A number, or null. */
private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.asDouble

/** A run-length column of a field that goes by number (Stretch.tables, "tab2") as strings, at most [n]; [table] the run's list so far, [cat] the catalogue's. */
private fun tabled(col: JsonElement, n: Int, table: MutableList<String?>, cat: List<JsonElement>?): List<String?> {
    val out = ArrayList<String?>()
    fun numbered(q: Long) = if (q >= 0) table.getOrNull(q.toInt()) else cat?.getOrNull((-1 - q).toInt())?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString }
    // (every row a number: written as numbers, not runs)
    if (col is JsonArray) return nums(col, n).map { q -> q?.takeIf { it == Math.floor(it) }?.let { numbered(it.toLong()) } }
    val r = (col as? JsonObject)?.get("r") as? JsonArray ?: throw Malformed("column")
    var i = 0
    while (i + 1 < r.size() && out.size < n) {
        val v = r[i]; val k = long(r[i + 1])
        if (k < 0) throw Malformed("run")
        for (j in 0 until minOf(k, (n - out.size).toLong())) out += when {
            v is JsonPrimitive && v.isNumber -> numbered(long(v))
            v is JsonPrimitive && v.isString -> v.asString.take(64).also { if (it.isNotEmpty() && table.size < SIB_MAX_IDS) table += it } // ("" isn't numbered)
            else -> null
        }
        i += 2
    }
    return out
}

/** A column (Stretch.column) as numbers, at most [n]: null where a row hasn't one. */
private fun nums(col: JsonElement, n: Int): List<Double?> {
    val out = ArrayList<Double?>()
    if (col is JsonArray) {
        if (col.size() == 0) return out
        val dec = long(col[0]).also { if (it !in 0..8) throw Malformed("decimals") }
        val scale = Math.pow(10.0, dec.toDouble())
        var q = 0L
        for (i in 1 until col.size()) { if (out.size >= n) break; q += long(col[i]); out += q / scale }
        return out
    }
    val r = arr((col as? JsonObject ?: throw Malformed("column"))["r"])
    var i = 0
    while (i + 1 < r.size() && out.size < n) {
        val v = num(r[i]); val k = long(r[i + 1])
        if (k < 0) throw Malformed("run")
        repeat(minOf(k, (n - out.size).toLong()).toInt()) { out += v }
        i += 2
    }
    return out
}

/** A "flat" cols line's mobs (players): each one's key, the lines it has a row in and its rows' x, y and z. */
private class FlatRows(val key: JsonElement, val lines: List<Int>, val x: List<Double?>, val y: List<Double?>, val z: List<Double?>)
private fun flatRows(l: JsonObject, lineCount: Int): List<FlatRows> {
    val keys = arr(l["keys"]); val ns = arr(l["n"]); val li = arr(l["li"]); val sz = arr(l["sz"]); val cols = arr(l["cols"])
    if (keys.size() != ns.size()) throw Malformed("keys")
    if (li.size() != sz.size() || li.size() > SIB_MAX_ROWS) throw Malformed("rows")
    // x, y, z: number columns, differences within each one's (its first as it is)
    val xyz = (0..2).map { c -> (cols.getOrNull(c) as? JsonArray)?.takeIf { it.size() > 0 } }
    val scale = xyz.map { col -> col?.let { Math.pow(10.0, long(it[0]).also { d -> if (d !in 0..8) throw Malformed("decimals") }.toDouble()) } }
    val ptr = IntArray(3) { 1 }
    val out = ArrayList<FlatRows>()
    var row = 0
    for (k in 0 until keys.size()) {
        val n = long(ns[k]).also { if (it < 0 || row + it > li.size()) throw Malformed("rows") }.toInt()
        val lines = ArrayList<Int>(n); val vals = List(3) { ArrayList<Double?>(n) }
        val q = LongArray(3); val started = BooleanArray(3)
        var at = 0L
        for (j in 0 until n) {
            val step = long(li[row])
            if (step < 0 || (j > 0 && step == 0L)) throw Malformed("line order")
            at = if (j == 0) step else at + step
            if (at >= lineCount) throw Malformed("line $at of $lineCount")
            lines += at.toInt()
            val size = long(sz[row]).also { if (it < 1 || it > 64) throw Malformed("row size") }
            for (c in 0..2) {
                val col = xyz[c]
                if (size <= c + 1 || col == null || ptr[c] >= col.size()) { vals[c] += null; continue }
                val d = long(col[ptr[c]++])
                q[c] = if (started[c]) q[c] + d else d; started[c] = true
                vals[c] += q[c] / scale[c]!!
            }
            row++
        }
        out += FlatRows(keys[k], lines, vals[0], vals[1], vals[2])
    }
    return out
}
private fun JsonArray.getOrNull(i: Int): JsonElement? = if (i in 0 until size()) get(i) else null

/** Which rows of a run-length column have a value. */
private fun present(col: JsonElement, n: Int): List<Boolean> {
    val out = ArrayList<Boolean>()
    val r = (col as? JsonObject)?.get("r") as? JsonArray ?: return List(n) { false }
    var i = 0
    while (i + 1 < r.size() && out.size < n) { val k = long(r[i + 1]); if (k < 0) throw Malformed("run"); repeat(minOf(k, (n - out.size).toLong()).toInt()) { out += r[i] !is JsonNull }; i += 2 }
    return out
}

/** A run-length column as strings, at most [n]: null where a row hasn't one (or it isn't a string). */
private fun strs(col: JsonElement, n: Int): List<String?> {
    val out = ArrayList<String?>()
    val r = (col as? JsonObject)?.get("r") as? JsonArray ?: return List(n) { null }
    var i = 0
    while (i + 1 < r.size() && out.size < n) {
        val v = (r[i] as? JsonPrimitive)?.takeIf { it.isString }?.asString?.take(64); val k = long(r[i + 1])
        if (k < 0) throw Malformed("run")
        repeat(minOf(k, (n - out.size).toLong()).toInt()) { out += v }
        i += 2
    }
    return out
}

/** A whole-number column (Stretch.column) as its values, at most [n]; null (a line without the field) left out. */
private fun longs(col: JsonElement, n: Int): List<Long> {
    val out = ArrayList<Long>()
    if (col is JsonArray) {
        if (col.size() == 0 || long(col[0]) != 0L) throw Malformed("not whole numbers")
        var q = 0L
        for (i in 1 until col.size()) { if (out.size >= n) break; q += long(col[i]); out += q }
        return out
    }
    val r = arr((col as? JsonObject ?: throw Malformed("column"))["r"])
    var i = 0
    while (i + 1 < r.size() && out.size < n) {
        val v = r[i]; val k = long(r[i + 1])
        if (k < 0) throw Malformed("run")
        val x = if (v is JsonNull) null else long(v)
        for (j in 0 until minOf(k, (n - out.size).toLong())) if (x != null) out += x else break
        i += 2
    }
    return out
}

/**
 * A party member's recording's lines, with [SIB_MAX_LINE] chars a line, [SIB_MAX_LINES] lines
 * and [SIB_MAX_CHARS] in all at most (a few KB of xz or gzip can unpack to gigabytes).
 */
private fun capped(r: BufferedReader): Sequence<String> = sequence {
    val buf = CharArray(1 shl 16)
    val sb = StringBuilder()
    var chars = 0L
    var lines = 0
    fun line(): String { if (++lines > SIB_MAX_LINES) throw Malformed("too many lines"); if (sb.endsWith('\r')) sb.setLength(sb.length - 1); return sb.toString().also { sb.setLength(0) } }
    while (true) {
        val n = r.read(buf)
        if (n < 0) break
        chars += n
        if (chars > SIB_MAX_CHARS) throw Malformed("too big")
        var from = 0
        for (i in 0 until n) if (buf[i] == '\n') { sb.appendRange(buf, from, i); from = i + 1; yield(line()) }
        sb.appendRange(buf, from, n)
        if (sb.length > SIB_MAX_LINE) throw Malformed("line too long")
    }
    if (sb.isNotEmpty()) yield(line())
}
