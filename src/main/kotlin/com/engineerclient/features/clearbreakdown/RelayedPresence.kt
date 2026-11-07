package com.engineerclient.features.clearbreakdown

class RelayedPresence(private val staleMs: Long = 10_000) {
    private class Seen(val roomKey: String, val atMs: Long)

    private val seen = HashMap<Pair<String, String>, Seen>()

    fun update(from: String, player: String, roomKey: String?, nowMs: Long) {
        if (roomKey == null) seen.remove(from to player) else seen[from to player] = Seen(roomKey, nowMs)
    }

    fun roomOf(player: String, nowMs: Long): String? =
        seen.entries.filter { it.key.second == player && nowMs - it.value.atMs <= staleMs }.maxByOrNull { it.value.atMs }?.value?.roomKey

    fun clear() = seen.clear()
}
