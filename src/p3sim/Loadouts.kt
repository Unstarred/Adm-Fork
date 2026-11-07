package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.index
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ContainerSynchronizer
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ItemLore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Loadouts {
    private class Entry(val slot: Int, val item: String, val name: String, val lore: List<String>, val tex: String?,
                        val dye: Int?, val glint: Boolean, val style: String?, val id: String?)

    private val entries: Map<Int, Entry> by lazy {
        val out = HashMap<Int, Entry>()
        EngineerClient.safely("p3sim loadouts.json") {
            val text = Loadouts::class.java.getResourceAsStream("/assets/engineerclient/p3sim/loadouts.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@safely
            for (e in JsonParser.parseString(text).asJsonArray) {
                val o = e.asJsonObject
                fun str(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
                out[o.get("slot").asInt] = Entry(o.get("slot").asInt, o.get("item").asString, str("name") ?: "",
                    o.getAsJsonArray("lore").map { it.asString }, str("tex"), o.get("dye")?.asInt, o.get("glint")?.asBoolean ?: false, str("style"), str("id"))
            }
        }
        out
    }

    private fun Entry.stack(): ItemStack {
        val s = if (tex != null) SimItems.head(tex, name)
            else Terminals.named(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(item)), name)
        if (lore.isNotEmpty()) s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        if (dye != null) s.set(DataComponents.DYED_COLOR, DyedItemColor(dye))
        if (id != null) s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", id); it.putBoolean("p3sim", true) }))
        if (style != null) SimItems.hy(s, null, style) else HypixelPack.look(s, null, null)
        return s
    }

    private fun Entry.withHelmet(line: String) = Entry(slot, item, name, lore.map { if (it.startsWith("§r§7Helmet: ")) line else it }, tex, dye, glint, style, id)

    private fun terrorHelmet(): ItemStack {
        val e = entries[54] ?: return ItemStack.EMPTY
        return Entry(e.slot, e.item, e.name, e.lore.map { it.replace("(3/4)", "(4/4)").replace("after 7s", "after 10s") }, e.tex, e.dye, e.glint, e.style, e.id).stack()
    }

    private enum class Helm { RACING, MASK, TERROR, WISE }
    private class Def(val slot: Int, val name: String, val set: SimItems.ArmorSet, val helm: Helm, val phoenix: Boolean, val mask: String? = null)

    private val defs = listOf(
        Def(24, "Cat terms", SimItems.ArmorSet.WISE, Helm.RACING, false),
        Def(23, "Phoenix terms", SimItems.ArmorSet.WISE, Helm.RACING, true),
        Def(34, "Terror", SimItems.ArmorSet.TERROR, Helm.MASK, false, "BONZO_MASK"),
        Def(41, "Mask terms", SimItems.ArmorSet.MAXOR, Helm.MASK, false, "SPIRIT_MASK"),
    )

    private fun maskId(s: ItemStack) = SimItems.idOf(s)?.removePrefix("STARRED_")
    private fun wantedMask() = if (P3Sim.wornMask == 0) "SPIRIT_MASK" else "BONZO_MASK"

    data class Worn(val head: String? = null, val chest: String? = null, val legs: String? = null, val feet: String? = null, val phoenix: Boolean = false)

    fun capture(p: ServerPlayer) = Worn(
        SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD)), SimItems.idOf(p.getItemBySlot(EquipmentSlot.CHEST)),
        SimItems.idOf(p.getItemBySlot(EquipmentSlot.LEGS)), SimItems.idOf(p.getItemBySlot(EquipmentSlot.FEET)), P3Sim.phoenix)

    private fun helmFor(id: String?): Helm? = when {
        id == null -> null
        id == "RACING_HELMET" -> Helm.RACING
        id == "TERROR_HELMET" -> Helm.TERROR
        id == "WISE_WITHER_HELMET" -> Helm.WISE
        id.endsWith("_MASK") -> Helm.MASK
        else -> null
    }

    fun applyWorn(p: ServerPlayer, w: Worn) {
        listOf(EquipmentSlot.CHEST to w.chest, EquipmentSlot.LEGS to w.legs, EquipmentSlot.FEET to w.feet).forEach { (slot, id) ->
            id?.let { SimItems.armorPiece(it) }?.let { SimItems.wear(p, slot, it) }
        }
        val h = helmFor(w.head)
        if (h == Helm.MASK) {
            P3Sim.setWornMask(if (w.head!!.endsWith("SPIRIT_MASK")) 0 else 1)
            val cur = SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD))
            if (cur?.endsWith("_MASK") == true) {
                if (P3Sim.realMasks) Masks.equip(p)
                else SimItems.wear(p, EquipmentSlot.HEAD, if (P3Sim.wornMask == 0) Masks.SPIRIT_MASK else Masks.BONZO_MASK)
            } else wearHelmet(p, h)
        } else if (h != null) wearHelmet(p, h)
        P3Sim.setPhoenix(w.phoenix)
        Fight.applySpeed(p)
    }

    fun applySaved(p: ServerPlayer, p3: Boolean) {
        val w = HotbarLayout.worn(p3)
        if (w != null && w.head?.endsWith("_MASK") == true) P3Sim.setWornMask(if (w.head.endsWith("SPIRIT_MASK")) 0 else 1)
        Masks.equip(p)
        SimItems.equipArmor(p, SimItems.ArmorSet.MAXOR)
        if (w != null) applyWorn(p, w)
    }

    private class Custom(val name: String = "", val worn: Worn = Worn())

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val customLists = ConcurrentHashMap<String, MutableList<Custom>>()
    private fun owner() = PlayerFiles.owner()?.toString() ?: ""

    fun forgetLocal() { customLists.remove("") }
    fun forget(id: UUID) { customLists.remove(id.toString()) }

    private fun customs(): MutableList<Custom> = customLists[owner()] ?: run {
        val l = runCatching {
            PlayerFiles.read(PlayerFiles.LOADOUTS)?.let { gson.fromJson(it, Array<Custom>::class.java)?.toMutableList() }
        }.onFailure { EngineerClient.logger.error("[ec] p3sim loadouts load failed", it); PlayerFiles.setAsideLocal(PlayerFiles.LOADOUTS) }.getOrNull() ?: mutableListOf()
        customLists[owner()] = l
        l
    }

    private fun saveCustoms() {
        EngineerClient.safely("p3sim loadouts save") { PlayerFiles.write(PlayerFiles.LOADOUTS, gson.toJson(customs().toTypedArray())) }
    }

    private val CUSTOM_SLOTS = listOf(12, 13, 14, 15, 16, 17, 30, 31, 32, 33, 39, 40, 42, 43)
    private const val SAVE_SLOT = 47

    private fun pretty(id: String?) = id?.removePrefix("STARRED_")?.lowercase()?.split('_')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } ?: "none"

    private fun Custom.stack(): ItemStack {
        val s = worn.chest?.let { SimItems.armorPiece(it) }?.copy() ?: Terminals.named(Items.BOOK, name)
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§a$name").withStyle { it.withItalic(false) })
        val lore = listOf("§7Helmet: §f${pretty(worn.head)}", "§7Chestplate: §f${pretty(worn.chest)}", "§7Leggings: §f${pretty(worn.legs)}",
            "§7Boots: §f${pretty(worn.feet)}", "§7Pet: §f${if (worn.phoenix) "Phoenix" else "Black Cat"}", "", "§eLeft-click to equip!", "§cShift-click to delete!")
        s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        return s
    }

    private fun saveCurrent(p: ServerPlayer) {
        val w = capture(p)
        customs().firstOrNull { it.worn == w }?.let { Sim.chatStyled("§cThat is already saved as ${it.name}."); return }
        if (customs().size >= CUSTOM_SLOTS.size) { Sim.chatStyled("§cNo free loadout slot: shift-click one to delete it."); return }
        var n = 1
        while (customs().any { it.name == "Custom $n" }) n++
        customs() += Custom("Custom $n", w)
        saveCustoms()
        Sim.chatStyled("§aSaved your gear and pet as Custom $n.")
    }

    private fun equipCustom(p: ServerPlayer, c: Custom) {
        if (capture(p) == c.worn) { Sim.chatStyled("§c${c.name} is already equipped!"); return }
        Fight.later(1, "loadout ${c.name}") {
            applyWorn(p, c.worn)
            sound("minecraft:block.lever.click", 0.5f, 1f, SoundSource.BLOCKS)
            Sim.chatStyled("§aYou equipped ${c.name}!")
            sound("minecraft:entity.horse.saddle", 1f, 1f, SoundSource.NEUTRAL)
            p.inventoryMenu.broadcastChanges()
            (p.containerMenu as? LoadoutsMenu)?.refresh()
        }
    }

    fun open(p: ServerPlayer) {
        p.openMenu(SimpleMenuProvider({ id, inv, _ -> LoadoutsMenu(id, inv, p) }, Component.literal("(1/3) Loadouts")))
    }

    fun isHelmet(s: ItemStack): Boolean {
        val id = SimItems.idOf(s) ?: return false
        return id.endsWith("_MASK") || id.endsWith("_HELMET")
    }

    private fun wearsHelm(h: Helm, s: ItemStack): Boolean {
        val id = SimItems.idOf(s) ?: return false
        return when (h) { Helm.RACING -> id == "RACING_HELMET"; Helm.TERROR -> id == "TERROR_HELMET"; Helm.WISE -> id == "WISE_WITHER_HELMET"; Helm.MASK -> id.endsWith("_MASK") }
    }

    private fun chestId(set: SimItems.ArmorSet) = when (set) {
        SimItems.ArmorSet.MAXOR -> "MITHRIL_COAT"; SimItems.ArmorSet.TERROR -> "TERROR_CHESTPLATE"; SimItems.ArmorSet.WISE -> "WISE_WITHER_CHESTPLATE"
    }

    private fun matches(p: ServerPlayer, d: Def): Boolean {
        val head = p.getItemBySlot(EquipmentSlot.HEAD)
        return SimItems.idOf(p.getItemBySlot(EquipmentSlot.CHEST)) == chestId(d.set) && wearsHelm(d.helm, head) && P3Sim.phoenix == d.phoenix &&
            (d.mask == null || maskId(head) == d.mask)
    }

    private fun wearHelmet(p: ServerPlayer, h: Helm) {
        val inv = p.inventory
        val old = p.getItemBySlot(EquipmentSlot.HEAD).copy()
        val wantMask = wantedMask()
        if (wearsHelm(h, old) && (h != Helm.MASK || maskId(old) == wantMask)) return
        val candidates = (0 until 36).filter { wearsHelm(h, inv.getItem(it)) && (h != Helm.MASK || maskId(inv.getItem(it)) == wantMask) }
        val from = candidates.firstOrNull()
        if (from != null) {
            SimItems.wear(p, EquipmentSlot.HEAD, inv.getItem(from).copy())
            inv.setItem(from, old)
        } else {
            when (h) {
                Helm.RACING -> SimItems.equipHelmet(p, false)
                Helm.WISE -> SimItems.equipHelmet(p, true)
                Helm.TERROR -> SimItems.wear(p, EquipmentSlot.HEAD, terrorHelmet())
                Helm.MASK -> SimItems.wear(p, EquipmentSlot.HEAD, if (wantMask == "SPIRIT_MASK") Masks.SPIRIT_MASK else Masks.BONZO_MASK)
            }
            if (!old.isEmpty) {
                val slot = if (inv.getItem(Masks.SPARE_SLOT).isEmpty) Masks.SPARE_SLOT else (9 until 36).firstOrNull { inv.getItem(it).isEmpty }
                if (slot != null) inv.setItem(slot, old)
            }
        }
        if (h == Helm.MASK) SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD))?.let { P3Sim.setWornMask(if (it.endsWith("SPIRIT_MASK")) 0 else 1) }
    }

    private fun sound(id: String, vol: Float, pitch: Float, src: SoundSource) {
        BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id))?.let { Sim.sound(it, vol, pitch, null, src) }
    }

    private fun wear(p: ServerPlayer, d: Def) {
        SimItems.equipArmor(p, d.set, d.helm == Helm.TERROR)
        d.mask?.let { P3Sim.setWornMask(if (it == "SPIRIT_MASK") 0 else 1) }
        wearHelmet(p, d.helm)
        P3Sim.setPhoenix(d.phoenix)
        Fight.applySpeed(p)
    }

    fun wearTerror(p: ServerPlayer) { defs.firstOrNull { it.name == "Terror" }?.let { wear(p, it) } }

    private fun equip(p: ServerPlayer, d: Def) {
        if (matches(p, d)) { Sim.chatStyled("§c${d.name} is already equipped!"); return }
        Fight.later(1, "loadout ${d.name}") {
            wear(p, d)
            sound("minecraft:block.lever.click", 0.5f, 1f, SoundSource.BLOCKS)
            Sim.chatStyled("§aYou equipped ${d.name}!")
            sound("minecraft:entity.horse.saddle", 1f, 1f, SoundSource.NEUTRAL)
            p.inventoryMenu.broadcastChanges()
            (p.containerMenu as? LoadoutsMenu)?.refresh()
        }
    }

    class LoadoutsMenu(id: Int, inv: Inventory, private val sp: ServerPlayer) :
        ChestMenu(MenuType.GENERIC_9x6, id, inv, SimpleContainer(54), 6) {
        init { draw() }

        override fun setSynchronizer(synchronizer: ContainerSynchronizer) = super.setSynchronizer(SpiritLeap.PerSlotSync(synchronizer, sp))

        fun refresh() { draw(); broadcastFullState() }

        private fun draw() {
            val c = container
            for (i in 0 until 54) c.setItem(i, Terminals.FILLER)
            for ((slot, e) in entries) if (slot < 54) c.setItem(slot, (if (slot == 34) e.withHelmet("§r§7Helmet: §r§9Bonzo's Mask") else e).stack())
            val head = sp.getItemBySlot(EquipmentSlot.HEAD).copy()
            c.setItem(11, if (head.isEmpty) Terminals.named(Items.STAINED_GLASS_PANE.gray(), "§7Empty Helmet Slot") else head)
            for ((slot, eq) in listOf(20 to EquipmentSlot.CHEST, 29 to EquipmentSlot.LEGS, 38 to EquipmentSlot.FEET)) {
                val s = sp.getItemBySlot(eq).copy()
                if (!s.isEmpty) c.setItem(slot, s)
            }
            for ((i, cu) in customs().withIndex()) if (i < CUSTOM_SLOTS.size) c.setItem(CUSTOM_SLOTS[i], cu.stack())
            c.setItem(SAVE_SLOT, Terminals.named(Items.DYE.lime(), "§aSave current as loadout").also {
                it.set(DataComponents.LORE, ItemLore(listOf("§7Saves what you wear and your pet", "§7as a new loadout.", "", "§eClick to save!").map { l -> Component.literal(l).withStyle { s -> s.withItalic(false) } }))
            })
            if (P3Sim.phoenix) c.setItem(21, SimItems.head(Masks.PHOENIX_TEX, "§7[Lvl 100] §5Phoenix"))
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (slot == 49) { sp.closeContainer(); return }
            val ci = CUSTOM_SLOTS.indexOf(slot)
            if (slot == SAVE_SLOT) saveCurrent(sp)
            else if (ci in customs().indices) {
                val cu = customs()[ci]
                if (button == 1 || input == ContainerInput.QUICK_MOVE) {
                    customs().removeAt(ci); saveCustoms(); Sim.chatStyled("§cDeleted loadout ${cu.name}.")
                } else if (button == 0) equipCustom(sp, cu)
            } else if (button == 0) defs.firstOrNull { it.slot == slot }?.let { equip(sp, it) }
            draw()
            broadcastFullState()
        }

        override fun quickMoveStack(p: Player, i: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = true
    }
}
