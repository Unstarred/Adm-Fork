package com.engineerclient.p3sim

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.ARCHER
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.BERSERK
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.HEALER
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.MAGE
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.TANK
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object Roles {
    class Preset(val name: String, val roles: Map<DungeonClass, String>, val times: List<String>, val moves: List<String>, val helps: List<Help> = emptyList(), val spots: Map<String, Vec3> = emptyMap(), val defaults: Map<String, Spots.Spot> = emptyMap(), val mines: Map<String, List<BlockPos>> = emptyMap())

    class Help(val section: Int, val yours: String, val by: String, val jobs: List<String>, val at: Double)

    val PF = Preset(
        "PF",
        roles = linkedMapOf(
            MAGE to "bl ee2 / dev 2 / core / recore",
            ARCHER to "43 / 4 rl / 4 bl / 2",
            HEALER to "ss / ll ee3 / 2 dev / 4 bl",
            TANK to "21 / 13 / 1 bl / 1",
            BERSERK to "i4 / 53 / 3 / 3 bl",
        ),
        times = listOf(
            "ll 0.35 | rl 2.15 | gate 2.85 | i4 5.1 | lights 7.1 | terms 9.0 | ss 9.6",
            "ll 2.8 | 1 4 5 3.0 | gate 3.4 | 2 3 rl 7.0",
            "1 4 3.0 | 2 3 5.0 | levers gate 8.0 | dev 9.0",
            "1 2 4.0 | 3 4 6.0 | levers 8.0",
        ),
        moves = listOf(
            "i4 leaps 5.8 | ee2 spot 7.8 | leap ee2 9.4",
            "ee3 spot 5.0 | ee3 waits 1 2 3 4",
            "", "",
        ),
    )

    val QUALITY_PF = Preset(
        "Quality PF",
        roles = linkedMapOf(
            MAGE to "bl ee2 / dev 2 3 / core / recore",
            ARCHER to "43 / 4 rl / 4 bl / 2",
            HEALER to "ss / (leap to 2nd term) 1 / 2 dev / 4 bl",
            TANK to "21 / ll ee3 / 1 bl / 1",
            BERSERK to "i4 / 53 / 3 dev / 3 bl",
        ),
        times = listOf(
            "ll 0.15 | rl 1.75 | gate 2.05 | i4 3.4 | lights 5.1 | terms 6.0 | ss 8.2",
            "ll 0.8 | gate 1.0 | 1 4 5 2.1 | 2 3 rl 5.0",
            "1 3 4 2.1 | 2 4.0 | levers gate 6.0 | dev 6.5",
            "1 3 4 2.1 | 2 4.0 | levers 5.2",
        ),
        moves = listOf(
            "i4 leaps 4.0 | ee2 spot 5.8 | leap ee2 6.3 | ee2 waits ee3 4 5",
            "ee3 spot 3.2 | ee3 waits 1 2 3 4",
            "", "",
        ),
        helps = listOf(
            Help(3, "S3 T4", "S3 T1", listOf("S3 east lever"), 4.8),
            Help(3, "S3 T1", "S3 T4", listOf("S3 west lever", "gate 3"), 4.8),
            Help(4, "S4 T3", "S4 T4", listOf("S4 high lever"), 4.2),
            Help(4, "S4 T4", "S4 T3", listOf("S4 low lever"), 4.2),
        ),
    )

    val ADVANCED_PF = Preset(
        "Advanced PF",
        roles = linkedMapOf(
            MAGE to "bl ee2 / dev 53 / core / recore",
            ARCHER to "43 / 2 / 4 bl / 2",
            HEALER to "ss / 1 / 2 dev / 4 bl",
            TANK to "21 / ll ee3 / 1 bl / 1",
            BERSERK to "i4 / 4 rl / 3 dev / 3 bl",
        ),
        times = listOf(
            "ll 0.15 | rl 1.75 | gate 2.05 | i4 3.4 | lights 5.1 | terms 6.0 | ss 8.2",
            "ll 0.8 | gate 1.0 | 1 2 4 5 2.1 | 3 3.8 | rl 4.0",
            "1 2 3 4 2.1 | levers gate 4.2 | dev 5.0",
            "1 2 3 4 2.1 | levers 4.0",
        ),
        moves = listOf(
            "i4 leaps 4.0 | ee2 spot 5.8 | leap ee2 6.3 | ee2 waits ee3 2 4",
            "ee3 spot 3.6 | ee3 waits 1 2 3 4",
            "", "",
        ),
    )

    val NEAR_OPTIMAL = Preset(
        "Near Optimal",
        roles = linkedMapOf(
            MAGE to "bl ee2 / dev 53 / core / 4 recore",
            ARCHER to "43 / 2 / i3 4 bl / 2 recore",
            HEALER to "ss / 1 / 1 / 1 recore",
            TANK to "21 / hee3 / 3 bl / bl",
            BERSERK to "i4 / bl 4 / 2 / 3 recore",
        ),
        times = listOf(
            "ll 0.15 | 2 1.3 | rl 1.5 | gate 1.8 | 4 2.95 | 1 3.0 | i4 3.0 | lights 4.6 | 3 5.0 | ss 7.8",
            "ll 0.25 | rl 1.05 | gate 1.25 | 2 1.25 | 5 1.6 | 1 2.9 | 3 2.95 | 4 4.0 | arrows 4.3",
            "3 1.25 | 4 2.0 | 1 2.95 | 2 3.35 | ll 3.6 | gate 3.8 | rl 4.4",
            "2 1.9 | 4 1.9 | 1 2.0 | 3 2.1 | ll 2.4 | rl 3.2",
        ),
        moves = listOf(
            "i4 leaps 3.4 | ee2 spot 5.0 | leap ee2 5.6 | ee2 waits 2 4",
            "ee3 spot 2.5 | ee3 waits 1 2",
            "", "",
        ),
        defaults = mapOf("ee3" to Spots.Spot("EE3", 18.70, 121.50, 91.30, 0.82f, -8.64f)),
        mines = mapOf("ee3" to listOf(15, 16).flatMap { x -> listOf(93, 94).map { BlockPos(x, 122, it) } }),
    )

    val PROTOTYPE_1 = Preset(
        "Prototype 1",
        roles = linkedMapOf(
            HEALER to "ss (leap archer) / 1 (leap bers) / 1 (leap mage) / 1 recore",
            MAGE to "bl 43 / 53 / / 4 recore",
            BERSERK to "i4 / ee3 / 3 bl / bl",
            ARCHER to "21 / 2 / dev 4 (leap healer) / 2 recore",
            TANK to "ee2 / dev bl 4 / 2 / 3 recore",
        ),
        times = listOf(
            "ll 0.1 | lights 0.1 | rl 0.45 | gate 0.6 | 2 2.75 | i4 3.05 | 4 3.35 | 1 4.85 | 3 5.45 | ss 8.1",
            "ll 0.1 | rl 0.8 | gate 1.0 | 5 2 2.1 | 1 3.0 | arrows 3.6 | 3 4.3 | 4 4.4",
            "4 2.1 | 3 2.15 | 1 2.45 | 2 2.95 | ll 4.0 | gate 4.2 | rl 4.4",
            "ll 1.1 | rl 1.75 | terms 2.1",
        ),
        moves = listOf(
            "ee2 waits 2 5 ee3",
            "ee3 waits 1 2 4",
            "",
            "",
        ),
        spots = mapOf(
            "ee2" to Vec3(34.05, 131.0, 139.05),
            "ee3" to Vec3(16.5, 123.0, 93.7),
        ),
    )

    val THEORETICAL = Preset(
        "Theoretical",
        roles = linkedMapOf(
            HEALER to "ss / 1 / 1 / 1",
            BERSERK to "i4 / ee3 / 3 bl / bl",
            TANK to "21 / 2 / i3 4 / 2",
            MAGE to "i2 gate ee2 / bl 4 / 2 / 3",
            ARCHER to "bl 43 / 53 / ee4 / 4",
        ),
        times = listOf(
            "ll 0.05 | lights gate 0.2 | rl 0.7 | 2 1.3 | i4 2.05 | 4 2.95 | 1 3.0 | 3 5.05 | ss 7.8",
            "gate 0.05 | ll 0.25 | rl 1.05 | 2 1.25 | 5 1.6 | 1 2.9 | 3 2.95 | 4 4.15 | arrows 4.5",
            "3 1.25 | 4 2.75 | 1 2.95 | 2 3.35 | gate 3.4 | ll 3.85 | rl 4.5",
            "3 1.7 | 2 1.85 | 1 2.0 | 4 2.6 | ll 2.2 | rl 3.0",
        ),
        moves = listOf(
            "ee2 spot 1.85 | leap ee2 3.5 | ee2 waits ee3 2 5",
            "ee3 waits 1 2 4",
            "ee4 spot 2.95 | ee4 waits 1 3 | 2 onto 1",
            "",
        ),
        defaults = mapOf(
            "ee2" to Spots.Spot("i2", 35.99, 131.0, 138.96, 92.20f, 3.86f),
            "ee3" to Spots.Spot("EE3", 18.70, 121.50, 91.30, 0.82f, -8.64f),
            "spawn/MAGE" to Spots.Spot("Spawn", 60.5, 132.0, 139.0, 90.47f, 1.64f),
            "spawn/ARCHER" to Spots.Spot("Spawn", 107.53, 123.06, 112.45, 47.59f, 19.81f),
            "stand/S2 T2" to Spots.Spot("S2 T2", 59.50, 120.00, 125.20, -179.66f, 23.96f),
        ),
        mines = mapOf("ee3" to listOf(15, 16).flatMap { x -> listOf(93, 94).map { BlockPos(x, 122, it) } }),
    )

    val PRESETS = listOf(PF, QUALITY_PF, ADVANCED_PF, PROTOTYPE_1, THEORETICAL, NEAR_OPTIMAL)

    val LEFT = mapOf(1 to "S1 east lever", 2 to "S2 high lever", 3 to "S3 west lever", 4 to "S4 low lever")
    val RIGHT = mapOf(1 to "S1 west lever", 2 to "S2 low lever", 3 to "S3 east lever", 4 to "S4 high lever")

    class Move(val section: Int, val kind: String, val who: String, val at: Double?, val args: List<String> = emptyList())

    class Plan(
        val owners: Map<String, List<DungeonClass>>,
        val jobsOf: Map<DungeonClass, List<String>>,
        val ee: Map<Int, DungeonClass>,
        val times: Map<String, Pair<Int, Double>>,
        val moves: List<Move>,
    )

    private val cache = HashMap<Preset, Plan>()
    fun plan(p: Preset): Plan = cache.getOrPut(p) { parse(p) }

    private fun device(s: Int) = when (s) { 1 -> "S1 SS"; 2 -> "S2 Lights"; 3 -> "S3 Arrows"; else -> "S4 Target" }
    private fun terms(s: Int) = (1..(if (s == 2) 5 else 4)).map { "S$s T$it" }

    private fun jobs(token: String, s: Int): List<String> = when {
        token.all { it.isDigit() } -> token.map { "S$s T$it" }.filter { it in terms(s) }
        token == "ss" -> listOf("S1 SS")
        token == "i4" -> listOf("S4 Target")
        token == "i2" -> listOf("S2 Lights")
        token == "i3" -> listOf("S3 Arrows")
        token == "lights" -> listOf("S2 Lights")
        token == "arrows" -> listOf("S3 Arrows")
        token == "dev" -> listOf(device(s))
        token == "ll" -> listOf(LEFT.getValue(s))
        token == "rl" -> listOf(RIGHT.getValue(s))
        token == "bl" || token == "levers" -> listOf(LEFT.getValue(s), RIGHT.getValue(s))
        token == "terms" -> terms(s)
        token == "gate" && s <= 3 -> listOf("gate $s")
        else -> emptyList()
    }

    private fun tokens(line: String) = line.replace(Regex("\\([^)]*\\)"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }

    private fun parse(p: Preset): Plan {
        val owners = LinkedHashMap<String, MutableList<DungeonClass>>()
        val jobsOf = LinkedHashMap<DungeonClass, MutableList<String>>()
        val ee = HashMap<Int, DungeonClass>()
        val leverFirst = HashMap<Int, MutableList<DungeonClass>>()
        val leverRight = HashMap<Int, MutableList<DungeonClass>>()
        for ((c, line) in p.roles) {
            val mine = jobsOf.getOrPut(c) { ArrayList() }
            line.split('/').forEachIndexed { i, part ->
                val s = i + 1
                if (s > 4) return@forEachIndexed
                for (t in tokens(part)) {
                    when {
                        t.startsWith("ee") && t.drop(2).toIntOrNull() != null -> ee[t.drop(2).toInt()] = c
                        t.startsWith("hee") && t.drop(3).toIntOrNull() != null -> ee[t.drop(3).toInt()] = c
                        t == "core" -> ee[5] = c
                    }
                    if (t == "ll" || t == "bl") leverFirst.getOrPut(s) { ArrayList() } += c
                    if (t == "rl") leverRight.getOrPut(s) { ArrayList() } += c
                    for (j in jobs(t, s)) {
                        if (j !in mine) mine += j
                        val o = owners.getOrPut(j) { ArrayList() }
                        if (c !in o) o += c
                    }
                }
            }
        }
        for (s in 1..3) if ("gate $s" !in owners) (leverFirst[s] ?: leverRight[s])?.distinct()?.forEach { c ->
            jobsOf.getValue(c) += "gate $s"
            owners.getOrPut("gate $s") { ArrayList() } += c
        }
        val times = parseTimes(p)
        val moves = parseMoves(p)
        return Plan(owners, jobsOf, ee, times, moves)
    }

    private fun parseTimes(p: Preset): HashMap<String, Pair<Int, Double>> {
        val times = HashMap<String, Pair<Int, Double>>()
        p.times.forEachIndexed { i, line ->
            val s = i + 1
            for (group in line.split('|')) {
                val ts = tokens(group)
                val at = ts.lastOrNull()?.toDoubleOrNull() ?: continue
                ts.dropLast(1).flatMap { jobs(it, s) }.forEach { times[it] = s to at }
            }
        }
        return times
    }

    private fun parseMoves(p: Preset): ArrayList<Move> {
        val moves = ArrayList<Move>()
        p.moves.forEachIndexed { i, line ->
            val s = i + 1
            for (group in line.split('|')) {
                val ts = tokens(group)
                when {
                    ts.size >= 2 && ts[0] == "leap" -> moves += Move(s, "preleap", ts[1], ts.getOrNull(2)?.toDoubleOrNull())
                    ts.size >= 2 && ts[0] == "hold" -> moves += Move(s, "hold", ts[1], null)
                    ts.size >= 2 -> moves += Move(s, ts[1], ts[0], ts.getOrNull(2)?.toDoubleOrNull(), ts.drop(2))
                }
            }
        }
        return moves
    }

    fun whoIs(plan: Plan, token: String): DungeonClass? = when {
        token.startsWith("ee") -> token.drop(2).toIntOrNull()?.let { plan.ee[it] }
        token == "core" -> plan.ee[5]
        else -> jobs(token, 1).firstOrNull()?.let { plan.owners[it]?.firstOrNull() }
    }

    fun label(c: DungeonClass) = c.name.lowercase().replaceFirstChar { it.uppercase() }
}
