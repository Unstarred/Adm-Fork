package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.ClearItems
import com.engineerclient.p3sim.ImplosionRules
import com.engineerclient.p3sim.SimPearl
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.item.component.Tool
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.BasePressurePlateBlock
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.BaseTorchBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CommandBlock
import net.minecraft.world.level.block.CraftingTableBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.PipeBlock
import net.minecraft.world.level.block.RedstoneWallTorchBlock
import net.minecraft.world.level.block.RedstoneWireBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.StandingSignBlock
import net.minecraft.world.level.block.TripWireBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.WallBannerBlock
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.piston.MovingPistonBlock
import net.minecraft.world.level.block.piston.PistonBaseBlock
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

object SimItems {

    private fun item(base: Item, id: String, name: String, lore: List<String> = emptyList(), glint: Boolean = false, extra: (CompoundTag) -> Unit = {}): ItemStack {
        val s = ItemStack(base)
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
        if (lore.isNotEmpty()) s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        s.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE)
        val tag = CompoundTag()
        tag.putString("id", id)
        tag.putBoolean("p3sim", true)
        extra(tag)
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        return s
    }

    fun hy(s: ItemStack, model: String?, style: String, unbreakable: Boolean = true, hidden: List<String> = Lore.HIDDEN, vanilla: String? = null): ItemStack {
        HypixelPack.look(s, model, style, vanilla)
        val types = LinkedHashSet<DataComponentType<*>>()
        for (h in hidden) BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(h))?.let { types.add(it) }
        s.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, types))
        if (!unbreakable) s.remove(DataComponents.UNBREAKABLE)
        return s
    }

    fun rankColour(name: String): String { val h = Math.floorMod(name.hashCode(), 100); return if (h < 63) "§a" else if (h < 96) "§b" else "§6" }

    fun etherPuff(at: Vec3) = Sim.level.sendParticles(ParticleTypes.WITCH, at.x, at.y + 1.0, at.z, 25, 0.25, 1.0, 0.25, 0.0)

    fun gatePuffs(centre: Vec3, box: AABB) {
        val alongX = box.xsize >= box.zsize
        repeat(8 + Random.nextInt(8) + Random.nextInt(8)) {
            val a = if (alongX) Random.nextInt(box.minX.toInt() + 2, box.maxX.toInt() - 2) else Random.nextInt(box.minZ.toInt() + 2, box.maxZ.toInt() - 2)
            val b = Random.nextInt(-1, 2)
            val x = if (alongX) a.toDouble() else Math.floor(centre.x) + b
            val z = if (alongX) Math.floor(centre.z) + b else a.toDouble()
            val c = Vec3(x, Math.floor(centre.y - 3 + Random.nextInt(0, 19)), z)
            Sim.level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 1.0, 1.0, 1.0, 0.0)
            Sim.sound(SoundEvents.GENERIC_EXPLODE, 0.5f, 0.49206f, c, SoundSource.BLOCKS)
        }
    }

    fun ghostStack(base: Item, id: String?, name: String, count: Int = 1): ItemStack {
        val s = if (id != null) item(base, id, name) else ItemStack(base).also { it.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { st -> st.withItalic(false) }) }
        s.remove(DataComponents.UNBREAKABLE)
        s.count = count
        return s
    }

    fun idOf(s: ItemStack): String? = s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.takeIf { it.isNotEmpty() }

    private const val LEAP_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY1MjE0NjYxMjc0MiwKICAicHJvZmlsZUlkIiA6ICI5ZWU3NTUxOGQyZWE0Y2Q4OGJiNGI1YTZkNmVhNTFjYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaWNyb3MxMTgyIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzM3N2Q0YTIwNmQ3NzU3ZjQ3OWYzMzJlYzFhMmJiYmVlNTdjZWY5NzU2OGRkODhkZjgxZjQ4NjRhZWU3ZDNkOTgiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ=="

    fun head(tex: String, name: String): ItemStack {
        val s = ItemStack(Items.PLAYER_HEAD)
        val profile = GameProfile(UUID.nameUUIDFromBytes(tex.toByteArray()), "p3sim", PropertyMap(ImmutableMultimap.of("textures", Property("textures", tex))))
        s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
        return s
    }

    val SUPERBOOM get() = item(Items.PAPER, "SUPERBOOM_TNT", Lore.SUPERBOOM_TNT_NAME, Lore.SUPERBOOM_TNT, glint = true).also { it.count = 64; hy(it, "uncategorized/superboom_tnt", "rare", unbreakable = false, vanilla = "tnt") }
    val HYPERION get() = item(Items.IRON_SWORD, "HYPERION", Lore.HYPERION_NAME, Lore.HYPERION, glint = true).also { hy(it, "uncategorized/hyperion", "mythic") }
    val BONZO get() = item(Items.BLAZE_ROD, "STARRED_BONZO_STAFF", "§5\uE068 Heroic Bonzo's Staff §6✪✪✪✪✪", listOf(
        "§7Gear Score: §d845 §8(1,213)", "§7Damage: §c+250 §8(+1,350)", "§7Strength: §c+185 §8(+550)", "§7Intelligence: §a+300 §8(+700)", "",
        "§9Ferocity: §a+10", "", "§7§8This item can be reforged!", "",
        "§6Ability: Showtime  §e§lRIGHT CLICK", "§7Shoots balloons that create a large explosion", "§7on impact, dealing up to §c16,956.3 §7damage.", "§8Mana Cost: §341", "",
        "§5§lEPIC DUNGEON SWORD"), glint = true).also { HypixelPack.look(it,"island_relevant/dungeons/bonzos_staff_fragged", "epic") }
    val SPIRIT_BOW get() = item(Items.BOW, "ITEM_SPIRIT_BOW", "§5Spirit Shortbow", listOf("§7Shortbow: instantly shoots!"), glint = true)
    val DUNGEONBREAKER get() = item(Items.DIAMOND_PICKAXE, "DUNGEONBREAKER", "§cDungeonbreaker", breakerLore(charges), glint = true).also {
        hy(it, null, "special", unbreakable = false, hidden = Lore.HIDDEN_BREAKER)
        it.set(DataComponents.TOOL, Tool(emptyList(), 1024f, 1, true))
        SimServer.level?.registryAccess()?.lookup(Registries.ENCHANTMENT)?.orElse(null)?.let { reg ->
            val e = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
            reg.get(Enchantments.EFFICIENCY).ifPresent { h -> e.set(h, 10) }
            reg.get(Enchantments.UNBREAKING).ifPresent { h -> e.set(h, 10) }
            it.set(DataComponents.ENCHANTMENTS, e.toImmutable())
        }
    }

    private fun breakerLore(n: Int) = listOf("§7Speed: §f+20", "", "§6Ability: Dungeon Breaker §e§lDIG",
        "§7While in §cThe Catacombs§7, consume §e1§c\u2e15", "§7charge to break a block. §320§7 blocks can", "§7be broken at a time, and re-appear after", "§a10s§7. §e2§c\u2e15§7 charges are regenerated each", "§7second.", "",
        "§7Charges: §e$n§7/§e$MAX_CHARGES§c\u2e15", "", "§8§l* §8Co-op Soulbound §8§l*", "§c§lSPECIAL DUNGEON PICKAXE")

    private fun refreshBreakerLore() {
        val inv = Sim.player?.inventory ?: return
        val lore = ItemLore(breakerLore(charges).map { l -> Component.literal(l).withStyle { it.withItalic(false) } })
        for (i in 0 until inv.containerSize) { val st = inv.getItem(i); if (!st.isEmpty && idOf(st) == "DUNGEONBREAKER") st.set(DataComponents.LORE, lore) }
    }
    val PEARLS get() = item(Items.ENDER_PEARL, "ENDER_PEARL", Lore.ENDER_PEARL_NAME, Lore.ENDER_PEARL).also { it.count = 12; hy(it, null, "common", unbreakable = false) }
    val LEAP get() = head(LEAP_TEX, "§5Infinileap").also { s ->
        s.set(DataComponents.LORE, ItemLore(Lore.INFINITE_SPIRIT_LEAP.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        hy(s, null, "epic", unbreakable = false)
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", "INFINITE_SPIRIT_LEAP"); it.putBoolean("p3sim", true) }))
    }
    val JERRY get() = item(Items.GOLDEN_HORSE_ARMOR, "JERRY_STAFF", Lore.JERRY_STAFF_NAME, Lore.JERRY_STAFF, glint = true).also { hy(it, "community_center/mayor/jerry/jerrychine_gun", "legendary", unbreakable = false) }
    val CLOAK get() = item(Items.STONE_SWORD, "WITHER_CLOAK", Lore.WITHER_CLOAK_NAME, Lore.WITHER_CLOAK).also { hy(it, "uncategorized/wither_cloak_sword", "legendary") }
    val MENU get() = item(Items.NETHER_STAR, "SKYBLOCK_MENU", Lore.SKYBLOCK_MENU_NAME, Lore.SKYBLOCK_MENU).also { hy(it, null, "common", unbreakable = false) }

    private var breakerHeld: Boolean? by perPlayer { null }

    fun miningEffects(p: ServerPlayer, force: Boolean = false) {
        val held = idOf(p.mainHandItem) == "DUNGEONBREAKER"
        if (!force && held == breakerHeld && (held || p.hasEffect(MobEffects.HASTE))) return
        breakerHeld = held
        if (held) {
            p.removeEffect(MobEffects.HASTE)
            p.removeEffect(MobEffects.MINING_FATIGUE)
        } else {
            p.addEffect(MobEffectInstance(MobEffects.HASTE, -1, 0, true, true, false))
            p.addEffect(MobEffectInstance(MobEffects.MINING_FATIGUE, -1, 255, true, true, false))
        }
    }

    private var quiverArrows by perPlayer { QUIVER_START }
    private const val QUIVER_START = 2769

    fun quiverShot() { if (quiverArrows > 0) quiverArrows-- }

    private fun quiverPreview(): ItemStack {
        val s = ItemStack(Items.FEATHER)
        s.count = quiverArrows.coerceIn(1, 64)
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Flint Arrow").withStyle { it.withItalic(false).withColor(ChatFormatting.WHITE) })
        val lore = listOf("§7Damage: §c+1", "", "§8Stats added when shot!", "§f§lCOMMON ARROW", "§8§m                                ",
            "§7Arrows Remaining: §a" + "%,d".format(Locale.ROOT, quiverArrows), "", "§8This item is a preview of your", "§8currently selected arrow.")
        s.set(DataComponents.LORE, ItemLore(lore.map { l -> Sim.legacy(l).copy().withStyle { it.withItalic(false) } }))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("quiver_arrow", "true") }))
        HypixelPack.look(s, "uncategorized/flint_arrow", null, vanilla = "arrow")
        return s
    }

    private fun magicalMap(): ItemStack {
        val s = ItemStack(Items.FILLED_MAP)
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Magical Map").withStyle { it.withItalic(false).withColor(ChatFormatting.AQUA) })
        s.set(DataComponents.LORE, ItemLore(listOf("Shows the layout of the Dungeon as", "it is explored and completed.").map { l -> Component.literal(l).withStyle { it.withItalic(false).withColor(ChatFormatting.GRAY) } }))
        s.set(DataComponents.MAP_ID, MapId(1024))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", "MAP"); it.putInt("dontSaveToProfile", 1); it.putInt("dontUpdateStack", 1) }))
        return s
    }

    private fun isQuiverPreview(s: ItemStack) = s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("quiver_arrow", "") == "true"
    private fun isMagicalMap(s: ItemStack) = idOf(s) == "MAP" && s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.contains("dontUpdateStack") == true

    fun isSlot9(s: ItemStack) = idOf(s) == "SKYBLOCK_MENU" || isQuiverPreview(s) || isMagicalMap(s)

    private fun tickSlot9(p: ServerPlayer) {
        if (Masks.ghost) return
        val inv = p.inventory
        val cur = inv.getItem(8)
        if (!isSlot9(cur)) return
        val held = inv.getItem(inv.selectedSlot)
        val heldId = idOf(held)
        val want = when {
            heldId != null && held.item is BowItem -> quiverPreview()
            heldId == "INFINITE_SPIRIT_LEAP" -> magicalMap()
            else -> if (idOf(cur) == "SKYBLOCK_MENU") return else MENU
        }
        if (ItemStack.isSameItemSameComponents(cur, want) && cur.count == want.count) return
        inv.setItem(8, want)
    }
    val AOTV get() = item(Items.DIAMOND_SHOVEL, "ASPECT_OF_THE_VOID", Lore.ASPECT_OF_THE_VOID_NAME, Lore.ASPECT_OF_THE_VOID, glint = true) { it.putInt("ethermerge", 1); it.putInt("tuned_transmission", 4) }.also { hy(it, "slayer/enderman/aspect_of_the_void", "legendary") }
    val PET_ROD get() = item(Items.FISHING_ROD, "PET_ROD", Lore.FISHING_ROD_NAME, Lore.FISHING_ROD).also { hy(it, null, "common") }
    val TERMINATOR get() = item(Items.BOW, "TERMINATOR", Lore.TERMINATOR_NAME, Lore.TERMINATOR, glint = true).also { hy(it, "slayer/enderman/weapons/terminator", "mythic") }
    val MOSQUITO get() = item(Items.BOW, "MOSQUITO_BOW", Lore.MOSQUITO_BOW_NAME, Lore.MOSQUITO_BOW, glint = true).also { hy(it, "slayer/spider/weapons/mosquito_shortbow", "legendary") }

    val LAST_BREATH get() = item(Items.BOW, Bows.LAST_BREATH, "§6Last Breath", listOf("§9Duplex I", "§7Shoot an extra arrow dealing §a4%§7 of the",
        "§7first arrow's damage.", "", "§7Draw and release, like a vanilla bow.", "", "§6§lLEGENDARY BOW"), glint = true)

    val EXPLOSIVE_BOW get() = item(Items.BOW, Bows.EXPLOSIVE, "§5Explosive Bow", listOf("§7Damage: §c+100", "§7Strength: §c+20", "",
        "§6Ability: Explosive Shot", "§7Your arrows explode on impact, dealing", "§7damage to nearby enemies.", "",
        "§7Draw and release, like a vanilla bow.", "", "§5§lEPIC BOW"))

    enum class ArmorSet { MAXOR, TERROR, WISE }

    private const val RACING_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY1NTg2ODcxMjQwMCwKICAicHJvZmlsZUlkIiA6ICJmZTYxY2RiMjUyMTA0ODYzYTljY2E2ODAwZDRiMzgzZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNeVNoYWRvd3MiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmNlMDc0NmIxMmVlNDA1Mzk1OGUxNDBiYTI5NTkzMjcyYmQ4NGNhMzRiYWY1MGQwZDgwYjViYzNjNjE1ZTljNiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
    private const val WISE_HELM_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTYwNTYyMzMzMzU2MSwKICAicHJvZmlsZUlkIiA6ICJjZGM5MzQ0NDAzODM0ZDdkYmRmOWUyMmVjZmM5MzBiZiIsCiAgInByb2ZpbGVOYW1lIiA6ICJSYXdMb2JzdGVycyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS85Y2E2YWM4Mzk2YmEyZmE2NGIwZjI3MTFkY2EyMDIzMmM3YTUyOTEyNmI5NmRiNmVmYWE4ZDdmMmUxODQwZDEiCiAgICB9CiAgfQp9"

    private fun piece(base: Item, id: String, name: String, lore: List<String>, rgb: Int?, style: String): ItemStack =
        item(base, id, name, lore).also { s ->
            if (rgb != null) s.set(DataComponents.DYED_COLOR, DyedItemColor(rgb))
            hy(s, null, style)
        }

    private fun terror(base: Item, id: String, rgb: Int): ItemStack {
        val n = 3 + (if (terrorHelmet) 1 else 0)
        val sec = when { n >= 4 -> 10; n == 3 -> 7; else -> 4 }
        val dmg = when { n >= 4 -> 6; n == 3 -> 4; else -> 2 }
        val lore = (when (id) { "TERROR_CHESTPLATE" -> Lore.TERROR_CHESTPLATE; "TERROR_LEGGINGS" -> Lore.TERROR_LEGGINGS; else -> Lore.TERROR_BOOTS })
            .map { it.replace("{T}", n.toString()).replace("{SEC}", sec.toString()).replace("{DMG}", dmg.toString()) }
        val name = when (id) { "TERROR_CHESTPLATE" -> Lore.TERROR_CHESTPLATE_NAME; "TERROR_LEGGINGS" -> Lore.TERROR_LEGGINGS_NAME; else -> Lore.TERROR_BOOTS_NAME }
        return piece(base, id, name, lore, rgb, "legendary")
    }

    fun equipArmor(p: ServerPlayer, set: ArmorSet? = null, terrorHelm: Boolean? = null) {
        terrorHelmet = terrorHelm ?: (idOf(p.getItemBySlot(EquipmentSlot.HEAD)) == "TERROR_HELMET")
        val (chest, legs, feet) = armorStacks(set)
        wear(p, EquipmentSlot.CHEST, chest)
        wear(p, EquipmentSlot.LEGS, legs)
        wear(p, EquipmentSlot.FEET, feet)
        terrorHelmet = false
    }

    private var terrorHelmet = false

    fun armorPiece(id: String): ItemStack? =
        ArmorSet.entries.firstNotNullOfOrNull { set -> armorStacks(set).toList().firstOrNull { idOf(it) == id } }

    private fun armorStacks(set: ArmorSet?): Triple<ItemStack, ItemStack, ItemStack> {
        val which = set ?: ArmorSet.MAXOR
        val chest: ItemStack; val legs: ItemStack; val feet: ItemStack
        when (which) {
            ArmorSet.TERROR -> {
                chest = terror(Items.LEATHER_CHESTPLATE, "TERROR_CHESTPLATE", 4064687)
                legs = terror(Items.LEATHER_LEGGINGS, "TERROR_LEGGINGS", 6104017)
                feet = terror(Items.LEATHER_BOOTS, "TERROR_BOOTS", 8144108)
            }
            ArmorSet.MAXOR -> {
                chest = piece(Items.CHAINMAIL_CHESTPLATE, "MITHRIL_COAT", Lore.MITHRIL_COAT_NAME, Lore.MITHRIL_COAT, null, "legendary")
                legs = piece(Items.LEATHER_LEGGINGS, "SPEED_WITHER_LEGGINGS", Lore.SPEED_WITHER_LEGGINGS_NAME, Lore.SPEED_WITHER_LEGGINGS, 6107065, "mythic")
                feet = piece(Items.LEATHER_BOOTS, "SPEED_WITHER_BOOTS", Lore.SPEED_WITHER_BOOTS_NAME, Lore.SPEED_WITHER_BOOTS, 9005512, "mythic")
            }
            ArmorSet.WISE -> {
                chest = piece(Items.LEATHER_CHESTPLATE, "WISE_WITHER_CHESTPLATE", Lore.WISE_WITHER_CHESTPLATE_NAME, Lore.WISE_WITHER_CHESTPLATE, 1545156, "mythic")
                legs = piece(Items.LEATHER_LEGGINGS, "WISE_WITHER_LEGGINGS", Lore.WISE_WITHER_LEGGINGS_NAME, Lore.WISE_WITHER_LEGGINGS, 1550532, "mythic")
                feet = piece(Items.LEATHER_BOOTS, "WISE_WITHER_BOOTS", Lore.WISE_WITHER_BOOTS_NAME, Lore.WISE_WITHER_BOOTS, 1889508, "mythic")
            }
        }
        return Triple(chest, legs, feet)
    }

    fun equipHelmet(p: ServerPlayer, wise: Boolean) {
        val h = if (wise) head(WISE_HELM_TEX, Lore.WISE_WITHER_HELMET_NAME) else head(RACING_TEX, Lore.RACING_HELMET_NAME)
        h.set(DataComponents.LORE, ItemLore((if (wise) Lore.WISE_WITHER_HELMET else Lore.RACING_HELMET).map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        h.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        h.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", if (wise) "WISE_WITHER_HELMET" else "RACING_HELMET"); it.putBoolean("p3sim", true) }))
        hy(h, null, "mythic")
        wear(p, EquipmentSlot.HEAD, h)
    }

    fun wear(p: ServerPlayer, slot: EquipmentSlot, s: ItemStack) = p.inventory.setItem(slot.getIndex(36), s)

    fun giveHotbar(p: ServerPlayer, p3: Boolean = true, start: Boolean = false) {
        val inv = p.inventory
        HotbarLayout.keep(p)
        val pet = P3Sim.phoenix
        inv.clearContent()
        val bar = listOf(if (p3) SUPERBOOM else HYPERION, BONZO, TERMINATOR, DUNGEONBREAKER, PET_ROD, LEAP, JERRY, CLOAK, MENU)
        val extras = listOf(10 to (if (p3) HYPERION else SUPERBOOM), 11 to AOTV, 12 to SPIRIT_BOW, 13 to PEARLS, 14 to MOSQUITO, 15 to LAST_BREATH, 16 to EXPLOSIVE_BOW)
        val (items, held) = HotbarLayout.arrange(bar.mapIndexed { i, s -> i to s } + extras, p3)
        items.forEach { (slot, s) -> inv.setItem(slot, s) }
        Loadouts.applySaved(p, p3)
        if (!start && P3Sim.phoenix != pet) { P3Sim.setPhoenix(pet); Fight.applySpeed(p) }
        if (p3 && P3Sim.terrorAtTerms) Loadouts.wearTerror(p)
        HotbarLayout.gave(p3, held)
        inv.selectedSlot = held
        p.connection.send(ClientboundSetHeldSlotPacket(held))
        p.containerMenu.broadcastChanges()
        p.inventoryMenu.broadcastChanges()
    }

    val SPIRIT_SCEPTRE get() = item(Items.PAPER, SCEPTRE, "§6 Heroic Spirit Sceptre §6✪✪✪✪✪", listOf(
        "§6Ability: Guided Bat §e§lRIGHT CLICK", "§7Shoots a guided spirit bat, following", "§7your aim and exploding on what it hits.",
        "", "§6§lLEGENDARY DUNGEON SWORD"), glint = true).also { hy(it, "island_relevant/dungeons/spirit_sceptre_fragged", "legendary") }
    const val SCEPTRE = "STARRED_BAT_WAND"

    private const val SCEPTRE_RANGE = 24.0

    private fun sceptre(p: ServerPlayer) {
        val eye = p.eyePosition
        val look = p.lookAngle
        val end = eye.add(look.scale(SCEPTRE_RANGE))
        val wall = p.level().clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
        val reach = if (wall.type == HitResult.Type.MISS) SCEPTRE_RANGE else wall.location.distanceTo(eye)
        val at = eye.add(look.scale(reach))
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 0.5f, 1.2f, at, SoundSource.PLAYERS)
    }

    private fun simServer(level: Level) = !level.isClientSide && level is ServerLevel && level.server === SimServer.server && SimServer.server != null
    private fun simClient(level: Level) = level.isClientSide && P3Sim.inSim

    fun register() {
        UseItemCallback.EVENT.register { player, level, hand ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            val id = idOf(stack) ?: return@register InteractionResult.PASS
            if (simClient(level)) {
                if (id == "SKYBLOCK_MENU") { mc.execute { mc.gui.setScreen(SimRestartScreen()) }; return@register InteractionResult.FAIL }
                return@register InteractionResult.PASS
            }
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            Sim.asPlayer(player) {
                blockFired?.let { (bid, t) -> if (bid == id && Fight.serverTick - t in 0..1) { blockFired = null; return@asPlayer InteractionResult.SUCCESS } }
                var result: InteractionResult = InteractionResult.PASS
                EngineerClient.safely("p3sim use $id") { result = use(player, id) }
                result
            }
        }
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val id = idOf(player.getItemInHand(hand))
            if (simClient(level)) {
                if (id == "SKYBLOCK_MENU") { mc.execute { mc.gui.setScreen(SimRestartScreen()) }; return@register InteractionResult.FAIL }
                return@register InteractionResult.PASS
            }
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use block") { result = Sim.asPlayer(player) { useBlock(player, hit.blockPos, id, hit.direction) } }
            if (player.mainHandItem.item is BlockItem) player.connection.send(ClientboundSetPlayerInventoryPacket(player.inventory.selectedSlot, player.mainHandItem.copy()))
            result
        }
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            if (hand != InteractionHand.MAIN_HAND || !simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use entity") { result = Sim.asPlayer(player) { useEntity(player, entity) } }
            result
        }
        AttackEntityCallback.EVENT.register { player, level, _, entity, _ ->
            if (simClient(level)) return@register if (entity is ItemFrame) InteractionResult.FAIL else InteractionResult.PASS
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim hit entity") { result = Sim.asPlayer(player) { useEntity(player, entity, left = true) } }
            result
        }
    }

    private var hitHeld: BlockPos? = null

    private val predicted = HashMap<BlockPos, Pair<Int, String>>()
    private var clientTicks = 0

    private fun watchPredicted() {
        clientTicks++
        if (predicted.isEmpty()) return
        val level = mc.level ?: run { predicted.clear(); return }
        val it = predicted.entries.iterator()
        while (it.hasNext()) {
            val (pos, v) = it.next()
            val age = clientTicks - v.first
            val now = level.getBlockState(pos)
            if (!now.isAir) {
                EngineerClient.logger.info("[p3sim] breaker: {},{},{} ({}) back in the game {} ticks after the hit as {}", pos.x, pos.y, pos.z, v.second, age,
                    now.block.descriptionId.substringAfterLast('.'))
                it.remove()
            } else if (age > 40) it.remove()
        }
    }

    @JvmStatic
    fun clientTick() {
        EngineerClient.safely("p3sim breaker watch") { watchPredicted() }
        if (!mc.options.keyAttack.isDown) { hitHeld = null; return }
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (mc.gui.screen() != null || player.isUsingItem || !simClient(level) || idOf(player.mainHandItem) != "DUNGEONBREAKER") return
        if (mc.hitResult?.type == HitResult.Type.BLOCK) player.swing(InteractionHand.MAIN_HAND, player.mainHandItem.attackAnimation, false)
    }

    @JvmStatic
    fun clientHitBlock(pos: BlockPos, face: Direction): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (!simClient(level)) return false
        val at = pos.immutable()
        val again = at == hitHeld
        hitHeld = at
        val held = idOf(player.mainHandItem)
        if (held == "DUNGEONBREAKER" && Practice.noBreaking()) return true
        val state = level.getBlockState(at)
        val lever = state.block is LeverBlock
        if (!again || held == "DUNGEONBREAKER") SimNet.send("hit", "${at.x},${at.y},${at.z},${face.ordinal},${if (again) 1 else 0}")
        if (held == "SUPERBOOM_TNT") return true
        if (lever && held != "DUNGEONBREAKER") return true
        if (held != "DUNGEONBREAKER") return false
        if (state.isAir) return true
        if (state.getDestroySpeed(level, at) >= 0) { predicted[at] = clientTicks to state.block.descriptionId.substringAfterLast('.'); level.destroyBlock(at, false) }
        return true
    }

    fun serverHit(p: ServerPlayer, data: String) {
        val v = data.split(',')
        if (v.size < 5) return
        val at = BlockPos(v[0].toIntOrNull() ?: return, v[1].toIntOrNull() ?: return, v[2].toIntOrNull() ?: return)
        val face = Direction.entries.getOrNull(v[3].toIntOrNull() ?: return) ?: return
        val again = v[4] == "1"
        Blocks.clientHit(at)
        val held = idOf(p.mainHandItem)
        fun sendBack() { if (!p.isRemoved) p.connection.send(ClientboundBlockUpdatePacket(Sim.level, at)) }
        if (p.position().distanceTo(Vec3.atCenterOf(at)) > 12.0) { if (held == "DUNGEONBREAKER") sendBack(); return }
        (Fight.phase as? GoldorPhase)?.let { ph -> if (ph.leverAt(at) != null || ph.devices.lights.isLever(at)) { if (!again) ph.devices.leftClick(at); if (held != "DUNGEONBREAKER") return } }
        if (held == "SUPERBOOM_TNT") {
            if (!again) Fight.afterPing("superboom") { Fight.later(1, "superboom") { superboom(p, at, face) } }
            return
        }
        if (held == "DUNGEONBREAKER") mine(p, at)
        else Fight.afterPing("hit ignored") { sendBack() }
    }

    @JvmStatic
    fun quiverArrow(p: Player, weapon: ItemStack): ItemStack? {
        if (idOf(weapon) !in Bows.DRAWN) return null
        val level = p.level()
        val sim = if (level.isClientSide) P3Sim.inSim else SimServer.isSimLevel(level)
        return if (sim) ItemStack(Items.ARROW) else null
    }

    @JvmStatic
    fun releaseBow(stack: ItemStack, level: Level, entity: LivingEntity, remainingTime: Int): Boolean? {
        val bow = idOf(stack)?.takeIf { it in Bows.DRAWN } ?: return null
        if (!simServer(level) || entity !is ServerPlayer) return null
        val power = BowItem.getPowerForTime(stack.getUseDuration(entity) - remainingTime)
        if (power < 0.1f) return false
        Bows.release(entity, bow, power)
        return true
    }

    @JvmStatic
    fun clientLeftClick() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (!simClient(level)) return
        val id = idOf(player.mainHandItem)
        if (id != null || Practice.active || Practice.clickRestarts()) SimNet.send("left", id ?: "")
    }

    fun serverLeftClick(p: ServerPlayer, held: String = "") {
        val id = idOf(p.mainHandItem)
        if (Sim.isHost(p) && (Practice.clickRestarts() || Practice.active && (id == "INFINITE_SPIRIT_LEAP" || held == "INFINITE_SPIRIT_LEAP"))) { Practice.restart(); return }
        if (Fight.phase == null && id == "STARRED_BONZO_STAFF") { val sneak = p.isShiftKeyDown; asClicked(p, "bonzo aotv") { if (sneak) Teleports.etherwarp(p) else Teleports.blink(p, 12) }; return }
        if (Mage.isMage() && Mage.beamItem(id)) { Fight.afterPing("mage beam") { Mage.click(p) }; return }
        if (id in Bows.SHORTBOWS) Bows.click(p, id!!, left = true)
    }

    @JvmStatic
    fun clientDrop(fullStack: Boolean): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (!simClient(level)) return false
        SimNet.send("drop", if (fullStack) "1" else "0")
        return true
    }

    fun serverDrop(p: ServerPlayer, fullStack: Boolean) {
        if (!fullStack) return
        if (P3Sim.myClass == DungeonClass.ARCHER) asClicked(p, "archer ability") { volley(p) }
        else if (Mage.isMage()) Fight.afterPing("guided sheep") { Mage.ability(p) }
    }

    private fun asClicked(p: ServerPlayer, what: String, prior: Boolean = false, run: () -> Unit) {
        val xRot = if (prior) Fight.lastRot.first else p.xRot
        val yRot = if (prior) Fight.lastRot.second else p.yRot
        val pos = p.position()
        val at = Fight.serverTick
        Fight.afterPing(what) {
            if (p.isRemoved) return@afterPing
            val nowX = p.xRot; val nowY = p.yRot
            p.xRot = xRot; p.yRot = yRot
            clickPos = if ((itemTpAt[p.uuid] ?: Int.MIN_VALUE) >= at) p.position() else pos
            try { run() } finally { p.xRot = nowX; p.yRot = nowY; clickPos = null }
        }
    }

    internal var clickPos: Vec3? = null
        private set

    private val itemTpAt = HashMap<UUID, Int>()

    internal fun tpKeepLook(p: ServerPlayer, x: Double, y: Double, z: Double) {
        itemTpAt[p.uuid] = Fight.serverTick
        p.teleportTo(Sim.level, x, y, z, Relative.ROTATION, 0f, 0f, false)
        p.deltaMovement = Vec3.ZERO
        p.fallDistance = 0.0
    }

    fun reset() { itemTpAt.clear(); Mage.reset(); MovementItems.rapidLast = -1000; resetBreaker(); MovementItems.cloakUntil = 0; MovementItems.cloakReady = 0; MovementItems.vitality = 0; MovementItems.discardVeil(); Teleports.lastHype = -100; Teleports.lastCure = -1000; MovementItems.bonzoLast = -100; MovementItems.jerryTick = -1; SpiritLeap.leapReady = 0; SpiritLeap.pearlLeapReady = 0; volleyReady = 0; blockFired = null; quiverArrows = QUIVER_START; breakerHeld = null; Bows.reset() }

    fun resetShared() {
        arrows.clear(); lastMotion.clear(); lastPos.clear(); MovementItems.pendingMotion.clear()
        if (MovementItems.liveRockets.isNotEmpty()) {
            val ids = IntArrayList(MovementItems.liveRockets)
            Sim.players().forEach { it.connection.send(ClientboundRemoveEntitiesPacket(ids)) }
            MovementItems.liveRockets.clear()
        }
    }

    private fun use(p: ServerPlayer, id: String, fromBlock: Boolean = false): InteractionResult {
        (Fight.phase as? P1Maxor)?.let { if (it.usePylon(p.position())) return InteractionResult.SUCCESS }
        if (id == "HYPERION") (Fight.phase as? P2Storm)?.beam()
        when (id) {
            "ASPECT_OF_THE_VOID" -> { val sneak = p.isShiftKeyDown; asClicked(p, "aotv") { if (sneak) Teleports.etherwarp(p) else Teleports.blink(p, 12) } }
            "HYPERION" -> { val cast = Teleports.replayCast?.invoke(); asClicked(p, "hype") {
                if (cast?.also { if (it) Teleports.lastHype = Fight.serverTick } ?: Teleports.hypeReady()) {
                    Teleports.implode(p)
                }
            } }
            "ENDER_PEARL" -> { p.mainHandItem.shrink(1); Sim.chat("§cA mystical force in this room prevents you from using that ability!") }
            "STARRED_BONZO_STAFF" -> asClicked(p, "bonzo") { MovementItems.bonzo(p) }
            "JERRY_STAFF" -> asClicked(p, "jerry", prior = !fromBlock) { MovementItems.jerry(p) }
            "WITHER_CLOAK" -> asClicked(p, "cloak") { MovementItems.cloak(p) }
            SCEPTRE -> asClicked(p, "sceptre") { sceptre(p) }
            ClearItems.INFINILEAP, ClearItems.SPIRIT_LEAP -> SpiritLeap.openLeap(p, id)
            in ClearItems.INERT -> inertItem(p, id)
            "HAUNT_ABILITY" -> SpiritLeap.openHaunt(p)
            "SUPERBOOM_TNT" -> {}
            in Bows.SHORTBOWS -> Bows.click(p, id, left = false)
            "PET_ROD" -> Fight.afterPing("pet rod") { Fight.later(1, "pet rod cast") { if (!p.isRemoved) MovementItems.castRod(p) } }
            else -> return InteractionResult.PASS
        }
        p.containerMenu.broadcastChanges()
        return InteractionResult.SUCCESS
    }

    private fun throwPearl(p: ServerPlayer) {
        val level = p.level() as? ServerLevel ?: return
        val held = p.mainHandItem
        if (held.isEmpty) return
        Sim.sound(SoundEvents.ARROW_SHOOT, ClearItems.PEARL_VOLUME, ClearItems.pearlPitch(Random.nextFloat()), p.position(), SoundSource.NEUTRAL)
        val recorded = replayPearl?.invoke(p)
        Projectile.spawnProjectileFromRotation(
            Projectile.ProjectileFactory<SimPearl> { l, o, s -> SimPearl(l, o, s).also { it.addTag(Sim.TAG); it.scripted = recorded != null } },
            level, held, p, 0f, ClearItems.PEARL_SPEED, ClearItems.PEARL_SPREAD)
        if (recorded != null) Fight.later(maxOf(1, recorded.first), "replay pearl") { if (!p.isRemoved) SimPearl.land(p, recorded.second) }
        if (!p.isCreative) held.shrink(1)
    }

    @Volatile var replayPearl: ((ServerPlayer) -> Pair<Int, Vec3>?)? = null

    internal fun resyncHeld(p: ServerPlayer) {
        p.connection.send(ClientboundContainerSetSlotPacket(0, p.inventoryMenu.incrementStateId(), 36 + p.inventory.selectedSlot, p.mainHandItem.copy()))
    }

    private var inertNoteAt: Map<String, Int> by perPlayer { emptyMap() }

    private fun inertItem(p: ServerPlayer, id: String) {
        resyncHeld(p)
        val now = Fight.serverTick
        if (now - (inertNoteAt[id] ?: -ClearItems.INERT_NOTE_GAP) < ClearItems.INERT_NOTE_GAP) return
        inertNoteAt = inertNoteAt + (id to now)
        ClearItems.INERT[id]?.let { Sim.note(ClearItems.inertNote(it)) }
    }

    fun vanillaLeverToggle(pos: BlockPos) {
        val st = Blocks.get(pos)?.takeIf { it.hasProperty(LeverBlock.POWERED) } ?: Sim.level.getBlockState(pos).takeIf { it.hasProperty(LeverBlock.POWERED) } ?: return
        val on = !st.getValue(LeverBlock.POWERED)
        Blocks.set(pos, st.setValue(LeverBlock.POWERED, on))
        Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (on) 0.5873016f else 0.4920635f, Vec3.atCenterOf(pos), SoundSource.BLOCKS)
    }

    private fun useBlock(p: ServerPlayer, pos: BlockPos, id: String?, hitFace: Direction): InteractionResult {
        val phase = Fight.phase
        if (phase is P1Maxor && phase.usePylon(Vec3.atCenterOf(pos))) return InteractionResult.SUCCESS
        if (phase is GoldorPhase) {
            phase.leverAt(pos)?.let { st -> Fight.afterPing("lever") { phase.pullLever(st, Sim.me) }; return InteractionResult.SUCCESS }
            if (phase.devices.use(pos)) return InteractionResult.SUCCESS
            if (id == "SUPERBOOM_TNT") {
                Fight.afterPing("superboom") { Fight.later(1, "superboom") { superboom(p, pos, hitFace) } }
                blockFired = id to Fight.serverTick
                return InteractionResult.SUCCESS
            }
        }
        val state = Sim.level.getBlockState(pos)
        if (state.block is LeverBlock) { Fight.afterPing("lever") { vanillaLeverToggle(pos) }; return InteractionResult.SUCCESS }
        if (state.block is ButtonBlock) return InteractionResult.SUCCESS
        if (id == null) return InteractionResult.SUCCESS
        if (takesClick(state)) return InteractionResult.SUCCESS
        val r = use(p, id, fromBlock = true)
        if (r != InteractionResult.PASS && p.mainHandItem.item !is BlockItem) blockFired = id to Fight.serverTick
        return r
    }

    private fun takesClick(s: BlockState) = s.block.let {
        it is ChestBlock || it is HopperBlock ||
            it is CraftingTableBlock || it is AnvilBlock ||
            it is AbstractCauldronBlock
    }

    private var blockFired: Pair<String, Int>? by perPlayer { null }

    fun swallowFollowUp(p: ServerPlayer) { Sim.asPlayer(p) { idOf(p.mainHandItem)?.let { blockFired = it to Fight.serverTick } } }

    private val termClickTick: WeakHashMap<Station, Int> by perPlayer { WeakHashMap() }

    private fun useEntity(p: ServerPlayer, e: Entity, left: Boolean = false): InteractionResult {
        if (e is EndCrystal) { (Fight.phase as? P1Maxor)?.useCrystal(e); return InteractionResult.SUCCESS }
        if (e is ArmorStand && (Fight.phase as? P1Maxor)?.useCrystal(e) == true) return InteractionResult.SUCCESS
        if (left && idOf(p.mainHandItem) == "HYPERION") (Fight.phase as? P2Storm)?.beam()
        val phase = Fight.phase as? GoldorPhase
        if (phase != null) {
            if (e is ItemFrame && phase.devices.arrows.owns(e)) { if (!left) phase.devices.arrows.use(e); return InteractionResult.SUCCESS }
            if (e is ArmorStand) {
                val st = phase.stations.firstOrNull { it.owns(e) }
                if (st != null && st.kind == Station.Kind.TERMINAL) {
                    if (termClickTick[st] != Fight.serverTick) {
                        termClickTick[st] = Fight.serverTick
                        Fight.afterPing("terminal") { phase.useTerminal(st) }
                    }
                    return InteractionResult.SUCCESS
                }
                return InteractionResult.SUCCESS
            }
        }
        if (e is ItemFrame || e is ArmorStand) return InteractionResult.SUCCESS
        if (e.entityTags().contains(Sim.TAG)) return InteractionResult.SUCCESS
        return InteractionResult.PASS
    }

    private fun superboom(p: ServerPlayer, on: BlockPos?, face: Direction) {
        val phase = Fight.phase as? GoldorPhase ?: return
        val at = on?.let { Vec3.atCenterOf(it) } ?: return
        boomFx(Vec3.atCenterOf(on.relative(face)))
        val gate = phase.gateNear(at, 1.5)
        if (gate > 0) phase.blowGate(gate, Sim.me)
    }

    private fun boomFx(at: Vec3) {
        Sim.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 1.0, 1.0, 1.0, 0.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, (floor((0.81 + Random.nextDouble() * 0.174) * 63) / 63).toFloat(), at)
    }

    private const val THAT_BLOCK = "§cA mystical force prevents you from digging that block!"
    private const val THERE = "§cA mystical force prevents you digging there!"
    private const val NO_CHARGES = "§cYou don't have enough charges to break this block right now!"
    const val MAX_CHARGES = 20

    private val CORE_DOOR = AABB(52.0, 115.0, 54.0, 57.0, 122.0, 55.0)
    private val S4_DEVICE = AABB(55.0, 132.0, 142.0, 65.0, 137.0, 148.0)
    private val SS_DEVICE = AABB(109.0, 119.0, 90.0, 112.0, 125.0, 96.0)

    private fun holdsSomething(pos: BlockPos): Boolean {
        val level = Sim.level
        if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return false
        val face = FaceAttachedHorizontalDirectionalBlock.FACE
        val facing = BlockStateProperties.HORIZONTAL_FACING
        val up = level.getBlockState(pos.above())
        val ub = up.block
        val onTop = ub is CarpetBlock || ub is BasePressurePlateBlock ||
            (ub is FaceAttachedHorizontalDirectionalBlock && up.getValue(face) == AttachFace.FLOOR) ||
            (ub is BaseTorchBlock && ub !is WallTorchBlock && ub !is RedstoneWallTorchBlock) ||
            ub is BaseFireBlock || ub is SkullBlock || ub is StandingSignBlock ||
            ub is BannerBlock || ub is BaseRailBlock || ub is RedstoneWireBlock ||
            ub is SnowLayerBlock || ub is DoorBlock || ub is TripWireBlock ||
            ub is VegetationBlock || ub is FlowerPotBlock
        if (onTop) return true
        val down = level.getBlockState(pos.below())
        if (down.block is FaceAttachedHorizontalDirectionalBlock && down.getValue(face) == AttachFace.CEILING) return true
        for (d in Direction.Plane.HORIZONTAL) {
            val n = level.getBlockState(pos.relative(d))
            val nb = n.block
            val wall = nb is WallTorchBlock || nb is RedstoneWallTorchBlock || nb is WallSkullBlock ||
                nb is WallSignBlock || nb is WallBannerBlock || nb is LadderBlock ||
                nb is TripWireHookBlock ||
                (nb is FaceAttachedHorizontalDirectionalBlock && n.getValue(face) == AttachFace.WALL)
            if (wall && n.hasProperty(facing) && n.getValue(facing) == d) return true
            val side = PipeBlock.PROPERTY_BY_DIRECTION[d.opposite]
            if ((nb is FireBlock || nb is VineBlock) && side != null && n.hasProperty(side) && n.getValue(side)) return true
        }
        return false
    }

    private fun refusal(p: ServerPlayer, pos: BlockPos, s: BlockState): String? {
        val b = s.block
        val c = Vec3.atCenterOf(pos)
        if (SS_DEVICE.contains(c) && (b is ButtonBlock || b == net.minecraft.world.level.block.Blocks.EMERALD_BLOCK || b == net.minecraft.world.level.block.Blocks.REDSTONE_LAMP || b == net.minecraft.world.level.block.Blocks.SEA_LANTERN)) return THERE
        if (S4_DEVICE.contains(c) && (b is LeverBlock || b is ButtonBlock || b == net.minecraft.world.level.block.Blocks.REDSTONE_LAMP || b == net.minecraft.world.level.block.Blocks.BEDROCK)) return THERE
        if (s.getDestroySpeed(Sim.level, pos) < 0) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.BARRIER || b == net.minecraft.world.level.block.Blocks.BEDROCK) return THAT_BLOCK
        if (b is CommandBlock) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.GOLD_BLOCK && !CORE_DOOR.contains(c)) return THAT_BLOCK
        if (b is LeverBlock || b is ButtonBlock) return THAT_BLOCK
        if (b is PistonBaseBlock || b is PistonHeadBlock || b is MovingPistonBlock) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.GRANITE || b == net.minecraft.world.level.block.Blocks.POLISHED_GRANITE) return THAT_BLOCK
        if (holdsSomething(pos)) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.REDSTONE_LAMP) return THERE
        if (b == net.minecraft.world.level.block.Blocks.EMERALD_BLOCK) return THAT_BLOCK
        if (p.eyePosition.distanceTo(c) > 5.2) return ""
        return null
    }

    private var chargesNow by perPlayer { MAX_CHARGES }
    var charges: Int get() = chargesNow; private set(v) { if (chargesNow != v) { chargesNow = v; refreshBreakerLore() } }
    private var refillStep by perPlayer { 0 }
    private val refillRng = java.util.Random()
    private class Broken(val pos: BlockPos, val state: BlockState, val at: Int) { var restoreAt = Int.MAX_VALUE }
    private val brokenBy = perPlayer { ArrayDeque<Broken>() }
    private val broken: ArrayDeque<Broken> by brokenBy
    private val window: ArrayDeque<Broken> by perPlayer { ArrayDeque() }
    private var refusedSaidAt by perPlayer { -100 }

    private fun resetBreaker() {
        charges = MAX_CHARGES; refillStep = 0; window.clear(); refusedSaidAt = -100
        if (!P3Sim.breakerPerma) broken.clear()
    }

    fun rebreak() {
        if (!P3Sim.breakerPerma) return
        brokenBy.values().forEach { l -> l.forEach { Blocks.set(it.pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()) } }
    }

    fun unbreakAll() {
        if (P3Sim.breakerPerma) return
        brokenBy.values().forEach { l -> l.forEach { restore(it) }; l.clear() }
        Sim.forEachPlayer { window.clear() }
    }

    fun unPerma() { broken.forEach { restore(it) }; broken.clear(); window.clear() }

    private fun mine(p: ServerPlayer, pos: BlockPos) {
        val level = Sim.level
        val s = level.getBlockState(pos)
        if (s.isAir) return
        if (Practice.noBreaking()) { p.connection.send(ClientboundBlockUpdatePacket(level, pos)); return }
        val now = Fight.serverTick
        val why = refusal(p, pos, s)
        EngineerClient.logger.info("[p3sim] breaker: server hit {},{},{} ({}) at tick {}: {}", pos.x, pos.y, pos.z, s.block.descriptionId.substringAfterLast('.'), now,
            when { why != null -> "refused (${why.ifEmpty { "quietly" }})"; charges <= 0 -> "refused (no charges)"; else -> "broken, ${charges - (if (P3Sim.breakerInfinite) 0 else 1)} charges left" })
        if (why != null || charges <= 0) {
            p.connection.send(ClientboundBlockUpdatePacket(level, pos))
            if (why == null) { if (now - refusedSaidAt >= 20) { refusedSaidAt = now; Sim.chat(NO_CHARGES) } }
            else if (why.isNotEmpty() && now - refusedSaidAt >= 20) { refusedSaidAt = now; Sim.chat(why) }
            return
        }
        if (!P3Sim.breakerInfinite) charges--
        breakerSet(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
        broken.addLast(Broken(pos, s, now))
        val b = broken.last()
        window.addLast(b)
        if (window.size > MAX_CHARGES) { val o = window.removeFirst(); if (!P3Sim.breakerPerma) o.restoreAt = minOf(o.restoreAt, now + 41) }
    }

    private fun restore(b: Broken) {
        EngineerClient.logger.info("[p3sim] breaker: {},{},{} back on the server (regen) at tick {}", b.pos.x, b.pos.y, b.pos.z, Fight.serverTick)
        breakerSet(b.pos, b.state)
    }

    private fun breakerSet(pos: BlockPos, state: BlockState) = Blocks.set(pos, state)

    private fun restoreBroken() {
        if (P3Sim.breakerPerma) return
        val now = Fight.serverTick
        val regen = Math.round(P3Sim.breakerRegen * 20).toInt()
        brokenBy.values().forEach { l -> l.removeAll { if (now - it.at >= regen || now >= it.restoreAt) { restore(it); true } else false } }
    }

    fun addBreakerCharges(n: Int) { charges = (charges + n).coerceAtMost(MAX_CHARGES) }

    private fun tickBreaker() {
        val now = Fight.serverTick
        if (charges >= MAX_CHARGES) refillStep = 0
        else {
            if (refillStep == 0) refillStep = now + 1 + refillRng.nextInt(2 * Math.max(1, (36 / P3Sim.breakerRefill)) - 1)
            if (now >= refillStep) {
                charges = (charges + (if (refillRng.nextInt(5) == 0) 1 else 2)).coerceAtMost(MAX_CHARGES)
                refillStep = 0
            }
        }
    }

    private var volleyReady by perPlayer { 0 }

    private fun volley(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now < volleyReady) { Sim.chat("§cYour Regular Ability is currently on cooldown for ${(volleyReady - now + 19) / 20} more seconds."); return }
        volleyReady = now + 440
        volleyArrows(p)
        Sim.chat("§aUsed §6Explosive Shot§a!")
        Fight.later(1, "explosive shot summary") { Sim.chat("§7Your Explosive Shot hit §c0 §7enemies for §c0 §7damage.") }
    }

    internal fun volleyArrows(p: ServerPlayer) {
        val mid = Vec3(p.x, p.y + p.bbHeight / 2, p.z)
        for (dy in listOf(-5.5f, 0f, 5.5f)) {
            val yaw = Math.toRadians((p.yRot + dy).toDouble()); val pitch = Math.toRadians(p.xRot.toDouble())
            val v = Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch)).scale(3.0)
            Bows.launch(mid, mid, v, owner = p) { h ->
                if (h is BlockHitResult) {
                    val at = h.location
                    boomFx(Vec3.atCenterOf(h.blockPos.relative(h.direction)))
                    (Fight.phase as? GoldorPhase)?.let { g -> g.gateNear(at, 2.0).takeIf { it > 0 }?.let { g.blowGate(it, Sim.me) } }
                }
            }?.isCritArrow = true
        }
        Sim.sound(SoundEvents.ARROW_SHOOT, 1f, 0.8f, p.position())
    }

    private val arrows = ArrayList<AbstractArrow>()
    private val lastMotion = HashMap<AbstractArrow, Vec3>()

    fun tick() {
        val level = SimServer.level ?: return
        Sim.forEachPlayer { p ->
            tickBreaker()
            EngineerClient.safely("p3sim veil") { MovementItems.tickVeil(p) }
            EngineerClient.safely("p3sim mage") { Mage.tick() }
            EngineerClient.safely("p3sim slot 9") { tickSlot9(p) }
            EngineerClient.safely("p3sim mining effects") { miningEffects(p) }
        }
        restoreBroken()
        level.getEntitiesOfClass(AbstractArrow::class.java, AABB(-20.0, 0.0, -20.0, 160.0, 256.0, 160.0)) { it.owner is Player && it !is SimArrow && it !in arrows }.forEach { arrows += it }
        val it = arrows.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (a.isRemoved) { it.remove(); lastMotion.remove(a); lastPos.remove(a); continue }
            val v = a.deltaMovement
            if (v.lengthSqr() > 1e-3) lastMotion[a] = v
            val dir = (lastMotion[a] ?: v).let { if (it.lengthSqr() < 1e-6) Vec3.ZERO else it.normalize() }
            val from = lastPos[a] ?: a.position().subtract(dir)
            val to = a.position().add(dir.scale(0.6))
            lastPos[a] = a.position()
            val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, a))
            if (hit.type == HitResult.Type.BLOCK) {
                (Fight.phase as? GoldorPhase)?.devices?.target?.hit(hit.blockPos)
                a.discard()
                it.remove(); lastMotion.remove(a); lastPos.remove(a)
                continue
            }
            if (a.tickCount > 100 || v.lengthSqr() < 1e-3) { a.discard(); it.remove(); lastMotion.remove(a); lastPos.remove(a) }
        }
    }

    private val lastPos = HashMap<AbstractArrow, Vec3>()
}
