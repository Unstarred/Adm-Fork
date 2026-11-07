package com.engineerclient.adr

import com.google.gson.JsonArray

object Templates {

    private val ROT = listOf("SOUTH", "WEST", "NORTH", "EAST")
    private val DIR = listOf("north", "east", "south", "west")
    private fun inGrid(x: Int, z: Int) = x >= -200 && x < -8 && z >= -200 && z < -8
    private fun gap(x: Int, z: Int) = (x + 200) % 32 == 31 || (z + 200) % 32 == 31

    fun turn(s: String, q0: Int): String {
        val q = ((q0 % 4) + 4) % 4
        if (q == 0 || !s.contains('[')) return s
        val at = s.indexOf('[')
        val props = HashMap<String, String>()
        fun step(d: String) = DIR[(DIR.indexOf(d) - q + 8) % 4]
        for (p in s.substring(at + 1, s.length - 1).split(',')) {
            val kv = p.split('=')
            val k = kv[0]; val v = kv.getOrElse(1) { "undefined" }
            when {
                k == "facing" && v in DIR -> props[k] = step(v)
                k in DIR -> props[step(k)] = v
                k == "axis" && q % 2 == 1 && v != "y" -> props[k] = if (v == "x") "z" else "x"
                k == "rotation" -> props[k] = v.toIntOrNull()?.let { ((it - 4 * q + 64) % 16).toString() } ?: "NaN"
                else -> props[k] = v
            }
        }
        return s.substring(0, at) + "[" + props.keys.sorted().joinToString(",") { it + "=" + props[it] } + "]"
    }

    class Framed(val space: String, val a: Int, val y: Int, val b: Int, val state: String)

    class Frame(rooms: JsonArray?) {
        private class Room(val space: String, val q: Int, val x0: Int, val z0: Int, val w: Int, val d: Int)
        private val byTile = HashMap<Long, Room>()
        private val bySpace = HashMap<String, Room>()
        private val count = HashMap<String, Int>()

        init {
            for (e in rooms ?: JsonArray()) runCatching {
                val r = e.asJsonArray
                val name = r[0].asString
                val q = ROT.indexOf(r[3].asString)
                val tiles = r[5].asJsonArray.map { it.asJsonArray[0].asInt to it.asJsonArray[1].asInt }
                val key = r.takeIf { it.size() > 8 }?.get(8)?.takeIf { it.isJsonPrimitive }?.asString
                if (name.isEmpty() || q < 0 || tiles.isEmpty()) return@runCatching
                val space = if (!key.isNullOrEmpty()) key.split('|').filterIndexed { i, _ -> i != 1 }.joinToString("|") else name
                val xs = tiles.map { it.first }; val zs = tiles.map { it.second }
                val f = Room(space, q, -200 + 32 * xs.min(), -200 + 32 * zs.min(), 32 * (xs.max() - xs.min()) + 31, 32 * (zs.max() - zs.min()) + 31)
                for ((tx, tz) in tiles) byTile[tile(tx, tz)] = f
                count.merge(space, 1, Int::plus)
                bySpace[space] = f
            }
        }
        private fun tile(tx: Int, tz: Int) = (tx.toLong() shl 32) or (tz.toLong() and 0xffffffffL)

        fun fwd(x: Int, y: Int, z: Int, s: String): Framed? {
            if (!inGrid(x, z)) return Framed("boss", x, y, z, s)
            if (gap(x, z)) return null
            val f = byTile[tile(Math.floorDiv(x + 200, 32), Math.floorDiv(z + 200, 32))] ?: return null
            if (count[f.space] != 1) return null
            var a = x - f.x0; var b = z - f.z0; var w = f.w; var d = f.d
            repeat(f.q) { val na = b; b = w - 1 - a; a = na; val nw = d; d = w; w = nw }
            return Framed(f.space, a, y, b, turn(s, f.q))
        }

        fun inv(space: String, a0: Int, y: Int, b0: Int, s: String): Pair<IntArray, String>? {
            if (space == "boss") return intArrayOf(a0, y, b0) to s
            val f = bySpace[space] ?: return null
            if (count[space] != 1) return null
            var w = if (f.q % 2 == 1) f.d else f.w; var d = if (f.q % 2 == 1) f.w else f.d
            var a = a0; var b = b0
            repeat(f.q) { val na = d - 1 - b; b = a; a = na; val nw = d; d = w; w = nw }
            return intArrayOf(a + f.x0, y, b + f.z0) to turn(s, -f.q)
        }
    }

    private fun short(s: String) = if (s.startsWith("minecraft:")) s.substring(10) else s
    private fun long(s: String) = if (s.contains(':')) s else "minecraft:$s"

    class Change(val t: Int, val x: Int, val y: Int, val z: Int, val state: String)

    class Piece(val t: Int, val key: String, val idx: IntArray) { var id = -1 }

