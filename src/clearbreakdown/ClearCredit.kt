package com.engineerclient.clearbreakdown

enum class Mark { WHITE, GREEN, RED, OTHER }

data class Clear(val room: String, val solo: Boolean)

class ClearCredit(private val helpTicks: Int = 40) {
    private val ticks = HashMap<String, HashMap<String, Int>>()
    private val credited = HashSet<String>()
    private val order = ArrayList<Pair<String, Set<String>>>()
    private var present: Map<String, String?> = emptyMap()

    fun tick(presence: Map<String, String?>) {
        present = presence
        for ((player, room) in presence) {
            if (room == null || room in credited) continue
            val inRoom = ticks.getOrPut(room) { HashMap() }
            inRoom[player] = (inRoom[player] ?: 0) + 1
        }
    }

    fun onCheckmark(roomKey: String, roomName: String, mark: Mark) {
        if (roomKey in credited) return
        when (mark) {
            Mark.RED -> ticks.remove(roomKey)
            Mark.WHITE, Mark.GREEN -> {
                val helpers = LinkedHashSet<String>()
                ticks[roomKey]?.forEach { (player, n) -> if (n > helpTicks) helpers += player }
                present.forEach { (player, room) -> if (room == roomKey) helpers += player }
                credited += roomKey
                ticks.remove(roomKey)
                if (helpers.isNotEmpty()) order += roomName to helpers
            }
            Mark.OTHER -> {}
        }
    }

    fun clears(): Map<String, List<Clear>> {
        val out = LinkedHashMap<String, MutableList<Clear>>()
        for ((room, helpers) in order) for (p in helpers) out.getOrPut(p) { ArrayList() } += Clear(room, helpers.size == 1)
        return out
    }

    fun reset() {
        ticks.clear(); credited.clear(); order.clear(); present = emptyMap()
    }
}
