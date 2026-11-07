package com.engineerclient.adr

import com.engineerclient.EngineerClient
import com.engineerclient.ads.P3Sim
import com.engineerclient.mixin.ArrowInGroundInvoker
import com.engineerclient.util.noFormatting
import com.google.gson.JsonParser
import com.mojang.authlib.properties.PropertyMap
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

class RunRecorder(
    private val dir: Path,
    private val captureGeometry: Boolean,
    private val onSaved: (Path) -> Unit = {},
) {

    private var tick = 0
    private var confirmed = false
    var abandoned = false
        private set
    private val backlog = ArrayList<String>()

    private val io = Executors.newSingleThreadExecutor { Thread(it, "engineerclient-adr-writer").apply { isDaemon = true } }
    private var writer: BufferedWriter? = null
    private var tempFile: Path? = null
    private var linesWritten = 0L

    private val startedAt = LocalDateTime.now()
    private var floorName: String? = null
    private var partyKey = ""

    private class Tracked(var x: Double, var y: Double, var z: Double, var yaw: Float, var name: String, var headYaw: Float, var flight: Int = NO_FLIGHT) {
        val ballistic get() = flight != NO_FLIGHT
        var px = 0.0; var py = 0.0; var pz = 0.0; var vx = 0.0; var vy = 0.0; var vz = 0.0
        var accel = 0.0; var inertia = 0.0
        var lx = Double.NaN; var ly = Double.NaN; var lz = Double.NaN
        fun launch(x: Double, y: Double, z: Double, v: Vec3) {
            lx = x; ly = y; lz = z
            px = r(x, 1000.0); py = r(y, 1000.0); pz = r(z, 1000.0); vx = r(v.x, 10000.0); vy = r(v.y, 10000.0); vz = r(v.z, 10000.0)
        }
        fun fly() {
            if (flight == ARROW) {
                px += vx; py += vy; pz += vz; vx *= 0.99; vy = vy * 0.99 - 0.05; vz *= 0.99
            } else {
                val len = Math.sqrt(vx * vx + vy * vy + vz * vz)
                if (len > 1e-9) { vx += vx / len * accel; vy += vy / len * accel; vz += vz / len * accel }
                vx *= inertia; vy *= inertia; vz *= inertia
                px += vx; py += vy; pz += vz
            }
        }
        fun flight() = ",\"x\":$px,\"y\":$py,\"z\":$pz,\"v\":[$vx,$vy,$vz]" + if (flight == POWERED) ",\"a\":$accel,\"i\":$inertia" else ""
        private fun r(v: Double, s: Double) = Math.round(v * s) / s
    }
    private val tracked = HashMap<Int, Tracked>()

    private val geometry = GeometryCapture(::emit)
    val hud = HudCapture(::emit)
    private val probes = Probes(::emit)
    private val palette = HashMap<BlockState, Int>()

    fun onTick(level: ClientLevel) {
        flushFrames()
        flushMouse()
        tick++
        if (!confirmed && P3Sim.inSim) {
            abandon(); return
        } else if (!confirmed) {
            if (DungeonUtils.inDungeons && !confirm()) return
            else if (tick > ABANDON_AFTER_TICKS || !LocationUtils.isCurrentArea(Island.Unknown)) { abandon(); return }
        }
        val now = System.currentTimeMillis()
        if (lastClockTick < 0 || Math.abs(now - (lastClockMs + (tick - lastClockTick) * 50L)) > 50) {
            emit("""{"k":"time","t":$tick,"ms":$now}""")
            lastClockMs = now; lastClockTick = tick
        }
        val st = serverTicks
        if (st != lastServerTicks + (tick - lastServerTickAt)) emit("""{"k":"st","t":$tick,"n":$st}""")
        lastServerTicks = st; lastServerTickAt = tick
        recordEther()
        recordFloorAndParty()
        if (tick % 10 == 0) recordRooms()
        recordPlayers(level)
        recordHotbar()
        recordSwings(level)
        if (tick % 5 == 0) recordMapPlayers()
        recordEntities(level)
        if (confirmed) recordSkulls(level)
        if (confirmed && captureGeometry) geometry.tick(level, tick)
        hud.tick(tick)
        if (confirmed) probes.tick(level, tick)
        flushPending()
    }

    private val frames = StringBuilder()
    private var lastFrameYaw = Float.NaN
    private var lastFramePitch = Float.NaN
    private var heldFrame: String? = null
    private var lastFrameNs = 0L

    fun onFrame(partialTick: Float, yaw: Float, pitch: Float) {
        val now = System.nanoTime()
        if (now - lastFrameNs < 960_000_000L / ADR.cameraFps.coerceIn(20, 160)) return
        lastFrameNs = now
        val entry = "[${f2(partialTick)},${f2(Mth.wrapDegrees(yaw))},${f2(pitch)}]"
        if (yaw == lastFrameYaw && pitch == lastFramePitch) { heldFrame = entry; return }
        heldFrame?.let { if (frames.isNotEmpty()) frames.append(','); frames.append(it) }
        heldFrame = null
        lastFrameYaw = yaw; lastFramePitch = pitch
        if (frames.isNotEmpty()) frames.append(',')
        frames.append(entry)
    }

    private fun flushFrames() {
        if (frames.isEmpty()) return
        emit("""{"k":"cam","t":$tick,"d":[$frames]}""")
        frames.setLength(0)
    }

    fun onBlockUpdate(pos: BlockPos, state: BlockState) {
        if (state.block is AbstractSkullBlock && freshSkulls.size < FRESH_SKULLS) freshSkulls.putIfAbsent(pos.immutable(), tick)
        emit("""{"k":"block","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"s":${paletteIndex(state)}}""")
    }

    @Volatile private var serverTicks = 0
    private var lastServerTicks = 0
    private var lastServerTickAt = 0
    private var lastClockMs = 0L
    private var lastClockTick = -1
    fun onServerTick() { serverTicks++ }

    val serverTickCount: Int get() = serverTicks
    val currentTick: Int get() = tick
    val isConfirmed: Boolean get() = confirmed
    val stamp: String get() = startedAt.format(STAMP)

    fun onSlotClick(slot: Int, button: Int, type: String) =
        emit("""{"k":"slotclick","t":$tick,"slot":$slot,"button":$button,"type":${str(type)}}""")

    fun onBlockUse(pos: BlockPos) = emit("""{"k":"use","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z}}""")

    fun onTeleport(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, rel: List<String>) {
        val r = if (rel.isEmpty()) "" else rel.joinToString(",", ",\"rel\":[", "]") { str(it) }
        emit("""{"k":"tp","t":$tick,"x":${n(x)},"y":${n(y)},"z":${n(z)},"yaw":${w(yaw)},"pitch":${a(pitch)}$r}""")
    }

    fun onBatSound(x: Double, y: Double, z: Double, volume: Float) =
        emit("""{"k":"batsound","t":$tick,"x":${n(x)},"y":${n(y)},"z":${n(z)},"v":${f2(volume)}}""")

    fun onPickup(itemId: Int, collectorId: Int) {
        val by = EngineerClient.mc.level?.getEntity(collectorId)?.let { if (it is Player) str(it.name.string) else null } ?: collectorId.toString()
        emit("""{"k":"pickup","t":$tick,"id":$itemId,"by":$by}""")
    }

    private var lastEther = ""
    private var lastEtherStack: ItemStack? = null
    private fun recordEther() {
        val stack = EngineerClient.mc.player?.mainHandItem
        if (stack != null && stack === lastEtherStack) return
        lastEtherStack = stack
        val tag = stack?.get(DataComponents.CUSTOM_DATA)?.copyTag()
        val entry = """"merge":${tag?.getIntOr("ethermerge", 0) ?: 0},"tuners":${tag?.getIntOr("tuned_transmission", 0) ?: 0}"""
        if (entry == lastEther) return
        lastEther = entry
        emit("""{"k":"ether","t":$tick,$entry}""")
    }

    fun onChat(message: String, colored: String? = null, n: Int? = null) {
        val c = if (colored != null && colored != message) ",\"c\":${str(colored)}" else ""
        val st = if (n != null) ",\"n\":$n" else ""
        emit("""{"k":"chat","t":$tick$st,"m":${str(message)}$c}""")
    }

    fun onChestEvent(pos: BlockPos, openCount: Int) =
        emit("""{"k":"bev","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"b":$openCount}""")

    fun onRoomEnter(name: String?) = emit("""{"k":"room","t":$tick,"name":${str(name ?: "Unknown")}}""")

    fun onGuiClose() {
        flushMouse()
        lastSlots.clear(); lastCarried = null; lastMouseX = Float.NaN
        emit("""{"k":"guiclose","t":$tick}""")
    }

    fun onContainerOpen(title: String, menu: String, w: Int, h: Int, slots: List<IntArray>) {
        lastSlots.clear(); lastCarried = null; lastMouseX = Float.NaN
        val sb = StringBuilder()
        slots.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append('[').append(p[0]).append(',').append(p[1]).append(']') }
        emit("""{"k":"gui","t":$tick,"title":${str(title)},"menu":${str(menu)},"w":$w,"h":$h,"slots":[$sb]}""")
    }

    private val lastSlots = HashMap<Int, String>()
    private var lastCarried: String? = null

    fun onContainerTick(items: List<ItemStack>, carried: ItemStack) {
        val sb = StringBuilder()
        items.forEachIndexed { i, stack ->
            val entry = slotEntry(i, stack)
            if (lastSlots.put(i, entry) == entry) return@forEachIndexed
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(entry)
        }
        if (sb.isNotEmpty()) emit("""{"k":"slots","t":$tick,"s":[$sb]}""")
        val c = """"id":${str(vanillaId(carried))},"count":${if (carried.isEmpty) 0 else carried.count}"""
        if (c != lastCarried) { lastCarried = c; emit("""{"k":"carried","t":$tick,$c}""") }
    }

    private fun slotEntry(i: Int, stack: ItemStack): String {
        val tex = stack.get(DataComponents.PROFILE)?.let { texturesOf(it.partialProfile().properties()) }
        val name = if (stack.isEmpty) "" else stack.hoverName.string.noFormatting()
        val glint = if (!stack.isEmpty && (stack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) || stack.hasFoil())) 1 else 0
        return "[$i,${str(vanillaId(stack))},${if (stack.isEmpty) 0 else stack.count},${str(tex ?: "")},${str(name)},$glint]"
    }

    private val mouse = StringBuilder()
    private var lastMouseNs = 0L
    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN
    private var heldMouse: String? = null

    fun onContainerMouse(partialTick: Float, x: Float, y: Float) {
        val now = System.nanoTime()
        if (now - lastMouseNs < FRAME_NS) return
        lastMouseNs = now
        val entry = "[${f2(partialTick)},${f2(x)},${f2(y)}]"
        if (x == lastMouseX && y == lastMouseY) { heldMouse = entry; return }
        heldMouse?.let { if (mouse.isNotEmpty()) mouse.append(','); mouse.append(it) }
        heldMouse = null
        lastMouseX = x; lastMouseY = y
        if (mouse.isNotEmpty()) mouse.append(',')
        mouse.append(entry)
    }

    private fun flushMouse() {
        if (mouse.isEmpty()) return
        emit("""{"k":"mouse","t":$tick,"d":[$mouse]}""")
        mouse.setLength(0)
    }

    fun onContainerClick(x: Float, y: Float, button: Int) =
        emit("""{"k":"click","t":$tick,"x":${f2(x)},"y":${f2(y)},"button":$button}""")

    fun finish() {
        if (!confirmed) { abandon(); return }
        emit("""{"k":"end","t":$tick,"ms":${System.currentTimeMillis()}}""")
        flushPending()
        val temp = tempFile ?: return
        val finalName = "${startedAt.format(STAMP)}_${(floorName ?: "unknown").replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz"
        val lines = linesWritten
        io.execute {
            try {
                writer?.close()
                val target = temp.resolveSibling(finalName)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                val mb = Files.size(target) / 1_000_000.0
                if (ADR.savedMessage) EngineerClient.msg("§7ADR: saved run §f$finalName §7(${String.format(Locale.ROOT, "%.1f", mb)} MB, $lines lines, ${tick / 20}s)")
                onSaved(target)
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] adr: failed to finish run file", t)
            }
        }
        io.shutdown()
    }

    private fun confirm(): Boolean {
        val temp = dir.resolve("recording-${startedAt.format(STAMP)}.jsonl.gz.part")
        val opened = try {
            Files.createDirectories(dir)
            BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(temp)), Charsets.UTF_8), 1 shl 16)
        } catch (e: IOException) {
            EngineerClient.logger.warn("[ec] adr recording file could not be opened, run not recorded path={}", temp, e)
            abandon()
            return false
        }
        confirmed = true
        tempFile = temp
        writer = opened
        val self = EngineerClient.mc.player?.name?.string ?: "?"
        fun modVersion(id: String) = FabricLoader.getInstance().getModContainer(id)
            .map { it.metadata.version.friendlyString }.orElse("?")
        val version = modVersion("engineerclient")
        val mc = modVersion("minecraft")
        writeNow("""{"k":"meta","format":2,"mod":${str(version)},"mc":${str(mc)},"self":${str(self)},"startMs":${System.currentTimeMillis() - tick * 50L},"confirmedAtTick":$tick,"geometry":$captureGeometry,"farHalf":$FAR_HALF,"stx":1}""")
        backlog.forEach(::writeNow)
        backlog.clear()
        flushPending()
        return true
    }


    private fun abandon() {
        abandoned = true
        backlog.clear()
        tracked.clear()
        io.shutdown()
    }

    private fun emit(line: String) {
        if (abandoned) return
        if (confirmed) writeNow(line) else backlog.add(line)
    }

    private val pending = StringBuilder(1 shl 14)

    private fun writeNow(line: String) {
        if (writer == null) return
        linesWritten++
        pending.append(line).append('\n')
    }

    private fun flushPending() {
        val w = writer ?: return
        if (pending.isEmpty()) return
        val chunk = pending.toString()
        pending.setLength(0)
        io.execute {
            try { w.write(chunk) } catch (t: Throwable) { EngineerClient.logger.error("[ec] adr write failed", t) }
        }
    }

    private fun recordFloorAndParty() {
        val floor = DungeonUtils.floor?.name
        if (floor != null && floor != floorName) {
            floorName = floor
            emit("""{"k":"floor","t":$tick,"floor":${str(floor)}}""")
        }
        val party = DungeonUtils.dungeonTeammates
        val key = party.joinToString("|") { "${it.name}:${it.clazz.name}" }
        if (key != partyKey && party.isNotEmpty()) {
            partyKey = key
            emit("""{"k":"party","t":$tick,"m":[${party.joinToString(",") { "[${str(it.name)},${str(it.clazz.name)}]" }}]}""")
        }
    }

    private var roomsKey = ""

    private fun recordRooms() {
        val rooms = DungeonScan.rooms
        if (rooms.isEmpty()) return
        val sb = StringBuilder()
        rooms.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('[').append(str(r.name ?: "")).append(',').append(str(r.type.name)).append(',')
                .append(str(r.shape.name)).append(',').append(str(r.rotation?.name ?: "")).append(',')
                .append(str(r.checkmark.name)).append(",[")
            r.tiles.forEachIndexed { j, t -> if (j > 0) sb.append(','); sb.append('[').append(t.x).append(',').append(t.z).append(']') }
            sb.append("],").append(r.foundSecrets ?: -1).append(',').append(r.data?.maxSecrets ?: -1)
            RoomKeys.key(r)?.let { sb.append(',').append(str(it)) }
            sb.append(']')
        }
        val body = sb.toString()
        if (body == roomsKey) return
        roomsKey = body
        emit("""{"k":"rooms","t":$tick,"r":[$body]}""")
    }

    private val lastPlayer = HashMap<String, String>()

    private var lastHotbar = ""
    private val lastHotbarStacks = arrayOfNulls<ItemStack>(9)
    private var lastHotbarSel = -1
    private fun recordHotbar() {
        val inv = EngineerClient.mc.player?.inventory ?: return
        if (lastHotbarSel == inv.selectedSlot && (0 until 9).all { lastHotbarStacks[it] === inv.getItem(it) }) return
        for (i in 0 until 9) lastHotbarStacks[i] = inv.getItem(i)
        lastHotbarSel = inv.selectedSlot
        val items = (0 until 9).joinToString(",", "[", "]") { str(vanillaId(inv.getItem(it))) }
        val tex = (0 until 9).mapNotNull { i ->
            inv.getItem(i).get(DataComponents.PROFILE)?.let { texturesOf(it.partialProfile().properties()) }?.let { "\"$i\":${str(it)}" }
        }.joinToString(",", "{", "}")
        val body = """"items":$items,"sel":${inv.selectedSlot},"tex":$tex"""
        if (body == lastHotbar) return
        lastHotbar = body
        emit("""{"k":"hotbar","t":$tick,$body}""")
    }

    private val lastHeldHead = HashMap<String, String>()
    private val lastHeldStack = HashMap<String, ItemStack>()
    private fun recordHeldHead(p: Player, name: String) {
        if (lastHeldStack.put(name, p.mainHandItem) === p.mainHandItem) return
        val tex = p.mainHandItem.get(DataComponents.PROFILE)?.let { texturesOf(it.partialProfile().properties()) } ?: ""
        val prev = lastHeldHead.put(name, tex)
        if (prev != tex && !(prev == null && tex.isEmpty())) emit("""{"k":"held","t":$tick,"name":${str(name)},"tex":${str(tex)}}""")
    }

    private class PlayerRaw(var x: Double = Double.NaN, var y: Double = 0.0, var z: Double = 0.0, var yRot: Float = 0f, var xRot: Float = 0f,
                            var crouching: Boolean = false, var stack: ItemStack? = null, var held: String = "", var vanilla: String = "")
    private val playerRaw = HashMap<String, PlayerRaw>()

    private fun recordPlayers(level: ClientLevel) {
        val sb = StringBuilder()
        var changed = 0
        val seen = HashSet<String>()
        for (p in level.players()) {
            val name = if (p.uuid.version() == 2) "${p.name.string}#${p.id}" else p.name.string
            seen += name
            if (skinsWritten.add(name)) texturesOf(p.gameProfile.properties())?.let { emit("""{"k":"skin","t":$tick,"name":${str(name)},"tex":${str(it)}}""") }
            recordEquipment(p, "\"name\":${str(name)}", name)
            recordHeldHead(p, name)
            val raw = playerRaw.getOrPut(name) { PlayerRaw() }
            val stack = p.mainHandItem
            if (lastPlayer.containsKey(name) && raw.x == p.x && raw.y == p.y && raw.z == p.z && raw.yRot == p.yRot &&
                raw.xRot == p.xRot && raw.stack === stack && raw.crouching == p.isCrouching) continue
            raw.x = p.x; raw.y = p.y; raw.z = p.z; raw.yRot = p.yRot; raw.xRot = p.xRot; raw.crouching = p.isCrouching
            if (raw.stack !== stack) {
                raw.stack = stack
                raw.vanilla = vanillaId(stack)
                raw.held = if (stack.isEmpty) "" else stack.itemId.ifEmpty { raw.vanilla }
            }
            val entry = "[${str(name)},${m(p.x)},${m(p.y)},${m(p.z)},${w(p.yRot)},${a(p.xRot)},${str(raw.held)},${p.uuid.version()},${str(raw.vanilla)},${if (raw.crouching) 1 else 0}]"
            if (lastPlayer.put(name, entry) == entry) continue
            if (changed++ > 0) sb.append(',')
            sb.append(entry)
        }
        if (changed > 0) emit("""{"k":"p","t":$tick,"d":[$sb]}""")
        for (name in lastPlayer.keys.filter { it !in seen }) {
            lastPlayer.remove(name)
            playerRaw.remove(name)
            emit("""{"k":"pgone","t":$tick,"name":${str(name)}}""")
        }
    }

    private val lastSwing = HashMap<String, Any?>()

    private fun recordSwings(level: ClientLevel) {
        val sb = StringBuilder()
        var count = 0
        for (p in level.players()) {
            val name = p.name.string
            val swing = p.currentSwing
            val prev = lastSwing.put(name, swing)
            if (swing == null || swing === prev) continue
            if (count++ > 0) sb.append(',')
            sb.append(str(name))
        }
        if (count > 0) emit("""{"k":"sw","t":$tick,"d":[$sb]}""")
    }

    fun onSsClick(pos: BlockPos, blocked: Boolean) {
        val state = EngineerClient.mc.level?.getBlockState(pos)
        val powered = state != null && state.hasProperty(BlockStateProperties.POWERED) &&
            state.getValue(BlockStateProperties.POWERED)
        emit("""{"k":"ssclick","t":${tick},"x":${pos.x},"y":${pos.y},"z":${pos.z},"blocked":${if (blocked) 1 else 0},"powered":${if (powered) 1 else 0}}""")
    }

    fun isSsButton(pos: BlockPos) = pos in SS_BUTTONS

    fun onSsSound(id: String, x: Double, y: Double, z: Double, volume: Float, pitch: Float) =
        emit("""{"k":"sssound","t":$tick,"id":${str(id)},"x":${n(x)},"y":${n(y)},"z":${n(z)},"v":${f2(volume)},"p":${f2(pitch)}}""")

    private val lastMapPos = HashMap<String, String>()

    private fun recordMapPlayers() {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return
        val sb = StringBuilder()
        var count = 0
        for (p in DungeonUtils.dungeonTeammatesNoSelf) {
            if (p.isDead || p.entity != null) { lastMapPos.remove(p.name); continue }
            val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
            val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
            val entry = "[${str(p.name)},${n(x)},${n(z)},${a(p.yaw)}]"
            if (lastMapPos.put(p.name, entry) == entry) continue
            if (count++ > 0) sb.append(',')
            sb.append(entry)
        }
        if (count > 0) emit("""{"k":"mp","t":$tick,"d":[$sb]}""")
    }

    private class Tag(val mob: Int, val dx: Double, val dy: Double, val dz: Double)
    private val tags = HashMap<Int, Tag>()

    private fun tagOf(level: ClientLevel, stand: ArmorStand): Tag? {
        for (back in intArrayOf(1, 3)) {
            val mob = level.getEntity(stand.id - back) as? LivingEntity ?: continue
            if (mob is ArmorStand || mob is Player) continue
            val dx = q(stand.x - mob.x); val dy = q(stand.y - mob.y); val dz = q(stand.z - mob.z)
            if (abs(dx) <= 0.05 && abs(dz) <= 0.05 && dy in 0.0..4.0) return Tag(mob.id, dx, dy, dz)
        }
        return null
    }

    private class Named(val of: Component?, val name: String, val colored: String)
    private val names = HashMap<Int, Named>()
    private fun namesOf(e: Entity): Named {
        val c = e.customName
        names[e.id]?.let { if (it.of === c) return it }
        return Named(c, c?.string ?: "", c?.let { ADR.legacyText(it) } ?: "").also { names[e.id] = it }
    }

    private val seenIds = HashSet<Int>()
    private var movedCount = 0

    private fun recordEntities(level: ClientLevel) {
        seenIds.clear()
        val moved = StringBuilder()
        movedCount = 0
        val me = EngineerClient.mc.player
        val oddTick = tick % 2 == 1
        for (e in level.entitiesForRendering()) {
            if (e is Player) continue
            if (!e.x.isFinite() || !e.y.isFinite() || !e.z.isFinite()) continue
            val id = e.id
            seenIds += id
            val named0 = namesOf(e)
            val name = named0.name
            val colored = named0.colored
            val c = if (colored.isNotEmpty() && colored != name) ",\"c\":${str(colored)}" else ""
            val t = tracked[id]
            val headYaw = if (e is LivingEntity) e.yHeadRot else e.yRot
            if (t == null) {
                recordSpawn(level, e, id, name, colored, c, headYaw)
                continue
            }
            if (t.ballistic) {
                if (!(e is AbstractArrow && (e as ArrowInGroundInvoker).`ec$isInGround`())) {
                    t.fly()
                    if (e.x == t.lx && e.y == t.ly && e.z == t.lz) continue
                    val dx = e.x - t.px; val dy = e.y - t.py; val dz = e.z - t.pz
                    if (dx * dx + dy * dy + dz * dz > ARC_SLACK * ARC_SLACK) {
                        t.launch(e.x, e.y, e.z, e.deltaMovement)
                        emit("""{"k":"arc","t":$tick,"id":$id${t.flight()}}""")
                    }
                    continue
                }
                t.flight = NO_FLIGHT
            }
            if (e is LivingEntity) recordEquipment(e, "\"id\":$id", "#$id")
            if (e is ArmorStand) recordStand(e)
            if (e is ItemFrame) recordFrame(e)
            if (colored != t.name) {
                t.name = colored
                emit("""{"k":"name","t":$tick,"id":$id,"name":${str(name)}$c}""")
            }
            recordMove(level, e, t, id, headYaw, oddTick, me, moved)
        }
        if (movedCount > 0) emit("""{"k":"e","t":$tick,"d":[$moved]}""")
        recordGone()
    }

    private fun recordSpawn(level: ClientLevel, e: Entity, id: Int, name: String, colored: String, c: String, headYaw: Float) {
        val flight = when {
            name.isNotEmpty() -> NO_FLIGHT
            e is AbstractArrow -> if ((e as ArrowInGroundInvoker).`ec$isInGround`()) NO_FLIGHT else ARROW
            e is AbstractHurtingProjectile -> POWERED
            else -> NO_FLIGHT
        }
        val tr = Tracked(q(e.x), q(e.y), q(e.z), deg(e.yRot), colored, deg(headYaw), flight)
        tracked[id] = tr
        if (e is AbstractHurtingProjectile && flight == POWERED) {
            tr.accel = Math.round(e.accelerationPower * 10000) / 10000.0
            tr.inertia = if (e is WitherSkull && e.isDangerous) 0.73 else 0.95
        }
        if (flight != NO_FLIGHT) {
            tr.launch(e.x, e.y, e.z, e.deltaMovement)
            emit("""{"k":"spawn","t":$tick,"id":$id,"type":${str(typeOf(e))}${tr.flight()}}""")
            return
        }
        val block = (e as? FallingBlockEntity)?.let { ",\"block\":" + str(BlockStateParser.serialize(it.blockState)) } ?: ""
        val item = (e as? ItemEntity)?.item?.let { ",\"item\":" + str(it.hoverName.string.noFormatting()) + ",\"itemId\":" + str(vanillaId(it)) } ?: ""
        val named = if (name.isEmpty()) "" else ",\"name\":${str(name)}$c"
        emit("""{"k":"spawn","t":$tick,"id":$id,"type":${str(typeOf(e))}$named,"x":${n(e.x)},"y":${n(e.y)},"z":${n(e.z)},"yaw":${w(e.yRot)}${if (e is LivingEntity) ",\"headYaw\":" + w(headYaw) else ""}${if (e is LivingEntity && e.isBaby) ",\"baby\":1" else ""}$block$item}""")
        if (e is ArmorStand) {
            recordStand(e)
            tagOf(level, e)?.let { tg -> tags[id] = tg; emit("""{"k":"tag","t":$tick,"id":$id,"of":${tg.mob},"dx":${tg.dx},"dy":${tg.dy},"dz":${tg.dz}}""") }
        }
        if (e is ItemFrame) recordFrame(e)
    }

    private fun recordMove(level: ClientLevel, e: Entity, t: Tracked, id: Int, headYaw: Float, oddTick: Boolean, me: LocalPlayer?, moved: StringBuilder) {
        val qx = q(e.x); val qy = q(e.y); val qz = q(e.z); val qYaw = deg(e.yRot); val qHead = deg(headYaw)
        val tag = tags[id]
        if (tag != null) {
            val mob = level.getEntity(tag.mob)
            if (mob != null && abs(mob.x + tag.dx - e.x) < 0.006 && abs(mob.y + tag.dy - e.y) < 0.006 && abs(mob.z + tag.dz - e.z) < 0.006) { t.x = qx; t.y = qy; t.z = qz; return }
            tags.remove(id)
            emit("""{"k":"tag","t":$tick,"id":$id,"of":null}""")
        }
        if (oddTick && me != null && e.distanceToSqr(me) > FAR_HALF * FAR_HALF) return
        if (qx != t.x || qy != t.y || qz != t.z || qYaw != t.yaw || qHead != t.headYaw) {
            t.x = qx; t.y = qy; t.z = qz; t.yaw = qYaw; t.headYaw = qHead
            if (movedCount++ > 0) moved.append(',')
            moved.append('[').append(id).append(',').append(m(e.x)).append(',').append(m(e.y)).append(',')
                .append(m(e.z)).append(',').append(qYaw.toInt())
            if (e is LivingEntity) moved.append(',').append(qHead.toInt())
            moved.append(']')
        }
    }

    private fun recordGone() {
        val gone = tracked.keys.filter { it !in seenIds }
        for (id in gone) {
            tracked.remove(id)
            names.remove(id)
            tags.remove(id)
            lastEquipment.remove("#$id")
            lastStacks.remove("#$id")
            lastStand.remove(id)
            lastStandRaw.remove(id)
            lastFrame.remove(id)
            emit("""{"k":"gone","t":$tick,"id":$id}""")
        }
    }

    private val lastFrame = HashMap<Int, String>()
    private fun recordFrame(f: ItemFrame) {
        val entry = """"item":${str(vanillaId(f.item))},"rot":${f.rotation}"""
        if (lastFrame.put(f.id, entry) == entry) return
        emit("""{"k":"frame","t":$tick,"id":${f.id},$entry}""")
    }

    private val skinsWritten = HashSet<String>()

    private val lastEquipment = HashMap<String, String>()
    private val lastStacks = HashMap<String, Array<ItemStack>>()

    private val lastStand = HashMap<Int, String>()
    private val lastStandRaw = HashMap<Int, Array<Any>>()

    private fun recordStand(e: ArmorStand) {
        val f = (if (e.isSmall) 1 else 0) or (if (e.isInvisible) 2 else 0) or (if (e.showArms()) 4 else 0) or
            (if (!e.showBasePlate()) 8 else 0) or (if (e.isMarker) 16 else 0)
        val raw = lastStandRaw[e.id]
        if (raw != null && raw[0] == f && raw[1] === e.headPose && raw[2] === e.bodyPose && raw[3] === e.leftArmPose &&
            raw[4] === e.rightArmPose && raw[5] === e.leftLegPose && raw[6] === e.rightLegPose) return
        lastStandRaw[e.id] = arrayOf(f, e.headPose, e.bodyPose, e.leftArmPose, e.rightArmPose, e.leftLegPose, e.rightLegPose)
        val pose = listOf(e.headPose, e.bodyPose, e.leftArmPose, e.rightArmPose, e.leftLegPose, e.rightLegPose)
            .joinToString(",") { "${a(it.x())},${a(it.y())},${a(it.z())}" }
        val body = """"f":$f,"pose":[$pose]"""
        if (lastStand.put(e.id, body) == body) return
        emit("""{"k":"stand","t":$tick,"id":${e.id},$body}""")
    }

    private val lastSkull = HashMap<BlockPos, String>()
    private val freshSkulls = HashMap<BlockPos, Int>()

    private fun recordSkulls(level: ClientLevel) {
        val player = EngineerClient.mc.player ?: return
        val cx = player.blockPosition().x shr 4
        val cz = player.blockPosition().z shr 4
        val fresh = freshSkulls.entries.iterator()
        while (fresh.hasNext()) {
            val (pos, at) = fresh.next()
            val be = level.getBlockEntity(pos) as? SkullBlockEntity
            val tex = be?.ownerProfile?.let { texturesOf(it.partialProfile().properties()) }
            if (tex == null) { if (tick - at > FRESH_TICKS) fresh.remove(); continue }
            fresh.remove()
            if (lastSkull.put(pos, tex) != tex) emit("""{"k":"skull","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"tex":${str(tex)}}""")
        }
        val slice = tick % 20
        for (x in cx - SKULL_CHUNKS..cx + SKULL_CHUNKS) for (z in cz - SKULL_CHUNKS..cz + SKULL_CHUNKS) {
            if (Math.floorMod(x * 7 + z, 20) != slice) continue
            val chunk = level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) ?: continue
            for (be in chunk.blockEntities.values) {
                if (be !is SkullBlockEntity) continue
                val tex = be.ownerProfile?.let { texturesOf(it.partialProfile().properties()) } ?: continue
                val pos = be.blockPos
                if (lastSkull.put(pos.immutable(), tex) == tex) continue
                emit("""{"k":"skull","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"tex":${str(tex)}}""")
            }
        }
    }

    private fun recordEquipment(e: LivingEntity, who: String, key: String) {
        val was = lastStacks[key]
        if (was != null && was[0] === e.mainHandItem && was[1] === e.getItemBySlot(EquipmentSlot.HEAD) && was[2] === e.getItemBySlot(EquipmentSlot.CHEST) &&
            was[3] === e.getItemBySlot(EquipmentSlot.LEGS) && was[4] === e.getItemBySlot(EquipmentSlot.FEET)) return
        lastStacks[key] = arrayOf(e.mainHandItem, e.getItemBySlot(EquipmentSlot.HEAD), e.getItemBySlot(EquipmentSlot.CHEST), e.getItemBySlot(EquipmentSlot.LEGS), e.getItemBySlot(EquipmentSlot.FEET))
        val head = e.getItemBySlot(EquipmentSlot.HEAD)
        val items = listOf(e.mainHandItem, head, e.getItemBySlot(EquipmentSlot.CHEST), e.getItemBySlot(EquipmentSlot.LEGS), e.getItemBySlot(EquipmentSlot.FEET))
        val headTex = head.get(DataComponents.PROFILE)?.let { texturesOf(it.partialProfile().properties()) }
        val body = items.joinToString(",", "[", "]") { str(vanillaId(it)) } + (headTex?.let { ",\"headTex\":${str(it)}" } ?: "")
        val same = body.replace(DYE, "")
        val previous = lastEquipment.put(key, same)
        if (previous == same || (previous == null && body == NO_EQUIPMENT)) return
        emit("""{"k":"eq","t":$tick,$who,"eq":$body}""")
    }

    private fun vanillaId(stack: ItemStack): String {
        if (stack.isEmpty) return ""
        val modelId = stack.get(DataComponents.ITEM_MODEL)
        val model = modelId?.takeIf { it.namespace == "minecraft" }?.toString()
        var id = model ?: BuiltInRegistries.ITEM.getKey(stack.item).toString()
        if (modelId != null && model == null) id += "@" + modelId
        val dye = stack.get(DataComponents.DYED_COLOR) ?: return id
        return id + "#" + Integer.toHexString((dye.rgb() and 0xFFFFFF) or 0x1000000).substring(1)
    }

    private fun texturesOf(props: PropertyMap): String? {
        val value = props.get("textures").firstOrNull()?.value() ?: return null
        return skinHashes.getOrPut(value) {
            runCatching {
                val skin = JsonParser.parseString(String(Base64.getDecoder().decode(value))).asJsonObject["textures"].asJsonObject["SKIN"].asJsonObject
                val hash = skin["url"].asString.substringAfterLast('/')
                if (!hash.matches(SKIN_HASH)) value
                else hash + if (skin["metadata"]?.asJsonObject?.get("model")?.asString == "slim") ":s" else ""
            }.getOrDefault(value)
        }
    }
    private val skinHashes = HashMap<String, String>()

    private fun paletteIndex(state: BlockState): Int = palette.getOrPut(state) {
        val i = palette.size
        emit("""{"k":"pal","i":$i,"s":${str(BlockStateParser.serialize(state))}}""")
        i
    }

    private fun typeOf(e: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString()

    private fun n(v: Double) = fixed(v, 3)
    private fun q(v: Double) = Math.round(v * 100) / 100.0
    private fun m(v: Double): String { val r = Math.round(v * 100); return if (r % 100 == 0L) (r / 100).toString() else (r / 100.0).toString() }
    private fun deg(v: Float) = Math.round(Mth.wrapDegrees(v)).toFloat()
    private fun w(v: Float) = a(Mth.wrapDegrees(v))
    private fun a(v: Float) = fixed(v.toDouble(), 1)
    private fun f2(v: Float) = fixed(v.toDouble(), 2)

    private fun fixed(v: Double, decimals: Int): String {
        if (!v.isFinite()) return "0"
        val scale = POW10[decimals]
        val r = Math.round(v * scale)
        val abs = abs(r)
        val sb = StringBuilder(20)
        if (r < 0) sb.append('-')
        sb.append(abs / scale)
        if (decimals == 0) return sb.toString()
        sb.append('.')
        val frac = (abs % scale).toString()
        for (i in frac.length until decimals) sb.append('0')
        return sb.append(frac).toString()
    }

    private fun str(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private companion object {
        const val ABANDON_AFTER_TICKS = 20 * 60
        private val POW10 = longArrayOf(1, 10, 100, 1000, 10000)
        private const val ARC_SLACK = 0.1
        private const val NO_FLIGHT = 0
        private const val ARROW = 1
        private const val POWERED = 2
        private val SKIN_HASH = Regex("[0-9a-f]{20,80}")
        const val FRAME_NS = 16_000_000L
        private val SS_BUTTONS = listOf(BlockPos(110, 121, 91)) + (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }

        const val FAR_HALF = 32
        const val NO_EQUIPMENT = """["","","","",""]"""
        private val DYE = Regex("#[0-9a-f]{6}")
        const val SKULL_CHUNKS = 12
        private const val FRESH_SKULLS = 4096
        private const val FRESH_TICKS = 5
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
