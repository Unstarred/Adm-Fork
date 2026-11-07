package com.engineerclient.features.brwaypoints

import com.engineerclient.EngineerClient
import com.engineerclient.features.splits.DoorBlocks
import com.engineerclient.util.noFormatting
import com.engineerclient.util.setting
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.Highlight
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.alert
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.itemUUID
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

object BrWaypoints2 : Module(
    name = "BR Roles",
    category = Category.custom("ADM", 860, 10),
    description = "Boxes that group a room's starred mobs, shown while any of them is alive. Made on undonecoffee.com/brroles.",
    key = null,
) {

    interface WandUser {
        fun tick()
        fun onDrop(): Boolean
        fun onMove(by: Int): Boolean
        fun blocksContinueAttack(): Boolean
        fun onUse(): Boolean
    }

    @Volatile var wandUser: WandUser? = null

    fun makeWand() {
        val held = mc.player?.mainHandItem
        if (held == null || held.isEmpty) return EngineerClient.msg("§cHold the item you want as the wand first.")
        wand = identity(held)
        EngineerClient.msg("§aWand set: §f${held.hoverName.string}")
    }

    private enum class Killers { DUO, TRIO, QUAD }
    private enum class MyRole { ALL_BOXES, DOOR, ROLE_1, ROLE_2, ROLE_3, ROLE_4 }

    private val myRole by SelectorSetting("My Role", MyRole.ROLE_2, desc = "Your blood rush role from undonecoffee.com/brroles: only your boxes show, numbered in kill order, the next one filled in; your stack once yours are dead. Door shows none. All Boxes (or a role past the number of killers) turns roles off. In master mode the site's M7 roles are used and your class picks the role (Archer 1, Mage 2, Berserk 3, Tank 4, Healer the door). Party chat (!br 2, !br d) overrides it for a run.")

    private val killers by SelectorSetting("Killers", Killers.DUO, desc = "How many kill on blood rush, not counting the door runner. Party chat (!3br 2) overrides it for a run.")

    private val doorKills by SelectorSetting("Door Kills", BrRoles.DoorKills.NEVER, desc = "When the door runner kills too, with undonecoffee.com/brroles's Doorer kills roles: one more role than Killers (the door runner's, the last), and each room's plan goes by its wither door as well as the door the rush came in by. Never, Always, or After Fairy: only in the rooms the rush reaches after fairy.")

    private val dropBottom by BooleanSetting("Drop Bottom After Fairy", false, desc = "Once the rush reaches fairy, the killer with the highest role number gets a Clear! alert and leaves the rush to clear. The rest go on with one killer fewer and the door runner killing: quad up to fairy, then trio with the door.").withDependency { doorKills == BrRoles.DoorKills.AFTER_FAIRY }

    private val recolorDone by BooleanSetting("Recolor Done Boxes", false, desc = "A done box stays up as a normal box in Done Color, its number greyed, instead of disappearing.")
    private val doneColor by ColorSetting("Done Color", Color(121, 255, 121, 166 / 255f), true, desc = "Colour of a done box with Recolor Done Boxes on. Its alpha fades the outline; Fill Opacity still sets the faces.").withDependency { recolorDone }

    private val opacity by NumberSetting("Fill Opacity", 0.03f, 0.0..1.0, 0.01f, desc = "How solid the boxes' faces are. The next of yours to kill is filled in more.")

    private val spawnMarkers by BooleanSetting("Starred Mobs Spawn", false, desc = "Marks where each starred mob was first seen, flat on the floor in Odin's Highlight colour.")

    private val allRooms by BooleanSetting("All Rooms", false, desc = "Shows boxes in every room. Off, only in rooms the blood rush went through.")

    private val debug by BooleanSetting("Debug", false, desc = "Says in chat, for each room the rush comes into: the door it came in by, your role, and the boxes it shows you.")

    private var wand by StringSetting("Wand", "", 256, desc = "The wand's identity.", placeholder = "").hide()

    class Box(val c: IntArray, val room: String?) {
        var saved = false

        fun aabb() = AABB(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
    }

    private val boxes = mutableListOf<Box>()

    private val savedFile = lazy { read() }
    private val saved: MutableMap<String, MutableList<IntArray>> by savedFile
    private val loadedRooms = HashMap<String, String>()

    private class Mob(val spawn: AABB, val x: Double, val y: Double, val z: Double, val room: String?, var entity: Entity?, var tag: Entity?) {
        var dead = false
        var tagGoneAt: Int? = null
        var claim: Box? = null
        var claimSig = 0L
        var claimed = false
    }

    private val mobs = mutableListOf<Mob>()
    private val byId = HashMap<Int, Mob>()
    private var ticks = 0

    private val MOB_NAMES = listOf("Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider", "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer")
    private val STARRED = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    private val PURPLE = Color(170, 0, 170, 1f)
    private val GOLD = Color(255, 170, 0, 1f)
    private val GREEN = Color(85, 255, 85, 1f)

    private const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
    private val WITHER_DOOR = Regex("""^\w+ opened a WITHER door!$""")

    private const val CLAIM_REACH = 5.0

    private const val DIES_WITH_TAG = 48.0
    private const val DIES_ALONE = 32.0

    init {
        on<LevelEvent.Load> {
            pull()
            BrRoles.pull()
            BrRoles.newRun(dungeonOver = wasInDungeon)
            wasInDungeon = false
            drawnThisTick = null
            entryDoors.clear(); noPlanSaid.clear(); walkedIn.clear(); myTile = null
            boxes.clear(); loadedRooms.clear(); mobs.clear(); byId.clear()
            rushing = false; rushRoom = null; rushed.clear(); startDoor = false; barriers.clear(); witherSides.clear(); path = emptyMap(); exits = emptyMap(); entrances = emptyMap(); lastRoom = null
            beforeFairy = null; witherSeen = false
        }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.noFormatting()
            mc.execute { if (!BrRoles.onChat(text)) onChat(text) }
        }

        on<BlockUpdateEvent> {
            if (rushing && updated.block == Blocks.BARRIER && old.block != Blocks.BARRIER) barriers += pos.x to pos.z
        }

        on<TickEvent.End> { onTickEnd() }

        on<RenderExtractEvent> { onRenderExtract() }
    }

    init {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("brrole")
                .executes { BrRoles.status(); 1 }
                .then(literal("door").executes { BrRoles.claimDoor(); 1 })
                .then(argument("killing", IntegerArgumentType.integer(2, BrRoles.MAX_KILLING)).then(argument("role", IntegerArgumentType.integer(1, BrRoles.MAX_KILLING)).executes { ctx ->
                    BrRoles.claim(IntegerArgumentType.getInteger(ctx, "killing"), IntegerArgumentType.getInteger(ctx, "role"))
                    1
                })))
        }
    }

    private fun TickEvent.End.onTickEnd() {
        drawnThisTick = null
        BrRoles.settingKilling = killers.ordinal + 2
        BrRoles.settingRole = when (myRole.ordinal) { 0 -> null; 1 -> 0; else -> myRole.ordinal - 1 }
        BrRoles.doorKills = doorKills
        BrRoles.dropBottom = dropBottom
        wandUser?.tick()
        if (!DungeonUtils.inDungeons) return
        wasInDungeon = true
        ticks++
        if (barriers.size >= DoorBlocks.DOOR_BLOCKS) for (d in DoorBlocks.doors(barriers)) doorFalling(tileRoom(d.a), tileRoom(d.b), d)
        barriers.clear()
        if (ticks % 10 == 0) mapPath()
        trackMyDoor()
        if (rushing && beforeFairy == null) DungeonUtils.currentRoom?.takeIf { it.type == RoomType.FAIRY && onRush(it.name) }?.let { reachFairy() }
        if (debug && (rushing || allRooms)) DungeonUtils.currentRoom?.name?.let { if (it != lastRoom) { lastRoom = it; debugRoom(it) } }
        if (debug) traceRoom()
        loadRooms()
        if (DungeonUtils.inClear) findStarred()
        watchDeaths()
    }

    private fun RenderExtractEvent.onRenderExtract() {
        if (!DungeonUtils.inDungeons) return

        val drawn = drawnThisTick ?: drawn().also { drawnThisTick = it }
        for (d in drawn) {
            val bb = d.bb
            if (d.done && recolorDone) {
                drawFilledBox(bb, doneColor.withAlpha(opacity), depth = false)
                drawWireFrameBox(bb, doneColor, depth = false)
                drawText(d.doneLabel, d.labelAt, 1.5f, false)
                continue
            }
            drawFilledBox(bb, d.colour.withAlpha(if (d.next) (opacity * 3).coerceIn(opacity + 0.15f, 1f) else opacity), depth = false)
            drawWireFrameBox(bb, d.colour, depth = false)
            drawText(d.label, d.labelAt, 1.5f, false)
        }

        if (spawnMarkers) {
            val colour = Highlight.setting<ColorSetting>("Highlight color")?.value ?: Colors.WHITE
            val style = Highlight.setting<SelectorSetting<*>>("Render Style")?.value as? BoxStyle ?: BoxStyle.OUTLINE
            for (mob in mobs) {
                val y = mob.y + 0.02
                drawStyledBox(AABB(mob.x - 0.5, y, mob.z - 0.5, mob.x + 0.5, y, mob.z + 0.5), colour, style, true)
            }
        }
    }

    private fun Color.withAlpha(a: Float) = Color(red, green, blue, a)

    private fun findStarred() {
        val level = mc.level ?: return
        for (tag in level.entitiesForRendering()) {
            if (tag !is ArmorStand || !tag.isAlive) continue
            val name = tag.name.string
            if (MOB_NAMES.none { it in name } || !STARRED.matches(name)) continue
            val mob = level.getEntities(tag, tag.boundingBox.move(0.0, -1.0, 0.0)) { isMob(it) }.firstOrNull() ?: continue
            val known = byId[mob.id]
            if (known != null) {
                if (known.entity == null && !known.dead) known.entity = mob
                if (known.tag !== tag) { known.tag = tag; known.tagGoneAt = null }
                continue
            }
            val m = Mob(mob.boundingBox, mob.x, mob.y, mob.z, roomAt(mob.x, mob.z)?.name, mob, tag)
            mobs += m
            byId[mob.id] = m
        }
    }

    private fun watchDeaths() {
        val player = mc.player ?: return
        for (mob in mobs) {
            val tag = mob.tag
            if (tag != null && tag.isRemoved && mob.tagGoneAt == null) mob.tagGoneAt = ticks
        }
        for (mob in mobs) {
            val e = mob.entity ?: continue
            if (!e.isRemoved) continue
            val d = hypot(player.x - e.x, player.z - e.z)
            val withTag = mob.tagGoneAt?.let { ticks - it <= 1 } == true
            if (d < DIES_WITH_TAG && (withTag || d < DIES_ALONE)) mob.dead = true
            mob.entity = null
            mob.tag = null
        }
    }

    private fun shown(): List<Box> {
        val rooms = shownRooms()
        val sig = boxesSig()
        val claimed = HashSet<Box>(); val alive = HashSet<Box>()
        for (mob in mobs) {
            val box = cachedClaim(mob, sig) ?: continue
            claimed += box
            if (!mob.dead) alive += box
        }
        val open = rooms.filter { it.checkmark != MapCheckmark.WHITE && it.checkmark != MapCheckmark.GREEN }.mapTo(HashSet()) { it.name }
        return boxes.filter { it.room in open && (it !in claimed || it in alive) }
    }

    private fun shownRooms() = listOfNotNull(DungeonUtils.currentRoom, rushRoom?.takeIf { rushing }?.let { n -> DungeonScan.rooms.firstOrNull { it.name == n } })
        .distinctBy { it.name }
        .filter { allRooms || onRush(it.name) }

    private var rushing = false
    private var rushRoom: String? = null
    private val rushed = HashSet<String>()
    private var startDoor = false
    private val barriers = mutableListOf<Pair<Int, Int>>()
    private var witherSeen = false
    private var beforeFairy: Set<String>? = null

    private fun late(name: String?): Boolean = beforeFairy.let { it != null && name !in it }

    private fun reachFairy() {
        beforeFairy = HashSet(rushed)
        if (debug) EngineerClient.msg("§dBR debug §7the rush reached fairy §8(after ${rushed.size} rooms)")
        if (BrRoles.youDropped(true)) {
            alert("Clear!")
            EngineerClient.msg("§dBR §7past fairy: you leave the rush §8— §fClear!")
        }
    }

    private fun onChat(msg: String) {
        if (!DungeonUtils.inDungeons) return
        when {
            msg == MORT -> { rushing = true; rushRoom = null; rushed.clear(); startDoor = true; beforeFairy = null; witherSeen = false }
            rushing && WITHER_DOOR.matches(msg) -> {
                startDoor = false
                witherSeen = true
                if (beforeFairy == null && DungeonScan.rooms.any { it.name == rushRoom && it.type == RoomType.FAIRY }) reachFairy()
            }
            msg == BLOOD_DOOR -> { rushing = false; rushRoom = null }
        }
    }

    private fun doorFalling(a: DungeonRoom?, b: DungeonRoom?, door: DoorBlocks.Door) {
        val sides = listOfNotNull(a, b)
        if (startDoor) {
            val entrance = sides.firstOrNull { it.type == RoomType.ENTRANCE } ?: return
            rushed += entrance.name ?: return
            startDoor = false
        }
        if (witherSeen && beforeFairy == null && sides.any { it.type == RoomType.FAIRY }) reachFairy()
        val next = sides.filter { it.name != null && it.name !in rushed }.minByOrNull { if (it.type == RoomType.FAIRY) 1 else 0 } ?: return
        rushRoom = next.name
        rushed += next.name!!
        entryDoors[next.name!!] = (-185 + 16 * (door.a.first + door.b.first)) to (-185 + 16 * (door.a.second + door.b.second))
    }

    private val witherSides = HashSet<DungeonRoom>()

    private var path: Map<String, Pair<Int, Int>?> = emptyMap()

    private var exits: Map<String, Pair<Int, Int>> = emptyMap()

    private var entrances: Map<String, Pair<Int, Int>> = emptyMap()

    private var lastRoom: String? = null

    private fun onRush(name: String?) = name != null && (name in path || name in rushed)

    private fun mapPath() {
        val links = HashMap<DungeonRoom, MutableList<Pair<DungeonRoom, Pair<Int, Int>>>>()
        worldDoors(links)
        for (door in DungeonScan.doors.values) {
            val a = DungeonScan.tiles.getOrNull(door.originTileIndex)?.room ?: continue
            val b = DungeonScan.tiles.getOrNull(door.destinationTileIndex)?.room ?: continue
            if (a === b) continue
            if (door.type == DoorType.Wither || door.type == DoorType.Blood) { witherSides += a; witherSides += b }
            val at = (-185 + 16 * (2 * door.position.x + door.rotation.offset.x)) to (-185 + 16 * (2 * door.position.z + door.rotation.offset.z))
            links.getOrPut(a) { mutableListOf() } += b to at
            links.getOrPut(b) { mutableListOf() } += a to at
        }
        val entrance = DungeonScan.rooms.firstOrNull { it.type == RoomType.ENTRANCE } ?: return
        val back = HashMap<DungeonRoom, Pair<DungeonRoom, Pair<Int, Int>>>()
        val queue = ArrayDeque(listOf(entrance))
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for ((next, at) in links[r].orEmpty()) if (next !== entrance && next !in back) { back[next] = r to at; queue += next }
        }
        val out = HashMap<String, Pair<Int, Int>?>()
        val outOf = HashMap<String, Pair<Int, Int>>()
        entrance.name?.let { out[it] = null }
        val ends = witherSides + DungeonScan.rooms.filter { it.type == RoomType.BLOOD || it.name in rushed }
        for (end in ends) {
            var r = end
            while (r !== entrance) {
                val (prev, at) = back[r] ?: break
                r.name?.let { out[it] = at }
                prev.name?.let { outOf[it] = at }
                r = prev
            }
        }
        path = out
        exits = outOf
        entrances = back.entries.mapNotNull { (r, b) -> r.name?.let { it to b.second } }.toMap()
    }

    private fun worldDoors(links: HashMap<DungeonRoom, MutableList<Pair<DungeonRoom, Pair<Int, Int>>>>) {
        val level = mc.level ?: return
        for (tz in 0..5) for (tx in 0..5) for ((dx, dz) in listOf(1 to 0, 0 to 1)) {
            if (tx + dx > 5 || tz + dz > 5) continue
            val a = DungeonScan.tiles[tx + tz * 6].room ?: continue
            val b = DungeonScan.tiles[tx + dx + (tz + dz) * 6].room ?: continue
            if (a === b) continue
            val gx = -185 + 16 * (2 * tx + dx)
            val gz = -185 + 16 * (2 * tz + dz)
            val cells = listOf(-2, -1, 1, 2).flatMap { d -> listOf(70, 71).map { y -> BlockPos(gx + d * dx, y, gz + d * dz) } }
            if (!cells.all { level.isLoaded(it) }) continue
            val states = cells.map { level.getBlockState(it) }
            if (!states.all { it.isAir || it.block == Blocks.BARRIER || it.block == Blocks.COAL_BLOCK || it.block == Blocks.DYED_TERRACOTTA.red() }) continue
            val wither = states.any { it.block == Blocks.COAL_BLOCK || it.block == Blocks.DYED_TERRACOTTA.red() }
            if (wither) { witherSides += a; witherSides += b }
            links.getOrPut(a) { mutableListOf() } += b to (gx to gz)
            links.getOrPut(b) { mutableListOf() } += a to (gx to gz)
        }
    }

    private var tracedRoom: String? = null

    private fun traceRoom() {
        val cur = DungeonUtils.currentRoom
        val key = cur?.name ?: "none"
        if (key == tracedRoom) return
        tracedRoom = key
        val name = cur?.name
        EngineerClient.logger.info(
            "[ec] br trace: room=$name checkmark=${cur?.checkmark} rushing=$rushing rushRoom=$rushRoom onRush=${onRush(name)} " +
                "placed=${name?.let { placed(it) != null }} scanRooms=${DungeonScan.rooms.size} path=${path.size} boxes=${boxes.size} " +
                "inRoom=${boxes.count { it.room == name }} shownRooms=${shownRooms().map { it.name }} shown=${shown().size} drawn=${drawn().size} " +
                "roles=${BrRoles.describe(late(name))} active=${BrRoles.active} lateRoom=${late(name)}"
        )
    }

    private fun debugRoom(name: String) {
        val door = entryOf(name) ?: return EngineerClient.msg("§dBR debug §f$name §8— " + if (onRush(name)) "on the rush, door not known yet" else "not on the rush path")
        val how = when {
            walkedIn[name] != null -> "you walked in"
            path[name] != null -> "rush path"
            entryDoors[name] != null -> "wither door seen falling"
            else -> "off the rush, Entrance side"
        }
        val room = placed(name)
        val rel = room?.getRelativeCoords(BlockPos(door.first, 0, door.second))
        val at = "§f$name §7by door §f${door.first}, ${door.second}" + (rel?.let { " §8(room ${it.x}, ${it.z})" } ?: " §8(room not placed yet)") + " §8· $how"
        val late = late(name)
        val role = "§7you: §f" + BrRoles.describe(late) + if (late) " §8(past fairy)" else ""
        val inRoom = boxes.filter { it.room == name }
        val wither = room?.let { witherOf(name, it) }
        val plan = if (!BrRoles.active || rel == null) null else BrRoles.planFor(name, rel.x to rel.z, wither, late)
        val shows = when {
            inRoom.isEmpty() -> "§8no boxes in this room"
            !BrRoles.active -> "§7showing §fall ${inRoom.size}"
            BrRoles.youDropped(late) -> "§7you left the rush to clear §8— showing §fall ${inRoom.size}"
            plan == null -> "§7no plan" + (if (BrRoles.doorerKills(late) && wither == null) " §8(wither door not known)" else "") + " §8— showing §fall ${inRoom.size}"
            BrRoles.playing(late) == null -> "§7on the door §8— showing §fnone"
            else -> {
                val looks = inRoom.map { number(it) to BrRoles.look(plan, number(it), late) }
                val mine = looks.mapNotNull { (n, l) -> (l as? BrRoles.Look.Mine)?.let { n to it.order } }.sortedBy { it.second }.map { it.first }
                val stack = looks.filter { it.second is BrRoles.Look.Stack }.map { it.first }
                val loose = looks.filter { it.second == null }.map { it.first }
                "§7kill §a" + mine.joinToString(" → ").ifEmpty { "none" } + " §7stack §6" + stack.joinToString().ifEmpty { "none" } +
                    (if (loose.isEmpty()) "" else " §7unassigned §d" + loose.joinToString())
            }
        }
        EngineerClient.msg("§dBR debug §7$at\n  $role\n  $shows")
    }

    private val entryDoors = HashMap<String, Pair<Int, Int>>()

    private fun entryOf(name: String) = walkedIn[name] ?: path[name] ?: entryDoors[name] ?: entrances[name]

    private val walkedIn = HashMap<String, Pair<Int, Int>>()
    private var myTile: Pair<Int, Int>? = null

    private fun trackMyDoor() {
        val p = mc.player ?: return
        val t = Math.floorDiv(p.blockX + 201, 32) to Math.floorDiv(p.blockZ + 201, 32)
        val was = myTile
        if (was == t) return
        val room = tileRoom(t)?.name ?: return
        myTile = t
        val from = was?.let { tileRoom(it)?.name } ?: return
        if (from == room || abs(was.first - t.first) + abs(was.second - t.second) != 1) return
        walkedIn[room] = (-185 + 16 * (was.first + t.first)) to (-185 + 16 * (was.second + t.second))
    }

    private data class Drawn(val box: Box, val colour: Color, val label: String, val next: Boolean, val done: Boolean = false) {
        val bb: AABB = box.aabb()
        val labelAt = Vec3((bb.minX + bb.maxX) / 2, bb.maxY + 0.6, (bb.minZ + bb.maxZ) / 2)
        val doneLabel = "§7" + label.noFormatting()
    }

    private var drawnThisTick: List<Drawn>? = null

    private val noPlanSaid = HashSet<String>()
    private var wasInDungeon = false

    private fun drawn(): List<Drawn> {
        val out = ArrayList<Drawn>()
        val live = shown()
        val all = if (recolorDone) shownRooms().mapTo(HashSet()) { it.name }.let { names -> boxes.filter { it.room in names } } else live
        val liveSet = live.toHashSet()
        for ((room, list) in all.groupBy { it.room }) {
            val late = late(room)
            val plan = if (!BrRoles.active || BrRoles.youDropped(late)) null else planOf(room)
            if (plan == null) {
                list.mapTo(out) { Drawn(it, PURPLE, "§d" + number(it), false, it !in liveSet) }
                continue
            }
            if (BrRoles.playing(late) == null) continue
            val looks = list.map { it to BrRoles.look(plan, number(it), late) }
            val mine = looks.mapNotNull { (b, l) -> (l as? BrRoles.Look.Mine)?.let { b to it.order } }.sortedBy { it.second }
            val next = mine.firstOrNull { it.first in liveSet }?.first
            for ((b, order) in mine) out += Drawn(b, GREEN, "§a$order", b === next, b !in liveSet)
            for ((b, l) in looks) if (l is BrRoles.Look.Stack) out += Drawn(b, GOLD, "§6stack", false, b !in liveSet)
            for ((b, l) in looks) if (l == null) out += Drawn(b, PURPLE, "§d" + number(b), false, b !in liveSet)
        }
        return out
    }

    private fun planOf(name: String?): BrRoles.Plan? {
        name ?: return null
        val late = late(name)
        if (BrRoles.isMini(name)) {
            val all = boxes.filter { it.room == name }.map { number(it) }
            val n = maxOf(1, BrRoles.planCount(late))
            return BrRoles.Plan(List(n) { all }, List(n) { emptyList() })
        }
        val door = entryOf(name) ?: return null
        val room = placed(name) ?: return null
        val rel = room.getRelativeCoords(BlockPos(door.first, 0, door.second))
        val plan = BrRoles.planFor(name, rel.x to rel.z, witherOf(name, room), late)
        if (plan == null && debug && noPlanSaid.add("$name $door $late")) EngineerClient.msg("§dBR §7no roles for §f$name §7from this door with §f${BrRoles.planCount(late)} §7on the plan yet §8— every box shows")
        return plan
    }

    private fun witherOf(name: String, room: DungeonRoom): Pair<Int, Int>? =
        exits[name]?.let { room.getRelativeCoords(BlockPos(it.first, 0, it.second)) }?.let { it.x to it.z }

    private fun tileRoom(t: Pair<Int, Int>): DungeonRoom? =
        if (t.first !in 0..5 || t.second !in 0..5) null else DungeonScan.tiles[t.first + t.second * 6].room

    private fun boxesSig(): Long {
        var h = boxes.size.toLong()
        for (b in boxes) h = h * 31 + System.identityHashCode(b) * 17L + b.c.contentHashCode()
        return h
    }

    private fun cachedClaim(mob: Mob, sig: Long): Box? {
        if (!mob.claimed || mob.claimSig != sig) { mob.claim = claimOf(mob); mob.claimSig = sig; mob.claimed = true }
        return mob.claim
    }

    private fun claimOf(mob: Mob): Box? {
        var best: Box? = null
        var most = 0.0
        for (box in boxes) {
            val o = overlap(mob.spawn, box.aabb())
            if (o > most) { most = o; best = box }
        }
        if (best != null || mob.room == null) return best
        return boxes.filter { it.room == mob.room && gap(mob.spawn, it.aabb()) <= CLAIM_REACH * CLAIM_REACH }
            .minByOrNull { gap(mob.spawn, it.aabb()) }
    }

    private fun gap(a: AABB, b: AABB): Double {
        val x = maxOf(0.0, maxOf(a.minX, b.minX) - minOf(a.maxX, b.maxX))
        val y = maxOf(0.0, maxOf(a.minY, b.minY) - minOf(a.maxY, b.maxY))
        val z = maxOf(0.0, maxOf(a.minZ, b.minZ) - minOf(a.maxZ, b.maxZ))
        return x * x + y * y + z * z
    }

    private fun overlap(a: AABB, b: AABB): Double {
        val x = minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX)
        val y = minOf(a.maxY, b.maxY) - maxOf(a.minY, b.minY)
        val z = minOf(a.maxZ, b.maxZ) - maxOf(a.minZ, b.minZ)
        return if (x > 0 && y > 0 && z > 0) x * y * z else 0.0
    }

    private fun isMob(e: Entity): Boolean = when (e) {
        is ArmorStand -> false
        is WitherBoss -> false
        is Enderman -> true
        is Player -> e.uuid.version() == 2 && e != mc.player
        else -> !e.isInvisible
    }

    @JvmStatic
    fun onDrop(): Boolean = wandUser?.onDrop() == true

    @JvmStatic
    fun onAttack(): Boolean = wandUser?.onMove(+1) == true

    @JvmStatic
    fun blocksContinueAttack(): Boolean = wandUser?.blocksContinueAttack() == true

    @JvmStatic
    fun onUse(): Boolean = wandUser?.onUse() == true

    @JvmStatic
    fun onScroll(y: Double): Boolean = y != 0.0 && wandUser?.onMove(if (y > 0) +1 else -1) == true

    internal fun faceSlab(bb: AABB, face: Face): AABB {
        val e = 0.02
        return when (face) {
            Face.EAST -> AABB(bb.maxX - e, bb.minY, bb.minZ, bb.maxX + e, bb.maxY, bb.maxZ)
            Face.WEST -> AABB(bb.minX - e, bb.minY, bb.minZ, bb.minX + e, bb.maxY, bb.maxZ)
            Face.SOUTH -> AABB(bb.minX, bb.minY, bb.maxZ - e, bb.maxX, bb.maxY, bb.maxZ + e)
            Face.NORTH -> AABB(bb.minX, bb.minY, bb.minZ - e, bb.maxX, bb.maxY, bb.minZ + e)
            Face.UP -> AABB(bb.minX, bb.maxY - e, bb.minZ, bb.maxX, bb.maxY + e, bb.maxZ)
        }
    }

    private fun roomAt(x: Double, z: Double): DungeonRoom? {
        val tx = Math.floorDiv(Math.floor(x).toInt() + 200, 32)
        val tz = Math.floorDiv(Math.floor(z).toInt() + 200, 32)
        return if (tx !in 0..5 || tz !in 0..5) null else DungeonScan.tiles[tx + tz * 6].room
    }

    private fun placed(name: String): DungeonRoom? = DungeonScan.rooms.firstOrNull { r ->
        r.name == name && r.rotation != null && r.clayPos != null &&
            r.tiles.size == (r.data?.shape?.tileAmount ?: r.tiles.size)
    }

    private fun transform(room: DungeonRoom) = "${room.rotation}|${room.clayPos}"

    private fun loadRooms() {
        val names = saved.keys + boxes.filter { !it.saved }.mapNotNull { it.room }
        for (name in names.toSet()) {
            val room = placed(name) ?: continue
            val t = transform(room)
            if (loadedRooms[name] == t) continue
            boxes.removeAll { it.room == name && it.saved }
            for (r in saved[name].orEmpty().sortedBy { it.getOrElse(6) { 0 } }) {
                val a = room.getRealCoords(BlockPos(r[0], r[1], r[2]))
                val b = room.getRealCoords(BlockPos(r[3], r[4], r[5]))
                boxes += Box(intArrayOf(
                    minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
                    maxOf(a.x, b.x) + 1, maxOf(a.y, b.y) + 1, maxOf(a.z, b.z) + 1,
                ), name).also { it.saved = true }
            }
            loadedRooms[name] = t
            if (boxes.any { it.room == name && !it.saved }) save(name)
        }
    }

    private fun save(name: String?): Boolean {
        if (name == null) return false
        val room = placed(name) ?: return false
        if (loadedRooms[name] != transform(room)) return false
        val mine = boxes.filter { it.room == name }
        val list = mine.mapIndexed { i, box ->
            val a = room.getRelativeCoords(BlockPos(box.c[0], box.c[1], box.c[2]))
            val b = room.getRelativeCoords(BlockPos(box.c[3] - 1, box.c[4] - 1, box.c[5] - 1))
            intArrayOf(a.x, a.y, a.z, b.x, b.y, b.z, i + 1)
        }
        mine.forEach { it.saved = true }
        if (list.isEmpty()) saved.remove(name) else saved[name] = list.toMutableList()
        write()
        return true
    }

    private val file get() = File(mc.gameDirectory, "config/adm/brwaypoints2.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun number(box: Box) = boxes.filter { it.room == box.room }.indexOf(box) + 1

    private fun read(): MutableMap<String, MutableList<IntArray>> = runCatching {
        rooms(JsonParser.parseString(file.readText())).mapValues { it.value.orEmpty().filterNotNull().toMutableList() }.toMutableMap()
    }.onFailure { if (file.exists()) EngineerClient.logger.info("[ec] brboxes: file unreadable: $it") }.getOrNull() ?: mutableMapOf()

    private fun rooms(json: JsonElement?): Map<String, List<IntArray?>?> {
        val obj = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return obj.entrySet().associate { (name, list) ->
            name to list.takeIf { it.isJsonArray }?.asJsonArray?.map { b ->
                b.takeIf { it.isJsonArray }?.asJsonArray?.let { a -> runCatching { IntArray(a.size()) { a[it].asInt } }.getOrNull() }
            }
        }
    }

    private fun write() {
        runCatching {
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(saved))
        }.onFailure { EngineerClient.msg("§cCould not save BR Roles boxes: ${it.message}") }
    }

    private var siteVersion = 0L

    private fun pull() {
        EngineerClient.logger.info("[ec] brboxes: pulling")
        BoxSync.pull { body -> mc.execute { adopt(body) } }
    }

    private fun adopt(body: String) {
        val log = EngineerClient.logger
        val site = runCatching { JsonParser.parseString(body).asJsonObject }.onFailure { log.info("[ec] brboxes: not JSON: ${it.message}") }.getOrNull() ?: return
        val at = runCatching { site["updatedAt"]?.asLong }.getOrNull() ?: 0L
        if (at != 0L && at <= siteVersion) return log.info("[ec] brboxes: already have $at")
        savedFile.value
        if (at == 0L) return log.info("[ec] brboxes: the site's copy has no date")
        val rooms = runCatching { siteBoxes(rooms(site["rooms"])) }.onFailure { log.info("[ec] brboxes: rooms unreadable: $it") }.getOrNull() ?: return
        log.info("[ec] brboxes: took the site's ${rooms.size} rooms, ${rooms.values.sumOf { it.size }} boxes")
        siteVersion = at
        saved.clear(); saved.putAll(rooms)
        runCatching { file.parentFile.mkdirs(); file.writeText(gson.toJson(saved)); file.setLastModified(at) }
        boxes.removeAll { it.saved }
        loadedRooms.clear()
    }

    private fun siteBoxes(rooms: Map<String, List<IntArray?>?>): MutableMap<String, MutableList<IntArray>> {
        val out = HashMap<String, MutableList<IntArray>>()
        var n = 0
        for ((name, list) in rooms) {
            if (n >= SITE_MAX_BOXES) break
            if (name.isEmpty() || name.length > 64 || list == null) continue
            val ok = list.filterNotNull().filter { b -> b.size == 7 && (0..2).all { Math.abs(b[it + 3].toLong() - b[it]) <= SITE_MAX_SIZE } }.take(SITE_MAX_BOXES - n)
            n += ok.size
            if (ok.isNotEmpty()) out[name] = ok.toMutableList()
        }
        return out
    }
    private const val SITE_MAX_SIZE = 64
    private const val SITE_MAX_BOXES = 5000

    internal fun wandInHand(): Boolean = wand.isNotEmpty() && identity(mc.player?.mainHandItem ?: return false) == wand

    private fun identity(stack: ItemStack): String = when {
        stack.isEmpty -> ""
        stack.itemUUID.isNotEmpty() -> "uuid:" + stack.itemUUID
        stack.itemId.isNotEmpty() -> "id:" + stack.itemId
        else -> "item:" + BuiltInRegistries.ITEM.getKey(stack.item) + "|" + stack.hoverName.string
    }
}
