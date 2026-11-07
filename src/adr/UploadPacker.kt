package com.engineerclient.adr

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.slf4j.LoggerFactory
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.TreeMap
import java.util.TreeSet
import java.util.zip.GZIPInputStream

/**
 * What a saved run (.jsonl.gz, as written while recording) is turned into to upload:
 *
 *  - xz (LZMA2, [xzOptions]) instead of gzip: well under half the size on a typical run.
 *    The site stores it as it comes; the viewer tells the two apart by their first bytes.
 *
 *  - When someone else in the party already uploaded their recording of the same run ([sibling]),
 *    a mob's life is left out only where that recording has the same one: the same entity id
 *    (the server's, the same on every client) and type, alive over all of it ([LIFE_MARGIN]),
 *    and nearly everywhere this recording saw it, within [AGREE_DIST] blocks of where that one
 *    says ([covered]). The viewer takes each life from one recording and fills in lives only the
 *    others have, so nothing it shows changes - and a made-up or edited upload, which doesn't
 *    agree, makes this one keep everything.
 *
 *  - Mobs as little as the viewer needs ([MobPlan], [MobWriter]): moves thinned to where it can't
 *    draw them from the ones either side (it slides between them, [MobPlan.LERP_GAP]), rounded;
 *    gone, a spawn's stand look and its name folded into the spawn; a mob coming back into range
 *    looking as it went the same life on; nothing the viewer couldn't show (stands never seen).
 *
 *  - Everything the viewer builds the world from comes first (after the meta line), with the
 *    whole room layout as a "roomsAll" line: those lines are written as the run goes, but none of
 *    them are about when. The viewer starts a long run from its first minute while the rest
 *    downloads; this way that first minute already has every room and door, and the world never
 *    has to be built again once the rest is in. The rest keeps its order.
 *
 *  - Other players the same way ([PlayerPlan]): rows thinned to where the viewer can't slide
 *    between them, rounded; the recording player's own the same, far finer.
 *
 *  - A projectile's whole life - its spawn and flight, new flights, where it stuck, when it was
 *    gone - is one "pj" line where it spawned: the few points the viewer needs to fly it from
 *    one to the next ([ProjPack]; most arrows are shot point-blank into a wall and stick a tick
 *    later: three lines and a move, for one arrow). One of each set of twins (a bow sends some
 *    twice over), none that never moved and were gone within a few ticks, and arrows the server
 *    sent already stuck as "pj" rows too ([Stretch.stuckArrows]); one moved after it stuck has
 *    those places as more points.
 *
 *  - Mob moves ("e") and player lines ("p") as columns ("cols"): the whole run's, a column per
 *    value with every mob's (player's) one after another, as differences within each mob's -
 *    mostly 0, 1 and -1, which pack to a fraction of the numbers written out. Mob events, the
 *    lines that came one at a time (but chat, time and skins: the site reads those), block changes
 *    and camera frames the same way ([Stretch]); the viewer turns them all back into the lines
 *    they were.
 *
 *  - The recording player's camera frames thinned: one is left out where the frames either side of it, as the
 *    viewer draws between them, are within [CAM_TOLERANCE] degrees of it (a pixel or so), never
 *    leaving more than [CAM_GAP] ticks between two (more than 2 is "not rendering" to the viewer).
 *    The mouse over an open window the same way ([thinMouse]), and the heads placed in the world
 *    as one "skulls" line ([skullsLine]).
 *
 *  - Other players a party member's upload already has ([sibling]), and where it has them
 *    where this one does: their lines are left out while that recording has them, with a margin either side: the two recordings' ticks are
 *    lined up the way the viewer does it (the same block changes in both), [ALIGNED_MARGIN]; if
 *    they can't be, by start time, [COVER_MARGIN]. The viewer fills a player from any
 *    recording that has them at that tick, so nothing it shows changes. If that recording is the
 *    player's own, their equipment, skin and held head go too: the viewer takes those from it.
 *    Only when the two line up by block changes: by start time alone, nothing is left out.
 *
 *  - Block changes as their numbers in the site's catalogue of what runs do, where it has them
 *    ([BlockTemplates], [Catalogue]): a crusher's tick, a room's moving granite, each one number - and
 *    the boss floors that go all at once (P1's end platforms, Goldor's floor) the same in every run
 *    first ([Templates.ends]).
 *
 *  - Private chat (see [privateChat]) never goes up, whatever the recording has in it: runs
 *    recorded with Hide Private Chats off, or from before it, are sent through Upload Missing Runs.
 *
 * A party member's upload is anyone's say: read with limits on everything, and if anything about
 * it is off - or packing with it fails - the run goes up whole, as if there were none.
 *
 * Two reads of the file: [scan] (also the summary the site lists it by), then [pack]. Runs on an
 * upload thread, never the game's. The local file is left as it is.
 */
object UploadPacker {

