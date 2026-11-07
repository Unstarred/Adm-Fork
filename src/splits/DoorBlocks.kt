package com.engineerclient.splits

import kotlin.math.abs

object DoorBlocks {

    const val DOOR_BLOCKS = 30

    data class Door(val a: Pair<Int, Int>, val b: Pair<Int, Int>)

    fun doors(blocks: List<Pair<Int, Int>>): List<Door> {
        val groups = mutableListOf<MutableList<Pair<Int, Int>>>()
        for (p in blocks) {
            val g = groups.firstOrNull { abs(it[0].first - p.first) <= 4 && abs(it[0].second - p.second) <= 4 }
            if (g != null) g += p else groups += mutableListOf(p)
        }
        return groups.mapNotNull { g ->
            if (g.size < DOOR_BLOCKS) return@mapNotNull null
            val tx = (g.sumOf { it.first }.toDouble() / g.size + 185) / 32
            val tz = (g.sumOf { it.second }.toDouble() / g.size + 185) / 32
            if (abs(tx - Math.round(tx)) < 0.1 && abs(tz - Math.round(tz)) < 0.1) return@mapNotNull null
            Door(
                Math.floor(tx + 0.01).toInt() to Math.floor(tz + 0.01).toInt(),
                Math.ceil(tx - 0.01).toInt() to Math.ceil(tz - 0.01).toInt(),
            )
        }
    }
}
