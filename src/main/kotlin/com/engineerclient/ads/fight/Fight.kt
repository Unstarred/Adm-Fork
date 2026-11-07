package com.engineerclient.ads.fight

import com.engineerclient.EngineerClient
import com.engineerclient.features.splits.SplitTracker
import com.odtheking.odin.features.impl.boss.ArrowsDevice
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import java.util.UUID
import kotlin.random.Random
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.arena.Blocks
import com.engineerclient.ads.arena.Spots
import com.engineerclient.ads.bots.Party
import com.engineerclient.ads.core.LagRuntime
import com.engineerclient.ads.core.PerPlayer
import com.engineerclient.ads.core.Recorder
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.core.SimServer
import com.engineerclient.ads.core.Stats
import com.engineerclient.ads.core.perPlayer
import com.engineerclient.ads.items.Bows
import com.engineerclient.ads.items.HotbarLayout
import com.engineerclient.ads.items.Masks
import com.engineerclient.ads.items.MovementItems
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.party.PartyClient
import com.engineerclient.ads.party.PartyServer
import com.engineerclient.ads.party.Players
import com.engineerclient.ads.plan.Practice

object Fight {
    abstract class Phase(val name: String) {
        var t = 0
        open fun start() {}
        open fun tick() {}
        open fun stop() {}
        abstract val restart: Start
    }

    enum class Start(val label: String) {
        P1("P1 Maxor"), P2("P2 Storm"), P3("P3 Goldor"), S1("S1"), S2("S2"), S3("S3"), S4("S4"), CORE("Core"), P4("P4 Necron"),
    }

    var phase: Phase? = null
        private set

    var previous: Phase? = null
        private set

    val forcedTerminal: Terminals.Type? get() = P3Sim.forcedTerminal

    var refreshPhase = 18
        private set

    var serverTick = 0
        private set

    private val PING_BASE = 698 + Random.nextInt(100)

    private class Later(val at: Int, val what: String, val epoch: Int, val run: () -> Unit) {
        val actor = Sim.acting
        val all = Sim.toAll
        fun go() = Sim.asPlayer(actor, all, run)
    }
    private var epoch = 0
    private val later = ArrayList<Later>()

    fun later(ticks: Int, what: String = "later", run: () -> Unit) {
        later += Later(serverTick + ticks.coerceAtLeast(0), what, epoch, run)
    }

    val pingTicks: Int get() = (P3Sim.ping.toInt() + 25) / 50

    private class Timed(val dueNs: Long, val what: String, val epoch: Int, val run: () -> Unit) {
        val actor = Sim.acting
        val all = Sim.toAll
        fun go() = Sim.asPlayer(actor, all, run)
    }
    private val timed = ArrayList<Timed>()

    fun pingMs(): Double {
        val base = P3Sim.ping.toDouble()
        if (base <= 0.0) return 0.0
        if (!P3Sim.jitter) return base
        val u = Random.nextDouble()
        val q = JITTER_Q
        var f = q.last()[1]
        for (i in 1 until q.size) if (u <= q[i][0]) { val a = q[i - 1]; val b = q[i]; f = a[1] + (b[1] - a[1]) * (u - a[0]) / (b[0] - a[0]); break }
        return (base + (f - 1.0) * 33.0).coerceAtLeast(base * 0.5)
    }
    private val JITTER_Q = arrayOf(
        doubleArrayOf(0.0, 0.85), doubleArrayOf(0.10, 0.91), doubleArrayOf(0.50, 1.0), doubleArrayOf(0.90, 1.9),
        doubleArrayOf(0.99, 2.2), doubleArrayOf(0.998, 3.5), doubleArrayOf(1.0, 8.0),
    )

    fun afterPing(what: String, run: () -> Unit) = afterMs(pingMs(), what, run)

    fun afterMs(ms: Double, what: String, run: () -> Unit) {
        val p = phase
        if (ms <= 0.0) run()
        else timed += Timed(System.nanoTime() + (ms * 1e6).toLong(), what, epoch) { if (phase === p) run() }
    }

