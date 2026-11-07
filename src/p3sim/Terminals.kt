package com.engineerclient.p3sim

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket
import net.minecraft.resources.Identifier
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
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.TooltipDisplay
import java.util.Collections
import java.util.LinkedHashSet
import kotlin.random.Random

object Terminals {
    enum class Type(val rows: Int) { ORDER(4), PANES(5), RUBIX(5), STARTS(5), SELECT(6), MELODY(6) }

    fun randomType(): Type = lucky(10, { t: Type -> if (t == Type.MELODY) 1 else 0 }) { Type.entries.filter { it != Type.MELODY || !P3Sim.noMelodies }.random() }

    fun <T> weighted(w: List<Pair<T, Int>>): T {
        var r = Random.nextInt(w.sumOf { it.second })
        for ((v, n) in w) { if (r < n) return v; r -= n }
        return w.last().first
    }

    private fun chance(x: Double): Int = x.toInt() + if (Random.nextDouble() < x - x.toInt()) 1 else 0

    fun <T> lucky(most: Int, cost: (T) -> Int, draw: () -> T): T {
        val luck = P3Sim.termLuck
        if (luck == 0.0) return draw()
        val n = chance(1 + (most - 1) * (Math.abs(luck) / 0.6).coerceAtMost(1.0)).coerceAtLeast(1)
        val draws = List(n) { draw() }
        return if (luck > 0) draws.minBy(cost) else draws.maxBy(cost)
    }

    fun beyond(clicks: Int, worst: Int): Int {
        val luck = P3Sim.termLuck
        val f = ((Math.abs(luck) - 0.4) / 0.6).coerceIn(0.0, 1.0).let { it * it }
        if (f <= 0.0) return clicks
        val to = if (luck > 0) 1 else worst
        return chance(clicks + (to - clicks) * f).coerceIn(minOf(1, clicks), maxOf(worst, clicks))
    }

