package com.engineerclient.adr

import com.engineerclient.adr.UploadPacker.BlockTemplates
import com.engineerclient.adr.UploadPacker.HEAD
import com.engineerclient.adr.UploadPacker.ID
import com.engineerclient.adr.UploadPacker.NL
import com.engineerclient.adr.UploadPacker.int
import com.engineerclient.adr.UploadPacker.log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.BufferedWriter
import java.math.BigDecimal

/**
 * Everything timed, a stretch of [COLS_TICKS] at a time, as fewer and denser lines, each stretch's
 * ahead of the rest of it (the viewer turns them back into the lines they were):
 *  - mob ("e") and player ("p") lines as "cols": per mob/player its values one after another
 *    as differences (mob ids too, in order);
 *  - mob events (spawn, tag, stand, frame, eq, name, gone), the server tick, map and swing
 *    lines, projectiles and the lines that come one at a time ([SINGLES]; a window's, [UI],
 *    all in one) as "grp": per kind, a column per field the same way;
 *  - block changes as "blocks": each tick's sorted by place, every one a difference from the one
 *    before;
 *  - camera frames as "cams": columns of hundredths (the turns tenths, as differences).
 * The order they come in keeps each tick's events in the order the viewer needs them (a mob's
 * spawn before its moves, its tag before them, gone after).
 */
internal class Stretch(private val w: BufferedWriter, private val blockTpl: BlockTemplates? = null, private val values: Catalogue.Values? = null) {
    private class Item(val t: Int, val rows: JsonArray)
    private var end = -1
    private val others = ArrayList<String>()
    private val items = mapOf("e" to ArrayList<Item>(), "p" to ArrayList<Item>())
    private val groups = LinkedHashMap<String, ArrayList<JsonObject>>()
    private val blocks = ArrayList<IntArray>() // t, x, y, z, s
    private val cams = ArrayList<Pair<Int, JsonArray>>()
    private val mice = ArrayList<Pair<Int, JsonArray>>()

    fun put(line: String) {
        val head = HEAD.find(line)
        val t = head?.groupValues?.get(2)?.toInt()
        if (t != null && t >= end) { flush(); end = (t / COLS_TICKS + 1) * COLS_TICKS }
        if (end < 0) { w.write(line); w.write(NL); return }
        val kind = head?.groupValues?.get(1)
        if (t == null || kind == null || !take(kind, t, line)) others += line
    }

    /** Whether the line went into this stretch's columns (one that doesn't fit their shape stays a line). */
    private fun take(kind: String, t: Int, line: String): Boolean = runCatching {
        when {
            kind in items -> { items.getValue(kind) += Item(t, JsonParser.parseString(line).asJsonObject.getAsJsonArray("d")); true }
            kind == "block" -> {
                val l = JsonParser.parseString(line).asJsonObject
                if (l.keySet() != BLOCK_KEYS) return false
                blocks += intArrayOf(t, int(l["x"]), int(l["y"]), int(l["z"]), int(l["s"])); true
            }
            kind == "cam" -> {
                val l = JsonParser.parseString(line).asJsonObject
                val d = l.getAsJsonArray("d")
                if (l.keySet() != CAM_KEYS || d.any { f -> f !is JsonArray || f.size() != 3 || f.any { hundredths(it) == null } }) return false
                cams += t to d; true
            }
            kind == "mouse" -> {
                val l = JsonParser.parseString(line).asJsonObject
                val d = l.getAsJsonArray("d")
                if (l.keySet() != CAM_KEYS || d.any { f -> f !is JsonArray || f.size() != 3 || hundredths(f[0]) == null || tenths(f[1]) == null || tenths(f[2]) == null }) return false
                mice += t to d; true
            }
            kind in GROUPED || (kind == "eq" && line.contains("\"id\":")) -> { groups.getOrPut(kind) { ArrayList() } += JsonParser.parseString(line).asJsonObject; true }
            kind in SINGLES -> { groups.getOrPut(kind) { ArrayList() } += JsonParser.parseString(line).asJsonObject; true }
            // (a window's lines in one "ui" group, each its kind in "of": the viewer needs a tick's in their order)
            kind in UI -> { groups.getOrPut("ui") { ArrayList() } += JsonParser.parseString(line).asJsonObject.apply { remove("k"); addProperty("of", kind) }; true }
            else -> false
        }
    }.getOrDefault(false)

