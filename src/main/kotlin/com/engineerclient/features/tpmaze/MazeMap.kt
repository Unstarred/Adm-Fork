package com.engineerclient.features.tpmaze

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object MazePads {
    const val END = 28
    const val START = 29
    const val START_AREA = 7
    const val END_AREA = 8

    val FRAMES: Array<IntArray> = arrayOf(
        intArrayOf(4, 12), intArrayOf(4, 6), intArrayOf(10, 12), intArrayOf(10, 6), intArrayOf(4, 20), intArrayOf(4, 14), intArrayOf(10, 20), intArrayOf(10, 14),
        intArrayOf(4, 28), intArrayOf(4, 22), intArrayOf(10, 28), intArrayOf(10, 22), intArrayOf(12, 28), intArrayOf(12, 22), intArrayOf(18, 28), intArrayOf(18, 22),
        intArrayOf(20, 28), intArrayOf(20, 22), intArrayOf(26, 28), intArrayOf(26, 22), intArrayOf(26, 20), intArrayOf(26, 14), intArrayOf(20, 20), intArrayOf(20, 14),
        intArrayOf(26, 12), intArrayOf(26, 6), intArrayOf(20, 12), intArrayOf(20, 6), intArrayOf(15, 14), intArrayOf(15, 12),
    )

    val LAND: Array<IntArray> = Array(30) { i ->
        val (x, z) = FRAMES[i].let { it[0] to it[1] }
        intArrayOf(x + if (i == END || x % 8 == 4) 1 else -1, z + if (z % 8 == 6) 1 else -1)
    }

    private val CELL_X0 = intArrayOf(4, 4, 4, 12, 20, 20, 20)
    private val CELL_Z0 = intArrayOf(6, 14, 22, 22, 22, 14, 6)

    fun cell(p: Int): Int = when { p < 28 -> p / 4; p == START -> START_AREA; else -> END_AREA }

    fun padsOf(c: Int): IntRange = c * 4 until c * 4 + 4

    fun label(p: Int): String = when (p) {
        END -> "END"
        START -> "START"
        else -> "${cell(p) + 1}${if (FRAMES[p][1] == CELL_Z0[cell(p)]) 'N' else 'S'}${if (FRAMES[p][0] == CELL_X0[cell(p)]) 'W' else 'E'}"
    }

    fun regionAt(x: Double, z: Double): Int {
        for (c in 0 until 7) if (x >= CELL_X0[c] && x < CELL_X0[c] + 7 && z >= CELL_Z0[c] && z < CELL_Z0[c] + 7) return c
        if (x >= 11 && x < 20 && z >= 4 && z < 13.5) return START_AREA
        if (x >= 11 && x < 20 && z >= 13.5 && z < 22) return END_AREA
        return -1
    }

    fun landingAt(x: Double, z: Double): Int {
        for (i in 0 until 30) if (abs(x - LAND[i][0] - 0.5) < 0.05 && abs(z - LAND[i][1] - 0.5) < 0.05) return i
        return -1
    }

    fun padUnder(x: Double, z: Double, reach: Double = 1.5): Int {
        val pads = when (val r = regionAt(x, z)) { in 0 until 7 -> padsOf(r).toList(); START_AREA -> listOf(START); END_AREA -> listOf(END); else -> return -1 }
        return pads.minByOrNull { hypot(x - FRAMES[it][0] - 0.5, z - FRAMES[it][1] - 0.5) }?.takeIf { hypot(x - FRAMES[it][0] - 0.5, z - FRAMES[it][1] - 0.5) <= reach } ?: -1
    }

    fun yawTo(x: Double, z: Double, p: Int): Double = Math.toDegrees(atan2(-(LAND[p][0] + 0.5 - x), LAND[p][1] + 0.5 - z))

    fun aim(land: Int, yaw: Double, tol: Double = 0.5): List<Int> {
        val lx = LAND[land][0] + 0.5; val lz = LAND[land][1] + 0.5
        val dx = -sin(Math.toRadians(yaw)); val dz = cos(Math.toRadians(yaw))
        return (0 until 28).filter { p ->
            if (p == land) return@filter false
            val tx = LAND[p][0] + 0.5 - lx; val tz = LAND[p][1] + 0.5 - lz
            if (tx * dx + tz * dz <= 0) return@filter false
            abs(((yawTo(lx, lz, p) - yaw) % 360 + 540) % 360 - 180) <= tol
        }
    }
}

