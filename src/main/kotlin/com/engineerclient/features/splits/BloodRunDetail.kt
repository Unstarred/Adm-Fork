package com.engineerclient.features.splits

import java.util.Locale
import kotlin.math.abs

class BloodRunDetail {

    data class MapRoom(val id: String, val name: String, val fairy: Boolean, val entrance: Boolean)

    private class Room(var start: Stamp) {
        var name = UNNAMED
        var mapId: String? = null
        var toFairy = false
        var doorFell: Stamp? = null
        var mobKilled: Stamp? = null
        var keyPicked: Stamp? = null
        var keyBy = ""
        var doorOpened: Stamp? = null
        var doorBy = ""

        var startFrom = "the door line"
        var keySeenAt: Double? = null
        var pickupAnonymous = false
    }

    private val rooms = mutableListOf<Room>()
    private var room: Room? = null
    private var started = false
    private var done = false

    fun reset() {
        rooms.clear(); room = null; started = false; done = false
    }

    val active: Boolean get() = started && !done

    fun onDoorStart(at: Stamp, a: MapRoom?, b: MapRoom?) {
        val r = room ?: return
        if (rooms.isEmpty() && r.doorFell == null && (a?.entrance == true || b?.entrance == true)) {
            r.start = at
            r.startFrom = "the start door seen falling"
        }
    }

    fun onDoorDown(at: Stamp, a: MapRoom?, b: MapRoom?) {
        val r = room ?: return
        if (r.doorFell != null) return
        val sides = listOfNotNull(a, b)
        if (rooms.isEmpty()) {
            if (sides.none { it.entrance }) return
        } else if (r.doorOpened == null && at.realMs < r.start.realMs) return

        r.doorFell = at
        val previous = rooms.lastOrNull()
        val fairy = sides.firstOrNull { it.fairy }
        if (fairy != null && sides.any { it.id == previous?.mapId }) {
            r.name = fairy.name; r.mapId = fairy.id
            previous?.toFairy = true
            return
        }
        val next = sides.firstOrNull { !it.entrance && !it.fairy && it.id != previous?.mapId }
        if (next != null) { r.name = next.name; r.mapId = next.id }
        if (fairy != null && previous != null && previous.mapId != fairy.id) previous.toFairy = true
    }

    fun onKeySpawned(at: Stamp, distance: Double) {
        val r = room ?: return
        if (r.mobKilled == null) { r.mobKilled = at; r.keySeenAt = distance }
    }

    fun onChat(msg: String, at: Stamp) {
        if (done) return
        if (!started) {
            if (msg == MORT) { started = true; room = Room(at).also { it.startFrom = "Mort's line (start door not seen falling)" } }
            return
        }
        val r = room ?: return

        KEY_PICKED.find(msg)?.let { key ->
            if (r.keyPicked == null) {
                r.keyPicked = at
                r.keyBy = key.groupValues[1]
                r.pickupAnonymous = r.keyBy.isEmpty()
                if (r.mobKilled == null) r.mobKilled = at
            }
            return
        }

        WITHER_DOOR.find(msg)?.let { door ->
            r.doorOpened = at
            r.doorBy = door.groupValues[1]
            rooms += r
            room = Room(at)
            return
        }

        if (msg == BLOOD_DOOR) {
            r.doorOpened = at
            rooms += r
            room = null
            done = true
        }
    }

    enum class Level { COMPACT, DETAILED, DEBUG }

    private fun all() = rooms + listOfNotNull(room)

    fun lines(level: Level, now: Stamp, totalRow: Boolean = true): List<String> = when (level) {
        Level.COMPACT -> compact(now, totalRow)
        Level.DETAILED -> detailed(now)
        Level.DEBUG -> debug(now)
    }

