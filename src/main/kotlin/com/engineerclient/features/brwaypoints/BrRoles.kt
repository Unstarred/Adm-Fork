package com.engineerclient.features.brwaypoints

import com.engineerclient.EngineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import kotlin.math.abs

object BrRoles {

    class Plan(val roles: List<List<Int>>, val stacks: List<List<Int>>)

    sealed interface Look {
        data class Mine(val order: Int) : Look
        data object Stack : Look
        data class Theirs(val role: Int) : Look
    }

    const val MAX_KILLING = 4

    @Volatile private var plans: Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>> = emptyMap()
    @Volatile private var m7Plans: Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>> = emptyMap()
    @Volatile private var doorerPlans: Map<String, Map<Pair<Pair<Int, Int>, Pair<Int, Int>>, Map<Int, Plan>>> = emptyMap()
    @Volatile private var doorerM7Plans: Map<String, Map<Pair<Pair<Int, Int>, Pair<Int, Int>>, Map<Int, Plan>>> = emptyMap()
    @Volatile private var mini: Set<String> = emptySet()

    fun isMini(room: String) = room in mini

    @Volatile var settingKilling = 2
    @Volatile var settingRole: Int? = null
    enum class DoorKills { NEVER, ALWAYS, AFTER_FAIRY }

    @Volatile var doorKills = DoorKills.NEVER
    @Volatile var dropBottom = false

    fun doorerKills(late: Boolean) = doorKills == DoorKills.ALWAYS || (doorKills == DoorKills.AFTER_FAIRY && late)

    fun dropped(late: Boolean) = late && doorKills == DoorKills.AFTER_FAIRY && dropBottom && count > 0

    fun youDropped(late: Boolean) = dropped(late) && mine == count

    fun planCount(late: Boolean): Int = if (count == 0) 0 else count - (if (dropped(late)) 1 else 0) + if (doorerKills(late)) 1 else 0

    fun playing(late: Boolean): Int? =
        if (youDropped(late)) null else mine ?: planCount(late).takeIf { doorerKills(late) && youOnDoor && it > 0 }

    val master: Boolean get() = DungeonUtils.floor?.isMM == true

