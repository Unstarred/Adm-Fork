package com.engineerclient.adr

import com.engineerclient.util.noFormatting
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.TreeMap

internal class MobPlan private constructor(
    val nothing: Set<Int>,
    val lives: Map<Int, List<Life>>,
    private val farHalf: Boolean,
    val players: Map<String, List<DoubleArray>>,
    val worn: Set<Int>,
    val pets: Set<Int>,
    val bridged: Set<Long>,
    private val bridgedGones: Set<Long>,
) {
    class Life(val id: Int, val index: Int, val spawn: Int, var gone: Int?, val type: String, val proj: Boolean) {
        val rows = ArrayList<DoubleArray>()
        var at = DoubleArray(3)
        var folded = false
        var named = false
        var sameSpot = false
        var spawnAt: DoubleArray? = null
    }

    fun rows(dropped: (Life) -> Boolean): TreeMap<Int, JsonArray> {
        val out = TreeMap<Int, JsonArray>()
        for (list in lives.values) {
            var last: DoubleArray? = null
            for (life in list) {
                if (life.id in nothing || dropped(life)) { last = null; continue }
                val at = spawnPlace(life)
                val l = last
                life.sameSpot = !life.proj && l != null && (0..2).all { Math.abs(l[it] - life.at[it]) <= SAME_SPOT }
                life.spawnAt = if (life.sameSpot) l else at
                last = life.spawnAt
                if (life.rows.isEmpty()) continue
                val o = anchor(life)
                for (r in thin(life.rows, life.id in pets, walks(life.type))) {
                    val rel = DoubleArray(3) { i -> tenth(Math.round(r[i + 1] / ROW_STEP) * ROW_STEP - o[i]).asDouble }
                    last = DoubleArray(3) { i -> rel[i] + o[i] }
                    out.getOrPut(r[0].toInt()) { JsonArray() }.add(JsonArray().apply {
                add(life.id); for (i in 0..2) add(tenth(rel[i])); add(angle(r[4]))
                if (!r[5].isNaN() && angle(r[5]) != angle(r[4])) add(angle(r[5]))
                    })
                }
            }
        }
        return out
    }

    fun isBridged(id: Int, t: Int) = key(id, t) in bridged
    fun isBridgedGone(id: Int, t: Int) = key(id, t) in bridgedGones

    fun lifeAt(id: Int, t: Int): Life? = lives[id]?.lastOrNull { it.spawn <= t }?.takeIf { it.gone == null || t <= it.gone!! }

    private fun spawnPlace(life: Life): DoubleArray = DoubleArray(3) { i ->
        val v = life.at[i]
        if (life.proj) v else if (life.type == STAND && life.id in worn) hundredth(v).asDouble else tenth(v).asDouble
    }

    private fun anchor(life: Life): DoubleArray = DoubleArray(3) { i -> fifthOf(life.spawnAt!![i]) }

    private fun thin(rows: List<DoubleArray>, rough: Boolean, walking: Boolean): List<DoubleArray> {
        val pts = ArrayList<DoubleArray>(rows.size * 2)
        for ((i, r) in rows.withIndex()) {
            if (i > 0) {
                val p = rows[i - 1]
                val gap = (r[0] - p[0]).toInt()
                if (gap >= 3 || (gap == 2 && !farHalf)) pts += p.copyOf().also { it[0] = r[0] - 1 }
            }
            pts += r
        }
        val far = BooleanArray(pts.size) { rough || farFromPlayers(pts[it]) }
        val keep = ArrayList<DoubleArray>()
        var k = 0
        keep += pts[0]
        while (k < pts.size - 1) {
            var m = k + 1
            while (m + 1 < pts.size && fits(pts, k, m + 1, far, walking)) m++
            keep += pts[m]; k = m
        }
        return keep
    }

    private val tracks = players.values.map { list -> list.sortedBy { it[0] } }

    private fun farFromPlayers(p: DoubleArray): Boolean {
        for (rows in tracks) {
            var lo = 0; var hi = rows.size - 1
            if (hi < 0 || rows[0][0] > p[0]) continue
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (rows[mid][0] <= p[0]) lo = mid else hi = mid - 1 }
            val q = rows[lo]
            if ((q[1] - p[1]) * (q[1] - p[1]) + (q[2] - p[2]) * (q[2] - p[2]) + (q[3] - p[3]) * (q[3] - p[3]) < FAR * FAR) return false
        }
        return true
    }

    private fun fits(pts: List<DoubleArray>, a: Int, c: Int, far: BooleanArray, walking: Boolean): Boolean {
        val pa = pts[a]; val pc = pts[c]
        val span = pc[0] - pa[0]
        for (j in a + 1 until c) {
            val pj = pts[j]
            val pos = if (far[j]) FAR_POS_TOL else POS_TOL
            val ang = if (far[j]) FAR_ANG_TOL else ANG_TOL
            val f = if (span > LERP_GAP) 0.0 else (pj[0] - pa[0]) / span
            for (i in 1..3) if (Math.abs(pa[i] + (pc[i] - pa[i]) * f - pj[i]) > pos) return false
            val walk = if (walking && span <= LERP_GAP) walkDir(pa, pc, span) else null
            for (i in 4..5) if (!pa[i].isNaN() && !pc[i].isNaN() && !pj[i].isNaN()) {
                val want = if (walk != null) walkYaw(pa[i], walk, pc[i], pj[0] - pa[0], span) else pa[i] + turn(pc[i] - pa[i]) * f
                if (Math.abs(turn(want - pj[i])) > (if (walking && !far[j]) WALK_ANG_TOL else ang)) return false
            }
        }
        return true
    }

    companion object {
        const val POS_TOL = 1.5
        const val ANG_TOL = 35.0
        const val SAME_SPOT = 0.5
        const val FAR = 20.0
        const val FAR_POS_TOL = 5.0
        const val FAR_ANG_TOL = 180.0
        private val HIDDEN = Regex("display|marker|interaction|area_effect")
        const val LERP_GAP = 60
        const val STAND = "minecraft:armor_stand"
        private val NOT_WALKING = Regex("armor_stand|item|tnt|end_crystal|minecart|falling_block|giant|boat|firework|experience|painting|leash|eye_of|arrow|skull|fireball|bobber|potion|snowball|egg|pearl|trident")
        fun walks(type: String) = type.isNotEmpty() && !NOT_WALKING.containsMatchIn(type)
        const val WALK_ANG_TOL = 45.0
        fun walkDir(a: DoubleArray, c: DoubleArray, n: Double): Double? {
            val dx = c[1] - a[1]; val dz = c[3] - a[3]
            return if (Math.hypot(dx, dz) / n < 0.04) null else Math.toDegrees(Math.atan2(-dx, dz))
        }
        private const val WALK_TURN = 3.0
        fun walkYaw(from: Double, dir: Double, to: Double, k: Double, n: Double): Double {
            val y = from + turn(dir - from) * Math.min(1.0, k / WALK_TURN)
            return if (n - k < WALK_TURN) y + turn(to - y) * (1 - (n - k) / WALK_TURN) else y
        }
        private const val INVISIBLE = 2

        fun turn(d: Double) = ((d % 360) + 540) % 360 - 180
        fun tenth(v: Double): JsonPrimitive = JsonPrimitive(BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it })
        fun hundredth(v: Double): JsonPrimitive = JsonPrimitive(BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it })
        fun angle(v: Double): JsonPrimitive = JsonPrimitive((Math.round(turn(v) / ANG_STEP) * ANG_STEP).toInt().let { if (it == -180) 180 else it })
        private const val ANG_STEP = 20.0
        private const val ROW_STEP = 0.5
        fun fifthOf(v: Double): Double = Math.round(v * 5) / 5.0
        private fun key(id: Int, t: Int) = (id.toLong() shl 32) or (t.toLong() and 0xffffffffL)
        private val NOT_LOOK = setOf("k", "t", "id", "x", "y", "z", "yaw", "headYaw", "name", "c")

        fun read(lines: Sequence<String>, skip: (Int) -> Boolean): MobPlan {
            return PlanReader(skip).read(lines)
        }

        private class PlanReader(private val skip: (Int) -> Boolean) {
            var farHalf = false
            val players = HashMap<String, MutableList<DoubleArray>>()
            val lives = HashMap<Int, MutableList<Life>>()
            val current = HashMap<Int, Life>()
            val spawnTicks = HashMap<Int, MutableSet<Int>>()
            val stands = HashMap<Int, Boolean>()
            val shows = HashSet<Int>()
            val wornIds = HashSet<Int>()
            val heldHead = HashSet<Int>()
            val looks = HashMap<Int, String>()
            val bridged = HashSet<Long>(); val bridgedGones = HashSet<Long>()
            val kindRe = Regex(""""k":"(\w+)"""")

            fun read(lines: Sequence<String>): MobPlan {
                for (line in lines) {
                    val kind = kindRe.find(line.take(24))?.groupValues?.get(1) ?: continue
                    if (kind != "spawn" && kind != "gone" && kind != "e" && kind != "stand" && kind != "eq" && kind != "name" && kind != "p" && kind != "meta") continue
                    val l = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    if (kind == "meta") { farHalf = (l["farHalf"]?.asInt ?: 0) > 0; continue }
                    val t = l["t"]?.asInt ?: continue
                    when (kind) {
                        "p" -> for (d in l.getAsJsonArray("d") ?: continue) {
                            val r = d.asJsonArray
                            if (r.size() < 4 || !r[1].isJsonPrimitive) continue
                            players.getOrPut(r[0].asString) { ArrayList() } += doubleArrayOf(t.toDouble(), r[1].asDouble, r[2].asDouble, r[3].asDouble)
                        }
                        "e" -> for (d in l.getAsJsonArray("d") ?: continue) {
                            val r = d.asJsonArray
                            val id = r[0].asInt
                            val life = current[id] ?: continue
                            life.rows += doubleArrayOf(t.toDouble(), r[1].asDouble, r[2].asDouble, r[3].asDouble, r[4].asDouble, if (r.size() > 5) r[5].asDouble else Double.NaN)
                        }
                        "spawn" -> spawn(l, t)
                        "gone" -> {
                            val id = l["id"]?.asInt ?: continue
                            val life = current.remove(id) ?: continue
                            life.gone = t
                        }
                        "stand" -> {
                            val id = l["id"]?.asInt ?: continue
                            val f = l["f"]?.asInt ?: 0
                            stands[id] = (stands[id] ?: true) && (f and INVISIBLE) != 0
                        }
                        "eq" -> {
                            val id = l["id"]?.takeIf { !it.isJsonNull }?.asInt ?: continue
                            val worn = l.getAsJsonArray("eq")?.any { it is JsonPrimitive && it.asString.isNotEmpty() } == true
                            if (worn || !l["headTex"]?.takeIf { !it.isJsonNull }?.asString.isNullOrEmpty()) { shows += id; wornIds += id }
                            if ((l.getAsJsonArray("eq")?.firstOrNull() as? JsonPrimitive)?.asString == "minecraft:player_head") heldHead += id
                        }
                        "name" -> {
                            val id = l["id"]?.asInt ?: continue
                            if (!(l["name"]?.asString.isNullOrEmpty() && l["c"]?.asString.isNullOrEmpty())) { shows += id; current[id]?.named = true }
                        }
                    }
                }
                for (list in lives.values) for (life in list) {
                    val g = life.gone ?: continue
                    life.folded = !life.proj && spawnTicks[life.id]?.contains(g) != true
                }
                val nothing = lives.filter { (id, list) -> list.all { HIDDEN.containsMatchIn(it.type) } || (list.all { it.type == STAND } && stands[id] == true && id !in shows) }.keys
                val named = HashSet<Int>().apply { for ((id, list) in lives) if (list.any { it.named }) add(id) }
                val pets = heldHead.filter { id -> id !in named && lives[id]?.all { it.type == STAND } == true }.toSet()
                return MobPlan(nothing, lives, farHalf, players, wornIds, pets, bridged, bridgedGones)
            }

            private fun spawn(l: JsonObject, t: Int) {
                val id = l["id"]?.asInt ?: return
                if (skip(id)) return
                current[id]?.let { if (it.gone == null) it.gone = t }
                val list = lives.getOrPut(id) { ArrayList() }
                val type = l["type"]?.asString ?: ""
                val look = JsonObject().apply { for ((k, v) in l.entrySet()) if (k !in NOT_LOOK) add(k, v) }.toString()
                val prev = list.lastOrNull()
                val pg = prev?.gone
                if (prev != null && pg != null && pg < t && current[id] == null && !prev.proj && !l.has("v") && prev.type == type && looks[id] == look) {
                    bridged += key(id, t); bridgedGones += key(id, pg)
                    prev.gone = null; current[id] = prev
                    prev.rows += doubleArrayOf(t.toDouble(), l["x"]?.asDouble ?: 0.0, l["y"]?.asDouble ?: 0.0, l["z"]?.asDouble ?: 0.0, l["yaw"]?.asDouble ?: 0.0, l["headYaw"]?.asDouble ?: Double.NaN)
                    if (!(l["name"]?.asString.isNullOrEmpty() && l["c"]?.asString.isNullOrEmpty())) { prev.named = true; if (type == STAND) shows += id }
                    return
                }
                looks[id] = look
                val life = Life(id, list.size, t, null, type, l.has("v"))
                life.at = doubleArrayOf(l["x"]?.asDouble ?: 0.0, l["y"]?.asDouble ?: 0.0, l["z"]?.asDouble ?: 0.0)
                list += life; current[id] = life
                spawnTicks.getOrPut(id) { HashSet() } += t
                if (!(l["name"]?.asString.isNullOrEmpty() && l["c"]?.asString.isNullOrEmpty())) { life.named = true; if (type == STAND) shows += id }
            }
        }
    }
}