    var lastRot: Pair<Float, Float> by perPlayer { 0f to 0f }
        private set

    @JvmStatic
    fun noteUseItem(p: ServerPlayer) {
        val s = p.level().server
        if (s !== SimServer.server || !s.isSameThread) return
        Sim.asPlayer(p) { lastRot = p.xRot to p.yRot }
    }
    private val posHistories = HashMap<UUID, ArrayDeque<Pair<Long, Vec3>>>()

    fun seenPos(p: ServerPlayer): Vec3 = Sim.asPlayer(p, Sim.toAll) {
        val live = p.position()
        val ms = P3Sim.ping / 2.0
        val posHistory = posHistories[p.uuid]
        if (ms <= 0.0 || posHistory == null || posHistory.isEmpty()) return@asPlayer live
        val target = System.nanoTime() - (ms * 1e6).toLong()
        var prev: Pair<Long, Vec3>? = null
        for (e in posHistory) {
            if (e.first >= target) {
                val a = prev ?: return@asPlayer e.second
                val t = (target - a.first).toDouble() / (e.first - a.first).coerceAtLeast(1)
                return@asPlayer a.second.lerp(e.second, t.coerceIn(0.0, 1.0))
            }
            prev = e
        }
        val a = prev ?: return@asPlayer live
        val t = (target - a.first).toDouble() / (System.nanoTime() - a.first).coerceAtLeast(1)
        a.second.lerp(live, t.coerceIn(0.0, 1.0))
    }

    fun reset(server: MinecraftServer) {
        PerPlayer.clearAll()
        LagRuntime.reset()
        serverTick = 0
        epoch++
        later.clear()
        timed.clear(); posHistories.clear()
        Practice.exit()
        Terminals.closeAll()
        Sim.forEachPlayer { SimItems.reset() }
        SimItems.resetShared()
        Players.clear()
        PartyServer.reset()
        phase = null
        BossBar.hide()
        Sim.clearEntities()
        Sim.command("time set noon")
        Sim.command("weather clear")
        Blocks.restoreAll()
        Devices.showIdle()
    }

    fun stop() {
        phase?.let { EngineerClient.safely("p3sim stop ${it.name}") { it.stop() } }
        Sim.players().forEach { it.connection.send(ClientboundClearTitlesPacket(true)) }
        phase = null
        epoch++
        later.clear()
        timed.clear()
        Masks.stopped()
        Recorder.finish()
        Terminals.closeAll()
        Sim.forEachPlayer { SimItems.reset() }
        SimItems.resetShared()
        BossBar.hide()
    }

    fun join(player: ServerPlayer) {
        EngineerClient.safely("ads no advancements") { player.advancements.clearTriggers() }
        val host = SimServer.player
        if (host != null && host !== player) {
            Sim.asPlayer(player) {
                PartyServer.rosterDirty = true
                setup(player)
                Sim.tp(player, host.x, host.y, host.z, host.yRot, host.xRot)
            }
            return
        }
        lobby(player)
    }

    private fun lobby(player: ServerPlayer) {
        setup(player)
        if (phase == null) {
            Sim.tp(player, Spots.LOBBY.x, Spots.LOBBY.y, Spots.LOBBY.z, Spots.LOBBY.yaw, Spots.LOBBY.pitch)
            later(20, "welcome") {
                Sim.note("Welcome to ADS, the Advanced Dungeon Simulator. §fRight click the Nether Star§7 (or /ads) for the menu.")
                if (P3Sim.autoStart) start(Start.P3)
            }
        }
    }