    private fun compact(now: Stamp, totalRow: Boolean): List<String> {
        val out = mutableListOf<String>()
        for (r in all()) {
            val live = if (r === room) now else null
            out += row(name(r) + ": ", keySpan(r, live), pickupSpan(r, live), doorSpan(r, live), span(r.start, r.doorOpened))
        }
        if (totalRow && done && rooms.isNotEmpty()) {
            fun avg(f: (Room) -> Pair<Long, Long>?) = rooms.mapNotNull(f).takeIf { it.isNotEmpty() }?.let(::mean)
            out += row(TOTAL + "Avg: ", avg { keySpan(it, null) }, avg { pickupSpan(it, null) }, avg { doorSpan(it, null) }, avg { span(it.start, it.doorOpened) })
        }
        return out
    }

    private fun keySpan(r: Room, live: Stamp?): Pair<Long, Long>? {
        val from = r.doorFell ?: r.start.takeIf { r.keyPicked != null }
        return span(from, r.keyPicked ?: live)?.let { maxOf(it.first, 0L) to maxOf(it.second, 0L) }
    }

    private fun pickupSpan(r: Room, live: Stamp?) = span(r.mobKilled, r.keyPicked ?: live)
    private fun doorSpan(r: Room, live: Stamp?) = span(r.keyPicked, r.doorOpened ?: live)

    private fun row(name: String, key: Pair<Long, Long>?, pickup: Pair<Long, Long>?, door: Pair<Long, Long>?, total: Pair<Long, Long>?): String {
        val keyColour = if (pickup != null && pickup.first > SLOW_PICKUP_MS) SLOW_KEY else KEY
        val cells = listOf(
            door?.takeIf { it.first > SLOW_DOOR_MS }?.let { DOOR + SplitFormat.seconds(it.first) + " §8| " }.orEmpty(),
            key?.let { keyColour + SplitFormat.seconds(it.first) + " " }.orEmpty(),
            name,
            total?.let { TOTAL + SplitFormat.seconds(it.first) }.orEmpty(),
        )
        return cells.joinToString("\t").trimEnd('\t')
    }

    private fun detailed(now: Stamp): List<String> {
        val out = mutableListOf<String>()
        for (r in all()) {
            out += name(r)
            stats(r, now).forEachIndexed { i, s -> if (s != null) out += labelled(COLOURS[i], LABELS[i], s) }
            out += ""
        }
        averages()?.let { avg ->
            avg.forEachIndexed { i, s -> if (s != null) out += labelled(COLOURS[i], LABELS[i] + " avg", s) }
        }
        return out
    }

    private fun debug(now: Stamp): List<String> {
        val out = mutableListOf<String>()
        for (r in all()) {
            out += name(r)
            full(r, now).forEach { (i, s, who) -> if (s != null) out += labelled(FULL_COLOURS[i], FULL_LABELS[i], s) + by(who) }
            out += how(r)
            out += ""
        }
        if (done && rooms.isNotEmpty()) {
            for (i in FULL_LABELS.indices) {
                val vals = rooms.mapNotNull { full(it, now)[i].second }
                if (vals.isNotEmpty()) out += labelled(FULL_COLOURS[i], "average " + FULL_LABELS[i], mean(vals))
            }
        }
        return out
    }

    private fun how(r: Room): List<String> {
        val out = mutableListOf<String>()
        out += "§8· start: ${r.startFrom}"
        out += if (r.doorFell != null) "§8· door down: seen (its blocks)" else "§8· door down: not seen - too far away; key time counts from the start"
        out += when {
            r.keySeenAt != null -> "§8· key dropped: seen " + String.format(Locale.ROOT, "%.0f blocks", r.keySeenAt!!) + " away"
            r.keyPicked != null -> "§8· key dropped: not seen (out of render distance) - put at the pickup line"
            else -> "§8· key dropped: not yet"
        }
        if (r.keyPicked != null) out += if (r.pickupAnonymous) "§8· picked up: chat, with no name" else "§8· picked up: chat"
        if (r.doorOpened != null) out += "§8· opened: chat"
        if (r.name == UNNAMED) out += "§8· name: unknown - its door was never seen"
        span(r.start, r.doorOpened)?.let { (ms, ticks) ->
            val lag = ms - ticks * 50
            if (abs(lag) >= 100) out += "§8· lag: server " + SplitFormat.seconds(abs(lag)) + (if (lag > 0) " behind" else " ahead") + " real time"
        }
        return out
    }