internal class MobWriter(private val plan: MobPlan, private val dropped: (MobPlan.Life) -> Boolean) {
    private val rows = plan.rows(dropped)
    private val spawned = HashMap<Int, Int>()
    private class State(var spawn: JsonObject, var eq: String? = null, var stand: String? = null, var name: String? = null)
    private val now = HashMap<Int, State>()
    private val left = HashMap<Int, State>()
    private var pending: JsonObject? = null

    private val kindRe = Regex("""^\{"k":"(\w+)","t":(\d+)""")

    fun put(line: String, out: (String) -> Unit) {
        val head = kindRe.find(line)
        val kind = head?.groupValues?.get(1)
        val t = head?.groupValues?.get(2)?.toInt()
        if (t != null) inject(t - 1, out)
        when (kind) {
            "e" -> { flush(out); return }
            "spawn", "gone", "stand", "eq", "name", "tag", "frame" -> {}
            else -> { flush(out); out(line); return }
        }
        val l = JsonParser.parseString(line).asJsonObject
        val id = l["id"]?.takeIf { !it.isJsonNull }?.asInt
        if (id == null) { flush(out); out(line); return }
        if (id in plan.nothing) return
        if (kind == "spawn" && l.has("v")) { flush(out); spawned.merge(id, 1, Int::plus); out(line); return }
        if (kind == "spawn" && plan.isBridged(id, t!!)) {
            flush(out)
            val life = plan.lifeAt(id, t)
            if (life == null || dropped(life)) return
            val c = l["c"]?.takeIf { !it.isJsonNull }?.asString
            val name = l["name"]?.takeIf { !it.isJsonNull }?.asString ?: ""
            val key = "$name\u0000${c ?: ""}"
            val st = now[id]
            if (st == null || st.name == key) return
            st.name = key
            val o = JsonObject().apply { addProperty("k", "name"); addProperty("t", t); addProperty("id", id); addProperty("name", name); if (c != null) addProperty("c", c) }
            plainName(o)
            out(o.toString()); return
        }
        if (kind == "spawn") {
            flush(out)
            val n = spawned.merge(id, 1, Int::plus)!! - 1
            val life = plan.lives[id]?.getOrNull(n)
            if (life == null) { out(line); return }
            if (dropped(life)) { now.remove(id); left.remove(id); return }
            pending = spawn(l, life, id)
            return
        }
        if (kind == "gone" && plan.isBridgedGone(id, t!!)) return
        val life = plan.lifeAt(id, t!!)
        if (life == null || dropped(life)) return
        putLifeLine(kind, line, l, id, t, life, out)
    }

