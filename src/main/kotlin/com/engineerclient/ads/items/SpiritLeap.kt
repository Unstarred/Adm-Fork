package com.engineerclient.ads.items

import com.mojang.authlib.GameProfile
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ContainerSynchronizer
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.Vec3
import com.engineerclient.ads.bots.GhostCapture
import com.engineerclient.ads.bots.Party
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.core.perPlayer
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.Terminals
import com.engineerclient.ads.plan.P3Plan
import com.engineerclient.ads.plan.Practice

/** The Spirit Leap and the ghost's Haunt: their windows and cooldowns, and [PerSlotSync], which Loadouts' window uses too. */
object SpiritLeap {
    /** The InfiniLeap's 2 s cooldown. */
    internal var leapReady by perPlayer { 0 }

    /** The Spirit Leap floor item's own 5 s cooldown (the item's lore; each item keeps its own, as an ability's cooldown is per item). */
    internal var pearlLeapReady by perPlayer { 0 }

    /** The server tick the leap item [id] can be used again. */
    private fun leapReadyAt(id: String): Int = if (id == ClearItems.SPIRIT_LEAP) pearlLeapReady else leapReady

    /** The Spirit Leap window for the leap item [id] (the InfiniLeap, or the Spirit Leap stack a room's floor gives). */
    internal fun openLeap(p: ServerPlayer, id: String = ClearItems.INFINILEAP) {
        // The floor item is an ender pearl to the client, which counts one off the stack as if it had thrown it: the stack is only used up by a leap.
        if (id == ClearItems.SPIRIT_LEAP) SimItems.resyncHeld(p)
        val now = Fight.serverTick
        val ready = leapReadyAt(id)
        if (now < ready) {
            // Cooldown: enderman.teleport HOSTILE vol 8.0 pitch 0.0 at you, then the chat line.
            Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f, p.position(), SoundSource.HOSTILE)
            Sim.chat("§cThis ability is on cooldown for ${(ready - now + 19) / 20}s."); return
        }
        // Practice: no bots, but every class is there to leap to (Practice.leapSpot). In a party: the players and bots.
        val mates = Party.mates(p).filter { Practice.active || it.here }.map { LeapRow.of(it) }
        // The menu opens one RTT after the use.
        Fight.afterPing("leapOpen") { p.openMenu(SimpleMenuProvider({ windowId, inv, _ -> LeapMenu(windowId, inv, mates, id) }, Component.literal("Spirit Leap"))) }
    }

    /**
     * One row of the leap or Haunt window: a head with a name, and where it takes you. A boss-sim mate ([of]).
     */
    class LeapRow(
        val name: String, val clazz: DungeonClass?, val profile: GameProfile, val posNow: () -> Vec3, val yaw: Float, val pitch: Float,
        /** The colour code of the name when the member's rank is known (a run's bots carry theirs); null: a colour drawn from the name. */
        val colour: String? = null,
    ) {
        val pos: Vec3 get() = posNow()
        /** The name's colour code (`§b`). */
        val nameColour: String get() = colour ?: SimItems.rankColour(name)
        companion object {
            fun of(m: Party.Mate) = LeapRow(m.name, m.clazz, m.profile, { m.pos }, m.yaw, m.pitch)
        }
    }

    /** The ghost's Haunt: the "Teleport to Player" menu, opened one RTT after the click. */
    internal fun openHaunt(p: ServerPlayer) {
        val mates = Party.mates(p).filter { it.here }.map { LeapRow.of(it) }
        Fight.afterPing("hauntOpen") { if (Masks.ghost) p.openMenu(SimpleMenuProvider({ id, inv, _ -> HauntMenu(id, inv, mates) }, Component.literal("Teleport to Player"))) }
    }

    /** Hypixel's ghost teleport window: teammates' heads in slots 11-15, a click teleports you to them (no cooldown). */
    class HauntMenu(id: Int, inv: Inventory, val bots: List<LeapRow>) : ChestMenu(MenuType.GENERIC_9x4, id, inv, SimpleContainer(36), 4) {
        init {
            for (i in 0 until 36) container.setItem(i, ItemStack.EMPTY)
            bots.forEachIndexed { i, b ->
                val h = ItemStack(Items.PLAYER_HEAD)
                h.set(DataComponents.PROFILE, ResolvableProfile.createResolved(b.profile))
                h.set(DataComponents.CUSTOM_NAME, Component.literal("${b.nameColour}${b.name}").withStyle { it.withItalic(false) })
                h.set(DataComponents.LORE, ItemLore(listOf("§7Click to teleport to this player!", "", "§7Health: §a100%", "", "§eClick to teleport!").map { Component.literal(it).withStyle { st -> st.withItalic(false) } }))
                container.setItem(listOf(11, 12, 14, 15).getOrElse(i) { 16 }, h)
            }
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (slot !in 11..16) return
            val name = ChatFormatting.stripFormatting(container.getItem(slot).hoverName.string)
            val bot = bots.firstOrNull { it.name == name } ?: return
            val sp = p as ServerPlayer
            Fight.afterPing("haunt") {
                sp.closeContainer()
                val e = bot.pos
                Sim.tp(sp, e.x, e.y, e.z, bot.yaw, bot.pitch)
                Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1.095f, sp.position(), SoundSource.HOSTILE)
                Sim.chatStyled("§aTeleported you to ${bot.name}!")
            }
        }

        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = true
    }

    /** Hypixel's Spirit Leap window: teammates' heads in slots 11-15, a click leaps (8 ticks, as measured). */
    class LeapMenu(id: Int, inv: Inventory, val bots: List<LeapRow>, private val item: String = ClearItems.INFINILEAP) : ChestMenu(MenuType.GENERIC_9x4, id, inv, SimpleContainer(36), 4) {
        private val owner = inv.player
        init {
            for (i in 0 until 36) container.setItem(i, Terminals.FILLER)
            // In the plan's leap slot order: slots 1-4 = chest slots 11, 12, 14, 15.
            bots.forEachIndexed { i, b ->
                val h = ItemStack(Items.PLAYER_HEAD)
                // The teammate's own head (the menu shows each one's skin): the bot's profile, a player's own.
                h.set(DataComponents.PROFILE, ResolvableProfile.createResolved(b.profile))
                // The name in its rank colour, one yellow "Click to teleport!" line.
                val colour = ChatFormatting.getByCode(b.nameColour[1]) ?: ChatFormatting.GREEN
                h.set(DataComponents.CUSTOM_NAME, Component.literal(b.name).withStyle { it.withItalic(false).withColor(colour) })
                h.set(DataComponents.LORE, ItemLore(listOf(Component.literal("Click to teleport!").withStyle { it.withItalic(false).withColor(ChatFormatting.YELLOW) })))
                container.setItem(listOf(11, 12, 14, 15).getOrElse(i) { 16 }, h)
            }
        }

        /** The window's contents arrive as one container_set_slot per slot, as main sends them. */
        override fun setSynchronizer(synchronizer: ContainerSynchronizer) =
            super.setSynchronizer((owner as? ServerPlayer)?.let { PerSlotSync(synchronizer, it) } ?: synchronizer)

        private var inClick = false

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (slot !in 11..16) return
            val name = ChatFormatting.stripFormatting(container.getItem(slot).hoverName.string)
            val bot = bots.firstOrNull { it.name == name } ?: return
            val sp = p as ServerPlayer
            inClick = true
            try {
                Fight.afterPing("leap") {
                    // At no ping this runs inside the click's own packet handler, which then hands the click's cursor to
                    // whatever window is open: leaping there would close onto the inventory and draw a set_cursor_item.
                    // Run it at the head of the next server tick instead (the same tick for the client).
                    if (inClick) Fight.later(0, "leap") { leap(sp, bot) } else leap(sp, bot)
                }
            } finally { inClick = false }
        }

        /** Main's tail: container_close(N), player_position, the sound, the chat line, then container_close(0). */
        private fun leap(sp: ServerPlayer, bot: LeapRow) {
            // A second click (a double click, or another head) arrives after the first leap closed this window: nothing.
            if (sp.isRemoved || sp.containerMenu !== this) return
            sp.closeContainer()
            val e = bot.pos
            // The cooldown is the item's own (2 s for the InfiniLeap, 5 s for the floor item); the floor item is used up by the leap.
            val cooldownEnd = Fight.serverTick + ClearItems.leapCooldownTicks(item, mage = false, doubled = false)
            if (item == ClearItems.SPIRIT_LEAP) pearlLeapReady = cooldownEnd else leapReady = cooldownEnd
            if (ClearItems.leapConsumes(item)) useUpLeapItem(sp)
            // You land on them exactly, facing as they face. Practice: where that class normally is in this section; a custom
            // practice's leap waypoint for that class's quadrant (top left, top right, bottom left, bottom right) first.
            val ps = if (Practice.active && bot.clazz != null) Practice.leapWaypoint(P3Plan.botOrder().indexOf(bot.clazz)) ?: Practice.leapSpot(bot.clazz) else null
            if (ps != null) Sim.tp(sp, ps.x, ps.y, ps.z, ps.yaw, ps.pitch)
            else Sim.tp(sp, e.x, e.y, e.z, bot.yaw, bot.pitch)
            Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, sp.position(), SoundSource.HOSTILE)
            Sim.chatStyled("§aYou have teleported to §r${bot.nameColour}${bot.name}§r§a!")
            sp.connection.send(ClientboundContainerClosePacket(0))
            if (Sim.isHost(sp)) GhostCapture.event("leap", bot.clazz?.name ?: bot.name)
        }

        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = true
    }

    /** One Spirit Leap off the first stack of them [sp] carries (the hotbar first), then the inventory shown again. */
    private fun useUpLeapItem(sp: ServerPlayer) {
        val inv = sp.inventory
        val order = (0 until 9) + (9 until inv.containerSize)
        val slot = order.firstOrNull { SimItems.idOf(inv.getItem(it)) == ClearItems.SPIRIT_LEAP } ?: return
        inv.getItem(slot).shrink(1)
        sp.inventoryMenu.broadcastChanges()
    }

    /**
     * Sends a window's opening (and full re-send) contents as one container_set_slot per slot, stateId counting up, instead
     * of vanilla's single container_set_content: Hypixel's way for the Spirit Leap and Loadouts windows. The rest is vanilla's.
     */
    class PerSlotSync(private val base: ContainerSynchronizer, private val sp: ServerPlayer) : ContainerSynchronizer by base {
        override fun sendInitialData(container: AbstractContainerMenu, slotItems: List<ItemStack>, carried: ItemStack, dataSlots: IntArray) {
            for ((i, s) in slotItems.withIndex()) sp.connection.send(ClientboundContainerSetSlotPacket(container.containerId, container.incrementStateId(), i, s))
            for ((i, v) in dataSlots.withIndex()) base.sendDataChange(container, i, v)
        }
    }
}