    private val baseRole: Int?
        get() {
            val s = settingRole ?: return null
            if (!master) return s
            val cls = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(me, ignoreCase = true) }?.clazz
            return when (cls) {
                DungeonClass.ARCHER -> 1
                DungeonClass.MAGE -> 2
                DungeonClass.BERSERK -> 3
                DungeonClass.TANK -> 4
                DungeonClass.HEALER -> 0
                else -> s
            }
        }

    private var chatCount: Int? = null
    private var chatMine: Int? = null

    val count: Int
        get() = chatCount ?: baseRole.let { if (it == null || it > settingKilling) 0 else settingKilling }

    val youOnDoor: Boolean
        get() = doorClaim?.equals(me, ignoreCase = true) ?: (baseRole == 0 && chatMine == null)

    val active: Boolean
        get() = count > 0 && (youOnDoor || mine != null)

    val mine: Int?
        get() {
            if (youOnDoor) return null
            chatMine?.let { return it }
            return baseRole?.takeIf { it in 1..count }
        }
    private val taken = LinkedHashMap<String, Int>()
    private var doorClaim: String? = null

    private val CLAIM = Regex("""^Party > (?:\[[^]]*] )?(\w{1,16}): !([2-4])?br ([1-4]|d)$""")

    private val me get() = mc.player?.gameProfile?.name()

    val doorRunner: String?
        get() = doorClaim ?: me.takeIf { youOnDoor }

    fun onChat(line: String): Boolean {
        val m = CLAIM.matchEntire(line) ?: return false
        val name = m.groupValues[1]
        val n = m.groupValues[2].toIntOrNull()
        val what = m.groupValues[3]
        val you = name.equals(me, ignoreCase = true)
        if (n != null && n != count) { taken.clear(); chatMine = null }
        if (n != null) chatCount = n
        if (what == "d") {
            doorClaim = name
            taken.remove(name)
            if (you) chatMine = null
            EngineerClient.msg(Component.literal(if (you) "§dBR §7you are on the §6door" else "§dBR §f$name §7is on the §6door").also { offer(it, you) })
            return true
        }
        val killing = n ?: chatCount ?: settingKilling
        val role = what.toInt()
        if (role > killing) return true
        chatCount = killing
        taken.remove(name)
        taken[name] = role
        if (doorClaim.equals(name, ignoreCase = true)) doorClaim = null
        if (you) chatMine = role
        val clash = taken.filter { it.value == role && it.key != name }.keys
        val line = Component.literal(if (you) "§dBR §7you are role §f$role §7of §f$count" else "§dBR §f$name §7is role §f$role §7of §f$count")
        if (clash.isNotEmpty()) line.append(Component.literal(" §c(so is ${clash.joinToString()})"))
        offer(line, you)
        EngineerClient.msg(line)
        return true
    }

    private fun offer(line: Component, you: Boolean) {
        if (you || mine != null || youOnDoor || count == 0) return
        val parts = line as? MutableComponent ?: return
        parts.append(Component.literal(" §7— yours:"))
        for (r in 1..count) {
            val holder = taken.entries.firstOrNull { it.value == r }?.key
            parts.append(if (holder != null) Component.literal(" §8[$r]").withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("§7Taken by §f$holder"))) }
            else button(" §a[$r]", "/brrole $count $r", "§7Say §f!${count}br $r §7in party chat"))
        }
        if (doorClaim == null) parts.append(button(" §6[door]", "/brrole door", "§7Say §f!br d §7in party chat"))
    }

    private fun button(text: String, command: String, hover: String) = Component.literal(text).withStyle {
        it.withClickEvent(ClickEvent.RunCommand(command)).withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
    }

    fun claim(n: Int, role: Int) {
        if (n !in 2..MAX_KILLING || role !in 1..n) return EngineerClient.msg("§c2 to $MAX_KILLING kill (the door runner is extra): /brrole 3 2")
        sendCommand("pc !${n}br $role")
    }

    fun claimDoor() = sendCommand(if (count > 0) "pc !${count}br d" else "pc !br d")

    fun status() {
        if (count == 0 && doorRunner == null) return EngineerClient.msg("§dBR §7no roles yet. §f/brrole <killing> <role>§7 or §f/brrole door§7, or §f!3br 2§7 / §f!br d§7 in party chat.")
        val who = (1..count).joinToString("§7, ") { r -> "§f$r §7${taken.entries.firstOrNull { it.value == r }?.key ?: "§8free"}" }
        val door = doorRunner?.let { "§6door §7$it" } ?: "§6door §8unknown"
        val from = if (chatCount != null) "§8(party chat)" else "§8(settings)"
        EngineerClient.msg("§dBR §7$count killing $from§7: $who§7, $door" + (mine?.let { " §7· you §f$it" } ?: ""))
    }

    fun describe(late: Boolean = false): String {
        val from = (if (chatCount != null || doorClaim != null) "party chat" else if (master) "class" else "settings") + if (master) ", M7 roles" else ""
        return when {
            count == 0 -> "roles off"
            youOnDoor -> (if (doorerKills(late)) "door (kills, role ${planCount(late)})" else "door") + ", $count killing ($from)"
            youDropped(late) -> "role $mine of $count, dropped to clear past fairy ($from)"
            mine != null -> "role $mine of $count ($from)"
            else -> "no role, $count killing ($from)"
        }
    }

    fun newRun(dungeonOver: Boolean) {
        if (dungeonOver) { taken.clear(); chatCount = null; chatMine = null; doorClaim = null }
    }

    @Volatile private var fetched = false

    fun pull() {
        if (fetched) return
        BoxSync.pullRoles { body -> mc.execute { adopt(body) } }
    }

    private fun adopt(body: String) {
        val got = runCatching {
            val doc = JsonParser.parseString(body).asJsonObject
            val rooms = read(doc["rooms"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return)
            val m7 = doc["m7"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { read(it) } ?: emptyMap()
            val mn = doc["mini"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.filter { it.value.isJsonPrimitive && it.value.asBoolean }?.map { it.key }?.toSet() ?: emptySet()
            val doorer = doc["doorer"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { readDoorer(it) } ?: emptyMap()
            val doorerM7 = doc["doorerM7"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { readDoorer(it) } ?: emptyMap()
            listOf(rooms, m7, mn, doorer, doorerM7)
        }.onFailure { EngineerClient.logger.warn("[ec] brroles: the site's roles couldn't be read: ${it.message}") }.getOrNull() ?: return
        @Suppress("UNCHECKED_CAST")
        run {
            plans = got[0] as Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>>
            m7Plans = got[1] as Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>>
            mini = got[2] as Set<String>
            doorerPlans = got[3] as Map<String, Map<Pair<Pair<Int, Int>, Pair<Int, Int>>, Map<Int, Plan>>>
            doorerM7Plans = got[4] as Map<String, Map<Pair<Pair<Int, Int>, Pair<Int, Int>>, Map<Int, Plan>>>
        }
        fetched = true
    }

    private fun read(rooms: JsonObject): Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>> =
        readBy(rooms) { door -> xz(door) }

    private fun readDoorer(rooms: JsonObject): Map<String, Map<Pair<Pair<Int, Int>, Pair<Int, Int>>, Map<Int, Plan>>> =
        readBy(rooms) { key -> key.split('>').takeIf { it.size == 2 }?.let { (e, w) -> xz(e)?.let { a -> xz(w)?.let { b -> a to b } } } }

    private fun xz(s: String): Pair<Int, Int>? = s.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    private fun <K> readBy(rooms: JsonObject, key: (String) -> K?): Map<String, Map<K, Map<Int, Plan>>> {
        val out = HashMap<String, Map<K, Map<Int, Plan>>>()
        for ((room, doors) in rooms.entrySet()) {
            val byDoor = HashMap<K, Map<Int, Plan>>()
            for ((door, counts) in doors.asJsonObject.entrySet()) {
                val k = key(door) ?: continue
                val byCount = HashMap<Int, Plan>()
                for ((n, p) in counts.asJsonObject.entrySet()) runCatching {
                    val o = p.asJsonObject
                    val roles = o["roles"].asJsonArray.map { r -> r.asJsonArray.map { it.asInt } }
                    val stacks = o["stacks"]?.asJsonArray?.map { r -> r.asJsonArray.map { it.asInt } } ?: roles.map { emptyList() }
                    byCount[n.toInt()] = Plan(roles, stacks)
                }
                byDoor[k] = byCount
            }
            out[room] = byDoor
        }
        return out
    }

    fun planFor(room: String, door: Pair<Int, Int>, wither: Pair<Int, Int>?, late: Boolean): Plan? {
        if (count == 0) return null
        val n = planCount(late)
        if (doorerKills(late)) {
            if (wither == null) return null
            if (master) doorerIn(doorerM7Plans, room, door, wither, n)?.let { return it }
            return doorerIn(doorerPlans, room, door, wither, n)
        }
        if (master) planIn(m7Plans, room, door, n)?.let { return it }
        return planIn(plans, room, door, n)
    }

    private fun planIn(table: Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>>, room: String, door: Pair<Int, Int>, n: Int): Plan? {
        val doors = table[room] ?: return null
        val near = doors.keys.minByOrNull { abs(it.first - door.first) + abs(it.second - door.second) } ?: return null
        if (abs(near.first - door.first) + abs(near.second - door.second) > 3) return null
        return doors[near]?.get(n)
    }

    private fun doorerIn(table: Map<String, Map<Pair<Pair<Int, Int>, Pair<Int, Int>>, Map<Int, Plan>>>, room: String, door: Pair<Int, Int>, wither: Pair<Int, Int>, n: Int): Plan? {
        val doors = table[room] ?: return null
        fun d(a: Pair<Int, Int>, b: Pair<Int, Int>) = abs(a.first - b.first) + abs(a.second - b.second)
        val near = doors.keys.minByOrNull { d(it.first, door) + d(it.second, wither) } ?: return null
        if (d(near.first, door) > 3 || d(near.second, wither) > 3) return null
        return doors[near]?.get(n)
    }

    fun look(plan: Plan, number: Int, late: Boolean): Look? {
        val role = playing(late)
        if (role != null) {
            plan.roles.getOrNull(role - 1)?.indexOf(number)?.takeIf { it >= 0 }?.let { return Look.Mine(it + 1) }
            if (plan.stacks.getOrNull(role - 1)?.contains(number) == true) return Look.Stack
        }
        plan.roles.indexOfFirst { number in it }.takeIf { it >= 0 }?.let { return Look.Theirs(it + 1) }
        if (plan.stacks.any { number in it }) return Look.Theirs(0)
        return null
    }
}