    private fun putLifeLine(kind: String?, line: String, l: JsonObject, id: Int, t: Int, life: MobPlan.Life, out: (String) -> Unit) {
        val st = now[id]
        when (kind) {
            "gone" -> {
                flush(out)
                now.remove(id)?.let { left[id] = it }
                if (!life.folded) out(line)
                return
            }
            "stand" -> {
                val key = JsonArray().apply { add(l["f"] ?: JsonNull.INSTANCE); add(l["pose"] ?: JsonNull.INSTANCE) }.toString()
                if (st != null && st.stand == key) return
                st?.stand = key
                val p = pending
                if (p != null && p["id"].asInt == id && p["t"].asInt == t) { l["f"]?.let { p.add("f", it) }; l["pose"]?.let { p.add("pose", it) }; return }
                flush(out); out(line); return
            }
            "eq" -> {
                flush(out)
                val key = JsonArray().apply { add(l["eq"] ?: JsonNull.INSTANCE); add(l["headTex"] ?: JsonNull.INSTANCE) }.toString()
                if (st != null && st.eq == key) return
                st?.eq = key
                out(line); return
            }
            "name" -> {
                flush(out)
                val c = l["c"]?.takeIf { !it.isJsonNull }?.asString
                val name = l["name"]?.takeIf { !it.isJsonNull }?.asString ?: ""
                val key = "$name\u0000${c ?: ""}"
                if (st != null && st.name == key) return
                st?.name = key
                plainName(l)
                out(l.toString()); return
            }
            else -> { flush(out); out(line) }
        }
    }