    fun end() = flush()

    private fun flush() {
        stuckArrows()
        groups["pj"]?.let { groups["pj"] = projectiles(it) }
        groups["pj"]?.let { list -> groups.remove("pj"); writeProjectiles(list) }
        groups["eq"]?.let { groups["eq"] = gear(it) }
        foldGear()
        for (kind in BEFORE_MOVES) groups[kind]?.let { write(it) { part -> group(kind, part) } }
        for ((kind, list) in items) if (list.isNotEmpty()) write(list) { encode(kind, it) }
        for ((kind, list) in groups) if (kind !in BEFORE_MOVES) write(list) { part -> group(kind, part) }
        if (blocks.isNotEmpty()) writeBlocks()
        if (cams.isNotEmpty()) write(cams) { camLine(it) }
        if (mice.isNotEmpty()) write(mice) { miceLine(it) }
        for (l in others) { w.write(l); w.write(NL) }
        for (list in items.values) list.clear()
        groups.clear(); blocks.clear(); cams.clear(); mice.clear(); others.clear()
    }

    /**
     * The projectiles as "pjc" lines, one per type (arrows, wither skulls, fireballs, arrows sent
     * already stuck: each alike, so each column packs as one kind): ticks, ids and starts as
     * differences ("d": decimals of the starts), how many points each has ("np") and all of their
     * points one after another (dt, dx, dy, dz: "pt", "px", "py", "pz"), the rest as a group's
     * columns ("c"). Rows that aren't like that stay a "pj" group.
     */
    private fun writeProjectiles(list: List<JsonObject>) {
        val odd = ArrayList<JsonObject>()
        val byType = LinkedHashMap<String, ArrayList<JsonObject>>()
        for (o in list) {
            if (!pjcRow(o)) { odd += o; continue }
            val type = (o["type"] as? JsonPrimitive)?.asString ?: ""
            byType.getOrPut(if (o.has("spawn")) "\u0000stuck" else type) { ArrayList() } += o
        }
        for ((key, rows) in byType) write(rows) { pjcLine(if (key.startsWith("\u0000")) null else key, it) }
        if (odd.isNotEmpty()) write(odd) { part -> group("pj", part) }
    }
    private fun pjcRow(o: JsonObject): Boolean {
        if (!PJC_KEYS.containsAll(o.keySet())) return false
        if (listOf("t", "id").any { (o[it] as? JsonPrimitive)?.takeIf { p -> p.isNumber }?.asString?.toLongOrNull() == null }) return false
        if (listOf("x", "y", "z").any { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { BigDecimal(it.asString).stripTrailingZeros().scale() <= 4 }.getOrNull() } != true }) return false
        val p = o["p"] ?: return true
        return p is JsonArray && p.size() % 4 == 0 && p.all { (it as? JsonPrimitive)?.takeIf { q -> q.isNumber }?.asString?.toLongOrNull() != null }
    }
    private fun pjcLine(type: String?, rows: List<JsonObject>): String {
        val dec = rows.maxOf { o -> listOf("x", "y", "z").maxOf { BigDecimal(o[it].asString).stripTrailingZeros().scale().coerceAtLeast(0) } }
        fun at(o: JsonObject, k: String) = BigDecimal(o[k].asString).movePointRight(dec).longValueExact()
        val np = JsonArray(); val pts = List(4) { ArrayList<Long>() }
        for (o in rows) {
            val p = o["p"] as? JsonArray
            np.add((p?.size() ?: 0) / 4)
            p?.forEachIndexed { i, v -> pts[i % 4] += v.asLong }
        }
        val rest = rows.map { o -> JsonObject().apply { for ((k, v) in o.entrySet()) if (k !in PJC_COLUMNS) add(k, v) } }
        val fields = LinkedHashSet<String>().apply { for (o in rest) addAll(o.keySet()) }
        return JsonObject().apply {
            addProperty("k", "pjc"); type?.let { addProperty("type", it) }; if (type == null) addProperty("stuck", 1)
            addProperty("d", dec)
            add("t", deltas(rows.map { it["t"].asLong })); add("id", deltas(rows.map { it["id"].asLong }))
            for (k in listOf("x", "y", "z")) add(k, deltas(rows.map { at(it, k) }))
            add("np", np)
            listOf("pt", "px", "py", "pz").forEachIndexed { i, k -> add(k, JsonArray().apply { pts[i].forEach(::add) }) }
            add("c", JsonObject().apply { for (f in fields) add(f, column(rest.map { it[f] ?: JsonNull.INSTANCE })) })
        }.toString()
    }

    /** One line of [list], or two of its halves (and so on) while it's longer than [COLS_MAX]. */
    private fun <T> write(list: List<T>, encode: (List<T>) -> String) { for (l in lines(list, encode)) { w.write(l); w.write(NL) } }
    private fun <T> lines(list: List<T>, encode: (List<T>) -> String): List<String> {
        val sizes = tables.mapValues { it.value.order.size }
        val line = encode(list)
        if (line.length > COLS_MAX && list.size > 1) {
            for ((f, t) in tables) t.reset(sizes.getValue(f)) // (the halves number them again)
            return lines(list.subList(0, list.size / 2), encode) + lines(list.subList(list.size / 2, list.size), encode)
        }
        return listOf(line)
    }

    /** The block changes: as template numbers and the rest ([BlockTemplates]) where that reads back the same, else as they are. */
    private fun writeBlocks() {
        val sorted = blocks.sortedWith(BLOCK_ORDER)
        val tpl = blockTpl
        val res = tpl?.let { runCatching { it.encode(sorted) }.onFailure { e -> log.warn("[ec] adr: block templates failed, sent as they are", e) }.getOrNull() }
        if (tpl != null && res != null) {
            val out = (if (res.streams.isEmpty()) emptyList() else lines(res.streams) { tpl.line(it) }) + (if (res.rest.isEmpty()) emptyList() else lines(res.rest) { blockLine(it) })
            if (runCatching { tpl.check(out, res.all) }.getOrDefault(false)) { for (l in out) { w.write(l); w.write(NL) }; return }
            log.warn("[ec] adr: block templates didn't read back the same, sent as they are")
        }
        write(sorted) { blockLine(it) }
    }

    /**
     * Gear lines as they go up:
     *  - a cosmetic's animation left out: a change of only dye colours or the head's skin that the
     *    same one's next change of gear comes within [COSMETIC_TICKS] of (animated helmets and
     *    rainbow armour change every tick or two; a change of gear stays) - the look before stays;
     *  - players' main hand left out ("" in its slot: the viewer has it from their "p" rows), and a
     *    line that leaves them as they were.
     */
    private fun gear(list: List<JsonObject>): ArrayList<JsonObject> {
        val byKey = LinkedHashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, o -> byKey.getOrPut((o["name"] ?: o["id"]).toString()) { ArrayList() } += i }
        val drop = BooleanArray(list.size)
        for (idx in byKey.values) {
            var kept: JsonObject? = null
            idx.forEachIndexed { n, i ->
                val o = list[i]
                val next = idx.getOrNull(n + 1)?.let { list[it] }
                val was = kept
                if (was != null && next != null && tick(next) - tick(o) < COSMETIC_TICKS && onlyCosmetic(o, was)) drop[i] = true else kept = o
            }
        }
        val out = ArrayList<JsonObject>()
        val last = HashMap<String, String>()
        list.forEachIndexed { i, o ->
            if (drop[i]) return@forEachIndexed
            val name = (o["name"] as? JsonPrimitive)?.asString
            if (name == null) { out += o; return@forEachIndexed }
            val c = o.deepCopy()
            (c["eq"] as? JsonArray)?.takeIf { it.size() > 0 }?.set(0, JsonPrimitive(""))
            val look = c["eq"].toString() + "|" + c["headTex"]
            if (last.put(name, look) != look) out += c
        }
        return out
    }
    private fun tick(o: JsonObject) = (o["t"] as? JsonPrimitive)?.asInt ?: 0
    /** Whether gear line [a] is [b] but for dye colours and the head's skin. */
    private fun onlyCosmetic(a: JsonObject, b: JsonObject): Boolean {
        val x = a["eq"] as? JsonArray ?: return false
        val y = b["eq"] as? JsonArray ?: return false
        if (x.size() != y.size()) return false
        for (i in 0 until x.size()) if ((x[i] as? JsonPrimitive)?.asString?.replace(DYE, "") != (y[i] as? JsonPrimitive)?.asString?.replace(DYE, "")) return false
        return true
    }

    /**
     * A mob's first gear, a tick after its spawn (nearly always): on its spawn row instead
     * ("eq", "headTex"; "eqfold" in the meta: the viewer puts it back a tick after the spawn).
     */
    private fun foldGear() {
        val spawns = groups["spawn"] ?: return
        val eqs = groups["eq"] ?: return
        class Life(val row: JsonObject, var first: Boolean = true)
        val life = HashMap<Int, Life>()
        val folded = HashSet<JsonObject>()
        var s = 0
        for (e in eqs) {
            val t = tick(e)
            while (s < spawns.size && tick(spawns[s]) <= t) { idOf(spawns[s])?.let { life[it] = Life(spawns[s]) }; s++ }
            val id = idOf(e) ?: continue
            val l = life[id] ?: continue
            if (l.first && tick(l.row) + 1 == t && !l.row.has("eq") && !l.row.has("headTex") && e.has("eq")) {
                l.row.add("eq", e["eq"]); e["headTex"]?.let { l.row.add("headTex", it) }
                folded += e
            }
            l.first = false
        }
        if (folded.isNotEmpty()) groups["eq"] = ArrayList(eqs.filter { it !in folded })
    }
    private fun idOf(o: JsonObject) = (o["id"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt

    /**
     * Arrows the server sent already stuck (a spawn with no flight) and nothing else is about but
     * their spawn: "pj" rows marked "spawn" ("pj2" in the meta), with its look and how long it
     * lives ("g", the spawn's "life") - the viewer makes the spawn it was again. Far smaller than a spawn
     * row; an F7 archer's run has hundreds.
     */
    private fun stuckArrows() {
        val spawns = groups["spawn"] ?: return
        val stuck = spawns.filter { s -> s.keySet().all { it in STUCK_KEYS } && (s["type"] as? JsonPrimitive)?.asString == "minecraft:arrow" && s.has("x") && s.has("y") && s.has("z") }
        if (stuck.isEmpty()) return
        // (only those no other line names: the order of a tick's events can't change)
        val named = HashMap<Int, Int>()
        for ((kind, list) in groups) for (o in list) (o["id"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt?.let { named.merge(it, if (kind == "spawn") 1 else 2, Int::plus) }
        for (it in items.getValue("e")) for (r in it.rows) named.merge(r.asJsonArray[0].asInt, 2, Int::plus)
        for (l in others) ID.find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { named.merge(it, 2, Int::plus) }
        val moving = stuck.filter { named[it["id"].asInt] == 1 }.toSet()
        if (moving.isEmpty()) return
        groups["spawn"] = ArrayList(spawns.filter { it !in moving })
        val rows = moving.map { s -> JsonObject().apply {
            add("t", s["t"]); add("id", s["id"]); addProperty("spawn", 1); add("x", s["x"]); add("y", s["y"]); add("z", s["z"])
            s["yaw"]?.let { add("yaw", it) }; s["life"]?.let { add("g", it) }
        } }
        groups["pj"] = ArrayList(((groups["pj"] ?: emptyList<JsonObject>()) + rows).sortedBy { it["t"].asInt })
    }

    /**
     * The projectiles that go up ("pj2"): one of each where several are the same (a bow sends
     * twins: the same tick, place and flight), none for an arrow that never moved and was gone
     * within [DUD_TICKS] (shot into nothing the server keeps), and the gone tick ("g") counted
     * from its last point, where it ends.
     */
    private fun projectiles(list: List<JsonObject>): ArrayList<JsonObject> {
        val seen = HashSet<String>()
        val out = ArrayList<JsonObject>()
        for (o in list) {
            val p = o["p"] as? JsonArray
            val g = (o["g"] as? JsonPrimitive)?.asInt
            if (p != null && !o.has("type") && g != null && g <= DUD_TICKS
                && (0 until p.size() / 4).all { i -> Math.hypot(Math.hypot(p[4 * i + 1].asDouble, p[4 * i + 2].asDouble), p[4 * i + 3].asDouble) < DUD_TENTHS }) continue
            if (!seen.add(JsonObject().apply { for ((k, v) in o.entrySet()) if (k != "id") add(k, v) }.toString())) continue
            out += if (p != null && g != null && p.size() >= 4) o.deepCopy().apply { addProperty("g", g - p[p.size() - 4].asInt) } else o
        }
        return out
    }

    /**
     * One "cols" line ("flat"): the lines' ticks; each mob/player once (mobs by id, as differences
     * from the one before: "kd") with how many rows it has ("n"); then every row's line (each
     * one's first as it is, the rest as differences: "li") and length ("sz"); then a column per
     * place in the rows, everyone's one after another - numbers as differences within each
     * mob's (its first as it is), anything else as runs. One long stream per value packs
     * smaller than each mob's own little table.
     */
    private fun encode(kind: String, list: List<Item>): String {
        val tracks = LinkedHashMap<String, MutableList<Pair<Int, JsonArray>>>()
        val keys = HashMap<String, JsonElement>()
        list.forEachIndexed { li, it ->
            for (r in it.rows) {
                val row = r.asJsonArray
                val key = row[0].toString()
                keys.getOrPut(key) { row[0] }
                tracks.getOrPut(key) { ArrayList() } += li to row
            }
        }
        val byId = kind == "e" && keys.values.all { (it as? JsonPrimitive)?.isNumber == true && it.asString.toLongOrNull() != null }
        val order = if (byId) tracks.keys.sortedBy { keys.getValue(it).asLong } else tracks.keys.toList()
        val width = tracks.values.maxOfOrNull { rows -> rows.maxOf { it.second.size() } } ?: 1
        val ks = JsonArray(); val n = JsonArray(); val li = JsonArray(); val sz = JsonArray()
        val vals = List(maxOf(0, width - 1)) { ArrayList<JsonElement>() }
        val starts = List(maxOf(0, width - 1)) { ArrayList<Boolean>() }
        var prevKey = 0L
        for (key in order) {
            val rows = tracks.getValue(key)
            ks.add(if (byId) keys.getValue(key).asLong.let { id -> JsonPrimitive(id - prevKey).also { prevKey = id } } else keys.getValue(key))
            n.add(rows.size)
            var prevLi = 0
            val first = BooleanArray(vals.size) { true }
            rows.forEachIndexed { j, (at, row) ->
                li.add(if (j == 0) at else at - prevLi); prevLi = at
                sz.add(row.size())
                for (c in 1 until row.size()) { vals[c - 1] += row[c]; starts[c - 1] += first[c - 1]; first[c - 1] = false }
            }
        }
        return JsonObject().apply {
            addProperty("k", "cols"); addProperty("of", kind); addProperty("flat", 1)
            if (byId) addProperty("kd", 1)
            add("t", deltas(list.map { it.t.toLong() }))
            add("keys", ks); add("n", n); add("li", li); add("sz", sz)
            add("cols", JsonArray().apply { vals.forEachIndexed { c, v -> add(flatColumn(v, starts[c])) } })
        }.toString()
    }

    /** A "flat" cols column: numbers (at most 4 decimals) as [decimals, ...] each a difference from the one before it in its mob's (one starting a mob's: as it is); else runs. */
    private fun flatColumn(values: List<JsonElement>, starts: List<Boolean>): JsonElement {
        val nums = values.map { v -> (v as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { BigDecimal(it.asString) }.getOrNull() } }
        if (nums.all { it != null }) {
            val dec = nums.maxOfOrNull { it!!.stripTrailingZeros().scale().coerceAtLeast(0) } ?: 0
            if (dec <= 4) return JsonArray().apply {
                add(dec)
                var prev = 0L
                nums.forEachIndexed { i, b -> val q = b!!.movePointRight(dec).longValueExact(); add(if (starts[i]) q else q - prev); prev = q }
            }
        }
        return column(values)
    }

    /**
     * Values that come over and over - gear ("eq"), head skins ("headTex"), names ("c"), stand poses,
     * mob and item types - on gear, spawn, name and stand lines ([TABLED]): each the first time whole,
     * then as its number in the order they first came ("eqtab"/"tab2" in the meta: the viewer keeps
     * the same lists), and one the site's catalogue has as -(its number there + 1) ([Catalogue.Values]).
     */
    private val tables = (TABLED_FIELDS + "slot").associateWith { Table() }
    private class Table {
        val index = HashMap<String, Int>(); val order = ArrayList<String>()
        fun of(v: JsonElement, cat: Map<String, Int>?): JsonElement {
            if (v.isJsonNull || (v is JsonPrimitive && v.isString && v.asString.isEmpty())) return v
            cat?.get(Catalogue.canon(v))?.let { return JsonPrimitive(-1 - it) }
            val key = v.toString()
            index[key]?.let { return JsonPrimitive(it) }
            index[key] = order.size; order += key
            return v
        }
        fun reset(size: Int) { while (order.size > size) index.remove(order.removeAt(order.size - 1)) }
    }

    /** A "grp" line: one kind's lines, a column per field ("n" of them; null where a line hasn't the field). */
    private fun group(kind: String, list: List<JsonObject>): String {
        val fields = LinkedHashSet<String>()
        for (o in list) for (f in o.keySet()) if (f != "k") fields += f
        val c = JsonObject()
        for (f in fields) c.add(f, column(list.map { o -> (o[f] ?: JsonNull.INSTANCE).let { v ->
            when {
                kind in TABLED -> tables[f]?.of(v, values?.fields?.get(f)) ?: v
                kind == "ui" && f == "s" && (o["of"] as? JsonPrimitive)?.asString == "slots" -> slots(v)
                else -> v
            }
        } }))
        return JsonObject().apply { addProperty("k", "grp"); addProperty("of", kind); addProperty("n", list.size); add("c", c) }.toString()
    }

    /**
     * A window's slots ("slots2"): each [slot, item...] as how many slots on from the one before
     * (0: the next), then the item - the whole of it the first time, then its number ([Table]; the
     * values catalogue's "slot" as -(number + 1)). As they are where they aren't all [slot, ...].
     */
    private fun slots(v: JsonElement): JsonElement {
        val s = v as? JsonArray ?: return v
        if (s.any { e -> ((e as? JsonArray)?.takeIf { it.size() > 0 }?.get(0) as? JsonPrimitive)?.let { it.isNumber && it.asDouble == it.asInt.toDouble() } != true }) return v
        val out = JsonArray()
        var prev = -1
        for (e in s) {
            val a = e.asJsonArray
            val slot = a[0].asInt
            out.add(slot - prev - 1); prev = slot
            out.add(tables.getValue("slot").of(JsonArray().apply { for (i in 1 until a.size()) add(a[i]) }, values?.fields?.get("slot")))
        }
        return out
    }

    /** A "blocks" line: [dt, dx, dy, dz, state] each, from the one before; a tick's changes by place (one block's in the order they came). */
    private fun blockLine(list: List<IntArray>): String {
        val sorted = list.sortedWith(BLOCK_ORDER)
        val d = JsonArray()
        var p = IntArray(4)
        for (b in sorted) { d.add(JsonArray().apply { for (i in 0 until 4) add(b[i] - p[i]); add(b[4]) }); p = b }
        return JsonObject().apply { addProperty("k", "blocks"); add("d", d) }.toString()
    }

    /** A "cams" line: each line's tick (differences) and frame count, then per frame its partial tick, yaw and pitch in hundredths (yaw and pitch as differences). */
    private fun camLine(list: List<Pair<Int, JsonArray>>): String {
        val t = JsonArray(); val n = JsonArray(); val pt = JsonArray(); val yaw = JsonArray(); val pitch = JsonArray()
        var pT = 0; var pY = 0L; var pP = 0L
        for ((tick, d) in list) {
            t.add(tick - pT); pT = tick; n.add(d.size())
            for (f in d) {
                val a = f.asJsonArray
                pt.add(Math.round(a[0].asDouble * 10))
                // (turns in tenths of a degree: "s")
                val y = Math.round(a[1].asDouble * 10); val p = Math.round(a[2].asDouble * 10)
                yaw.add(y - pY); pitch.add(p - pP); pY = y; pP = p
            }
        }
        return JsonObject().apply { addProperty("k", "cams"); addProperty("s", 10); addProperty("ps", 10); add("t", t); add("n", n); add("pt", pt); add("yaw", yaw); add("pitch", pitch) }.toString()
    }

    /** A "mice" line: each mouse line's tick (differences) and point count, its points' partial tick in hundredths, x and y in tenths of a GUI pixel (differences). */
    private fun miceLine(list: List<Pair<Int, JsonArray>>): String {
        val pt = ArrayList<Long>(); val x = ArrayList<Long>(); val y = ArrayList<Long>()
        for ((_, d) in list) for (f in d) { val a = f.asJsonArray; pt += hundredths(a[0])!!; x += tenths(a[1])!!; y += tenths(a[2])!! }
        return JsonObject().apply {
            addProperty("k", "mice"); add("t", deltas(list.map { it.first.toLong() })); add("n", JsonArray().apply { for ((_, d) in list) add(d.size()) })
            add("pt", JsonArray().apply { pt.forEach(::add) }); add("x", deltas(x)); add("y", deltas(y))
        }.toString()
    }

    private fun deltas(v: List<Long>) = JsonArray().apply { var prev = 0L; for (x in v) { add(x - prev); prev = x } }

    /**
     * A column: all numbers (at most 4 decimals) as [decimals, first, differences...] in units
     * of the last decimal, exactly; anything else as runs, {"r": [value, count, ...]}.
     */
    private fun column(values: List<JsonElement>): JsonElement {
        val nums = values.map { v -> (v as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { BigDecimal(it.asString) }.getOrNull() } }
        if (nums.all { it != null }) {
            val dec = nums.maxOf { it!!.stripTrailingZeros().scale().coerceAtLeast(0) }
            if (dec <= 4) return JsonArray().apply {
                add(dec)
                var prev = 0L
                for (n in nums) { val q = n!!.movePointRight(dec).longValueExact(); add(q - prev); prev = q }
            }
        }
        val r = JsonArray()
        var last: JsonElement? = null; var n = 0
        for (v in values) {
            if (n > 0 && v == last) { n++; continue }
            if (n > 0) { r.add(last); r.add(n) }
            last = v; n = 1
        }
        if (n > 0) { r.add(last); r.add(n) }
        return JsonObject().apply { add("r", r) }
    }

    companion object {
        private val BLOCK_KEYS = setOf("k", "t", "x", "y", "z", "s")
        /** Block changes by tick, then y, z, x (stable: one block's in a tick in the order they came). */
        private val BLOCK_ORDER = compareBy<IntArray>({ it[0] }, { it[2] }, { it[3] }, { it[1] })
        private val CAM_KEYS = setOf("k", "t", "d")
        /** Kinds that go in "grp" lines; the first ones go ahead of the moves, the rest after. */
        private val PJC_COLUMNS = setOf("k", "t", "id", "x", "y", "z", "p", "type")
        private val PJC_KEYS = PJC_COLUMNS + setOf("g", "s", "pw", "spawn", "yaw", "life")
        private val BEFORE_MOVES = listOf("spawn", "tag", "stand", "frame", "eq", "name")
        private val GROUPED = BEFORE_MOVES.toSet() + setOf("gone", "st", "mp", "sw", "pj")
        /**
         * Lines that were each a line of their own, grouped too ("grp"; the viewer reads each kind into
         * a list of its own). Not chat, time or skin: the site reads those lines as they are.
         */
        private val SINGLES = setOf("mouse", "pgone", "use", "hotbar", "tp", "slotclick", "rooms", "held", "ether", "room")
        /** A window's lines, one "ui" group in their order. */
        private val UI = setOf("gui", "slots", "carried", "click", "guiclose")
        /** The kinds whose values go by number ([tables]), and the fields. */
        private val TABLED = setOf("eq", "spawn", "name", "stand")
        private val TABLED_FIELDS = listOf("eq", "headTex", "c", "pose", "type", "item", "itemId")
        /** A change of only colours and skins on gear followed this soon by another isn't sent (see gear). */
        private const val COSMETIC_TICKS = 10
        private val DYE = Regex("#[0-9a-f]{6}")
        /** A spawn that can be a "pj" row (see stuckArrows). */
        private val STUCK_KEYS = setOf("k", "t", "id", "type", "x", "y", "z", "yaw", "life")
        /** An arrow that went no further than this (tenths of a block) and was gone within [DUD_TICKS] isn't sent. */
        private const val DUD_TENTHS = 5.0
        private const val DUD_TICKS = 3
        /** A number with at most 2 decimals, in hundredths (exactly), else null. */
        fun hundredths(e: JsonElement): Long? = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.let { p ->
            runCatching { BigDecimal(p.asString).movePointRight(2).longValueExact() }.getOrNull()
        }
        fun tenths(e: JsonElement): Long? = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.let { p ->
            runCatching { BigDecimal(p.asString).movePointRight(1).longValueExact() }.getOrNull()
        }
    }
}

/** Ticks a stretch of columns holds (see [Stretch]): more than any run, so a run is one (its columns pack smaller whole); a line bigger than [COLS_MAX] is split. */
private const val COLS_TICKS = 1_000_000_000
private const val COLS_MAX = 100_000 // (the site takes lines up to 128 KB)
