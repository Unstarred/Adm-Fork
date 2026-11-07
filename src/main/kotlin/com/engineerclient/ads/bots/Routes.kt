package com.engineerclient.ads.bots

import com.engineerclient.EngineerClient
import net.minecraft.world.phys.Vec3
import java.io.DataInputStream
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream

object Routes {
    fun gate(s: Int) = s
    fun door(s: Int) = s + 4
    const val CORE_DOOR = 4

    private const val JOIN_SPEED = 0.9
    private const val CLIP_COST = 2.0
    private const val ATTACH = 2.0
    private const val ATTACH_FAR = 5.0
    private const val CELL = 4

    private class Graph(
        val x: FloatArray, val y: FloatArray, val z: FloatArray,
        val yaw: FloatArray, val pitch: FloatArray, val held: Array<String?>, val flags: IntArray,
        val off: IntArray, val to: IntArray, val ticks: FloatArray,
        val cells: Map<Long, IntArray>,
    ) {
        val n get() = x.size
        fun grounded(i: Int) = flags[i] and 1 != 0
        fun opening(i: Int) = (flags[i] shr 1) and 7
        fun clip(i: Int) = flags[i] and 16 != 0
    }

    @Volatile private var loaded: Graph? = null
    @Volatile private var failed = false

    private val graph: Graph?
        get() = loaded ?: if (failed) null else synchronized(this) {
            loaded ?: runCatching { read() }.onFailure {
                failed = true
                EngineerClient.logger.warn("[p3sim] routes.bin: {}", it.toString())
            }.getOrNull().also { loaded = it }
        }