    fun end(out: (String) -> Unit) { flush(out); inject(Int.MAX_VALUE, out) }

    private fun inject(upTo: Int, out: (String) -> Unit) {
        while (rows.isNotEmpty() && rows.firstKey() <= upTo) {
            flush(out)
            val (t, d) = rows.pollFirstEntry()
            out(JsonObject().apply { addProperty("k", "e"); addProperty("t", t); add("d", d) }.toString())
        }
    }

    private fun flush(out: (String) -> Unit) { pending?.let { out(it.toString()) }; pending = null }

    private fun spawn(l: JsonObject, life: MobPlan.Life, id: Int): JsonObject {
        val fields = JsonObject().apply { for ((k, v) in l.entrySet()) if (k !in PLACE && k != "name" && k != "c") add(k, v) }
        val name = l["name"]?.takeIf { !it.isJsonNull }?.asString ?: ""
        val c = l["c"]?.takeIf { !it.isJsonNull }?.asString
        val before = left.remove(id)
        val o = JsonObject()
        o.add("k", l["k"]); o.add("t", l["t"]); o.add("id", l["id"])
        val key = "$name\u0000${c ?: ""}"
        val back = before != null && before.spawn == fields && before.name == key
        if (back) o.addProperty("b", 1)
        else for ((k, v) in l.entrySet()) if (k !in PLACE) o.add(k, v)
        val fine = life.type == MobPlan.STAND && id in plan.worn
        if (!life.sameSpot) for (k in listOf("x", "y", "z")) l[k]?.takeIf { it.isJsonPrimitive }?.let { o.add(k, if (fine) MobPlan.hundredth(it.asDouble) else MobPlan.tenth(it.asDouble)) }
        val yaw = l["yaw"]?.takeIf { it.isJsonPrimitive }?.asDouble
        val head = l["headYaw"]?.takeIf { it.isJsonPrimitive }?.asDouble
        o.remove("yaw"); o.remove("headYaw")
        if (yaw != null) o.add("yaw", MobPlan.angle(yaw))
        if (head != null && (yaw == null || MobPlan.angle(head) != MobPlan.angle(yaw))) o.add("headYaw", MobPlan.angle(head))
        if (life.folded) o.addProperty("life", life.gone!! - life.spawn + 1)
        if (!back) plainName(o)
        now[id] = if (back) State(fields, before!!.eq, before.stand, key) else State(fields, name = key)
        return o
    }

