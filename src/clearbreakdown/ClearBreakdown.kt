package com.engineerclient.clearbreakdown

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.adr.OnlineLink
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Practice
import com.engineerclient.splits.SplitTracker
import com.odtheking.odin.OdinMod
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.CheckmarkUpdateEvent
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.SecretsUpdateEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.network.hypixelapi.RequestUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

object ClearBreakdown : Module(
    name = "Clear Breakdown",
    category = Category.custom("ADM", 860, 10),
    description = "At the end of a finished run, everyone's rooms (cleared alone - cleared) and secrets; hover for the rooms in order and the secrets room by room. Replaces Odin's Secrets Counter lines while on.",
    key = null,
) {
    private val CONTROL_CODES = Regex("§.")
    private val NOT_CLEARS = setOf(RoomType.FAIRY, RoomType.ENTRANCE)
    private const val FULL_RESEND_TICKS = 100
    private const val FIRST_POLL_MS = 15_000L
    private const val POLL_MS = 30_000L
    private const val LAST_ASK_MS = 800L

    var clears = ClearCredit(); private set
    var secrets = SecretCredit(); private set
    var active = false; private set
    private val gate = RunGate()
    @Volatile private var syncOpen = false
    val roster = LinkedHashMap<String, Char>()
    private var before = ConcurrentHashMap<String, Long>()
    private var latest = ConcurrentHashMap<String, Long>()
    private var nextPollMs = 0L
    val relayed = RelayedPresence()
    private val toldSeen = HashMap<String, String>()
    private var relayTick = 0

    val me: String get() = mc.user.name

    fun roomKey(room: DungeonRoom): String = room.tiles.map { it.data }.sorted().joinToString(",")

    fun roomName(room: DungeonRoom): String = room.name ?: room.type.name

    fun roomAt(x: Double, z: Double): DungeonRoom? {
        val tx = (floor(x).toInt() + 201) shr 5
        val tz = (floor(z).toInt() + 201) shr 5
        if (tx !in 0..5 || tz !in 0..5) return null
        return DungeonScan.tiles[tx + tz * 6].room
    }

    init {
        on<LevelEvent.Load> { EngineerClient.safely("clear breakdown reset") { reset(); closeSync() } }
        on<FloorEnterEvent> { mc.execute { EngineerClient.safely("clear breakdown sync") { OdinSync.connect(); syncOpen = true } } }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            mc.execute { EngineerClient.safely("clear breakdown chat") { onChat(text) } }
        }

        on<TickEvent.End> { EngineerClient.safely("clear breakdown tick") { tick() } }
    }

    init {
        onReceive<ClientboundTakeItemEntityPacket> {
            val item = itemId; val by = playerId
            mc.execute { EngineerClient.safely("clear breakdown pickup") { Sightings.onPickup(item, by) } }
        }
        onReceive<ClientboundBlockEventPacket> {
            if (b0 != 1 || b1 <= 0 || (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST)) return@onReceive
            val at = pos.immutable()
            mc.execute { EngineerClient.safely("clear breakdown chest") { Sightings.onChestOpen(at) } }
        }
        onReceive<ClientboundSoundPacket> {
            val sound = sound.value()
            if ((sound != SoundEvents.BAT_HURT && sound != SoundEvents.BAT_DEATH) || volume != 0.1f) return@onReceive
            val x = x; val y = y; val z = z
            mc.execute { EngineerClient.safely("clear breakdown bat") { Sightings.onBat(x, y, z) } }
        }
        on<BlockInteractEvent> { EngineerClient.safely("clear breakdown click") { Sightings.onClick(pos) } }
        on<BlockUpdateEvent> { EngineerClient.safely("clear breakdown block") { Sightings.onBlockChange(pos, updated) } }
    }

    init {
        on<CheckmarkUpdateEvent> {
            val key = roomKey(room); val name = roomName(room); val counts = room.type !in NOT_CLEARS
            val mark = when (checkmark) {
                MapCheckmark.WHITE -> Mark.WHITE
                MapCheckmark.GREEN -> Mark.GREEN
                MapCheckmark.RED -> Mark.RED
                else -> Mark.OTHER
            }
            val max = if (mark == Mark.GREEN) room.data?.maxSecrets else null
            mc.execute {
                EngineerClient.safely("clear breakdown checkmark") {
                    if (!active) return@safely
                    if (counts) clears.onCheckmark(key, name, mark)
                    max?.let { secrets.observe(key, name, it) }
                }
            }
        }

        on<SecretsUpdateEvent> {
            EngineerClient.safely("clear breakdown secrets") { if (active) secrets.observe(roomKey(room), roomName(room), foundSecrets) }
        }

        OnlineLink.onRelay = { text ->
            RelayCodec.decode(text)?.let { (from, items) -> mc.execute { EngineerClient.safely("clear breakdown relay") { fromParty(from, items) } } }
        }

        OdinSync.onPayload = { player, tiles, found ->
            val key = tiles.sorted().joinToString(",")
            mc.execute {
                EngineerClient.safely("clear breakdown odin sync") {
                    if (!active || player == me || player !in roster) return@safely
                    DungeonScan.rooms.firstOrNull { roomKey(it) == key }?.let { secrets.observe(key, roomName(it), found) }
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
        closeSync()
    }

    private fun reset() {
        clears = ClearCredit(); secrets = SecretCredit()
        active = false; gate.reset()
        roster.clear(); before = ConcurrentHashMap(); latest = ConcurrentHashMap(); nextPollMs = 0L; relayed.clear(); toldSeen.clear()
        Sightings.reset()
    }

    private fun closeSync() {
        if (syncOpen) { OdinSync.shutdown(); syncOpen = false }
    }

    private fun onChat(text: String) {
        if (!DungeonUtils.inDungeons) return
        if (P3Sim.inSim && Practice.active) return
        if (text == SplitTracker.MORT) start()
        else if (active && gate.line(text)) finish()
    }

    private fun start() {
        reset()
        active = true
        for (p in DungeonUtils.dungeonTeammates) join(p)
    }

    private fun join(p: DungeonPlayer) {
        if (p.name in roster) return
        roster[p.name] = p.clazz.colorCode
        val name = p.name
        val into = before
        OdinMod.scope.launch { RequestUtils.pullSecrets(name).onSuccess { into[name] = it } }
    }

    private fun tick() {
        if (!active || !DungeonUtils.inDungeons) return
        for (p in DungeonUtils.dungeonTeammates) join(p)
        if (DungeonUtils.inBoss) { pollTotals(); return closeSync() }
        Sightings.tick()
        val where = presence()
        clears.tick(where)
        secrets.tick(where)
        if (++relayTick % 20 == 0) toParty(full = relayTick % FULL_RESEND_TICKS == 0)
    }

    private fun toParty(full: Boolean) {
        val items = ArrayList<Relayed>(Sightings.outbox)
        val team = DungeonUtils.dungeonTeammates.associateBy { it.name }
        val seen = HashMap<String, String>()
        for (name in roster.keys) {
            val p = team[name] ?: continue
            if (p.isDead) continue
            val e = (if (name == me) mc.player else p.entity) ?: continue
            seen[name] = roomAt(e.x, e.z)?.let(::roomKey) ?: ""
        }
        for ((name, room) in seen) if (full || toldSeen[name] != room) items += Relayed.Presence(name, room)
        for (name in toldSeen.keys) if (name !in seen) items += Relayed.Presence(name, null)
        if (items.isEmpty() || !OnlineLink.sendRelay(RelayCodec.frames(items))) return
        Sightings.outbox.clear()
        toldSeen.clear(); toldSeen.putAll(seen)
    }

    private fun fromParty(from: String, items: List<Relayed>) {
        if (!active || from == me || from !in roster) return
        val now = System.currentTimeMillis()
        for (item in items) when (item) {
            is Relayed.Secret -> {
                val candidates = item.candidates.filter { it in roster }.toSet()
                val room = DungeonScan.rooms.firstOrNull { roomKey(it) == item.roomKey } ?: continue
                if (candidates.isNotEmpty()) secrets.sighting(item.key, item.roomKey, roomName(room), candidates, item.exact, now)
            }
            is Relayed.Presence -> if (item.player in roster) relayed.update(from, item.player, item.roomKey, now)
        }
    }

    private fun presence(): Map<String, String?> {
        val team = DungeonUtils.dungeonTeammates.associateBy { it.name }
        val now = System.currentTimeMillis()
        val out = HashMap<String, String?>()
        for (name in roster.keys) {
            val p = team[name]
            out[name] = if (p == null || p.isDead) null else roomOf(p, now)
        }
        return out
    }

    private fun roomOf(p: DungeonPlayer, now: Long): String? {
        val entity = if (p.name == me) mc.player else p.entity
        if (entity != null) return roomAt(entity.x, entity.z)?.let(::roomKey)
        relayed.roomOf(p.name, now)?.let { return it.ifEmpty { null } }
        if (p.mapPos.data == 0L) return null
        val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        return roomAt(x, z)?.let(::roomKey)
    }

    private fun pollTotals() {
        val now = System.currentTimeMillis()
        if (nextPollMs == 0L) nextPollMs = now + FIRST_POLL_MS
        if (now < nextPollMs) return
        nextPollMs = now + POLL_MS
        val into = latest
        for (name in roster.keys) OdinMod.scope.launch { RequestUtils.pullSecrets(name).onSuccess { into[name] = it } }
    }

    private fun finish() {
        active = false
        val names = roster.toMap()
        val start = before; val now = latest
        val runClears = clears; val runSecrets = secrets
        fun postNow() = post(names, runClears.clears(), runSecrets.resolve(names.keys.associateWith { n ->
            val after = now[n]; val was = start[n]
            if (after != null && was != null) (after - was).coerceAtLeast(0L).toInt() else null
        }))
        val missing = names.keys.filter { !now.containsKey(it) && start.containsKey(it) }
        if (missing.isEmpty()) return postNow()
        OdinMod.scope.launch {
            withTimeoutOrNull(LAST_ASK_MS) {
                missing.map { n -> async { RequestUtils.pullSecrets(n).onSuccess { now[n] = it } } }.awaitAll()
            }
            mc.execute { EngineerClient.safely("clear breakdown post") { postNow() } }
        }
    }

    private fun post(names: Map<String, Char>, rooms: Map<String, List<Clear>>, found: Map<String, PlayerSecrets>) {
        EngineerClient.msg("§6Clear Breakdown")
        for ((name, colour) in names) {
            val p = BreakdownText.line(name, colour, rooms[name].orEmpty(), found[name])
            EngineerClient.msg(Component.literal(p.name)
                .append(hover(p.rooms, p.roomsHover))
                .append(Component.literal(" "))
                .append(hover(p.secrets, p.secretsHover)))
        }
    }

    private fun hover(text: String, hover: String) =
        Component.literal(text).withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(hover))) }
}