    private fun read(): Graph {
        val stream = Routes::class.java.getResourceAsStream("/assets/engineerclient/p3sim/routes.bin") ?: error("missing from the jar")
        DataInputStream(GZIPInputStream(stream.buffered(), 1 shl 16).buffered()).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            require(String(magic) == "P3R1") { "bad magic" }
            val n = inp.readInt()
            val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
            val yaw = FloatArray(n); val pitch = FloatArray(n); val heldIx = IntArray(n); val flags = IntArray(n)
            for (i in 0 until n) {
                x[i] = inp.readFloat(); y[i] = inp.readFloat(); z[i] = inp.readFloat()
                yaw[i] = inp.readUnsignedByte() * 360f / 256f
                pitch[i] = inp.readUnsignedByte() - 90f
                heldIx[i] = inp.readUnsignedByte()
                flags[i] = inp.readUnsignedByte()
            }
            val names = Array(inp.readInt()) { ByteArray(inp.readUnsignedShort()).also { b -> inp.readFully(b) }.toString(Charsets.UTF_8) }
            val held = Array(n) { names.getOrNull(heldIx[it]) }
            val off = IntArray(n + 1) { inp.readInt() }
            val m = off[n]
            val to = IntArray(m); val ticks = FloatArray(m)
            for (e in 0 until m) { to[e] = inp.readInt(); ticks[e] = inp.readUnsignedShort() / 64f }
            val cells = HashMap<Long, MutableList<Int>>()
            for (i in 0 until n) cells.getOrPut(cell(x[i].toDouble(), z[i].toDouble())) { ArrayList() } += i
            return Graph(x, y, z, yaw, pitch, held, flags, off, to, ticks, cells.mapValues { it.value.toIntArray() })
        }
    }

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
    private fun cell(x: Double, z: Double) = key(Math.floorDiv(Math.floor(x).toInt(), CELL), Math.floorDiv(Math.floor(z).toInt(), CELL))

    fun preload() { graph }

    class Route(val x: DoubleArray, val y: DoubleArray, val z: DoubleArray, val t: DoubleArray, val yaw: FloatArray, val pitch: FloatArray, val held: Array<String?>) {
        val ticks get() = t[t.size - 1]
        val size get() = t.size
        fun frameAt(time: Double): Int {
            var lo = 0; var hi = t.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (t[mid] <= time) lo = mid else hi = mid - 1 }
            return lo
        }
        fun pos(time: Double): Vec3 {
            val i = frameAt(time)
            if (i >= t.size - 1) return Vec3(x[i], y[i], z[i])
            val span = t[i + 1] - t[i]
            val f = if (span <= 1e-6) 1.0 else ((time - t[i]) / span).coerceIn(0.0, 1.0)
            return Vec3(x[i] + (x[i + 1] - x[i]) * f, y[i] + (y[i + 1] - y[i]) * f, z[i] + (z[i + 1] - z[i]) * f)
        }
    }

    private class Near(val node: Int, val d: Double)

    private fun near(g: Graph, p: Vec3, closed: (Int) -> Boolean): List<Near> {
        for (r in doubleArrayOf(ATTACH, ATTACH_FAR)) {
            val found = ArrayList<Near>()
            val c = (r / CELL).toInt() + 1
            val cx = Math.floorDiv(Math.floor(p.x).toInt(), CELL); val cz = Math.floorDiv(Math.floor(p.z).toInt(), CELL)
            for (dx in -c..c) for (dz in -c..c) {
                val ids = g.cells[key(cx + dx, cz + dz)] ?: continue
                for (i in ids) {
                    if (!g.grounded(i) || g.clip(i)) continue
                    val o = g.opening(i)
                    if (o != 0 && closed(o)) continue
                    val ddx = g.x[i] - p.x; val ddy = g.y[i] - p.y; val ddz = g.z[i] - p.z
                    val d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
                    if (d <= r) found += Near(i, d)
                }
            }
            if (found.isNotEmpty()) return found.sortedBy { it.d }.take(12)
        }
        return emptyList()
    }

    fun find(from: Vec3, to: Vec3, closed: (Int) -> Boolean): Route? {
        val g = graph ?: return null
        val starts = near(g, from, closed)
        val ends = near(g, to, closed)
        if (starts.isEmpty() || ends.isEmpty()) return null
        val endCost = HashMap<Int, Double>()
        for (e in ends) endCost[e.node] = e.d / JOIN_SPEED
        val dist = DoubleArray(g.n) { Double.POSITIVE_INFINITY }
        val prev = IntArray(g.n) { -1 }
        val done = BooleanArray(g.n)
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for (s in starts) {
            val c = s.d / JOIN_SPEED
            if (c < dist[s.node]) { dist[s.node] = c; pq += c to s.node }
        }
        var best = Double.POSITIVE_INFINITY
        var bestNode = -1
        while (pq.isNotEmpty()) {
            val (c, i) = pq.poll()
            if (done[i]) continue
            if (c > best) break
            done[i] = true
            endCost[i]?.let { if (c + it < best) { best = c + it; bestNode = i } }
            for (e in g.off[i] until g.off[i + 1]) {
                val j = g.to[e]
                if (done[j]) continue
                val o = g.opening(j)
                if (o != 0 && closed(o)) continue
                val nc = c + g.ticks[e] + if (g.clip(j)) CLIP_COST else 0.0
                if (nc < dist[j]) { dist[j] = nc; prev[j] = i; pq += nc to j }
            }
        }
        if (bestNode < 0) return null
        val path = ArrayList<Int>()
        var i = bestNode
        while (i >= 0) { path += i; i = prev[i] }
        path.reverse()
        val k = path.size + 2
        val x = DoubleArray(k); val y = DoubleArray(k); val z = DoubleArray(k); val t = DoubleArray(k)
        val yaw = FloatArray(k) { Float.NaN }; val pitch = FloatArray(k) { Float.NaN }; val held = arrayOfNulls<String>(k)
        x[0] = from.x; y[0] = from.y; z[0] = from.z
        for ((f, node) in path.withIndex()) {
            val q = f + 1
            x[q] = g.x[node].toDouble(); y[q] = g.y[node].toDouble(); z[q] = g.z[node].toDouble()
            yaw[q] = g.yaw[node]; pitch[q] = g.pitch[node]; held[q] = g.held[node]
            t[q] = if (f == 0) dist3(from, x[q], y[q], z[q]) / JOIN_SPEED
            else t[q - 1] + edgeTicks(g, path[f - 1], node)
        }
        x[k - 1] = to.x; y[k - 1] = to.y; z[k - 1] = to.z
        t[k - 1] = t[k - 2] + dist3(to, x[k - 2], y[k - 2], z[k - 2]) / JOIN_SPEED
        return Route(x, y, z, t, yaw, pitch, held)
    }

    private fun edgeTicks(g: Graph, a: Int, b: Int): Double {
        for (e in g.off[a] until g.off[a + 1]) if (g.to[e] == b) return g.ticks[e].toDouble()
        return 1.0
    }

    private fun dist3(p: Vec3, x: Double, y: Double, z: Double) = Math.sqrt((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y) + (p.z - z) * (p.z - z))
}