    /** Line kinds that are about one mob, by its "id" (the "e" lines hold many, in "d"). */
    internal val ENTITY_KINDS = setOf("spawn", "arc", "gone", "name", "stand", "frame")
    private val KIND = Regex(""""k":"(\w+)"""")
    internal val HEAD = Regex("""^\{"k":"(\w+)","t":(\d+)""")
    internal val ID = Regex(""""id":(-?\d+)""")
    private val ROW_ID = Regex("""\[(-?\d+),""")
    /** Line kinds the world is built from, untimed as far as the viewer goes (its WORLD_KINDS). */
    internal val WORLD = setOf("lib", "dslots", "door", "vol", "chunk", "pal", "skull", "skin", "party", "floor")
    internal fun kindOf(line: String) = KIND.find(line.take(24))?.groupValues?.get(1)

    private const val RUN_START = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val RUN_END = Regex("""^\s*☠ Defeated """)

    internal const val NL = "\n" // never newLine(): on Windows that is "\r\n"
    private const val CAM_TOLERANCE = 1.0
    private const val CAM_GAP = 1.95
    /** GUI pixels the viewer's mouse may be off (see thinMouse), and how far apart (ticks) it slides between two points. */
    private const val MOUSE_TOLERANCE = 1.0
    private const val MOUSE_GAP = 2.0
    private const val COVER_MARGIN = 600
    private const val MAX_MOVES = 50 // (the viewer flies a projectile through at most 64 points)
    private const val ALIGNED_MARGIN = 40

    // Limits on a party member's upload (a real one is a few MB, 100-odd thousand lines, 50-odd
    // thousand block changes, a few hours of ticks at the very most). Past one: no sibling.
    private const val XZ_MEMORY_KIB = 65536 // the decoder's memory: preset 6 needs about 9 MB
    internal const val SIB_MAX_CHARS = 300_000_000L
    internal const val SIB_MAX_LINES = 3_000_000
    internal const val SIB_MAX_LINE = 1 shl 20 // (the site takes lines up to 128 KB)
    internal const val SIB_MAX_IDS = 500_000
    internal const val SIB_MAX_ROWS = 5_000_000
    internal const val SIB_MAX_TICK = 1_000_000L
    private const val MAX_BLOCKS = 1_000_000
    private const val MAX_PAL = 65_536
    /** Block pairs [lineUp] compares at most (the same block changing over and over is many). */
    private const val MAX_COMPARES = 20_000_000

    internal val log = LoggerFactory.getLogger("engineerclient")

    /** Something in a recording that isn't what it should be. */
    internal class Malformed(what: String) : IOException(what)

    internal fun obj(line: String): JsonObject = JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject ?: throw Malformed("not an object")
    internal fun arr(e: JsonElement?): JsonArray = (e as? JsonArray) ?: throw Malformed("not an array")
    internal fun at(a: JsonArray, i: Int): JsonElement = if (i in 0 until a.size()) a[i] else throw Malformed("index $i of ${a.size()}")
    internal fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: throw Malformed("not a string")
    /** A whole number, written as one (1.5, 1e3 and anything past a long aren't). */
    internal fun long(e: JsonElement?): Long = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.asString?.toLongOrNull() ?: throw Malformed("not a whole number")
    internal fun int(e: JsonElement?): Int = long(e).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw Malformed("$it isn't an int") }