    companion object {
        private val PLACE = setOf("k", "t", "id", "x", "y", "z", "yaw", "headYaw")
        fun plainName(o: JsonObject) {
            val c = o["c"]?.takeIf { !it.isJsonNull }?.asString ?: return
            if (o["name"]?.takeIf { !it.isJsonNull }?.asString == c.noFormatting()) o.remove("name")
        }
    }
}

internal object ProjPack {
    private const val VIA_TOL = 1.0
    private const val MAX_VIAS = 4
    private const val FLIGHT_MAX = 600

    fun line(p: UploadPacker.Proj): JsonObject {
        val s = p.spawn
        val type = s["type"]?.asString ?: "minecraft:arrow"
        val arrow = !(s.has("i") || s.has("a"))
        val x0 = s["x"].asDouble; val y0 = s["y"].asDouble; val z0 = s["z"].asDouble
        val seen = ArrayList<DoubleArray>()
        seen += doubleArrayOf(0.0, x0, y0, z0)
        var last = doubleArrayOf(0.0, x0, y0, z0) + (s.getAsJsonArray("v")?.map { it.asDouble }?.toDoubleArray() ?: doubleArrayOf(0.0, 0.0, 0.0))
        for (a in p.arcs) {
            val arc = a.asJsonArray
            if (arc.size() < 7) continue
            val f = DoubleArray(7) { arc[it].asDouble }
            if (f[0] > seen.last()[0]) seen += f.copyOf(4)
            last = f
        }
        val stuck = p.stuck
        val end: DoubleArray = if (stuck != null && stuck.size() >= 4) doubleArrayOf(stuck[0].asDouble, stuck[1].asDouble, stuck[2].asDouble, stuck[3].asDouble)
        else fly(last, ((p.gone ?: (p.t0 + 100)) - p.t0).toDouble(), s["a"]?.asDouble, s["i"]?.asDouble)
        val path = ArrayList<DoubleArray>()
        path += seen[0]
        if (end[0] > 0) path += end
        for (n in 0 until MAX_VIAS) {
            if (path.size < 2) break
            var worst: DoubleArray? = null; var wd = VIA_TOL
            for (q in seen) { if (q[0] <= 0 || q[0] >= path.last()[0]) continue; val d = off(path, q, arrow); if (d > wd) { wd = d; worst = q } }
            if (worst == null) break
            path += worst; path.sortBy { it[0] }
        }
        if (path.size > 1) for (m in p.moves) {
            val a = m.asJsonArray
            if (a.size() < 4) continue
            val q = doubleArrayOf(a[0].asDouble, a[1].asDouble, a[2].asDouble, a[3].asDouble)
            val prev = path.last()
            if (q[0] <= prev[0] || (1..3).all { Math.abs(Math.round((q[it] - path[0][it]) * 10) - Math.round((prev[it] - path[0][it]) * 10)) <= 1 }) continue
            path += q
        }
        val o = JsonObject()
        o.addProperty("k", "pj"); o.add("t", s["t"]); o.add("id", s["id"])
        if (type != "minecraft:arrow") o.addProperty("type", type)
        o.add("x", MobPlan.tenth(x0)); o.add("y", MobPlan.tenth(y0)); o.add("z", MobPlan.tenth(z0))
        if (path.size > 1) o.add("p", JsonArray().apply {
            for (q in path.drop(1)) { add(q[0].toInt()); for (i in 1..3) add(Math.round((q[i] - path[0][i]) * 10).toInt()) }
        })
        if (!arrow) o.addProperty("pw", 1)
        if (stuck != null) o.addProperty("s", 1)
        p.gone?.let { o.addProperty("g", it - p.t0) }
        return o
    }