    private fun span(from: Stamp?, to: Stamp?): Pair<Long, Long>? =
        if (from == null || to == null) null else (to.realMs - from.realMs) to (to.tick - from.tick).toLong()

    private fun stats(r: Room, now: Stamp): List<Pair<Long, Long>?> {
        val live = if (r === room) now else null
        val killFrom = r.doorFell ?: r.start.takeIf { r.mobKilled != null }
        return listOf(
            span(r.start, r.doorFell ?: live),
            span(killFrom, r.mobKilled ?: live)?.let { maxOf(it.first, 0L) to maxOf(it.second, 0L) },
            span(r.mobKilled, r.keyPicked ?: live),
            span(r.keyPicked, r.doorOpened ?: live),
            span(r.start, r.doorOpened),
        )
    }

    private fun full(r: Room, now: Stamp): List<Triple<Int, Pair<Long, Long>?, String>> {
        val live = if (r === room) now else null
        return listOf(
            Triple(0, span(r.start, r.doorFell ?: live), ""),
            Triple(1, span(r.start, r.mobKilled ?: live), ""),
            Triple(2, span(r.start, r.keyPicked ?: live?.takeIf { r.mobKilled != null }), r.keyBy),
            Triple(3, span(r.mobKilled, r.keyPicked ?: live), ""),
            Triple(4, span(r.start, r.doorOpened ?: live?.takeIf { r.keyPicked != null }), r.doorBy),
            Triple(5, span(r.keyPicked, r.doorOpened ?: live), ""),
            Triple(6, span(r.start, r.doorOpened), ""),
        )
    }

    private fun averages(): List<Pair<Long, Long>?>? {
        if (!done || rooms.isEmpty()) return null
        val cols = (0..4).map { i -> rooms.mapNotNull { stats(it, it.doorOpened!!)[i] } }
        return cols.map { if (it.isEmpty()) null else mean(it) }
    }

    private fun mean(vals: List<Pair<Long, Long>>) = vals.sumOf { it.first } / vals.size to vals.sumOf { it.second } / vals.size

    private fun labelled(colour: String, label: String, s: Pair<Long, Long>) =
        "$colour$label §b> $colour" + SplitFormat.seconds(s.first) + " §8(§7" + SplitFormat.seconds(s.second * 50L) + "§8)"

    private fun name(r: Room) = (if (r.toFairy) FAIRY else NAME) + r.name

    private fun by(who: String) = if (who.isEmpty()) "" else " §7$who"

    private companion object {
        const val UNNAMED = "..."
        const val NAME = "§5"
        const val FAIRY = "§d"
        const val TOTAL = "§6"

        const val KEY = "§c"
        const val SLOW_KEY = "§4"
        const val DOOR = "§7"
        const val SLOW_PICKUP_MS = 350L
        const val SLOW_DOOR_MS = 250L

        val LABELS = listOf("door fell", "last mob", "pickup", "opened", "room total")
        val COLOURS = listOf("§8", "§7", "§c", "§4", "§6")

        val FULL_LABELS = listOf("door fell", "last mob killed", "key picked up", "key delta", "door opened", "door delta", "total room time")
        val FULL_COLOURS = listOf("§8", "§7", "§c", "§c", "§4", "§4", "§6")

        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
        val KEY_PICKED = Regex("""^(?:\[[^\]]+] )?(\w+) has obtained (?:Wither|Blood) Key!$|^A (?:Wither|Blood) Key was picked up!$""")
        val WITHER_DOOR = Regex("""^(\w+) opened a WITHER door!$""")
    }
}