    fun pieces(changes: List<Change>, frame: Frame): List<Piece> {
        val fr = changes.map { frame.fwd(it.x, it.y, it.z, it.state) }
        val out = ArrayList<Piece>()
        var i = 0
        while (i < changes.size) {
            var j = i
            while (j < changes.size && changes[j].t == changes[i].t) j++
            val ids = (i until j).filter { fr[it] != null }
            val at = HashMap<Triple<Int, Int, Int>, Int>()
            ids.forEachIndexed { q, k -> at.putIfAbsent(Triple(changes[k].x, changes[k].y, changes[k].z), q) }
            val par = IntArray(ids.size) { it }
            fun find(q0: Int): Int { var q = q0; while (par[q] != q) { par[q] = par[par[q]]; q = par[q] }; return q }
            ids.forEachIndexed { q, k ->
                val c = changes[k]
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    val a = at[Triple(c.x + dx, c.y + dy, c.z + dz)] ?: continue
                    if (fr[ids[a]]!!.space != fr[k]!!.space) continue
                    val ra = find(a); val rq = find(q)
                    if (ra != rq) par[maxOf(ra, rq)] = minOf(ra, rq)
                }
            }
            val groups = LinkedHashMap<Int, MutableList<Int>>()
            ids.forEachIndexed { q, k -> groups.getOrPut(find(q)) { ArrayList() } += k }
            for (l in groups.values) {
                l.sortWith(compareBy<Int>({ fr[it]!!.a }, { fr[it]!!.y }, { fr[it]!!.b }, { it }))
                var a = 0; var y = 0; var b = 0; var st: String? = null
                val key = StringBuilder(fr[l[0]]!!.space).append('\n')
                l.forEachIndexed { n, k ->
                    val f = fr[k]!!
                    if (n > 0) key.append(' ')
                    key.append(f.a - a).append(',').append(f.y - y).append(',').append(f.b - b)
                    if (f.state != st) key.append('=').append(short(f.state))
                    a = f.a; y = f.y; b = f.b; st = f.state
                }
                out += Piece(changes[i].t, key.toString(), l.toIntArray())
            }
            i = j
        }
        return out
    }

    fun keyCells(key: String): Pair<String, List<Pair<IntArray, String>>>? {
        val nl = key.indexOf('\n')
        if (nl < 0) return null
        var a = 0; var y = 0; var b = 0; var st: String? = null
        val cells = ArrayList<Pair<IntArray, String>>()
        for (c in key.substring(nl + 1).split(' ')) {
            val eq = c.indexOf('=')
            val d = (if (eq < 0) c else c.substring(0, eq)).split(',').map { it.toIntOrNull() ?: return null }
            if (d.size != 3) return null
            a += d[0]; y += d[1]; b += d[2]
            if (eq >= 0) st = long(c.substring(eq + 1))
            cells += intArrayOf(a, y, b) to (st ?: return null)
        }
        return key.substring(0, nl) to cells
    }

    fun pieceCells(key: String, frame: Frame, t: Int): List<Change>? {
        val (space, cells) = keyCells(key) ?: return null
        return cells.map { (p, s) -> val (w, st) = frame.inv(space, p[0], p[1], p[2], s) ?: return null; Change(t, w[0], w[1], w[2], st) }
    }

    fun streams(pieces: List<Piece>): List<List<Int>> {
        val par = IntArray(pieces.size) { it }
        fun find(i0: Int): Int { var i = i0; while (par[i] != i) { par[i] = par[par[i]]; i = par[i] }; return i }
        val owner = HashMap<String, Int>()
        pieces.forEachIndexed { i, p ->
            val (space, cells) = keyCells(p.key)!!
            for ((c, _) in cells) {
                val cell = space + "|" + c[0] + "," + c[1] + "," + c[2]
                val o = owner[cell]
                if (o == null) owner[cell] = i
                else { val a = find(i); val b = find(o); if (a != b) par[maxOf(a, b)] = minOf(a, b) }
            }
        }
        val g = LinkedHashMap<Int, MutableList<Int>>()
        pieces.indices.forEach { g.getOrPut(find(it)) { ArrayList() } += it }
        return g.values.toList()
    }

    class End(val boxes: List<IntArray>, val min: Int, val near: Int, val all: Boolean, val cells: List<IntArray>)

    fun ends(changes: List<Change>, ends: List<End>): List<Change> {
        var out = changes
        for (e in ends) {
            fun inBox(c: Change) = e.boxes.any { b -> c.x >= b[0] && c.x <= b[1] && c.y >= b[2] && c.y <= b[3] && c.z >= b[4] && c.z <= b[5] }
            val per = LinkedHashMap<Int, Int>()
            for (c in out) if (c.state == AIR && inBox(c)) per.merge(c.t, 1, Int::plus)
            var at: Int? = null; var n = 0
            for ((t, m) in per) if (m > n || (m == n && t < at!!)) { at = t; n = m }
            val tick = at ?: continue
            if (n < e.min) continue
            val cells = e.cells.map { Triple(it[0], it[1], it[2]) }.toHashSet()
            fun near(c: Change) = c.state == AIR && Math.abs(c.t - tick) <= e.near && Triple(c.x, c.y, c.z) in cells
            val gone = out.filter(::near).map { Triple(it.x, it.y, it.z) }.toHashSet()
            val all = e.all || gone.size * 2 >= cells.size
            out = out.filterNot(::near)
            if (all) out = out + e.cells.map { Change(tick, it[0], it[1], it[2], AIR) }
        }
        return out
    }
    private const val AIR = "minecraft:air"
}