class MazeMap {
    private val partner = IntArray(30) { -1 }
    private val sure = BooleanArray(30)
    private var exitPad = -1
    private val candidate = BooleanArray(28) { true }
    private val looks = LinkedHashMap<Int, Double>()

    fun partnerOf(p: Int): Int? = partner[p].takeIf { it >= 0 }

    fun known(p: Int): Boolean = partner[p] >= 0 || p == exitPad

    fun links(sureOnly: Boolean = false): List<IntArray> = (0 until 30).filter { partner[it] > it && (!sureOnly || sure[it]) }.map { intArrayOf(it, partner[it]) }

    fun looks(): Map<Int, Double> = looks

    fun link(from: Int, to: Int, exact: Boolean = true): Boolean {
        if (from == to || from !in 0 until 30 || to !in 0 until 30 || from == MazePads.END) return false
        if (to == MazePads.END) {
            if (from >= 28 || exitPad == from || partner[from] >= 0) return false
            exitPad = from
            return true
        }
        if (from == exitPad || to == exitPad) return false
        if (partner[from] == to) { if (exact && !sure[from]) { sure[from] = true; sure[to] = true; return true }; return false }
        val clash = intArrayOf(from, to).filter { partner[it] >= 0 }
        if (clash.any { !exact || sure[it] }) return false
        for (p in clash) { val q = partner[p]; if (q >= 0) { partner[q] = -1; sure[q] = false; partner[p] = -1 } }
        partner[from] = to; partner[to] = from
        sure[from] = exact; sure[to] = exact
        if (from < 28) candidate[from] = false
        if (to < 28) candidate[to] = false
        return true
    }

    fun look(land: Int, yaw: Double): Boolean {
        if (land !in 0 until 28 && land != MazePads.START) return false
        val on = MazePads.aim(land, yaw).toSet()
        if (on.none { candidate[it] }) return false
        if (looks.containsKey(land)) return false
        looks[land] = yaw
        for (p in 0 until 28) if (p !in on) candidate[p] = false
        if (land < 28) candidate[land] = false
        return true
    }

    val final: Int?
        get() = exitPad.takeIf { it >= 0 } ?: candidates().singleOrNull()

    fun candidates(): List<Int> = if (exitPad >= 0) listOf(exitPad) else if (looks.isEmpty()) emptyList() else (0 until 28).filter { candidate[it] && partner[it] < 0 }

    fun finalCells(): Set<Int> = candidates().mapTo(HashSet()) { MazePads.cell(it) }

    fun merge(links: List<IntArray>, exit: Int?, looks: Map<Int, Double>): Boolean {
        var changed = false
        for (l in links) if (l.size == 2) changed = link(l[0], l[1]) or changed
        if (exit != null) changed = link(exit, MazePads.END) or changed
        for ((land, yaw) in looks) changed = look(land, yaw) or changed
        return changed
    }

    val exit: Int? get() = exitPad.takeIf { it >= 0 }
}

object MazePlan {
    enum class Kind {
         FINAL,
         ROUTE,
         EXPLORE,
         DETOUR,
         START,
    }

    class Advice(val pad: Int, val kind: Kind, val steps: Int)

