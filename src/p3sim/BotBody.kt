package com.engineerclient.p3sim

import com.engineerclient.p3sim.Party.Bot
import com.mojang.authlib.GameProfile
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** A bot's body: the Mannequin it's shown as, dressed and skinned as a teammate, placed, swinging, holding what it holds. */
object BotBody {
    internal fun swing(b: Bot) { b.entity?.let { it.swing(InteractionHand.MAIN_HAND, it.mainHandItem.interactAnimation, false) } }

    internal fun setHeld(b: Bot, key: String) {
        val e = b.entity ?: return
        if (b.heldKey == key) return
        b.heldKey = key
        // The bots' keys, or a ghost's: what you held (the sim item's id).
        e.setItemSlot(EquipmentSlot.MAINHAND, when (key) {
            "leap", "INFINITE_SPIRIT_LEAP" -> SimItems.LEAP
            "hyperion", "HYPERION" -> SimItems.HYPERION
            "terminator", "TERMINATOR" -> SimItems.TERMINATOR
            "SUPERBOOM_TNT" -> SimItems.SUPERBOOM
            "ASPECT_OF_THE_VOID" -> SimItems.AOTV
            "STARRED_BONZO_STAFF", "BONZO_STAFF" -> SimItems.BONZO
            "JERRY_STAFF" -> SimItems.JERRY
            "ITEM_SPIRIT_BOW" -> SimItems.SPIRIT_BOW
            "MOSQUITO_BOW" -> SimItems.MOSQUITO
            "ENDER_PEARL" -> SimItems.PEARLS
            "WITHER_CLOAK" -> SimItems.CLOAK
            "PET_ROD" -> SimItems.PET_ROD
            else -> SimItems.DUNGEONBREAKER
        })
    }

    /**
     * What each class holds standing about outside P3, its most-held item there (measured):
     * P1 the Archer's Terminator, the Healer's Dungeonbreaker, everyone else's Hyperion;
     * P2 the same; P4 Hyperion but the Healer's Dungeonbreaker.
     */
    internal fun restingItem(c: DungeonClass): String = when {
        c == DungeonClass.HEALER -> "breaker"
        c == DungeonClass.ARCHER && Fight.phase !is P4Necron -> "terminator"
        else -> "hyperion"
    }

    internal fun spawn(b: Bot, at: Vec3) {
        val m = Mannequin(EntityTypes.MANNEQUIN, Sim.level)
        m.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(profile(b)))
        m.setCustomName(Component.literal("§a${b.name}"))
        m.isCustomNameVisible = true
        m.isPermanentlyInvulnerable = true
        m.setNoGravity(true)
        dress(m, b.clazz)
        hideDescription(m)
        b.pos = at
        m.snapTo(at.x, at.y, at.z, 0f, 0f)
        b.entity = Sim.spawn(m)
        b.heldKey = ""
        setHeld(b, if (Fight.phase is GoldorPhase || Fight.phase is StormEnd) "breaker" else restingItem(b.clazz))
    }

    /**
     * Teammates' gear in P3 (measured from recorded runs): a mask on the head (mostly the Spirit Mask,
     * then Bonzo's Mask) and dyed leather - each player's own dyes,
     * mostly Storm's blues (chest #1793c4 / legs #17a8c4) with #8969c8 or #1cd4e4 boots. Each class
     * wears one set seen on that class.
     */
    private fun dress(m: Mannequin, c: DungeonClass) {
        m.setItemSlot(EquipmentSlot.HEAD, if (c == DungeonClass.BERSERK) Masks.BONZO_MASK else Masks.SPIRIT_MASK)
        val (chest, legs, boots) = when (c) {
            DungeonClass.HEALER -> Triple(0x1793c4, 0x17a8c4, 0x8969c8)
            DungeonClass.MAGE -> Triple(0x1793c4, 0x17a8c4, 0x1cd4e4)
            DungeonClass.TANK -> Triple(-1, 0x5d2fb9, 0x8969c8)
            DungeonClass.ARCHER -> Triple(0xe7413c, 0xe75c3c, 0x8969c8)
            else -> Triple(0x3e05af, 0x5d23d1, 0x7c44ec)
        }
        fun dyed(item: Item, rgb: Int) =
            if (rgb < 0) ItemStack(Items.CHAINMAIL_CHESTPLATE) else ItemStack(item).also { it.set(DataComponents.DYED_COLOR, DyedItemColor(rgb)) }
        m.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, chest))
        m.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, legs))
        m.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, boots))
    }

    /** A bot's profile: its skin (and its head in the leap menu). */
    fun profile(b: Bot) = GameProfile(UUID.nameUUIDFromBytes("p3sim:${b.name}".toByteArray()), b.name)

    private val hideMethod by lazy { Mannequin::class.java.getDeclaredMethod("setHideDescription", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun hideDescription(m: Mannequin) { runCatching { hideMethod.invoke(m, true) } }

    internal fun place(b: Bot) {
        val e = b.entity ?: return
        e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, b.pitch)
        e.yHeadRot = b.yaw; e.yBodyRot = b.yaw
    }
}
