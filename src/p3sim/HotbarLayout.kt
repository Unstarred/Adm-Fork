package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object HotbarLayout {

    private class Layout(val slots: Map<String, Int> = emptyMap(), val selected: Int = 3, val worn: Loadouts.Worn? = null)
    private data class Saved(val p12: Layout? = null, val p3: Layout? = null, val worn: Loadouts.Worn? = null, val roles: Map<String, Layout>? = null,
                             val live: Map<String, Layout>? = null)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val cache = ConcurrentHashMap<String, Saved>()
    private val live = ConcurrentHashMap<String, ConcurrentHashMap<String, Layout>>()
    private val given = ConcurrentHashMap<String, Pair<String, Int>>()
    private fun owner() = PlayerFiles.owner()?.toString() ?: ""
    private fun lives() = live.getOrPut(owner()) { ConcurrentHashMap(saved().live.orEmpty().filterValues { it.slots != null }) }

    private fun keepLives() = write(saved().copy(live = lives().toMap()))

    fun forgetLocal() { cache.remove("") }
    fun forget(id: UUID) { cache.remove(id.toString()); live.remove(id.toString()); given.remove(id.toString()) }

    private fun saved(): Saved = cache[owner()] ?: run {
        val s = runCatching { PlayerFiles.read(PlayerFiles.HOTBAR)?.let { gson.fromJson(it, Saved::class.java) } }
            .onFailure { EngineerClient.logger.error("[ec] p3sim hotbar load failed", it); PlayerFiles.setAsideLocal(PlayerFiles.HOTBAR) }
            .getOrNull() ?: Saved()
        cache[owner()] = s
        s
    }

    private fun partKey(p3: Boolean) = if (p3) "P3" else "P12"
    private fun key(p3: Boolean) = "${partKey(p3)}/${P3Sim.myClass.name}"
    private fun skillKey(p3: Boolean) = "${P3Plan.skillName()}/${key(p3)}"
    private fun isSkillKey(k: String, p3: Boolean) = k.endsWith("/" + key(p3)) && k.count { it == '/' } == 2

    private fun savedLayout(p3: Boolean): Layout? = saved().roles?.let { it[key(p3)] ?: it[skillKey(p3)] } ?: if (p3) saved().p3 else saved().p12
    private fun layout(p3: Boolean): Layout? = savedLayout(p3) ?: lives()[key(p3)]

    fun worn(p3: Boolean): Loadouts.Worn? {
        val s = savedLayout(p3)
        if (s != null) return s.worn ?: saved().worn
        return lives()[key(p3)]?.worn
    }

    fun has(p3: Boolean) = savedLayout(p3) != null

    private fun capture(p: ServerPlayer, worn: Loadouts.Worn?, selected: Int = p.inventory.selectedSlot): Layout? {
        val inv = p.inventory
        val slots = LinkedHashMap<String, Int>()
        for (i in 0..35) {
            val s = inv.getItem(i)
            val id = (if (SimItems.isSlot9(s)) "SKYBLOCK_MENU" else SimItems.idOf(s)?.let { if (it == "MAXOR_ENERGY_CRYSTAL") "SKYBLOCK_MENU" else it }) ?: continue
            if (id.endsWith("_MASK")) continue
            slots.putIfAbsent(id, i)
        }
        return if (slots.isEmpty()) null else Layout(slots, selected, worn)
    }

    fun keep(p: ServerPlayer, save: Boolean = false) {
        if (Masks.ghost) return
        val (k, held) = given[owner()] ?: return
        val was = lives()[k]
        val worn = if (k.contains("/P3/") && P3Sim.terrorAtTerms) was?.worn else Loadouts.capture(p)
        val l = capture(p, worn, held) ?: return
        lives()[k] = l
        if (!save) return keepLives()
        val s = saved()
        val part = k.substringBefore('/') == "P3"
        write(s.copy(roles = s.roles.orEmpty().filterKeys { !isSkillKey(it, part) } + (k to l), live = lives().toMap()))
    }

    fun gave(p3: Boolean, held: Int) { given[owner()] = key(p3) to held }

    fun forgetGiven() { given.remove(owner()) }

    fun dropLive(p3: Boolean) { lives().remove(key(p3)); given.remove(owner()); keepLives() }

    fun arrange(defaults: List<Pair<Int, ItemStack>>, p3: Boolean): Pair<List<Pair<Int, ItemStack>>, Int> {
        val l = layout(p3) ?: return defaults to 3
        val taken = HashSet<Int>().apply { add(Masks.SPARE_SLOT) }
        val placed = ArrayList<Pair<Int, ItemStack>>()
        val left = ArrayList<Pair<Int, ItemStack>>()
        for ((slot, stack) in defaults) {
            val want = SimItems.idOf(stack)?.let { l.slots[it] }
            if (want != null && want in 0..35 && taken.add(want)) placed += want to stack else left += slot to stack
        }
        for ((slot, stack) in left) {
            val at = if (slot !in taken) slot else (0..35).first { it !in taken }
            taken += at
            placed += at to stack
        }
        return placed to l.selected.coerceIn(0, 8)
    }

    fun save(p: ServerPlayer, p3: Boolean): String {
        val l = capture(p, Loadouts.capture(p)) ?: return "§cnothing to save: no sim items in your inventory"
        lives()[key(p3)] = l
        val s = saved()
        write(s.copy(roles = s.roles.orEmpty().filterKeys { !isSkillKey(it, p3) } + (key(p3) to l), live = lives().toMap()))
        return "§7saved your §f${part(p3)}§7 hotbar (${l.slots.size} items, holding slot ${l.selected + 1}) with your armour, helmet and pet; Reset Items brings it back"
    }

    fun reset(p3: Boolean): String {
        lives().remove(key(p3)); given.remove(owner()); keepLives()
        if (!has(p3)) return "§7the ${part(p3)} hotbar is already the default"
        val s = saved().copy(live = lives().toMap())
        val roles = s.roles.orEmpty()
        when {
            roles.keys.any { it == key(p3) || isSkillKey(it, p3) } -> write(s.copy(roles = roles.filterKeys { it != key(p3) && !isSkillKey(it, p3) }))
            else -> {
                val otherLeft = if (p3) s.p12 != null else s.p3 != null
                write(if (p3) s.copy(p3 = null, worn = if (otherLeft) s.worn else null) else s.copy(p12 = null, worn = if (otherLeft) s.worn else null))
            }
        }
        return "§7${part(p3)} hotbar back to the ${if (has(p3)) "one saved for every class" else "default"}"
    }

    private fun part(p3: Boolean) = (if (p3) "P3" else "P1/P2") + " " + Roles.label(P3Sim.myClass)

    private fun write(s: Saved) {
        cache[owner()] = s
        EngineerClient.safely("p3sim hotbar save") { PlayerFiles.write(PlayerFiles.HOTBAR, gson.toJson(s)) }
    }
}