    fun plan(map: MazeMap, where: Map<String, Int>): Map<String, Advice> {
        val out = HashMap<String, Advice>()
        val fin = map.final
        val finCells = map.finalCells()
        val claimed = HashSet<Int>()
        val open = ArrayList<Pair<String, Int>>()
        for ((name, r) in where) {
            when {
                r == MazePads.START_AREA -> out[name] = Advice(MazePads.START, Kind.START, 1)
                r !in 0 until 7 -> {}
                fin != null && MazePads.cell(fin) == r -> out[name] = Advice(fin, Kind.FINAL, 1)
                else -> {
                    val route = if (finCells.size == 1 && r !in finCells) route(map, r) { it in finCells } else null
                    if (route != null) out[name] = Advice(route.pad, Kind.ROUTE, route.steps + 1) else open += name to r
                }
            }
        }
        val needDetour = ArrayList<Pair<String, Int>>()
        for ((cell, names) in open.groupBy({ it.second }, { it.first }).toSortedMap()) {
            val maybe = map.candidates().toSet()
            val fresh = MazePads.padsOf(cell).filter { !map.known(it) }.sortedBy { if (it in maybe) 0 else 1 }
            for ((i, name) in names.sorted().withIndex()) {
                if (i < fresh.size) { out[name] = Advice(fresh[i], Kind.EXPLORE, 1); claimed += fresh[i] }
                else needDetour += name to cell
            }
        }
        val sent = HashMap<Int, Int>()
        for ((name, cell) in needDetour.sortedBy { it.first }) {
            val way = route(map, cell) { c -> c != cell && MazePads.padsOf(c).count { !map.known(it) && it !in claimed } > (sent[c] ?: 0) }
            if (way != null) sent.merge(way.cell, 1, Int::plus)
            out[name] = when {
                way != null -> Advice(way.pad, Kind.DETOUR, way.steps)
                else -> {
                    val fresh = MazePads.padsOf(cell).filter { !map.known(it) }
                    val any = fresh.firstOrNull() ?: MazePads.padsOf(cell).firstOrNull { map.partnerOf(it) != MazePads.START } ?: MazePads.padsOf(cell).first()
                    Advice(any, if (any in fresh) Kind.EXPLORE else Kind.DETOUR, 1)
                }
            }
        }
        return out
    }

    class Way(val pad: Int, val steps: Int, val cell: Int)

    fun route(map: MazeMap, from: Int, goal: (Int) -> Boolean): Way? {
        val dist = HashMap<Int, Int>(); val first = HashMap<Int, Int>()
        val queue = ArrayDeque<Int>()
        dist[from] = 0; queue += from
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c != from && c in 0 until 7 && goal(c)) return Way(first[c]!!, dist[c]!!, c)
            val moves = if (c == MazePads.START_AREA) listOfNotNull(map.partnerOf(MazePads.START)?.let { MazePads.START to MazePads.cell(it) })
            else MazePads.padsOf(c).mapNotNull { p -> map.partnerOf(p)?.let { p to MazePads.cell(it) } }
            for ((pad, next) in moves) {
                if (next in dist) continue
                dist[next] = dist[c]!! + 1
                first[next] = if (c == from) pad else first[c]!!
                queue += next
            }
        }
        return null
    }
}

class RoomFrame(private val ox: Int, private val oz: Int, private val axX: Int, private val axZ: Int, private val azX: Int, private val azZ: Int) {
    fun toRoom(x: Double, z: Double): DoubleArray {
        val dx = x - ox - 0.5; val dz = z - oz - 0.5
        return doubleArrayOf(dx * axX + dz * axZ + 0.5, dx * azX + dz * azZ + 0.5)
    }

    fun toWorld(x: Double, z: Double): DoubleArray =
        doubleArrayOf(ox + 0.5 + axX * (x - 0.5) + azX * (z - 0.5), oz + 0.5 + axZ * (x - 0.5) + azZ * (z - 0.5))

    fun yawToRoom(yaw: Double): Double {
        val dx = -sin(Math.toRadians(yaw)); val dz = cos(Math.toRadians(yaw))
        return Math.toDegrees(atan2(-(dx * axX + dz * axZ), dx * azX + dz * azZ))
    }

    fun sameAs(o: RoomFrame) = ox == o.ox && oz == o.oz && axX == o.axX && axZ == o.axZ && azX == o.azX && azZ == o.azZ

    val anchor: IntArray get() = intArrayOf(ox, oz)
}