    fun leave(player: ServerPlayer) {
        Sim.asPlayer(player) { HotbarLayout.keep(player, save = true); HotbarLayout.forgetGiven() }
        (phase as? P1Maxor)?.playerLeft(player)
        Masks.leftOne(player)
        Players.forgetFiles(player)
        val joiner = !Sim.isHost(player)
        val c = Players.classOf(player)
        val theirs = Players.jobsOf(player)
        val at = player.position()
        Players.left(player)
        if (joiner) (phase as? GoldorPhase)?.let { Party.takeOver(it, c, at, theirs) }
        PartyServer.left(player)
        Party.rosterChanged()
        if (!Sim.isHost(player)) Sim.everyone { Sim.note("§e${player.gameProfile.name}§7 left the party.") }
    }

    fun setup(player: ServerPlayer, giveHotbar: Boolean = true) = Sim.asPlayer(player) {
        if (player.gameMode() != GameType.CREATIVE) player.setGameMode(GameType.SURVIVAL)
        Sim.guardBlocks()
        applySpeed(player)
        player.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        player.getAttribute(Attributes.STEP_HEIGHT)?.baseValue = 0.6
        player.isPermanentlyInvulnerable = true
        player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 40.0
        player.health = 40f
        player.getAttribute(Attributes.MAX_ABSORPTION)?.let { if (it.baseValue < 16.0) it.baseValue = 16.0 }
        player.absorptionAmount = 16f
        player.removeEffect(MobEffects.SATURATION)
        player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, -1, 1, true, true, false))
        player.foodData.setFoodLevel(20); player.foodData.setSaturation(20f)
        if (giveHotbar) SimItems.giveHotbar(player, p3 = phase !is P1Maxor && phase !is P2Storm, start = true)
        if (player.gameMode() != GameType.CREATIVE) {
            player.removeEffect(MobEffects.INVISIBILITY)
            player.abilities.flying = false; player.abilities.mayfly = false; player.onUpdateAbilities()
        }
        SimItems.miningEffects(player, force = true)
    }

    private fun resetI4Solver() = later(2, "i4 solver reset") { PartyClient.send("arrowsReset") }

    fun resetArrowsDevice() {
        val c = ArrowsDevice::class.java
        fun field(name: String) = c.getDeclaredField(name).apply { isAccessible = true }
        (field("markedPositions").get(null) as? MutableSet<*>)?.clear()
        field("targetPosition").set(null, null)
        field("isDeviceComplete").setBoolean(null, false)
        field("optimalAimPositions").set(null, emptyList<Any>())
    }

    fun speedStat(player: ServerPlayer): Int = Sim.asPlayer(player, Sim.toAll) {
        val racing = SimItems.idOf(player.getItemBySlot(EquipmentSlot.HEAD)) == "RACING_HELMET"
        P3Sim.speed + (if (P3Sim.phoenix) 0 else 100) + (if (racing) 100 else 0)
    }

    fun applySpeed(player: ServerPlayer) {
        val speed = speedStat(player)
        player.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = speed.coerceAtLeast(100).toDouble() / 1000.0
        player.abilities.setWalkingSpeed(speed.coerceAtLeast(100) / 1000f)
        player.onUpdateAbilities()
    }

    @Volatile var lastStart = Start.P3
        private set

    fun start(what: Start, practice: Boolean = false) = Sim.asPlayer(null) { startAll(what, practice) }

    private fun startAll(what: Start, practice: Boolean) {
        if (Sim.players().isEmpty()) return
        if (!practice) Practice.exit()
        lastStart = what
        stop()
        PartyClient.send("termStart", 0)
        later.clear()
        Sim.clearEntities()
        Party.clear()
        Blocks.restoreAll()
        Blocks.prepare(what)
        if (what != Start.P4) Devices.showIdle()
        resetI4Solver()
        SimItems.rebreak()
        Sim.forEachPlayer { pl ->
            setup(pl)
            if (P3Sim.blackCatAtStart && P3Sim.phoenix) { P3Sim.setPhoenix(false); applySpeed(pl) }
            Bows.start()
            Lava.reset()
        }
        Masks.reset()
        refreshPhase = if (Random.nextInt(36) < 21) 18 else 17
        Stats.runStart = if (what == Start.P1) serverTick else -1
        previous = null
        val (split, termsDone) = when (what) {
            Start.P1 -> SplitTracker.MAXOR to 0
            Start.P2 -> SplitTracker.STORM to 0
            Start.P3, Start.S1 -> SplitTracker.TERMS to 0
            Start.S2 -> SplitTracker.TERMS to 1
            Start.S3 -> SplitTracker.TERMS to 2
            Start.S4 -> SplitTracker.TERMS to 3
            Start.CORE -> SplitTracker.GOLDOR to 0
            Start.P4 -> SplitTracker.NECRON to 0
        }
        if (practice) PartyClient.send("splitsStop") else PartyClient.send("splitsStart", termsDone, split)
        Recorder.begin(what.label)
        val p: Phase = when (what) {
            Start.P1 -> P1Maxor()
            Start.P2 -> P2Storm()
            Start.P3 -> StormEnd()
            Start.S1 -> if (practice) GoldorPhase(1) else StormEnd()
            Start.S2 -> GoldorPhase(2)
            Start.S3 -> GoldorPhase(3)
            Start.S4 -> GoldorPhase(4)
            Start.CORE -> GoldorPhase(5)
            Start.P4 -> P4Necron()
        }
        begin(p)
    }

    fun begin(p: Phase) {
        phase?.let { if (it !== p) EngineerClient.safely("p3sim stop ${it.name}") { it.stop() } }
        previous = phase
        phase = p
        p.t = 0
        p.start()
    }

    fun end() {
        Practice.exit()
        stop()
        Sim.clearEntities()
        Blocks.restoreAll()
        Devices.showIdle()
        resetI4Solver()
        Party.clear()
        PartyClient.send("splitsStop")
        PartyClient.send("termStart", 0)
    }

    fun tick(server: MinecraftServer) {
        Sim.clearActor()
        serverTick++
        server.playerList.players.forEach { it.connection.send(ClientboundPingPacket(-(PING_BASE + serverTick))) }
        server.playerList.players.forEach { p -> if (p.foodData.foodLevel != 20) { p.foodData.setFoodLevel(20); p.foodData.setSaturation(5f) } }
        if (later.isNotEmpty()) {
            val due = later.filter { it.at <= serverTick }
            later.removeAll(due.toSet())
            due.forEach { if (it.epoch == epoch) EngineerClient.safely("p3sim ${it.what}") { it.go() } }
        }
        if (timed.isNotEmpty()) {
            val nowNs = System.nanoTime()
            val due = timed.filter { it.dueNs <= nowNs }.sortedBy { it.dueNs }
            timed.removeAll(due.toSet())
            due.forEach { if (it.epoch == epoch) EngineerClient.safely("p3sim ${it.what}") { it.go() } }
        }
        val now = System.nanoTime()
        for (pl in Sim.players()) {
            val h = posHistories.getOrPut(pl.uuid) { ArrayDeque() }
            h.addLast(now to pl.position())
            while (h.size > 40) h.removeFirst()
        }
        EngineerClient.safely("p3sim party") { PartyServer.tick() }
        EngineerClient.safely("p3sim blocks") { Blocks.tick() }
        EngineerClient.safely("p3sim terminals") { Terminals.tick() }
        EngineerClient.safely("p3sim items") { SimItems.tick() }
        Sim.forEachPlayer { EngineerClient.safely("p3sim bows") { Bows.tick() } }
        Sim.forEachPlayer { pl -> if (P3Sim.lava) EngineerClient.safely("p3sim lava") { Lava.tick(pl) } }
        val p = phase
        if (p == null) { Recorder.finish(); MovementItems.flushMotion(); return }
        if (p is P4Necron) Recorder.finish()
        EngineerClient.safely("p3sim ${p.name}") { p.tick() }
        p.t++
        if (p !is P4Necron) EngineerClient.safely("p3sim recorder") { Recorder.tick(serverTick) }
        MovementItems.flushMotion()
    }
}
