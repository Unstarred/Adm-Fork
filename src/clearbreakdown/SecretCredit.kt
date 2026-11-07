package com.engineerclient.clearbreakdown

import kotlin.math.abs

data class RoomSecrets(val room: String, val count: Int, val estimate: Boolean)

data class PlayerSecrets(val total: Int?, val rooms: List<RoomSecrets>, val unknown: Int)

class SecretCredit(private val mergeMs: Long = 1000, private val exactMs: Long = 3000) {
    private class Group(val roomKey: String, val roomName: String, var candidates: Set<String>, var exact: Boolean, val atMs: Long, var units: Int, val gap: Boolean = false)

    private val groups = ArrayList<Group>()
    private val byKey = HashMap<String, Group>()
    private val lastSeen = HashMap<String, Int>()
    private val directSince = HashMap<String, Int>()
    private val visited = HashMap<String, HashSet<String>>()
    private var present: Map<String, String?> = emptyMap()

    fun tick(presence: Map<String, String?>) {
        present = presence
        for ((player, room) in presence) if (room != null) visited.getOrPut(room) { HashSet() } += player
    }

    fun sighting(key: String, roomKey: String, roomName: String, candidates: Set<String>, exact: Boolean, atMs: Long) {
        val known = byKey[key]
        if (known != null) {
            if (exact && !known.exact && abs(atMs - known.atMs) <= exactMs) { known.candidates = candidates; known.exact = true }
            else if (!exact && !known.exact && abs(atMs - known.atMs) <= mergeMs) known.candidates = known.candidates + candidates
            return
        }
        val gap = groups.lastOrNull { it.gap && it.roomKey == roomKey && atMs - it.atMs in 0..GAP_CATCH_MS && it.units > 0 }
        if (gap != null) gap.units-- else directSince[roomKey] = (directSince[roomKey] ?: 0) + 1
        val g = Group(roomKey, roomName, candidates, exact, atMs, 1)
        groups += g
        byKey[key] = g
    }

    fun observe(roomKey: String, roomName: String, found: Int, atMs: Long = System.currentTimeMillis()) {
        val last = lastSeen[roomKey] ?: 0
        if (found <= last) return
        val gap = found - last - (directSince[roomKey] ?: 0)
        if (gap > 0) groups += Group(roomKey, roomName, visited[roomKey].orEmpty().toSet(), false, atMs, gap, gap = true)
        lastSeen[roomKey] = found
        directSince[roomKey] = 0
        visited[roomKey] = present.filterValues { it == roomKey }.keys.toHashSet()
    }

    fun resolve(apiTotals: Map<String, Int?>): Map<String, PlayerSecrets> {
        val left = HashMap<String, Int>()
        for ((p, t) in apiTotals) left[p] = t ?: Int.MAX_VALUE
        val credits = ArrayList<Credit>()
        val remaining = IntArray(groups.size)
        val cands = groups.map { g -> g.candidates.filter { it in left }.sorted() }

        fun give(i: Int, p: String, n: Int, estimate: Boolean) {
            if (n <= 0) return
            credits += Credit(i, p, n, estimate)
            left[p] = (left.getValue(p) - n).coerceAtLeast(0)
        }

        for ((i, g) in groups.withIndex()) {
            if (g.units <= 0) continue
            if (cands[i].size == 1) give(i, cands[i][0], g.units, false) else if (cands[i].size > 1) remaining[i] = g.units
        }
        var changed = true
        while (changed) {
            changed = false
            for (i in groups.indices) {
                while (remaining[i] > 0) {
                    val eligible = cands[i].filter { left.getValue(it) > 0 }
                    if (eligible.size > 1) break
                    if (eligible.size == 1) give(i, eligible[0], 1, false)
                    remaining[i]--
                    changed = true
                }
            }
        }
        for (i in groups.indices) {
            val n = remaining[i]
            if (n <= 0) continue
            val eligible = cands[i].filter { left.getValue(it) > 0 }
            if (eligible.isEmpty()) continue
            val unlimited = eligible.any { left.getValue(it) == Int.MAX_VALUE }
            val weights = eligible.map { if (unlimited) 1.0 else left.getValue(it).toDouble() }
            val sum = weights.sum()
            val exact = weights.map { n * it / sum }
            val shares = exact.map { it.toInt() }.toIntArray()
            val byRemainder = eligible.indices.sortedWith(compareByDescending<Int> { exact[it] - shares[it] }.thenBy { it })
            for (k in 0 until n - shares.sum()) shares[byRemainder[k % eligible.size]]++
            for ((k, p) in eligible.withIndex()) give(i, p, shares[k].coerceAtMost(left.getValue(p)), true)
        }

        return playerSecrets(apiTotals, credits)
    }

    private fun playerSecrets(apiTotals: Map<String, Int?>, credits: List<Credit>): Map<String, PlayerSecrets> {
        return apiTotals.mapValues { (p, total) ->
            val rooms = LinkedHashMap<String, RoomSecrets>()
            for (c in credits.filter { it.player == p }.sortedBy { it.group }) {
                val name = groups[c.group].roomName
                val had = rooms[name]
                rooms[name] = RoomSecrets(name, (had?.count ?: 0) + c.count, (had?.estimate ?: false) || c.estimate)
            }
            val placed = rooms.values.sumOf { it.count }
            PlayerSecrets(total, rooms.values.toList(), if (total != null) (total - placed).coerceAtLeast(0) else 0)
        }
    }

    fun reset() {
        groups.clear(); byKey.clear(); lastSeen.clear(); directSince.clear(); visited.clear(); present = emptyMap()
    }

    private class Credit(val group: Int, val player: String, val count: Int, val estimate: Boolean)

    private companion object {
        const val GAP_CATCH_MS = 3000L
    }
}