    /**
     * Chat that is nobody else's business: private messages both ways, guild, officer and co-op
     * chat and notices, friends coming online, friend requests, and what /g online, /g info and
     * /f list show. Hypixel writes them "From [RANK] name: ...", "To name: ...", "Guild > ...",
     * "Officer > ...", "Co-op > ...", "Friend > ...", or as boxes of several lines - any line of a
     * message being one of these leaves the whole message out. Party chat is only left out with Hide
     * Party Chat on ([partyChat]): the viewer uses it.
     * Used both while recording (Hide Private Chats) and on upload (always).
     */
    private val PRIVATE_LINE = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > |Friend request from |You are now friends with |-*\s*Friends \(Page |Guild Name: |Total Members: |Online Members: |Offline Members: |-- .+ --$)""")
    private val PRIVATE_ANYWHERE = Regex("""(?i)\bco-op\b|\b(?:joined|left) the guild\b|\bto join (?:their|the|your) guild\b|\bremoved you from your friends\b""")
    fun privateChat(text: String): Boolean = text.lineSequence().any { raw ->
        val l = raw.trim()
        PRIVATE_LINE.containsMatchIn(l) || (!l.startsWith("Party > ") && PRIVATE_ANYWHERE.containsMatchIn(l))
    }

    /** Party chat ("Party > [RANK] name: ..."): left out with Hide Party Chat on. */
    fun partyChat(text: String): Boolean = text.lineSequence().any { it.trim().startsWith("Party > ") }

    /** A "chat" line whose message is private (see [privateChat]), or party chat with [party]; one that can't be read is left out too. */
    private fun privateChatLine(line: String, party: Boolean): Boolean =
        runCatching { (JsonParser.parseString(line).asJsonObject["m"] as? JsonPrimitive)?.asString?.let { privateChat(it) || (party && partyChat(it)) } ?: false }.getOrDefault(true)

    /** A projectile, followed through the file for its "proj" line. */
    internal class Proj(val spawn: JsonObject) {
        val t0 = spawn["t"].asInt
        val arcs = JsonArray()
        var stuck: JsonArray? = null
        /** Where it was moved after that (dt, x, y, z, yaw each): settling into the block, or carried on. */
        val moves = JsonArray()
        var gone: Int? = null
        /** Anything more than a flight, a stick and gone (moved after sticking, a name...): kept as lines. */
        var foldable = true
    }

    /** What one read of a run file gives: its summary, the layout, the world's lines, each mob's event count, its projectiles. */
    class Scan internal constructor(
        val summary: JsonObject,
        internal val layout: String?,
        internal val world: List<String>,
        internal val counts: Map<Int, Int>,
        private val projs: Map<Int, Proj>,
        internal val self: String,
        internal val startMs: Long,
        /** Per "cam" line (in order), which of its frames stay; null: all. */
        internal val camKeep: List<BooleanArray?>,
        /** The times (tick - 1 + partial tick, as the viewer reads them) of the camera frames that stay, in order. */
        internal val camTimes: DoubleArray,
        /** Block changes as (tick, "x,y,z,state"), for lining this recording up with another. */
        internal val blocks: List<Pair<Long, String>>,
        /** Per "mouse" line (in order), which of its points stay; null: all. */
        internal val mouseKeep: List<BooleanArray?> = emptyList(),
    ) {
        internal fun fold(id: Int) = projs[id]?.takeIf { it.foldable }
    }

    /** One pass over the run file: the summary the site lists it by (self, startMs, floor, party, ticks, cleared...) and what [pack] needs. */
    fun scan(file: Path, privateRun: Boolean): Scan {
        val summary = JsonObject()
        var ticks = 0
        var startTick: Int? = null
        var endTick: Int? = null
        var layout: String? = null
        val world = ArrayList<String>()
        val counts = HashMap<Int, Int>()
        val projs = HashMap<Int, Proj>()
        val flying = HashSet<Int>() // projectiles not gone yet
        var self = ""
        var startMs = 0L
        val frames = ArrayList<DoubleArray>() // time, yaw, pitch, cam line, frame
        var camLines = 0
        val mice = ArrayList<DoubleArray>() // time, x, y, mouse line, point
        var mouseLines = 0
        val blocks = BlockReader()
        lines(Files.newInputStream(file)).use { r ->
            for (line in r.lineSequence()) {
                val kind = kindOf(line) ?: continue
                val head = HEAD.find(line)
                val t = head?.groupValues?.get(2)?.toInt()
                if (t != null && t > ticks && kind !in WORLD) ticks = t
                if (kind in WORLD) world += line
                blocks.read(kind, line)
                when (kind) {
                    "meta" -> JsonParser.parseString(line).asJsonObject.let {
                        summary.add("self", it["self"]); summary.add("startMs", it["startMs"])
                        self = it["self"]?.asString ?: ""; startMs = it["startMs"]?.asLong ?: 0L
                    }
                    "cam" -> {
                        val d = JsonParser.parseString(line).asJsonObject.getAsJsonArray("d")
                        d.forEachIndexed { k, f -> val a = f.asJsonArray; frames += doubleArrayOf(t!! - 1 + a[0].asDouble, a[1].asDouble, a[2].asDouble, camLines.toDouble(), k.toDouble()) }
                        camLines++
                    }
                    "mouse" -> {
                        val d = runCatching { JsonParser.parseString(line).asJsonObject.getAsJsonArray("d") }.getOrNull()
                        d?.forEachIndexed { k, f -> val a = f.asJsonArray; mice += doubleArrayOf(t!! - 1 + a[0].asDouble, a[1].asDouble, a[2].asDouble, mouseLines.toDouble(), k.toDouble()) }
                        mouseLines++
                    }
                    "floor" -> summary.add("floor", JsonParser.parseString(line).asJsonObject["floor"])
                    "party" -> summary.add("party", JsonParser.parseString(line).asJsonObject["m"])
                    "rooms" -> if (line.length > (layout?.length ?: -1)) layout = line
                    "chat" -> {
                        val l = JsonParser.parseString(line).asJsonObject
                        val m = l["m"]?.asString ?: continue
                        if (startTick == null && m.startsWith(RUN_START)) startTick = t ?: 0
                        else if (RUN_END.containsMatchIn(m)) endTick = t ?: 0
                    }
                }
                // Each mob's events (for leaving out what a party member's upload has), and projectiles.
                if (kind == "e") {
                    var mine: JsonArray? = null
                    for (m in ROW_ID.findAll(line)) {
                        val id = m.groupValues[1].toInt()
                        counts.merge(id, 1, Int::plus)
                        if (id in flying) {
                            val rows = mine ?: JsonParser.parseString(line).asJsonObject.getAsJsonArray("d").also { mine = it }
                            val row = rows.first { it.asJsonArray[0].asInt == id }.asJsonArray
                            val p = projs.getValue(id)
                            val at = JsonArray().apply { add(t!! - p.t0); for (i in 1 until row.size()) add(row[i]) }
                            if (p.stuck == null) p.stuck = at else if (p.moves.size() < MAX_MOVES) p.moves.add(at) else p.foldable = false
                        }
                    }
                } else if (kind in ENTITY_KINDS || kind == "tag" || (kind == "eq" && line.contains("\"id\":"))) {
                    val id = ID.find(line)?.groupValues?.get(1)?.toInt() ?: continue
                    if (kind != "tag") counts.merge(id, 1, Int::plus)
                    when {
                        kind == "spawn" && line.contains("\"v\":[") -> { projs[id] = Proj(JsonParser.parseString(line).asJsonObject); flying += id }
                        id !in flying -> {}
                        kind == "arc" -> projs.getValue(id).let { p ->
                            val l = JsonParser.parseString(line).asJsonObject
                            val v = l.getAsJsonArray("v")
                            p.arcs.add(JsonArray().apply { add(t!! - p.t0); add(l["x"]); add(l["y"]); add(l["z"]); v.forEach(::add) })
                        }
                        kind == "gone" -> { projs.getValue(id).gone = t; flying -= id }
                        else -> projs.getValue(id).foldable = false
                    }
                }
            }
        }
        if (!summary.has("floor")) summary.addProperty("floor", "")
        if (!summary.has("party")) summary.add("party", JsonArray())
        summary.addProperty("ticks", ticks)
        // Whether it is a whole run (Mort's map to "☠ Defeated") and how long it took — worked out
        // here so the server doesn't have to unpack the recording, which a big run can't afford on
        // its CPU budget (Cloudflare error 1102).
        val start = startTick; val end = endTick
        val cleared = start != null && end != null && end > start
        summary.addProperty("cleared", if (cleared) 1 else 0)
        if (privateRun) summary.addProperty("private", 1)
        if (cleared) summary.addProperty("timeMs", (end!! - start!!) * 50L)
        return Scan(summary, layout?.replaceFirst(""""k":"rooms"""", """"k":"roomsAll""""), world, counts, projs, self, startMs, thinCamera(frames, camLines), camTimes(frames), blocks.changes(), thinMouse(mice, mouseLines))
    }

    /**
     * Which camera frames to keep (see the top): from each kept frame, the furthest one the viewer's
     * straight line to it stays within [CAM_TOLERANCE] of every frame between, at most [CAM_GAP]
     * ticks on. In time order, as the viewer sorts them.
     */
    /** The frames thinCamera kept (it marks them, f[5]): their times as the viewer reads them (partial ticks in tenths). */
    private fun camTimes(frames: List<DoubleArray>): DoubleArray =
        frames.filter { it.size > 5 && it[5] > 0 }.map { f -> val t = Math.floor(f[0]) ; t + Math.round((f[0] - t) * 10) / 10.0 }.sorted().toDoubleArray()

    /**
     * Which mouse points to keep: the viewer draws the mouse on the straight line between two
     * points up to [MOUSE_GAP] ticks apart (further, where the first was), so from each kept point
     * the furthest one that line stays within [MOUSE_TOLERANCE] GUI pixels of every point between.
     */
    private fun thinMouse(pts: MutableList<DoubleArray>, lines: Int): List<BooleanArray?> {
        val keep = arrayOfNulls<BooleanArray>(lines)
        if (pts.size < 3) return keep.toList()
        pts.sortBy { it[0] }
        val kept = BooleanArray(pts.size)
        kept[0] = true; kept[pts.size - 1] = true
        var k = 0
        while (k < pts.size - 1) {
            var m = k + 1
            var c = k + 2
            while (c < pts.size) {
                val pk = pts[k]; val pc = pts[c]
                val span = pc[0] - pk[0]
                var ok = true
                // (held still till c: the point before it stays if the viewer slid from it to c)
                if (span > MOUSE_GAP && c - k > 1 && pc[0] - pts[c - 1][0] <= MOUSE_GAP) break
                for (j in k + 1 until c) {
                    val a = if (span > MOUSE_GAP) 0.0 else (pts[j][0] - pk[0]) / span
                    if (Math.hypot(pk[1] + (pc[1] - pk[1]) * a - pts[j][1], pk[2] + (pc[2] - pk[2]) * a - pts[j][2]) > MOUSE_TOLERANCE) { ok = false; break }
                }
                if (!ok) break
                m = c; c++
            }
            kept[m] = true; k = m
        }
        val counts = IntArray(lines)
        for (f in pts) counts[f[3].toInt()] = maxOf(counts[f[3].toInt()], f[4].toInt() + 1)
        pts.forEachIndexed { i, f ->
            val li = f[3].toInt()
            val arr = keep[li] ?: BooleanArray(counts[li]).also { keep[li] = it }
            arr[f[4].toInt()] = kept[i]
        }
        return keep.toList()
    }

    private fun thinCamera(frames: MutableList<DoubleArray>, lines: Int): List<BooleanArray?> {
        val keep = arrayOfNulls<BooleanArray>(lines)
        if (frames.size < 3) return keep.toList()
        frames.sortBy { it[0] }
        val kept = BooleanArray(frames.size)
        kept[0] = true; kept[frames.size - 1] = true
        fun turn(a: Double, b: Double) = ((b - a) % 360 + 540) % 360 - 180
        var k = 0
        while (k < frames.size - 1) {
            var m = k + 1
            var c = k + 2
            while (c < frames.size && frames[c][0] - frames[k][0] <= CAM_GAP) {
                val fk = frames[k]; val fc = frames[c]
                var ok = true
                for (j in k + 1 until c) {
                    val a = (frames[j][0] - fk[0]) / (fc[0] - fk[0])
                    val yaw = fk[1] + turn(fk[1], fc[1]) * a; val pitch = fk[2] + (fc[2] - fk[2]) * a
                    if (Math.abs(turn(yaw, frames[j][1])) > CAM_TOLERANCE || Math.abs(pitch - frames[j][2]) > CAM_TOLERANCE) { ok = false; break }
                }
                if (!ok) break
                m = c; c++
            }
            kept[m] = true; k = m
        }
        val counts = IntArray(lines)
        for (f in frames) counts[f[3].toInt()] = maxOf(counts[f[3].toInt()], f[4].toInt() + 1)
        frames.forEachIndexed { i, f ->
            if (kept[i]) frames[i] = f + 1.0
            val li = f[3].toInt()
            val arr = keep[li] ?: BooleanArray(counts[li]).also { keep[li] = it }
            arr[f[4].toInt()] = kept[i]
        }
        return keep.toList()
    }

    /**
     * A recording's block changes with their palette's states, as (tick, "x,y,z,state"). An upload's
     * template lines ("btpl") too, with [cat] - the version its meta says, and its rooms' frames.
     */
    internal class BlockReader(private val cat: Catalogue.Blocks? = null) {
        private val pal = HashMap<Int, String>()
        private val raw = ArrayList<Triple<Long, String, Int>>()
        private val framed = ArrayList<Pair<Long, String>>()
        private var frame: Templates.Frame? = null
        private var catOk = false
        fun read(kind: String, line: String) {
            if (kind == "meta" && cat != null) { val v = cat.version; catOk = runCatching { int((obj(line)["cat"] as? JsonObject)?.get("blocks")) == v }.getOrDefault(false); return }
            if (kind == "roomsAll" && cat != null) { frame = Templates.Frame(obj(line)["r"] as? JsonArray); return }
            if (kind == "btpl") {
                val f = frame
                if (cat == null || !catOk || f == null) return
                for (c in decodeBtpl(obj(line), cat, f, MAX_BLOCKS - raw.size - framed.size)) framed += c.t.toLong() to "${c.x},${c.y},${c.z},${c.state}"
                return
            }
            if (kind != "pal" && kind != "block" && kind != "blocks") return
            val l = obj(line)
            // (past the limits the rest is left out: lining up needs only some of them)
            if (kind == "pal") { val i = int(l["i"]); if (pal.size < MAX_PAL || i in pal) pal[i] = str(l["s"]) }
            else if (kind == "block") { if (raw.size < MAX_BLOCKS) raw += Triple(long(l["t"]), "${int(l["x"])},${int(l["y"])},${int(l["z"])}", int(l["s"])) }
            else {
                // A "blocks" line (Stretch): each change from the one before.
                var t = 0L; var x = 0; var y = 0; var z = 0
                for (e in arr(l["d"])) {
                    if (raw.size >= MAX_BLOCKS) break
                    val b = arr(e)
                    t += long(at(b, 0)); x += int(at(b, 1)); y += int(at(b, 2)); z += int(at(b, 3))
                    raw += Triple(t, "$x,$y,$z", int(at(b, 4)))
                }
            }
        }
        fun changes() = raw.map { (t, at, s) -> t to at + "," + (pal[s] ?: "?") } + framed
    }

    /**
     * A "btpl" line's block changes (see [BlockTemplates]): per device, its first piece's tick (from
     * the device before's first) and number, then each next piece's tick (from the one before) and
     * its rank among the pieces the catalogue says come after that one - or -(number + 1).
     */
    private fun decodeBtpl(l: JsonObject, cat: Catalogue.Blocks, frame: Templates.Frame, max: Int): List<Templates.Change> {
        val out = ArrayList<Templates.Change>()
        var qt = 0L
        for (e in arr(l["s"])) {
            val s = arr(e)
            if (s.size() < 2 || s.size() % 2 != 0) throw Malformed("device")
            qt += long(s[0])
            var t = qt
            var id = int(s[1])
            var i = 0
            while (true) {
                if (id !in cat.pieces.indices) throw Malformed("piece $id")
                if (t !in 0..SIB_MAX_TICK) throw Malformed("tick $t")
                out += Templates.pieceCells(cat.pieces[id], frame, t.toInt()) ?: throw Malformed("piece $id isn't in this run")
                if (out.size > max) throw Malformed("too many block changes")
                i += 2
                if (i >= s.size()) break
                t += long(s[i])
                val c = int(s[i + 1])
                id = if (c >= 0) cat.next.getOrNull(id)?.getOrNull(c) ?: throw Malformed("next") else -(c.toLong() + 1).toInt()
            }
        }
        return out
    }

    /**
     * Block changes as template numbers ([Templates]; "btpl" lines): the pieces the catalogue has, a
     * device at a time - each piece as its rank among those the catalogue says usually come next, or
     * its number - and the rest as "blocks" lines. The boss floors that go all at once go the same
     * way in every run first ([Templates.ends]). Read back before it goes: anything not the same
     * and the changes go up as they are.
     */
    internal class BlockTemplates(val cat: Catalogue.Blocks, val frame: Templates.Frame, private val pal: Map<Int, String>) {
        private val index = HashMap<String, Int>().apply { for ((i, st) in pal.entries.sortedBy { it.key }) putIfAbsent(st, i) }
        class Result(val streams: List<List<Templates.Piece>>, val rest: List<IntArray>, val all: List<Templates.Change>)

        /** [sorted]: [t, x, y, z, state number] by tick, y, z, x (as [Stretch.blockLine] has them). */
        fun encode(sorted: List<IntArray>): Result? {
            val changes = sorted.map { Templates.Change(it[0], it[1], it[2], it[3], pal[it[4]] ?: return null) }
            val all = if (cat.ends.isEmpty() || AIR !in index) changes
                else Templates.ends(changes, cat.ends).withIndex().sortedWith(compareBy({ it.value.t }, { it.value.y }, { it.value.z }, { it.value.x }, { it.index })).map { it.value }
            val covered = Templates.pieces(all, frame).filter { p -> cat.id[p.key]?.also { p.id = it } != null }
            val used = BooleanArray(all.size)
            for (p in covered) for (i in p.idx) used[i] = true
            val streams = Templates.streams(covered).map { s -> s.map { covered[it] } }.sortedWith(compareBy({ it[0].t }, { it[0].id }))
            val rest = all.indices.filter { !used[it] }.map { val c = all[it]; intArrayOf(c.t, c.x, c.y, c.z, index[c.state] ?: return null) }
            return Result(streams, rest, all)
        }

        fun line(streams: List<List<Templates.Piece>>): String {
            var pt = 0
            val s = JsonArray()
            for (st in streams) s.add(JsonArray().apply {
                add(st[0].t - pt); add(st[0].id); pt = st[0].t
                for (i in 1 until st.size) {
                    val r = cat.next[st[i - 1].id].indexOf(st[i].id)
                    add(st[i].t - st[i - 1].t); add(if (r >= 0) r else -(st[i].id + 1))
                }
            })
            return JsonObject().apply { addProperty("k", "btpl"); addProperty("v", cat.version); add("s", s) }.toString()
        }

        /** Whether [lines] read back as [all]: the same changes, and a block's in a tick in the same order. */
        fun check(lines: List<String>, all: List<Templates.Change>): Boolean {
            fun into(m: HashMap<String, MutableList<String>>, t: Long, x: Int, y: Int, z: Int, st: String) { m.getOrPut("$t,$x,$y,$z") { ArrayList() } += st }
            val want = HashMap<String, MutableList<String>>(); val got = HashMap<String, MutableList<String>>()
            for (c in all) into(want, c.t.toLong(), c.x, c.y, c.z, c.state)
            for (line in lines) {
                val l = obj(line)
                when (str(l["k"])) {
                    "btpl" -> for (c in decodeBtpl(l, cat, frame, MAX_BLOCKS)) into(got, c.t.toLong(), c.x, c.y, c.z, c.state)
                    "blocks" -> { var t = 0L; var x = 0; var y = 0; var z = 0; for (e in arr(l["d"])) { val b = arr(e); t += long(b[0]); x += int(b[1]); y += int(b[2]); z += int(b[3]); into(got, t, x, y, z, pal[int(b[4])] ?: return false) } }
                    else -> return false
                }
            }
            return got == want
        }
    }
    private const val AIR = "minecraft:air"

    /**
     * How many ticks to add to the sibling's to get this recording's, and how sure: the viewer's
     * way (align) - the middle of the differences between the same block change in both, near
     * what the start times say - or failing that the start times.
     */
    private fun lineUp(scan: Scan, sib: Sibling): Pair<Long, Int> {
        // (a recording of the same run starts within minutes of this one)
        if (Math.abs(sib.startMs - scan.startMs) > 3_600_000L) return 0L to COVER_MARGIN
        val guess = (sib.startMs - scan.startMs) / 50
        val theirs = HashMap<String, MutableList<Long>>()
        for ((t, k) in sib.blocks) theirs.getOrPut(k) { ArrayList() } += t
        val diffs = ArrayList<Long>()
        var compares = 0
        // (one block changing thousands of times in both would be millions of pairs: stops at enough)
        pairs@ for ((t, k) in scan.blocks) {
            for (ts in theirs[k] ?: continue) {
                val d = t - ts; if (Math.abs(d - guess) <= 400) diffs += d
                if (diffs.size > 2000 || ++compares > MAX_COMPARES) break@pairs
            }
        }
        if (diffs.size < 3) return guess to COVER_MARGIN
        diffs.sort()
        return diffs[diffs.size / 2] to ALIGNED_MARGIN
    }

    /**
     * The run as a temp xz file to send, and how many mobs were left out as already uploaded.
     * Preset 6: 17% smaller than 3 for about three times the CPU (a few seconds, on the upload's
     * low-priority thread, after the run) and about 100 MB while it packs.
     */
    fun pack(file: Path, scan: Scan, sibling: ByteArray?, hideParty: Boolean = false, cat: Catalogue? = null): Pair<Path, Int> {
        // Whatever is wrong with a party member's upload, this one goes up - whole, if need be.
        val sib = sibling?.let { b ->
            runCatching { readSibling(b.inputStream(), cat?.blocks, cat?.values, cat?.dict) }.onFailure { log.warn("[ec] adr: a party member's upload couldn't be read, nothing left out: {}", it.toString()) }.getOrNull()
        }
        if (sib != null) {
            try {
                return write(file, scan, sib, hideParty, cat)
            } catch (e: Exception) {
                log.warn("[ec] adr: packing with a party member's upload failed, packing it whole", e)
            }
        }
        return write(file, scan, null, hideParty, cat)
    }

    private fun write(file: Path, scan: Scan, sibling: Sibling?, hideParty: Boolean, cat: Catalogue?): Pair<Path, Int> {
        // Nothing is left out unless the two recordings line up by their block changes: by start
        // time alone (or a recording that only claims to be of this run) is too unsure to trust.
        val aligned = sibling?.let { lineUp(scan, it) }
        val sib = sibling?.takeIf { aligned?.second == ALIGNED_MARGIN }
        if (sibling != null && sib == null) log.info("[ec] adr: a party member's upload doesn't line up with this one, nothing left out")
        val plan = lines(Files.newInputStream(file)).use { r -> MobPlan.read(r.lineSequence()) { scan.fold(it) != null } }
        // Mob lives the party member's upload has, the same (checked against this one's): left out.
        val ticks = scan.summary["ticks"]?.asInt ?: 0
        val left = sib?.let { s -> plan.lives.values.flatten().filter { covered(it, s, aligned!!.first, ticks) }.toHashSet() } ?: emptySet()
        val players = sib?.let { Players(scan, it, aligned!!, plan.players) }
        val others = lines(Files.newInputStream(file)).use { r -> PlayerPlan.read(r.lineSequence(), scan.self, scan.camTimes) }
        val out = Files.createTempFile("adr-upload-", ".jsonl.xz")
        try {
            writeTo(file, scan, out, plan, others, left, players, hideParty, cat)
        } catch (t: Throwable) {
            Files.deleteIfExists(out)
            throw t
        }
        return out to left.size
    }

    private fun writeTo(file: Path, scan: Scan, out: Path, plan: MobPlan, others: PlayerPlan, left: Set<MobPlan.Life>, players: Players?, hideParty: Boolean, cat: Catalogue?) {
        var camLine = 0
        var mouseLine = 0
        // Block changes as template numbers: with the catalogue, and the rooms (their frames) and palette the viewer will have.
        val blockTpl = cat?.blocks?.let { b ->
            val rooms = scan.layout?.let { runCatching { obj(it)["r"] as? JsonArray }.getOrNull() } ?: return@let null
            val pal = HashMap<Int, String>()
            for (l in scan.world) if (kindOf(l) == "pal") runCatching { val o = obj(l); pal.putIfAbsent(int(o["i"]), str(o["s"])) }
            BlockTemplates(b, Templates.Frame(rooms), pal)
        }
        val file0 = Files.newOutputStream(out)
        val dict = cat?.dict?.takeIf { dictUsable(it) }
        val cut = dict?.let { DictCut(file0, it) }
        val stream = XZOutputStream(cut ?: file0, xzOptions())
        if (dict != null) { stream.write(dict.text); stream.flush(); cut!!.cut() }
        BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 16).use { w ->
            val cols = Stretch(w, blockTpl, cat?.values)
            val mobs = MobWriter(plan) { it in left }
            val timeline: (String) -> Unit = { l -> if (players == null) cols.put(l) else players.put(l, cols::put) }
            fun put(line: String, timed: Boolean = true) {
                var kept = fold(line, scan) ?: return
                if (kept.startsWith("{\"k\":\"cam\"")) kept = thin(kept, scan.camKeep.getOrNull(camLine++)) ?: return
                if (kept.startsWith("{\"k\":\"mouse\"")) kept = mouse(kept, scan.mouseKeep.getOrNull(mouseLine++)) ?: return
                // (the world's lines carry ticks, but aren't in the timeline: none of them start a stretch)
                if (!timed) { if (players?.keepsWorld(kept) != false) { w.write(kept); w.write(NL) }; return }
                others.put(kept) { mobs.put(it, timeline) }
            }
            // The first line (meta), the layout and the world's lines; then everything else.
            lines(Files.newInputStream(file)).use { r ->
                var first = true
                for (line in r.lineSequence()) {
                    if (first) {
                        // (the viewer fills mobs' and other players' moves in between the rows, up to this many ticks apart: MobPlan, PlayerPlan)
                        first = false; put(runCatching { obj(line).apply { addProperty("lerp", MobPlan.LERP_GAP); addProperty("plerp", MobPlan.LERP_GAP); addProperty("hy", 1); addProperty("selfcam", 1); addProperty("sthin", 1); addProperty("walkyaw", 1); addProperty("erel", 1); addProperty("eqtab", 1); addProperty("pj2", 1); addProperty("tab2", 1); addProperty("eqfold", 1); addProperty("slots2", 1)
                            val catMeta = JsonObject().apply { blockTpl?.let { addProperty("blocks", it.cat.version) }; cat?.values?.let { addProperty("values", it.version) }; cat?.skulls?.let { addProperty("skulls", it.version) } }
                            if (catMeta.size() > 0) add("cat", catMeta) }.toString() }.getOrDefault(line), timed = false)
                        scan.layout?.let { w.write(it); w.write(NL) }
                        val skulls = ArrayList<String>()
                        for (l in scan.world) if (kindOf(l) == "skull") skulls += l else put(l, timed = false)
                        val frame = blockTpl?.frame ?: scan.layout?.let { runCatching { Templates.Frame(obj(it)["r"] as? JsonArray) }.getOrNull() }
                        for (l in skullsLines(skulls, cat?.skulls?.takeIf { frame != null }, frame)) { w.write(l); w.write(NL) }
                        continue
                    }
                    // A line cut short (a crash part way through writing it) would get the whole run refused.
                    if (!line.startsWith("{") || !line.endsWith("}")) continue
                    val kind = kindOf(line)
                    if (kind == "chat" && privateChatLine(line, hideParty)) continue
                    if (kind == "chat") { put(plainChat(line)); continue }
                    if (kind !in WORLD) put(line)
                }
            }
            others.end { mobs.put(it, timeline) }
            mobs.end(timeline)
            cols.end()
        }
    }

    /**
     * The heads placed in the world as "skulls" lines (the viewer has no use for when each was
     * seen): by place, each [dx, dy, dz, texture] from the one before, the textures by number
     * ("tex"); [SKULLS_LINE] to a line at most (each line on its own: the site takes lines up to 128 KB).
     * Those the site's skulls catalogue has (the same place in the same room, the same skin) go as
     * its numbers instead ("cat": in order, each from the one before).
     */
    private fun skullsLines(lines: List<String>, cat: Catalogue.Skulls? = null, frame: Templates.Frame? = null): List<String> {
        val at = TreeMap<Triple<Int, Int, Int>, String>(compareBy<Triple<Int, Int, Int>>({ it.first }, { it.third }, { it.second }))
        for (line in lines) runCatching {
            val l = obj(line)
            at[Triple(int(l["x"]), int(l["y"]), int(l["z"]))] = (l["tex"] as JsonPrimitive).asString
        }
        val ids = TreeSet<Int>()
        if (cat != null && frame != null) at.entries.removeIf { (p, tex) ->
            val f = frame.fwd(p.first, p.second, p.third, "") ?: return@removeIf false
            val id = cat.id[Catalogue.skullKey(f.space, f.a, f.y, f.b, tex)] ?: return@removeIf false
            // (only where the viewer gets the same place back)
            if (frame.inv(f.space, f.a, f.y, f.b, "")?.first?.contentEquals(intArrayOf(p.first, p.second, p.third)) != true) return@removeIf false
            ids += id; true
        }
        val catLines = ids.chunked(SKULLS_LINE * 10).map { part ->
            JsonObject().apply { addProperty("k", "skulls"); addProperty("v", cat!!.version); add("cat", JsonArray().apply { var prev = 0; for (i in part) { add(i - prev); prev = i } }) }.toString()
        }
        return catLines + at.entries.chunked(SKULLS_LINE).map { part ->
            val tex = LinkedHashMap<String, Int>()
            val d = JsonArray()
            var p = Triple(0, 0, 0)
            for ((q, t) in part) {
                d.add(JsonArray().apply { add(q.first - p.first); add(q.second - p.second); add(q.third - p.third); add(tex.getOrPut(t) { tex.size }) })
                p = q
            }
            JsonObject().apply { addProperty("k", "skulls"); add("tex", JsonArray().apply { tex.keys.forEach(::add) }); add("d", d) }.toString()
        }
    }
    private const val SKULLS_LINE = 1200 // (every one a new texture: still under 100 KB)

    /**
     * xz as small as it gets for these files (a few percent under preset 6): preset 9's match finder
     * with an 8 MB dictionary (the site takes up to 16; an upload unpacks to a few MB), and no
     * position bits but four of literal context - it is text, where the byte before says more
     * than where a byte is.
     */
    private fun xzOptions() = LZMA2Options(9).apply { niceLen = 273; dictSize = 8 shl 20; pb = 0; lc = 4; lp = 0 }

    /** A chat line without its plain text ("m") where that is the coloured one ("c") without its § codes (the viewer works it out). */
    private fun plainChat(line: String): String = runCatching {
        val l = obj(line)
        val c = (l["c"] as? JsonPrimitive)?.asString ?: return line
        if ((l["m"] as? JsonPrimitive)?.asString != c.replace(CODES, "")) return line
        l.remove("m"); l.toString()
    }.getOrDefault(line)
    private val CODES = Regex("§.")

    /**
     * Whether this build packs the dictionary's text to exactly the bytes the site has for it (the
     * same xz library, the same settings): only then can the site and viewer put them back.
     */
    private fun dictUsable(d: Catalogue.Dict): Boolean = d.usable ?: runCatching {
        val b = ByteArrayOutputStream()
        XZOutputStream(b, xzOptions()).apply { write(d.text); flush() }
        val all = b.toByteArray()
        val start = 12 + ((all[12].toInt() and 0xff) + 1) * 4
        all.copyOfRange(start, all.size).contentEquals(d.packed)
    }.getOrDefault(false).also { d.usable = it; if (!it) log.warn("[ec] adr: the shared dictionary doesn't pack the same here, uploading without it") }

    /**
     * An upload packed after the shared dictionary: "ADRd", its version, then the xz stream without
     * the dictionary's own bytes (the stream's header and its block's, then straight on after them).
     * The xz stream goes in up to the end of the dictionary ([cut]); then everything goes through.
     */
    private class DictCut(private val out: OutputStream, private val dict: Catalogue.Dict) : OutputStream() {
        private var head: ByteArrayOutputStream? = ByteArrayOutputStream()
        fun cut() {
            val b = head!!.toByteArray(); head = null
            val start = b.size - dict.packed.size
            if (start != 12 + ((b[12].toInt() and 0xff) + 1) * 4 || !b.copyOfRange(start, b.size).contentEquals(dict.packed)) throw IllegalStateException("the dictionary packed differently")
            out.write(byteArrayOf('A'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 'd'.code.toByte(), dict.version.toByte()))
            out.write(b, 0, start)
        }
        override fun write(b: Int) { head?.write(b) ?: out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { head?.write(b, off, len) ?: out.write(b, off, len) }
        override fun flush() { if (head == null) out.flush() }
        override fun close() = out.close()
    }

    /** A line as it goes up: a projectile's spawn becomes its "proj" line, the rest of its lines go (null). */
    private fun fold(line: String, scan: Scan): String? {
        val kind = kindOf(line) ?: return line
        when (kind) {
            "spawn", "arc", "gone" -> {
                val id = ID.find(line)?.groupValues?.get(1)?.toInt() ?: return line
                val p = scan.fold(id) ?: return line
                if (kind != "spawn") return null
                return ProjPack.line(p).toString()
            }
            "e" -> {
                if (ROW_ID.findAll(line).none { scan.fold(it.groupValues[1].toInt()) != null }) return line
                val l = JsonParser.parseString(line).asJsonObject
                val left = JsonArray()
                for (e in l.getAsJsonArray("d")) if (scan.fold(e.asJsonArray[0].asInt) == null) left.add(e)
                if (left.size() == 0) return null
                l.add("d", left)
                return l.toString()
            }
        }
        return line
    }

    /** A "cam" line with only the frames [keep] keeps, or null when none are left. */
    /** A "mouse" line with only the points [keep] has (see thinMouse): when to a hundredth of a tick (it moves fast), where to a tenth of a GUI pixel. */
    private fun mouse(line: String, keep: BooleanArray?): String? = runCatching {
        val l = JsonParser.parseString(line).asJsonObject
        val left = JsonArray()
        l.getAsJsonArray("d").forEachIndexed { k, f -> if (keep?.getOrElse(k) { true } != false) left.add(JsonArray().apply { f.asJsonArray.forEachIndexed { i, v -> add(if (i == 0) MobPlan.hundredth(v.asDouble) else MobPlan.tenth(v.asDouble)) } }) }
        if (left.size() == 0) return null
        l.add("d", left)
        l.toString()
    }.getOrDefault(line)

    private fun thin(line: String, keep: BooleanArray?): String? {
        if (keep == null || keep.all { it }) return line
        if (keep.none { it }) return null
        val l = JsonParser.parseString(line).asJsonObject
        val d = l.getAsJsonArray("d")
        val left = JsonArray()
        d.forEachIndexed { k, f -> if (keep.getOrElse(k) { true }) left.add(f) }
        l.add("d", left)
        return l.toString()
    }

    /**
     * A recording's lines, gzip or xz (told apart by their first bytes, as the site and viewer do).
     * xz with a memory limit: its header says how big a dictionary to make, and a made-up one would
     * have it allocate gigabytes.
     */
    internal fun lines(raw: InputStream, dict: Catalogue.Dict? = null): BufferedReader {
        var input = BufferedInputStream(raw, 1 shl 16)
        input.mark(6)
        var head = input.readNBytes(6)
        input.reset()
        // (packed after the shared dictionary, DictCut: its bytes put back, its text skipped)
        var skip = 0L
        if (head.size >= 5 && String(head, 0, 4, Charsets.ISO_8859_1) == "ADRd") {
            if (dict == null || head[4].toInt() != dict.version) throw Malformed("packed after dictionary ${head[4]}, which isn't here")
            input.skipNBytes(5)
            val sh = input.readNBytes(13)
            if (sh.size < 13) throw Malformed("cut short")
            val bh = input.readNBytes(((sh[12].toInt() and 0xff) + 1) * 4 - 1)
            input = BufferedInputStream(SequenceInputStream(Collections.enumeration(listOf(ByteArrayInputStream(sh + bh), ByteArrayInputStream(dict.packed), input))), 1 shl 16)
            head = sh.copyOf(6); skip = dict.text.size.toLong()
        }
        val xz = head.size >= 3 && head[0] == 0xFD.toByte() && head[1] == '7'.code.toByte() && head[2] == 'z'.code.toByte()
        val unpacked = if (xz) XZInputStream(input, XZ_MEMORY_KIB) else GZIPInputStream(input)
        if (skip > 0) unpacked.skipNBytes(skip)
        return BufferedReader(InputStreamReader(unpacked, Charsets.UTF_8), 1 shl 16)
    }
}