    fun named(item: Item, name: String, count: Int = 1, glint: Boolean = false, color: ChatFormatting? = null): ItemStack {
        val s = ItemStack(item, count)
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { st -> st.withItalic(false).let { if (color != null) it.withColor(color) else it } })
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return s
    }

    val FILLER: ItemStack get() = named(Items.STAINED_GLASS_PANE.black(), "").also {
        it.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(true, LinkedHashSet()))
    }

    private fun item(id: String): Item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id))

    fun open(player: ServerPlayer, station: Station, type: Type? = null) {
        val keep = station.term?.type?.takeIf { Fight.forcedTerminal == null }
        val term = Term.create(type ?: keep ?: station.nextType()).also { station.term = it }
        player.openMenu(SimpleMenuProvider({ id, inv, _ -> TerminalMenu(id, inv, term, station) }, Component.literal(term.title)))
        Sim.sound(SoundEvents.LEVER_CLICK, 0.5f, 1f, source = SoundSource.BLOCKS)
    }

    abstract class Term(val type: Type) {
        abstract val title: String
        val size get() = type.rows * 9
        val items = Array(type.rows * 9) { FILLER }
        var done = false
        abstract fun click(slot: Int, button: Int, input: ContainerInput): Boolean
        abstract fun solved(): Boolean
        open fun tick(t: Int) {}

        companion object {
            fun create(t: Type): Term = when (t) {
                Type.ORDER -> Order()
                Type.PANES -> Panes()
                Type.RUBIX -> Rubix()
                Type.STARTS -> Starts()
                Type.SELECT -> Select()
                Type.MELODY -> Melody()
            }
        }
    }

    class Order : Term(Type.ORDER) {
        override val title = "Click in order!"
        private val slots = (11..15) + (20..24)
        private var next = 1
        init {
            val order = slots.shuffled().toMutableList()
            val luck = P3Sim.termLuck
            val steps = chance(Math.pow(1500.0, Math.abs(luck)) - 1)
            var cost = walk(order)
            repeat(steps) {
                val i = Random.nextInt(10); val j = Random.nextInt(10)
                Collections.swap(order, i, j)
                val c = walk(order)
                if (if (luck > 0) c <= cost else c >= cost) cost = c else Collections.swap(order, i, j)
            }
            order.forEachIndexed { i, s -> items[s] = named(Items.STAINED_GLASS_PANE.red(), "${i + 1}", i + 1, color = ChatFormatting.GREEN) }
        }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val it = items[slot]
            if (it.item != Items.STAINED_GLASS_PANE.red() || it.count != next) return false
            items[slot] = named(Items.STAINED_GLASS_PANE.lime(), "$next", next, color = ChatFormatting.GREEN)
            next++
            return true
        }
        override fun solved() = next > 10
        private fun walk(o: List<Int>) = o.zipWithNext().sumOf { (a, b) -> Math.abs(a % 9 - b % 9) + Math.abs(a / 9 - b / 9) }
    }

    class Panes : Term(Type.PANES) {
        override val title = "Correct all the panes!"
        private val slots = (11..15) + (20..24) + (29..33)
        init {
            val on = lucky(10, { n: Int -> -n }) { weighted(listOf(0 to 8, 1 to 24, 2 to 36, 3 to 44, 4 to 40, 5 to 16, 6 to 5, 7 to 4, 8 to 3, 9 to 1)) }
                .let { 15 - beyond(15 - it, 15) }
            val lit = slots.shuffled().take(on).toSet()
            slots.forEach { items[it] = pane(it in lit) }
        }
        private fun pane(on: Boolean) = if (on) named(Items.STAINED_GLASS_PANE.lime(), "On", color = ChatFormatting.GREEN) else named(Items.STAINED_GLASS_PANE.red(), "Off", color = ChatFormatting.RED)
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            if (slot !in slots) return false
            items[slot] = pane(items[slot].item == Items.STAINED_GLASS_PANE.red())
            return true
        }
        override fun solved() = slots.all { items[it].item == Items.STAINED_GLASS_PANE.lime() }
    }

    class Rubix : Term(Type.RUBIX) {
        override val title = "Change all to same color!"
        private val slots = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        private val cycle = listOf(Items.STAINED_GLASS_PANE.red() to "Red", Items.STAINED_GLASS_PANE.orange() to "Orange", Items.STAINED_GLASS_PANE.yellow() to "Yellow", Items.STAINED_GLASS_PANE.green() to "Green", Items.STAINED_GLASS_PANE.blue() to "Blue")
        private val colour = IntArray(45)
        init {
            val want = beyond(lucky(10, { n: Int -> n }) { weighted(listOf(4 to 3, 5 to 4, 6 to 4, 7 to 6, 8 to 7, 9 to 7, 10 to 2)) }, 10)
            if (want >= 4) do { slots.forEach { colour[it] = Random.nextInt(5) } } while (solved() || minClicks() != want)
            else do {
                val t = Random.nextInt(5)
                slots.forEach { colour[it] = t }
                var left = want
                for (s in slots.shuffled()) {
                    if (left == 0) break
                    val d = if (left >= 2 && Random.nextBoolean()) 2 else 1
                    colour[s] = (t + if (Random.nextBoolean()) d else 5 - d) % 5
                    left -= d
                }
            } while (solved() || minClicks() != want)
            slots.forEach { set(it) }
        }
        private fun set(slot: Int) { val (i, n) = cycle[colour[slot]]; items[slot] = named(i, n, color = ChatFormatting.GREEN) }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            if (slot !in slots) return false
            colour[slot] = (colour[slot] + if (button == 1 && input == ContainerInput.PICKUP) 4 else 1) % 5
            set(slot)
            return true
        }
        override fun solved() = slots.all { colour[it] == colour[slots[0]] }
        fun minClicks() = (0 until 5).minOf { t -> slots.sumOf { val f = (t - colour[it] + 5) % 5; minOf(f, 5 - f) } }
    }

    class Starts : Term(Type.STARTS) {
        private val slots = (10..16) + (19..25) + (28..34)
        private val letter: Char
        override val title: String
        init {
            letter = STARTS_POOL.random().second[0]
            title = "What starts with: '$letter'?"
            val right = STARTS_POOL.filter { it.second[0] == letter }
            val wrong = STARTS_POOL.filter { it.second[0] != letter }
            val n = lucky(10, { n: Int -> n }) { weighted(listOf(2 to 1, 3 to 5, 4 to 14, 5 to 21, 6 to 32, 7 to 43, 8 to 27, 9 to 20, 10 to 16, 11 to 3, 12 to 4)) }
                .let { beyond(it, 16) }
            val picks = (List(n) { right.random() } + List(slots.size - n) { wrong.random() }).shuffled()
            slots.forEachIndexed { i, s -> val (id, name) = picks[i]; items[s] = named(item(id), name, color = ChatFormatting.GREEN) }
        }
        private fun want(s: ItemStack) = s.hoverName.string.startsWith(letter)
        private val picked = HashSet<Int>()
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val s = items[slot]
            if (slot !in slots || !want(s) || slot in picked) return false
            picked += slot
            items[slot] = s.copy().also { it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
            return true
        }
        override fun solved() = slots.all { !want(items[it]) || it in picked }
    }

    class Select : Term(Type.SELECT) {
        private val slots = (10..16) + (19..25) + (28..34) + (37..43)
        private val target = COLOURS.random()
        override val title = "Select all the ${target.title} items!"
        init {
            val families = listOf(target) + (COLOURS - target).shuffled().take(4)
            val sixes = lucky(10, { s: Set<Colour> -> if (target in s) 1 else 0 }) { families.shuffled().take(3).toSet() }
            val mine = beyond(if (target in sixes) 6 else 5, 12)
            val others = families - target
            val sizes = if (mine == (if (target in sixes) 6 else 5)) families.associateWith { if (it in sixes) 6 else 5 }
                else (others.mapIndexed { i, c -> c to (28 - mine) / 4 + if (i < (28 - mine) % 4) 1 else 0 } + (target to mine)).toMap()
            val picks = families.flatMap { c -> List(sizes.getValue(c)) { c.items.random() } }.shuffled()
            slots.forEachIndexed { i, s -> val (id, name) = picks[i]; items[s] = named(item(id), name, color = ChatFormatting.GREEN) }
        }
        private val wanted = target.items.map { item(it.first) }.toSet()
        private fun want(s: ItemStack) = s.item in wanted
        private val picked = HashSet<Int>()
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val s = items[slot]
            if (slot !in slots || !want(s) || slot in picked) return false
            picked += slot
            items[slot] = s.copy().also { it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
            return true
        }
        override fun solved() = slots.all { !want(items[it]) || it in picked }
    }

    class Melody : Term(Type.MELODY) {
        override val title = "Click the button on time!"
        private var row = 0
        private var lime = 1
        private var dir = 1
        private var target = if (P3Sim.firstClickMelody) 1 else lucky(8, { c: Int -> stepsTo(c) }) { Random.nextInt(1, 6) }
        private var locked = false
        private var lastStep = -1
        private var frozen = 0
        init { draw() }
        private fun draw() {
            for (i in items.indices) items[i] = FILLER
            val bottom = (LANES + 1) * 9
            for (c in 1..5) { items[c] = named(Items.STAINED_GLASS_PANE.black(), ""); items[bottom + c] = named(Items.STAINED_GLASS_PANE.black(), "") }
            items[target] = named(Items.STAINED_GLASS_PANE.magenta(), "")
            items[bottom + target] = named(Items.STAINED_GLASS_PANE.magenta(), "")
            for (r in 0 until LANES) {
                for (c in 1..5) {
                    val slot = (r + 1) * 9 + c
                    items[slot] = when {
                        r != row -> named(Items.STAINED_GLASS_PANE.white(), "")
                        c == lime -> named(Items.STAINED_GLASS_PANE.lime(), "")
                        else -> named(Items.STAINED_GLASS_PANE.red(), "")
                    }
                }
                items[(r + 1) * 9 + 7] = if (r == row) button(named(Items.DYED_TERRACOTTA.lime(), "Lock In Slot", color = ChatFormatting.GREEN)) else button(named(Items.DYED_TERRACOTTA.red(), "Row Not Active", color = ChatFormatting.RED))
            }
        }
        private fun button(s: ItemStack) = s.also {
            it.set(DataComponents.LORE, ItemLore(listOf("Click this button when the Green", "lines up with the Purple!")
                .map { l -> Component.literal(l).withStyle { st -> st.withItalic(false).withColor(ChatFormatting.GRAY) } as Component }))
        }
        override fun tick(t: Int) {
            if (t <= 0) lastStep = -1
            if (t <= 0 || t % 10 != 0 || t == lastStep) return
            lastStep = t
            if (frozen > 0) frozen--
            else {
                if (lime + dir !in 1..5) dir = -dir
                lime += dir
            }
            if (locked) { val last = target; locked = false; row++; target = lucky(8, { c: Int -> stepsTo(c) }) { ((1..5) - last).random() } }
            draw()
        }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val lock = slot % 9 == 7 && slot / 9 in 1..LANES
            if (!lock) return false
            if (slot != (row + 1) * 9 + 7 || locked || lime != target) {
                if (!(locked && slot == (row + 1) * 9 + 7)) frozen = 2
                return false
            }
            if (row == LANES - 1) { row = LANES; return true }
            locked = true
            return true
        }
        override fun solved() = row >= LANES

        private fun stepsTo(c: Int): Int {
            var l = lime; var d = dir; var k = 0
            while (l != c && k < 10) { if (l + d !in 1..5) d = -d; l += d; k++ }
            return k
        }

        companion object {
            const val LANES = 3
        }
    }

    class TerminalMenu(id: Int, inv: Inventory, val term: Term, val station: Station) :
        ChestMenu(menuType(term.type.rows), id, inv, SimpleContainer(term.size), term.type.rows) {
        private val player = inv.player as ServerPlayer
        private val opened = Fight.serverTick
        private var answeredAt = -1
        private val counted = ArrayDeque<Long>()
        private var sync: ContainerSynchronizer? = null
        private var refilled = false

        init {
            Terminals.open += this
            for (i in 0 until term.size) container.setItem(i, term.items[i].copy())
        }

        override fun setSynchronizer(s: ContainerSynchronizer) {
            sync = s
            super.setSynchronizer(object : ContainerSynchronizer by s {
                override fun sendInitialData(menu: AbstractContainerMenu, items: List<ItemStack>, carried: ItemStack, data: IntArray) {
                    items.forEachIndexed { i, it -> if (i < term.size || !it.isEmpty) s.sendSlotChange(menu, i, it) }
                }
            })
        }

        fun tick() = Sim.asPlayer(player) {
            if (player.containerMenu !== this) { open -= this; return@asPlayer }
            val age = Fight.serverTick - opened
            term.tick(age)
            if (Fight.serverTick > answeredAt) { copyIn(); broadcastChanges() }
            if (!refilled && age >= 5) {
                refilled = true
                sync?.sendInitialData(this, slots.map { it.item }, carried, IntArray(0))
                player.connection.send(ClientboundSetCursorItemPacket(ItemStack.EMPTY))
            }
        }

        private fun copyIn() {
            for (i in 0 until term.size) {
                if (!ItemStack.matches(container.getItem(i), term.items[i])) container.setItem(i, term.items[i].copy())
            }
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (term.done || slot !in 0 until term.size) return
            Fight.afterPing("terminal click") {
                if (player.containerMenu !== this || term.done) return@afterPing
                val now = Fight.serverTick
                if (P3Sim.clickLimit) {
                    val at = System.nanoTime()
                    while (counted.isNotEmpty() && counted.first() <= at - 500_000_000L) counted.removeFirst()
                    if (counted.size >= 5) return@afterPing
                    counted.addLast(at)
                }
                if (!term.click(slot, button, input)) return@afterPing
                Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = SoundSource.BLOCKS)
                answeredAt = now
                if (term.solved()) {
                    term.done = true
                    player.closeContainer()
                    station.complete(Sim.me)
                    player.connection.send(ClientboundContainerClosePacket(0))
                }
            }
        }

        override fun broadcastFullState() = broadcastChanges()

        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = !term.done
        override fun removed(p: Player) { super.removed(p); open -= this }
    }

    private fun menuType(rows: Int): MenuType<ChestMenu> = when (rows) {
        4 -> MenuType.GENERIC_9x4
        5 -> MenuType.GENERIC_9x5
        else -> MenuType.GENERIC_9x6
    }

    private val open = ArrayList<TerminalMenu>()

    fun tick() { open.toList().forEach { it.tick() } }

    fun closeAll() {
        Sim.players().forEach { if (it.containerMenu is TerminalMenu) it.closeContainer() }
        open.clear()
    }

    fun closeFor(station: Station, except: ServerPlayer? = null) {
        for (p in Sim.players()) if (p !== except && (p.containerMenu as? TerminalMenu)?.station === station) p.closeContainer()
    }

    fun inUse(station: Station) = open.any { it.station === station }

    class Colour(val title: String, val items: List<Pair<String, String>>)

    private fun colour(id: String, title: String, name: String, dye: Pair<String, String>, wool: String = "$name Wool") =
        Colour(title, listOf("${id}_stained_glass" to "$name Stained Glass", "${id}_terracotta" to "$name Stained Clay", "${id}_wool" to wool, dye))

    val COLOURS = listOf(
        colour("white", "WHITE", "White", "bone_meal" to "Bone Meal", wool = "Wool"),
        colour("orange", "ORANGE", "Orange", "orange_dye" to "Orange Dye"),
        colour("magenta", "MAGENTA", "Magenta", "magenta_dye" to "Magenta Dye"),
        colour("light_blue", "LIGHT BLUE", "Light Blue", "light_blue_dye" to "Light Blue Dye"),
        colour("yellow", "YELLOW", "Yellow", "yellow_dye" to "Dandelion Yellow"),
        colour("lime", "LIME", "Lime", "lime_dye" to "Lime Dye"),
        colour("pink", "PINK", "Pink", "pink_dye" to "Pink Dye"),
        colour("gray", "GRAY", "Gray", "gray_dye" to "Gray Dye"),
        colour("light_gray", "SILVER", "Silver", "light_gray_dye" to "Light Gray Dye"),
        colour("cyan", "CYAN", "Cyan", "cyan_dye" to "Cyan Dye"),
        colour("purple", "PURPLE", "Purple", "purple_dye" to "Purple Dye"),
        colour("blue", "BLUE", "Blue", "lapis_lazuli" to "Lapis Lazuli"),
        colour("brown", "BROWN", "Brown", "cocoa_beans" to "Cocoa Bean"),
        colour("green", "GREEN", "Green", "green_dye" to "Cactus Green"),
        colour("red", "RED", "Red", "red_dye" to "Rose Red"),
        colour("black", "BLACK", "Black", "ink_sac" to "Ink Sack"),
    )

    val STARTS_POOL: List<Pair<String, String>> = """
        acacia_door=Acacia Door|apple=Apple|armor_stand=Armor Stand|arrow=Arrow|baked_potato=Baked Potato|beef=Raw Beef|birch_door=Birch Door
        blaze_powder=Blaze Powder|blaze_rod=Blaze Rod|bone=Bone|book=Book|bow=Bow|bowl=Bowl|bread=Bread|brewing_stand=Brewing Stand|brick=Brick
        bucket=Bucket|cake=Cake|carrot=Carrot|carrot_on_a_stick=Carrot on a Stick|cauldron=Cauldron|chainmail_boots=Chainmail Boots
        chainmail_chestplate=Chainmail Chestplate|chainmail_helmet=Chainmail Helmet|chainmail_leggings=Chainmail Leggings|chest_minecart=Storage Minecart
        chicken=Raw Chicken|clay_ball=Clay|clock=Watch|coal=Coal|cod=Raw Fish|command_block_minecart=Minecart with Command Block
        comparator=Redstone Comparator|compass=Compass|cooked_beef=Steak|cooked_chicken=Roast Chicken|cooked_cod=Cooked Fish|cooked_mutton=Cooked Mutton
        cooked_porkchop=Cooked Porkchop|cooked_rabbit=Cooked Rabbit|cookie=Cookie|dark_oak_door=Dark Oak Door|diamond=Diamond|diamond_axe=Diamond Axe
        diamond_boots=Diamond Boots|diamond_chestplate=Diamond Chestplate|diamond_helmet=Diamond Helmet|diamond_hoe=Diamond Hoe|diamond_leggings=Diamond Leggings
        diamond_pickaxe=Diamond Pickaxe|diamond_sword=Diamond Sword|diamond_horse_armor=Diamond Horse Armor|egg=Egg|emerald=Emerald|enchanted_book=Enchanted Book
        ender_eye=Eye of Ender|ender_pearl=Ender Pearl|experience_bottle=Bottle o' Enchanting|feather=Feather|fermented_spider_eye=Fermented Spider Eye
        filled_map=Map|fire_charge=Fire Charge|firework_rocket=Firework Rocket|firework_star=Firework Star|fishing_rod=Fishing Rod|flint=Flint
        flint_and_steel=Flint and Steel|flower_pot=Flower Pot|furnace_minecart=Powered Minecart|ghast_tear=Ghast Tear|glass_bottle=Glass Bottle
        glistering_melon_slice=Glistering Melon|glowstone_dust=Glowstone Dust|gold_ingot=Gold Ingot|gold_nugget=Gold Nugget|golden_apple=Golden Apple
        golden_carrot=Golden Carrot|golden_axe=Gold Axe|golden_boots=Gold Boots|golden_chestplate=Gold Chestplate|golden_helmet=Gold Helmet|golden_hoe=Gold Hoe
        golden_leggings=Gold Leggings|golden_pickaxe=Gold Pickaxe|golden_shovel=Gold Shovel|golden_sword=Gold Sword|golden_horse_armor=Gold Horse Armor
        gunpowder=Gunpowder|hopper_minecart=Minecart with Hopper|ink_sac=Ink Sac|iron_axe=Iron Axe|iron_boots=Iron Boots|iron_chestplate=Iron Chestplate
        iron_helmet=Iron Helmet|iron_hoe=Iron Hoe|iron_leggings=Iron Leggings|iron_pickaxe=Iron Pickaxe|iron_shovel=Iron Shovel|iron_sword=Iron Sword
        iron_door=Iron Door|iron_horse_armor=Iron Horse Armor|iron_ingot=Iron Ingot|item_frame=Item Frame|jungle_door=Jungle Door|lava_bucket=Lava Bucket
        lead=Lead|leather=Leather|leather_boots=Leather Boots|leather_chestplate=Leather Chestplate|leather_helmet=Leather Helmet|leather_leggings=Leather Leggings
        magma_cream=Magma Cream|map=Empty Map|melon_seeds=Melon Seeds|melon_slice=Melon|milk_bucket=Milk Bucket|minecart=Minecart|mushroom_stew=Mushroom Stew
        mutton=Raw Mutton|name_tag=Name Tag|nether_brick=Nether Brick|nether_star=Nether Star|nether_wart=Nether Wart|oak_boat=Boat|oak_door=Wooden Door
        oak_sign=Oak Sign|painting=Painting|paper=Paper|poisonous_potato=Poisonous Potato|polar_bear_spawn_egg=Spawn Egg|porkchop=Raw Porkchop|potato=Potato
        potion=Water Bottle|prismarine_crystals=Prismarine Crystals|prismarine_shard=Prismarine Shard|pumpkin_pie=Pumpkin Pie|pumpkin_seeds=Pumpkin Seeds
        quartz=Nether Quartz|rabbit=Raw Rabbit|rabbit_foot=Rabbit Foot|rabbit_hide=Rabbit Hide|rabbit_stew=Rabbit Stew|red_bed=Bed|redstone=Redstone
        repeater=Redstone Repeater|rotten_flesh=Rotten Flesh|saddle=Saddle|shears=Shears|skeleton_skull=Skull Item|slime_ball=Slime Ball|snowball=Snowball
        spider_eye=Spider Eye|spruce_door=Spruce Door|stick=Stick|stone_axe=Stone Axe|stone_hoe=Stone Hoe|stone_pickaxe=Stone Pickaxe|stone_shovel=Stone Shovel
        stone_sword=Stone Sword|string=String|sugar=Sugar|sugar_cane=Sugar Cane|tnt_minecart=Minecart with TNT|water_bucket=Water Bucket|wheat=Wheat
        wheat_seeds=Seeds|wooden_axe=Wooden Axe|wooden_hoe=Wooden Hoe|wooden_pickaxe=Wooden Pickaxe|wooden_shovel=Wooden Shovel|wooden_sword=Wooden Sword
        writable_book=Book and Quill|written_book=Written Book
    """.trimIndent().split('|', '\n').map { it.trim() }.filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
}