    private fun off(path: List<DoubleArray>, q: DoubleArray, arrow: Boolean): Double {
        var best = Double.MAX_VALUE
        for (k in 0 until path.size - 1) {
            val a = path[k]; val b = path[k + 1]
            for (i in 0..40) {
                val tt = a[0] + (b[0] - a[0]) * i / 40
                val p = at(a, b, tt, arrow)
                best = minOf(best, Math.hypot(Math.hypot(p[0] - q[1], p[1] - q[2]), p[2] - q[3]))
            }
        }
        return best
    }

    fun at(a: DoubleArray, b: DoubleArray, tt: Double, arrow: Boolean): DoubleArray {
        val span = (b[0] - a[0]).takeIf { it > 0 } ?: 1.0
        val f = (tt - a[0]) / span
        val g = if (arrow) 0.025 * (tt - a[0]) * (b[0] - tt) else 0.0
        return doubleArrayOf(a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f + g, a[3] + (b[3] - a[3]) * f)
    }

    private fun fly(f: DoubleArray, until: Double, accel: Double?, inertia: Double?): DoubleArray {
        var x = f[1]; var y = f[2]; var z = f[3]; var vx = f[4]; var vy = f[5]; var vz = f[6]
        val n = minOf(maxOf(0, (until - f[0]).toInt()), FLIGHT_MAX)
        for (k in 0 until n) {
            if (inertia != null) {
                val len = Math.sqrt(vx * vx + vy * vy + vz * vz)
                val a = accel ?: 0.0
                if (len > 1e-9) { vx += vx / len * a; vy += vy / len * a; vz += vz / len * a }
                vx *= inertia; vy *= inertia; vz *= inertia
            }
            x += vx; y += vy; z += vz
            if (inertia == null) { vx *= 0.99; vy = vy * 0.99 - 0.05; vz *= 0.99 }
        }
        return doubleArrayOf(f[0] + n, x, y, z)
    }
}
