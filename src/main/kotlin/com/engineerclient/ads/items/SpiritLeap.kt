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

object SpiritLeap {
    internal var leapReady by perPlayer { 0 }

    internal var pearlLeapReady by perPlayer { 0 }

    private fun leapReadyAt(id: String): Int = if (id == ClearItems.SPIRIT_LEAP) pearlLeapReady else leapReady

    internal fun openLeap(p: ServerPlayer, id: String = ClearItems.INFINILEAP) {
        if (id == ClearItems.SPIRIT_LEAP) SimItems.resyncHeld(p)
        val now = Fight.serverTick
        val ready = leapReadyAt(id)
        if (now < ready) {
            Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f, p.position(), SoundSource.HOSTILE)
            Sim.chat("§cThis ability is on cooldown for ${(ready - now + 19) / 20}s."); return
        }
        val mates = Party.mates(p).filter { Practice.active || it.here }.map { LeapRow.of(it) }
        Fight.afterPing("leapOpen") { p.openMenu(SimpleMenuProvider({ windowId, inv, _ -> LeapMenu(windowId, inv, mates, id) }, Component.literal("Spirit Leap"))) }
    }

    class LeapRow(
        val name: String, val clazz: DungeonClass?, val profile: GameProfile, val posNow: () -> Vec3, val yaw: Float, val pitch: Float,
        val colour: String? = null,
    ) {
        val pos: Vec3 get() = posNow()
        val nameColour: String get() = colour ?: SimItems.rankColour(name)
        companion object {
            fun of(m: Party.Mate) = LeapRow(m.name, m.clazz, m.profile, { m.pos }, m.yaw, m.pitch)
        }
    }

    internal fun openHaunt(p: ServerPlayer) {
        val mates = Party.mates(p).filter { it.here }.map { LeapRow.of(it) }
        Fight.afterPing("hauntOpen") { if (Masks.ghost) p.openMenu(SimpleMenuProvider({ id, inv, _ -> HauntMenu(id, inv, mates) }, Component.literal("Teleport to Player"))) }
    }

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

    class LeapMenu(id: Int, inv: Inventory, val bots: List<LeapRow>, private val item: String = ClearItems.INFINILEAP) : ChestMenu(MenuType.GENERIC_9x4, id, inv, SimpleContainer(36), 4) {
        private val owner = inv.player
        init {
            for (i in 0 until 36) container.setItem(i, Terminals.FILLER)
            bots.forEachIndexed { i, b ->
                val h = ItemStack(Items.PLAYER_HEAD)
                h.set(DataComponents.PROFILE, ResolvableProfile.createResolved(b.profile))
                val colour = ChatFormatting.getByCode(b.nameColour[1]) ?: ChatFormatting.GREEN
                h.set(DataComponents.CUSTOM_NAME, Component.literal(b.name).withStyle { it.withItalic(false).withColor(colour) })
                h.set(DataComponents.LORE, ItemLore(listOf(Component.literal("Click to teleport!").withStyle { it.withItalic(false).withColor(ChatFormatting.YELLOW) })))
                container.setItem(listOf(11, 12, 14, 15).getOrElse(i) { 16 }, h)
            }
        }

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
                    if (inClick) Fight.later(0, "leap") { leap(sp, bot) } else leap(sp, bot)
                }
            } finally { inClick = false }
        }

        private fun leap(sp: ServerPlayer, bot: LeapRow) {
            if (sp.isRemoved || sp.containerMenu !== this) return
            sp.closeContainer()
            val e = bot.pos
            val cooldownEnd = Fight.serverTick + ClearItems.leapCooldownTicks(item, mage = false, doubled = false)
            if (item == ClearItems.SPIRIT_LEAP) pearlLeapReady = cooldownEnd else leapReady = cooldownEnd
            if (ClearItems.leapConsumes(item)) useUpLeapItem(sp)
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

    private fun useUpLeapItem(sp: ServerPlayer) {
        val inv = sp.inventory
        val order = (0 until 9) + (9 until inv.containerSize)
        val slot = order.firstOrNull { SimItems.idOf(inv.getItem(it)) == ClearItems.SPIRIT_LEAP } ?: return
        inv.getItem(slot).shrink(1)
        sp.inventoryMenu.broadcastChanges()
    }

    class PerSlotSync(private val base: ContainerSynchronizer, private val sp: ServerPlayer) : ContainerSynchronizer by base {
        override fun sendInitialData(container: AbstractContainerMenu, slotItems: List<ItemStack>, carried: ItemStack, dataSlots: IntArray) {
            for ((i, s) in slotItems.withIndex()) sp.connection.send(ClientboundContainerSetSlotPacket(container.containerId, container.incrementStateId(), i, s))
            for ((i, v) in dataSlots.withIndex()) base.sendDataChange(container, i, v)
        }
    }
}
