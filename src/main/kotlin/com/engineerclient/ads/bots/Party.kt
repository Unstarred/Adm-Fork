package com.engineerclient.ads.bots

import com.engineerclient.ads.bots.BotBody.place
import com.engineerclient.ads.bots.BotBody.profile
import com.engineerclient.ads.bots.BotBody.restingItem
import com.engineerclient.ads.bots.BotBody.setHeld
import com.engineerclient.ads.bots.BotBody.spawn
import com.engineerclient.ads.bots.BotBody.swing
import com.engineerclient.ads.bots.BotLegs.Legs
import com.engineerclient.ads.bots.BotLegs.leadFrame
import com.engineerclient.ads.bots.BotLegs.legLead
import com.engineerclient.ads.bots.BotLegs.legTick
import com.engineerclient.ads.bots.BotLegs.legsFor
import com.engineerclient.ads.bots.BotMoves.WALL
import com.engineerclient.ads.bots.BotMoves.arrive
import com.engineerclient.ads.bots.BotMoves.go
import com.engineerclient.ads.bots.BotMoves.ground
import com.engineerclient.ads.bots.BotMoves.leapTo
import com.engineerclient.ads.bots.BotMoves.move
import com.engineerclient.ads.bots.DeviceBots.i4Bot
import com.engineerclient.ads.bots.DeviceBots.ssBot
import com.engineerclient.ads.bots.GhostBots.GhostBody
import com.engineerclient.ads.bots.GhostBots.GhostWorld
import com.engineerclient.ads.bots.GhostBots.trackYou
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CommandBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt
import kotlin.random.Random
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.arena.Blocks
import com.engineerclient.ads.arena.Spots
import com.engineerclient.ads.arena.Station
import com.engineerclient.ads.core.Recorder
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.fight.P1Maxor
import com.engineerclient.ads.fight.P2Storm
import com.engineerclient.ads.party.Players
import com.engineerclient.ads.plan.P3Plan
import com.engineerclient.ads.plan.Practice
import com.engineerclient.ads.plan.Roles

object Party {
    val CLASSES by lazy { listOf(DungeonClass.HEALER, DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.TANK, DungeonClass.MAGE) }

    private const val DIG = 0.6
    private const val CORE_N = 118.0

    class Bot(val clazz: DungeonClass, val slot: Int) {
        val name = Roles.label(clazz)
        var entity: Mannequin? = null
        var pos: Vec3 = Vec3.ZERO
        var yaw = 0f
        var pitch = 0f
        var face: Pair<Float, Float>? = null
        var to: Vec3? = null
        var goAt = 0
        var due = -1
        var working: Station? = null
        var inSection = 0
        var hold = false
        var vy = 0.0
        var blinkReady = 0
        var blinkAt = -100
        var leaptAt = -100
        var heldKey = ""
        var ghost: GhostPlayer? = null
        var lead = 0
        var route: Routes.Route? = null
        var routeTo: Vec3? = null
        var routeT = 0.0
        var routeHeld: String? = null
        var routeHeldUntil = -1
        var dig: ArrayDeque<Vec3>? = null
        var leadTo: Vec3? = null
        var leadFrames: List<GhostRun.Frame>? = null
        var legs: Legs? = null
    }

    class Ee(val into: Int, val label: String, val spot: Vec3, val yaw: Float, val pitch: Float, val owner: DungeonClass?) {
        val byYou get() = owner != null && Players.isPlayerClass(owner)
        val player get() = Players.playerOf(owner)
    }

    class Mate(val name: String, val clazz: DungeonClass, val player: ServerPlayer?, val bot: Bot?) {
        val pos: Vec3 get() = player?.position() ?: bot!!.pos
        val yaw: Float get() = player?.yRot ?: bot!!.yaw
        val pitch: Float get() = player?.xRot ?: bot!!.entity?.xRot ?: bot!!.pitch
        val here: Boolean get() = player != null || bot?.entity != null
        val profile: GameProfile get() = player?.gameProfile ?: profile(bot!!)
    }

    fun mates(viewer: ServerPlayer?): List<Mate> {
        val classless = viewer != null && !Players.hasClass(viewer)
        val mine = viewer?.let { Players.classOf(it) } ?: P3Sim.ownClass
        val all = bots()
        return (if (classless) CLASSES else P3Plan.orderFor(mine)).mapNotNull { c ->
            val pl = Players.playerOf(c)?.takeIf { it !== viewer }
            if (pl != null) Mate(pl.gameProfile.name, c, pl, null)
            else all.firstOrNull { it.clazz == c }?.let { Mate(it.name, c, null, it) }
        }
    }

    fun rosterChanged() {
        bots()
        (Fight.phase as? P1Maxor)?.botsChanged()
        (Fight.phase as? P2Storm)?.botsChanged()
        val ph = Fight.phase as? GoldorPhase ?: return
        if (!planOn || botsFor !== ph) return
        for (c in P3Plan.botOrder()) if (!Players.isPlayerClass(c) && botOf(c)?.entity == null) takeOver(ph, c, null, P3Plan.plan().jobsOf[c].orEmpty().toSet())
        adoptOrphans(ph)
    }

    private var botsFor: GoldorPhase? = null

    private fun adoptOrphans(phase: GoldorPhase) {
        val ghostJobs = bots.flatMap { it.ghost?.jobs.orEmpty() }.toSet()
        val crew = bots.filter { it.ghost == null && it.entity != null && !it.hold }
        if (crew.isEmpty()) return
        val plan = P3Plan.plan()
        for (job in P3Plan.allJobs()) {
            if (Players.isPlayerJob(job) || job in ghostJobs || jobs.any { it.job == job }) continue
            val st = phase.stations.firstOrNull { it.id == job }
            if (st?.done == true) continue
            if (st == null && job.startsWith("gate") && phase.gateIsDown(job.removePrefix("gate ").toIntOrNull() ?: 0)) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < phase.section) continue
            val bot = P3Plan.doer(job)?.let { c -> crew.firstOrNull { it.clazz == c } }
                ?: plan.owners[job]?.firstNotNullOfOrNull { c -> crew.firstOrNull { it.clazz == c } }
                ?: crew.minByOrNull { b -> jobs.count { it.bot === b } } ?: continue
            val (ts, sec) = plan.times[job] ?: (s to 5.0)
            if (ts <= phase.section) jobs += Job(job, bot, phase.section, 0.0).also { it.at = phase.n + 20 } else jobs += Job(job, bot, ts, sec)
            dbg("§e${bot.name}§7 picks up $job (nobody had it)")
            walkOn(bot, phase.n)
        }
    }

    internal fun posOf(c: DungeonClass?): Vec3? = Players.playerOf(c)?.position()
    private val ees = arrayOfNulls<Ee>(7)

    fun eeOf(into: Int): Ee? = ees.getOrNull(into)

    private fun resolveEes() {
        val plan = P3Plan.plan()
        for (into in 2..6) {
            val k = if (into == 6) 5 else into
            val base = P3Plan.earlyEnters.firstOrNull { it.into == into }
            val owner = GhostPlayer.eeOwner(plan.ee[k]?.name, Players.playerClasses().map { it.name }.toSet(),
                bots.filter { it.ghost?.earlyEnters(k) == true }.map { it.clazz.name }, bots.filter { it.ghost != null }.map { it.clazz.name }.toSet())
            val c = CLASSES.firstOrNull { it.name == owner }
            val spot = base?.let { P3Plan.eeSpot(it) }
            val g = bots.firstOrNull { it.clazz == c }?.ghost
            val f = if (into == 6) null else g?.eeSpot(k)
            val js = base?.let { e -> Players.playerOf(c)?.takeIf { !Sim.isHost(it) }?.let { Players.spotOf(it, e.key) } }
            ees[into] = when {
                c == null -> null
                f != null -> Ee(into, base?.label ?: "EE$into", Vec3(f.p.x, f.p.y, f.p.z), f.yaw, f.pitch, c)
                base != null && js != null -> Ee(into, base.label, Vec3(js.x, js.y, js.z), js.yaw, js.pitch, c)
                base != null && spot != null -> Ee(into, base.label, Vec3(spot.x, spot.y, spot.z), spot.yaw, spot.pitch, c)
                else -> null
            }
        }
    }

    internal val bots = ArrayList<Bot>()

    internal var dbgN = 0
    private var dbgS = 0
    internal var curPhase: GoldorPhase? = null

    internal fun dbg(text: String) {
        val sec = (dbgN - sectionN[dbgS.coerceIn(0, 5)]) / 20.0
        val line = "§8[bots S$dbgS %.2f] §7$text".format(sec)
        if (P3Sim.debugBots) Sim.chat(line) else Recorder.event(line)
    }

    fun record(o: JsonObject) {
        fun v(p: Vec3?) = p?.let { JsonArray().apply { add(Recorder.r(it.x)); add(Recorder.r(it.y)); add(Recorder.r(it.z)) } }
        o.add("bots", JsonArray().apply {
            for (b in bots) add(JsonObject().apply {
                addProperty("name", b.name)
                add("pos", v(b.pos))
                b.to?.let { add("to", v(it)); addProperty("goAt", b.goAt); addProperty("due", b.due) }
                addProperty("in", b.inSection)
                if (b.hold) addProperty("hold", true)
                b.working?.let { addProperty("working", it.id) }
                add("jobs", JsonArray().apply { jobs.filter { it.bot === b }.forEach { j -> add("${j.job}@${j.at}") } })
            })
        })
        o.addProperty("ee", "arrived=${eeArrived.toList()} youOn=${youOn.toList()} preleapAt=${preleapAt.toList()} spotBy=${eeSpotBy.toList()}")
        if (leaps.isNotEmpty()) o.addProperty("leaps", leaps.joinToString { "${it.bot.name}@${it.at}" })
    }

    internal fun Vec3.short() = "%.1f %.1f %.1f".format(x, y, z)

    fun bots(): List<Bot> {
        val order = P3Plan.botOrder()
        val want = order.filter { !Players.isPlayerClass(it) }
        if (bots.map { it.clazz } == want) return bots
        val midP3 = botsFor != null && Fight.phase === botsFor
        val live = bots.any { it.entity != null } || midP3
        if (live) {
            val gone = bots.filter { it.clazz !in want }
            for (b in gone) {
                b.entity?.discard(); b.entity = null
                jobs.removeAll { it.bot === b }; leaps.removeAll { it.bot === b }
            }
            bots.removeAll(gone.toSet())
            if (bots.map { it.clazz }.toSet() == want.toSet() || bots.any { it.entity != null } || midP3) return bots
        }
        clear(); bots.clear(); want.forEach { c -> bots += Bot(c, order.indexOf(c) + 1) }
        return bots
    }

    fun takeOver(phase: GoldorPhase, c: DungeonClass, at: Vec3?, theirs: Set<String>) {
        if (!planOn || botsFor !== phase || Players.isPlayerClass(c)) return
        val had = botOf(c)
        if (had?.entity != null) return
        val order = P3Plan.botOrder()
        val b = had ?: Bot(c, order.indexOf(c) + 1).also { bots += it; bots.sortBy { b -> order.indexOf(b.clazz) } }
        b.inSection = phase.section
        for (into in 2..5) if (eeOf(into)?.owner == c) {
            if (into <= phase.section || youArrived[into]) {
                eeArrived[into] = true
                if (eeArrivedN[into] < 0) eeArrivedN[into] = phase.n
            }
            if (into <= phase.section) released[into] = true
        }
        val plan = P3Plan.plan()
        val others = Sim.players().filter { Players.classOf(it) != c }
        val todo = theirs.filter { j -> others.none { j in Players.jobsOf(it) } && jobs.none { it.job == j } && phase.stations.firstOrNull { it.id == j }?.done != true }
        put(b, at ?: todo.firstOrNull { (phase.stations.firstOrNull { s -> s.id == it }?.section ?: 0) == phase.section }?.let { spotOf(it) } ?: Sim.host?.position() ?: CORE_SPOT)
        for (job in todo) {
            val st = phase.stations.firstOrNull { it.id == job }
            if (st == null && job.startsWith("gate") && phase.gateIsDown(job.removePrefix("gate ").toIntOrNull() ?: 0)) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < phase.section) continue
            val (ts, sec) = plan.times[job] ?: (s to 5.0)
            if (ts <= phase.section) jobs += Job(job, b, phase.section, 0.0).also { it.at = phase.n + 20 }
            else jobs += Job(job, b, ts, sec)
        }
        dbg("§e${b.name}§7 takes over the ${c.name}'s jobs: ${jobs.filter { it.bot === b }.joinToString { it.job }}")
        if (coreIn) { b.inSection = 5; go(b, CORE_SPOT.add(0.0, 0.0, 2.0), phase.n, -1, "the core") } else walkOn(b, phase.n)
    }

    fun bot(slot: Int) = bots().getOrNull(slot - 1)
    internal fun botOf(c: DungeonClass?) = bots.firstOrNull { it.clazz == c }

    internal var generation = 0

    fun clear() {
        generation++
        jobs.clear(); leaps.clear()
        bots.forEach {
            it.entity?.discard(); it.entity = null; it.to = null; it.due = -1; it.working = null; it.hold = false; it.inSection = 0
            it.vy = 0.0; it.blinkReady = 0; it.blinkAt = -100; it.leaptAt = -100; it.heldKey = ""; it.ghost = null
            it.route = null; it.routeTo = null; it.routeHeld = null; it.routeHeldUntil = -1; it.leadTo = null; it.leadFrames = null; it.legs = null
        }
        leadIn = false
    }

    internal var leadIn = false

    fun startLeadIn(ticks: Int) {
        if (!botsOn) return
        leadIn = true
        resolveEes()
        val plan = P3Plan.plan()
        for (b in bots()) {
            val dev = earlyDevice(b, plan)
            if (dev != null) { b.pos = STANDS.getValue(dev); Spots.startLook(dev).let { b.yaw = it.first; b.pitch = it.second }; place(b); continue }
            if (P3Plan.ghostOn(b.clazz) && GhostStore.best(P3Plan.skillName(), b.clazz.name) != null) continue
            val lead = if (P3Sim.realMoves) legLead(b.clazz.name) else null
            if (lead != null) {
                b.leadFrames = lead; b.leadTo = lead.last().p.vec()
                leadFrame(b, 0)
                dbg("§e${b.name}§7 lead-in as yours: ${"%.1f %.1f %.1f".format(java.util.Locale.ROOT, b.leadTo!!.x, b.leadTo!!.y, b.leadTo!!.z)}")
                continue
            }
            val to = leadInTarget(b, plan) ?: continue
            b.leadTo = to
            go(b, to, 0, ticks, "where it starts P3 (lead-in)")
        }
    }

    internal var i4Shot = 0
    internal var ssClick = 0

    fun tickLeadIn(t: Int) { if (leadIn) for (b in bots) { if (b.leadFrames != null) leadFrame(b, t) else { move(b, t); hold(b, t) } } }

    private val EARLY_DEVICES get() = setOf("S3 Arrows", "S4 Target")

    private fun earlyDevice(b: Bot, plan: Roles.Plan): String? {
        fun time(j: String) = plan.times[j] ?: (sectionOf(j) to 5.0)
        val mine = P3Plan.allJobs().filter { j -> !Players.isPlayerJob(j) &&
            (P3Plan.doer(j)?.takeIf { !Players.isPlayerClass(it) } ?: plan.owners[j]?.firstOrNull()) == b.clazz }
        return mine.minWithOrNull(compareBy({ time(it).first }, { time(it).second }))?.takeIf { it in EARLY_DEVICES && time(it).first == 1 }
    }

    private fun leadInTarget(b: Bot, plan: Roles.Plan): Vec3? {
        val c = b.clazz
        if (P3Plan.ghostOn(c) && GhostStore.best(P3Plan.skillName(), c.name) != null) return null
        fun time(j: String) = plan.times[j] ?: (sectionOf(j) to 5.0)
        val mine = P3Plan.allJobs().filter { j -> !Players.isPlayerJob(j) &&
            (P3Plan.doer(j)?.takeIf { !Players.isPlayerClass(it) } ?: plan.owners[j]?.firstOrNull()) == c }
        for (k in 2..4) {
            val ee = eeOf(k) ?: continue
            if (ee.owner == c && !ee.byYou && mine.none { time(it).first < k }) return ee.spot
        }
        val first = mine.filter { time(it).first == 1 }.minWithOrNull(compareBy({ time(it).second }, { it })) ?: return null
        if (first in EARLY_DEVICES) return null
        return spotOf(first)
    }

    fun busyAt(st: Station) = bots.any { it.working === st }

    fun allIn(box: AABB) = !botsOn || bots.all { it.entity == null || box.contains(it.pos) }

    internal class Job(val job: String, val bot: Bot, val timeSection: Int, var sec: Double) { var at = -1 }

    internal val jobs = ArrayList<Job>()
    private class Leap(val bot: Bot, val at: Int, val youAt: Vec3? = null, val onto: () -> Target?)
    internal class Target(val pos: Vec3, val name: String? = null, val yaw: Float? = null)
    internal fun Bot.asTarget() = Target(pos, name, yaw)
    internal fun ServerPlayer.asTarget() = Target(position(), gameProfile.name, yRot)
    private val leaps = ArrayList<Leap>()
    internal val ontoLeaps = ArrayList<Roles.Move>()
    private var planned = 0
    private val youArrived = BooleanArray(6)
    internal val eeArrived = BooleanArray(6)
    private val preleapAt = IntArray(6)
    private val eeSpotBy = IntArray(6)
    private val holdJob = arrayOfNulls<String>(6)
    private val waitsFor = arrayOfNulls<List<String>>(6)
    private val youOn = Array(6) { HashSet<DungeonClass>() }
    private val holdNoted = BooleanArray(6)
    internal val released = BooleanArray(6)
    internal val readyAt = IntArray(6) { -1 }
    private const val RELEASE_DELAY = 6
    private const val IN_GRACE = 40
    private var lastLeap = 0
    internal val sectionN = IntArray(6)
    internal val eeArrivedN = IntArray(6) { -1 }
    internal val youArrivedN = IntArray(6) { -1 }
    internal val youLeftN = IntArray(6) { -1 }
    internal var recoredN = -1
    internal var youInCoreN = -1
    private var coreEeLeaps = false

    internal val botsOn get() = P3Sim.bots && !Practice.active
    private val planOn get() = !Practice.active

    private fun put(b: Bot, at: Vec3) { if (P3Sim.bots) spawn(b, at) else { b.pos = at; b.entity = null } }

    fun startP3(phase: GoldorPhase) {
        val walked = if (leadIn && phase.arrived && phase.from == 1) bots.filter { it.leadTo != null }.associate { it.clazz to (it.pos to it.leadTo!!) } else emptyMap()
        clear()
        youArrived.fill(false); eeArrived.fill(false); preleapAt.fill(-1); eeSpotBy.fill(-1); holdJob.fill(null); waitsFor.fill(null); youOn.forEach { it.clear() }; holdNoted.fill(false); released.fill(false); readyAt.fill(-1); coreIn = false
        allInAt.fill(-1)
        eeArrivedN.fill(-1); youArrivedN.fill(-1); youLeftN.fill(-1); recoredN = -1; youInCoreN = -1; coreEeLeaps = false; i4Shot = 0; ssClick = 0
        ontoLeaps.clear()
        planned = 0
        lastLeap = 0
        if (planOn) { bots(); if (botsOn && phase.from == 1) startGhosts(phase) }
        botsFor = if (planOn) phase else null
        resolveEes()
        if (!planOn) return
        if (P3Sim.realMoves) Routes.preload()
        val from = phase.from.coerceIn(1, 5)
        for (i in 0..from) eeArrived[i] = true
        val plan = P3Plan.plan()
        val ghostJobs = bots.flatMap { it.ghost?.jobs.orEmpty() }.toSet()
        val crew = bots.filter { it.ghost == null }
        for (job in P3Plan.allJobs()) {
            if (Players.isPlayerJob(job) || job in ghostJobs) continue
            val st = phase.stations.firstOrNull { it.id == job }
            if (st?.done == true) continue
            if (st == null && job.startsWith("gate") && phase.gateIsDown(job.removePrefix("gate ").toIntOrNull() ?: 0)) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < from) continue
            var (ts, sec) = plan.times[job] ?: (s to 5.0)
            val bot = P3Plan.doer(job)?.let { c -> botOf(c)?.takeIf { it.ghost == null } }
                ?: plan.owners[job]?.firstNotNullOfOrNull { c -> botOf(c)?.takeIf { it.ghost == null } }
                ?: crew.minByOrNull { b -> jobs.count { it.bot === b && it.timeSection == ts } } ?: continue
            if (P3Plan.skill == P3Plan.RANDOM) sec = P3Plan.botMin + Random.nextDouble() * (P3Plan.botMax - P3Plan.botMin).coerceAtLeast(0.0)
            if (ts < from) { ts = from; sec = 0.5 }
            jobs += Job(job, bot, ts, sec)
        }
        spread(plan)
        helperJobs(phase, from, plan)
        placeBots(phase, from, walked)
    }

    private fun helperJobs(phase: GoldorPhase, from: Int, plan: Roles.Plan) {
        if (P3Plan.helper) for (job in P3Plan.allJobs()) {
            if (!P3Plan.isMine(job) || !P3Plan.isStacked(job)) continue
            val st = phase.stations.firstOrNull { it.id == job }
            if (st?.done == true) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < from) continue
            val bot = P3Plan.helperOf(job)?.let { botOf(it) }?.takeIf { it.ghost == null } ?: continue
            var (ts, sec) = plan.times[job] ?: (s to 5.0)
            if (ts < from) { ts = from; sec = 0.5 }
            jobs += Job(job, bot, ts, sec)
            dbg("helper: §e${bot.name}§7 does your stacked $job too, by ${sec}s into S$ts")
        }
        if (P3Plan.helper) for (pl in Sim.players()) {
            if (Sim.isHost(pl)) continue
            val theirs = Players.jobsOf(pl)
            for ((job, c) in Players.profile(pl).stacks) {
                if (job !in theirs) continue
                val st = phase.stations.firstOrNull { it.id == job }
                if (st?.done == true) continue
                val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
                if (s < from) continue
                val bot = botOf(c)?.takeIf { it.ghost == null } ?: continue
                var (ts, sec) = plan.times[job] ?: (s to 5.0)
                if (ts < from) { ts = from; sec = 0.5 }
                jobs += Job(job, bot, ts, sec)
                dbg("helper: §e${bot.name}§7 does ${pl.gameProfile.name}'s stacked $job too, by ${sec}s into S$ts")
            }
        }
        if (P3Plan.helper) for (h in P3Plan.preset().helps) {
            if (h.section < from || Sim.players().none { pl -> Players.jobsOf(pl).let { js -> h.yours in js && h.jobs.all { it in js } } }) continue
            val c = P3Plan.doer(h.by)?.takeIf { !Players.isPlayerClass(it) } ?: continue
            val bot = botOf(c)?.takeIf { it.ghost == null } ?: continue
            for (j in h.jobs) if (phase.stations.firstOrNull { it.id == j }?.done != true) {
                jobs += Job(j, bot, h.section, h.at)
                dbg("helper: §e${bot.name}§7 gets $j by ${h.at}s into S${h.section}")
            }
        }
    }

    private fun placeBots(phase: GoldorPhase, from: Int, walked: Map<DungeonClass, Pair<Vec3, Vec3>>) {
        for (b in bots) {
            b.inSection = from
            b.dig = null
            val g = b.ghost
            if (g != null) { val f = g.run.frame(0); b.yaw = f.yaw; b.pitch = f.pitch; spawn(b, f.p.vec()); g.tick(phase.n); place(b); continue }
            val lg = legsFor(b, from)
            val f = lg?.pieces?.firstOrNull { it.frames.isNotEmpty() }?.frames?.first()
            if (lg != null && f != null) { b.legs = lg; b.yaw = f.yaw; b.pitch = f.pitch; put(b, walked[b.clazz]?.first?.takeIf { it.distanceTo(f.p.vec()) <= 3.0 } ?: f.p.vec()); place(b); continue }
            val core = eeOf(5)?.owner == b.clazz && from >= 3
            if (core) { b.inSection = 5; eeArrived[5] = true; b.hold = from < 5; eeOf(5)?.let { b.yaw = it.yaw; b.pitch = it.pitch } }
            val first = next(b)?.takeIf { sectionOf(it.job) == from }
            val start = when {
                core -> CORE_EE
                from == 1 && next(b)?.let { it.job in EARLY_DEVICES && it.timeSection == 1 } == true -> STANDS.getValue(next(b)!!.job)
                from == 1 && next(b)?.let { it.timeSection == 1 && it.sec <= 1.0 } == true -> spotOf(next(b)!!.job)
                from == 1 -> startPos(1)
                first != null -> spotOf(first.job)
                else -> eeOf(from)?.spot ?: startPos(from)
            }
            val w = walked[b.clazz]
            if (w != null && !core && next(b)?.job !in EARLY_DEVICES) {
                put(b, w.first)
                if (w.first.distanceTo(w.second) > 0.5 && w.second == start) go(b, start, phase.n, phase.n, "where it starts P3 (still on its way)")
            } else put(b, start)
        }
    }

    private fun startGhosts(phase: GoldorPhase) {
        val names = ArrayList<String>()
        for (b in bots) {
            if (!P3Plan.ghostOn(b.clazz)) continue
            val run = GhostStore.best(P3Plan.skillName(), b.clazz.name) ?: continue
            b.ghost = GhostPlayer(run, GhostWorld(b, phase), GhostBody(b, phase))
            names += "§d${b.name}§7 (${"%.2f".format(java.util.Locale.ROOT, run.time / 20.0)}s)"
        }
        if (names.isNotEmpty()) Sim.note("Your best runs play: ${names.joinToString(", ")}.")
    }

    internal fun P.vec() = Vec3(x, y, z)
    internal fun Vec3.p() = P(x, y, z)

    internal fun arrivedOnEe(b: Bot, into: Int, n: Int) {
        eeArrived[into] = true
        if (eeArrivedN[into] < 0) eeArrivedN[into] = n
        GhostCapture.event("ee", b.clazz.name, into)
        val ee = eeOf(into) ?: return
        if (P3Sim.debugBots) dbg("§a${b.name} is on its ${ee.label} spot§7, holding") else Sim.note("§e${b.name}§7 is on ${ee.label}.")
        P3Plan.preset().mines[if (into == 5) "core" else "ee$into"]?.let { blocks ->
            for (pos in blocks) if (!Sim.level.getBlockState(pos).isAir) {
                Sim.level.levelEvent(2001, pos, Block.getId(Sim.level.getBlockState(pos)))
                Blocks.set(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
            }
        }
    }

    private fun spread(plan: Roles.Plan) {
        for (b in bots) for (ts in 1..5) {
            val role = plan.jobsOf[b.clazz].orEmpty()
            val mine = jobs.filter { it.bot === b && it.timeSection == ts }
                .sortedWith(compareBy<Job> { it.sec }.thenBy { role.indexOf(it.job).let { i -> if (i < 0) 99 else i } })
            var prev = 0.0
            var i = 0
            while (i < mine.size) {
                val t = mine[i].sec
                val group = mine.drop(i).takeWhile { it.sec == t }
                group.forEachIndexed { k, j -> j.sec = prev + (t - prev) * (k + 1) / group.size }
                prev = t
                i += group.size
            }
        }
    }

    fun tickP3(phase: GoldorPhase) {
        trackYou(phase)
        if (!planOn) return
        val n = phase.n
        val s = phase.section
        dbgN = n; dbgS = s; curPhase = phase
        if (P3Sim.debugBots && n % 40 == 0) dbgHolds(phase)
        if (s != planned) {
            planned = s; sectionN[s.coerceIn(0, 5)] = n; dbg("§bsection $s starts")
            if (s >= 5) for (b in bots) if (b.legs != null) {
                b.legs = null; b.to = null
                if (b.hold && b === coreBot() && eeArrived[5] && !released[5]) { b.inSection = 5; continue }
                b.hold = false; b.inSection = 4
            }
            sectionStarted(phase, s)
        }
        val beaten = HashSet<Bot>()
        jobs.removeAll { j ->
            (phase.stations.firstOrNull { it.id == j.job }?.done == true || (j.job.startsWith("gate") && phase.gateIsDown(sectionOf(j.job)))).also { if (it) beaten += j.bot }
        }
        beaten.filter { b -> leaps.none { it.bot === b } }.forEach { walkOn(it, n) }
        youAtEarlyEnter(phase)
        dueLeaps(n)
        dueJobs(phase, n, s)
        ghosts(phase)
        earlyEnterBots(phase)
        preleaps(phase)
        ontoLeapsTick(phase)
        releaseEarlyEnterers(phase)
        recoreAfterJobs(phase)
        intoCoreWhenRecored(phase)
        for (b in bots) when { b.legs != null -> legTick(b, phase); b.ghost == null -> move(b, n); else -> place(b) }
        i4Bot(phase)
        ssBot(phase)
        for (b in bots) {
            if (b.ghost != null) continue
            val was = b.working
            b.working = next(b)?.takeIf { it.at >= 0 && it.at - n <= 40 && b.pos.distanceTo(spotOf(it.job)) <= 2.5 }?.let { j -> phase.stations.firstOrNull { it.id == j.job && it.kind == Station.Kind.TERMINAL } }
            if (b.working != null && b.working !== was) swing(b)
            if (b.legs == null) hold(b, n)
        }
    }

    private fun dueLeaps(n: Int) {
        leaps.filter { n >= it.at }.forEach { l ->
            if (l.youAt != null && Sim.players().none { it.position().distanceTo(l.youAt) <= 3.0 }) return@forEach
            leaps.remove(l)
            if (l.bot.legs != null) return@forEach
            l.onto()?.let { dbg("§e${l.bot.name}§7 leaps (${it.pos.short()})"); leapTo(l.bot, it, n); walkOn(l.bot, n) }
        }
    }

    private fun dueJobs(phase: GoldorPhase, n: Int, s: Int) {
        val held = holding(phase)
        val finished = ArrayList<Bot>()
        jobs.removeAll { j ->
            if (j.at < 0 || n < j.at) return@removeAll false
            if (!inReach(j.bot, j.job) && j.bot.legs == null) {
                dbg("§e${j.bot.name}§7 wasn't at ${j.job} on time: put there")
                j.bot.hold = false; j.bot.dig = null
                arrive(j.bot, spotOf(j.job))
            }
            val st = phase.stations.firstOrNull { it.id == j.job }
            if (st != null) {
                if (st.section == s && held && phase.stations.count { it.section == s && !it.done } == 1) return@removeAll false
                if (st.kind != Station.Kind.TERMINAL) swing(j.bot)
                if (st.kind == Station.Kind.LEVER) phase.pullLever(st, j.bot.name) else st.complete(j.bot.name)
                if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
                if (!st.done) return@removeAll false
            } else {
                val k = sectionOf(j.job)
                if (!phase.gateIsDown(k)) { swing(j.bot); if (!phase.blowGate(k, j.bot.name)) return@removeAll false }
            }
            dbg("§e${j.bot.name}§7 did ${j.job}")
            j.bot.working = null
            finished += j.bot
            true
        }
        finished.forEach { walkOn(it, n) }
    }

    private fun ghosts(phase: GoldorPhase) {
        val n = phase.n
        for (b in bots) {
            val g = b.ghost ?: continue
            g.tick(n)
            if (g.finished && phase.section >= 5 && !GoldorPhase.CORE_BOX.contains(b.pos)) {
                b.ghost = null; b.hold = false; b.inSection = 5
                go(b, CORE_SPOT, n, -1, "the core (its run ended outside)")
            }
        }
        if (phase.section >= 5 && !coreEeLeaps && !coreIn) coreBot()?.takeIf { it.ghost != null && eeArrived[5] && !released[5] }?.let { leapOntoCoreEe(it, n) }
    }

    private fun sectionStarted(phase: GoldorPhase, s: Int) {
        val n = phase.n
        val plan = P3Plan.plan()
        jobs.filter { it.timeSection == s }.forEach { it.at = n + (it.sec * 20).roundToInt() }
        for (m in plan.moves.filter { it.section == s }) {
            val at = m.at?.let { n + (it * 20).roundToInt() }
            when (m.kind) {
                "spot" -> into(m.who)?.let { if (at != null) eeSpotBy[it] = at }
                "preleap" -> into(m.who)?.let { if (at != null) preleapAt[it] = at }
                "hold" -> holdJob[s + 1] = "S${s + 1} T${m.who}"
                "waits" -> into(m.who)?.let { waitsFor[it] = m.args }
                "onto" -> ontoLeaps += m
                "leaps" -> botOf(Roles.whoIs(plan, m.who))?.takeIf { it.ghost == null }?.let { b ->
                    if (at != null) leaps += Leap(b, at) {
                        bots.firstOrNull { it !== b && it.inSection == s && busy(it) }?.asTarget()
                            ?: (Sim.players().firstOrNull { GoldorPhase.dtZone(it.position()) == s } ?: Sim.host)?.asTarget()
                    }
                }
            }
        }
        if (s >= 5) { core(phase); return }
        val ee = eeOf(s)
        val eeBot = ee?.takeIf { !it.byYou && s > phase.from }?.let { botOf(it.owner) }
        if (eeBot != null && !eeArrived[s]) dbg("§e${eeBot.name}§7 isn't on its ${ee.label} spot yet: going on there, the others leap to it")
        val coreHold = if (s == 4 && ee == null) coreBot()?.takeIf { it.ghost == null && eeArrived[5] && it.hold } else null
        val coreYou = s == 4 && ee == null && eeOf(5)?.let { it.byYou && it.player?.position()?.let { p -> p.distanceTo(it.spot) <= 3.0 } == true } == true
        val eeYou = ee?.takeIf { it.byYou && s > phase.from && it.player?.position()?.let { p -> p.distanceTo(it.spot) <= 3.0 } == true }
        val onto = eeBot ?: coreHold
        var i = 0
        for (b in bots) {
            if (b.ghost != null || b.legs != null) continue
            if (b.hold && b.inSection > s && eeArrived[b.inSection.coerceAtMost(5)] && !released[b.inSection.coerceAtMost(5)]) continue
            if (b !== onto && onWayToCore(b)) continue
            if (b.hold && b !== onto) dbg("§e${b.name}§7 stops holding (section $s started)")
            b.hold = false
            if (b === onto) { b.hold = eeArrived[s] && !released[s]; continue }
            if (b.inSection >= s) { walkOn(b, n); continue }
            if (s == 3 && eeOf(4)?.let { !it.byYou && it.owner == b.clazz } == true && jobs.none { it.bot === b && sectionOf(it.job) <= 3 }) { b.inSection = s; continue }
            if (ontoFor(b.clazz, s) != null) continue
            b.inSection = s
            if (onto != null) leaps += Leap(b, n + 2 + gapTicks() * i++) { onto.asTarget() }
            else if (eeYou != null) leaps += Leap(b, n + 2 + gapTicks() * i++, eeYou.spot) { eeYou.player?.asTarget() }
            else if (coreYou) leaps += Leap(b, n + 2 + gapTicks() * i++) { eeOf(5)?.player?.asTarget() }
            else walkOn(b, n)
        }
    }

    private fun lateForEe(b: Bot, s: Int): Boolean {
        if (s > 4 || eeArrived[s]) return false
        val ee = eeOf(s)?.takeIf { !it.byYou } ?: return false
        return botOf(ee.owner) === b && b.ghost == null
    }

    private fun before(j: Job, into: Int) = sectionOf(j.job) < into || j.timeSection < into

    private fun into(token: String) = if (token == "core") 5 else token.removePrefix("ee").toIntOrNull()

    private fun earlyEnterBots(phase: GoldorPhase) {
        val s = phase.section
        val sent = HashSet<Bot>()
        for (into in s..5) {
            if (into == s && (into > 4 || eeArrived[into])) continue
            val ee = eeOf(into) ?: continue
            if (ee.byYou) continue
            val b = botOf(ee.owner)?.takeIf { it !in sent } ?: continue
            sent += b
            if (b.ghost != null || b.legs != null) continue
            if (into > s && (b.inSection >= into || busy(b) || b.hold)) continue
            if (jobs.any { it.bot === b && before(it, if (into == 5) 4 else into) }) continue
            if (b.pos.distanceTo(ee.spot) < 0.5) {
                b.inSection = into; b.hold = true
                b.yaw = ee.yaw; b.pitch = ee.pitch
                arrivedOnEe(b, into, phase.n)
                continue
            }
            if (into == 5 && digCore(b, ee, phase)) continue
            if (b.to != ee.spot) go(b, ee.spot, phase.n, if (P3Sim.realMoves) eeSpotBy[into] else phase.n, "its ${ee.label} spot", ee.yaw to ee.pitch)
        }
    }

    private fun onWayToCore(b: Bot) = eeOf(5)?.let { !it.byYou && it.owner == b.clazz } == true && b.inSection < 5 && jobs.none { it.bot === b && before(it, 4) }

    private fun digCore(b: Bot, ee: Ee, phase: GoldorPhase): Boolean {
        if (b.dig != null) return true
        val zone = GoldorPhase.dtZone(b.pos)
        if (!(zone == 1 || zone == 2 || (zone == -1 && b.pos.z > CORE_N))) return false
        val wall = Vec3(54.5, ground(54.5, CORE_N + 3.0, 125.0)?.takeIf { it != WALL } ?: 120.0, CORE_N + 3.0)
        val way = listOf(Vec3(54.5, 115.0, CORE_N - 1.0), Vec3(54.5, 115.0, 56.0), ee.spot)
        if (b.pos.distanceTo(wall) > 0.6) {
            val mining = ((wall.distanceTo(way[0]) + way[0].distanceTo(way[1]) + way[1].distanceTo(way[2])) / DIG).toInt()
            val due = if (!P3Sim.realMoves) phase.n else eeSpotBy[5].takeIf { it >= 0 }?.let { it - mining } ?: -1
            if (b.to != wall) go(b, wall, phase.n, due, "the core's S2 wall (mining in to ${ee.label})")
            return true
        }
        b.to = null; b.face = null
        b.dig = ArrayDeque(way)
        dbg("§e${b.name}§7 mines into the core from S2, through it and out of its door to its ${ee.label} spot")
        return true
    }

    internal fun digOn(b: Bot, way: ArrayDeque<Vec3>, n: Int) {
        var left = DIG * (if (b.due in 0..n) 2.0 else 1.0)
        while (left > 1e-6 && way.isNotEmpty()) {
            val d = way.first().subtract(b.pos)
            val len = d.length()
            if (Math.hypot(d.x, d.z) > 0.01) { b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat(); b.pitch = 10f }
            if (len <= left) { b.pos = way.removeFirst(); left -= len } else { b.pos = b.pos.add(d.scale(left / len)); left = 0.0 }
        }
        b.vy = 0.0
        var broke = false
        val level = Sim.level
        for (bx in Math.floor(b.pos.x - 0.3).toInt()..Math.floor(b.pos.x + 0.3).toInt())
            for (by in Math.floor(b.pos.y).toInt()..Math.floor(b.pos.y + 1.79).toInt())
                for (bz in Math.floor(b.pos.z - 0.3).toInt()..Math.floor(b.pos.z + 0.3).toInt()) {
                    val pos = BlockPos(bx, by, bz)
                    val s = level.getBlockState(pos)
                    if (s.isAir || s.getDestroySpeed(level, pos) < 0 || s.`is`(net.minecraft.world.level.block.Blocks.BARRIER) ||
                        s.`is`(net.minecraft.world.level.block.Blocks.BEDROCK) || s.block is CommandBlock) continue
                    level.levelEvent(2001, pos, Block.getId(s))
                    Blocks.set(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
                    broke = true
                    Fight.later(Math.round(P3Sim.breakerRegen * 20).toInt(), "bot dig regen") {
                        if (level.getBlockState(pos).isAir && (Fight.phase as? GoldorPhase)?.let { it.section < 5 } == true) Blocks.set(pos, s)
                    }
                }
        if (broke) swing(b)
        if (way.isEmpty()) { b.dig = null; b.face?.let { b.yaw = it.first; b.pitch = it.second } }
    }

    private fun preleaps(phase: GoldorPhase) {
        val n = phase.n
        val into = phase.section + 1
        if (into > 5) return
        val ee = eeOf(into) ?: return
        val eeBot = if (ee.byYou) null else botOf(ee.owner)
        val open = if (ee.byYou) youArrived[into] else eeBot != null && eeArrived[into] && eeBot.dig == null && (into < 5 || eeBot.hold) &&
            n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)
        if (!open) return
        val target: () -> Target? = if (eeBot != null) ({ eeBot.asTarget() }) else ({ ee.player?.asTarget() })
        for (b in bots) {
            if (b === eeBot || b.ghost != null || b.inSection >= into || busy(b) || onWayToCore(b) || ontoFor(b.clazz, into) != null) continue
            b.inSection = into
            lastLeap = maxOf(n + 1, lastLeap + if (eeBot != null) 2 else gapTicks())
            if (holdJob[into] != null && jobs.any { it.bot === b && it.job == holdJob[into] }) b.hold = true
            leaps += Leap(b, lastLeap, if (ee.byYou) ee.spot else null, target)
        }
    }

    private fun ontoFor(c: DungeonClass, into: Int) = ontoLeaps.firstOrNull { it.section + 1 == into && whoFor(it.who, into) == c }

    internal fun ontoTarget(m: Roles.Move): Target? {
        val into = m.section + 1
        val t = m.args.firstOrNull() ?: return null
        val c = whoFor(t, into) ?: return null
        val stand = spotOf("S$into T$t")
        Players.playerOf(c)?.let { return if (it.position().distanceTo(stand) <= 3.0) it.asTarget() else null }
        return botOf(c)?.takeIf { it.pos.distanceTo(stand) <= 3.0 }?.asTarget()
    }

    private fun ontoLeapsTick(phase: GoldorPhase) {
        for (m in ontoLeaps) {
            val into = m.section + 1
            val b = botOf(whoFor(m.who, into))?.takeIf { it.ghost == null && it.legs == null && it.inSection < into } ?: continue
            if (leaps.any { it.bot === b } || jobs.any { it.bot === b && sectionOf(it.job) < into }) continue
            val t = ontoTarget(m) ?: continue
            b.inSection = into
            b.hold = false
            leaps += Leap(b, phase.n + 1) { ontoTarget(m) ?: t }
            dbg("§e${b.name}§7 leaps onto ${t.name} at S$into T${m.args.first()}")
        }
    }

    private fun releaseEarlyEnterers(phase: GoldorPhase) {
        val n = phase.n
        val s = phase.section
        for (into in s..(s + 1).coerceAtMost(5)) {
            val b = eeOf(into)?.takeIf { !it.byYou }?.let { botOf(it.owner) } ?: continue
            if (!b.hold || !eeArrived[into] || released[into]) continue
            for (pl in Sim.players()) {
                val c = Players.classOf(pl)
                if (c !in youOn[into] && pl.position().distanceTo(b.pos) < 3.0) { youOn[into] += c; dbg("§a${pl.gameProfile.name} is on ${b.name}§7 (EE$into)") }
            }
            if (into == 5 && s == 4) for (o in bots) {
                if (o === b || o.ghost != null || o.legs != null || o.clazz in youOn[5] || o.pos.distanceTo(b.pos) >= 3.0) continue
                youOn[5] += o.clazz; dbg("§a${o.name} is on ${b.name}§7 (EE5)")
            }
        }
        for (into in s..(s + 1).coerceAtMost(5)) {
            val ee = eeOf(into)?.takeIf { !it.byYou } ?: continue
            val b = botOf(ee.owner) ?: continue
            if (released[into] || !b.hold || !eeArrived[into] || b.inSection < into) continue
            val waits = waitsFor[into]
            val players = Players.playerClasses().filter { it != b.clazz }
            val needed = if (waits == null) players else waits.mapNotNull { whoFor(it, into) }.filter { it in players }
            fun there(c: DungeonClass?) = leapt(c, b, into) || into == s && inSectionNow(c, into)
            val ok = when {
                into == s && n - sectionN[s] >= 600 -> true
                needed.any { !there(it) } -> { allInAt[into] = -1; false }
                needed.any { !leapt(it, b, into) } -> {
                    if (allInAt[into] < 0) { allInAt[into] = n; dbg("§e${b.name}§7: everyone's in S$into, not all by EE$into: moving on in ${IN_GRACE / 20.0}s") }
                    n - allInAt[into] >= IN_GRACE
                }
                into == s && n - sectionN[s] >= 200 -> true
                waits != null -> waits.all { t -> there(whoFor(t, into)) }
                else -> (into == s || preleapOpen(into, n)) &&
                    bots.all { it === b || there(it.clazz) } && players.all { there(it) }
            }
            if (!ok) { readyAt[into] = -1; continue }
            if (readyAt[into] < 0) { readyAt[into] = n; dbg("§e${b.name}§7: everyone's on EE$into${if (b.ghost == null) ", moving in ${RELEASE_DELAY / 20.0}s" else ""}") }
            if (b.ghost != null) continue
            if (n - readyAt[into] < RELEASE_DELAY) continue
            released[into] = true
            dbg("§c${b.name} moves on from EE$into§7: ${waitStatus(b, into)}${if (into == s) ", ${(n - sectionN[s]) / 20.0}s into S$s" else ""}")
            val recore = if (into == 5) eeOf(6) else null
            if (recore != null && jobs.none { it.bot === b }) toRecore(b, recore, s, n)
            else { b.hold = false; walkOn(b, n) }
        }
    }

    private fun toRecore(b: Bot, recore: Ee, s: Int, n: Int) {
        b.hold = true
        if (s >= 5) { go(b, recore.spot, n, -1, "the recore (in the core)", recore.yaw to recore.pitch); return }
        b.to = null; b.due = -1; b.face = recore.yaw to recore.pitch
        b.dig = ArrayDeque(listOf(Vec3(54.5, 115.0, 56.0), recore.spot))
        dbg("§e${b.name}§7 mines back into the core to the recore (${recore.spot.short()})")
    }

    private fun recoreAfterJobs(phase: GoldorPhase) {
        if (coreIn || phase.section < 4) return
        val b = coreBot()?.takeIf { it.ghost == null && it.legs == null && eeArrived[5] && !it.hold && it.to == null && it.dig == null } ?: return
        if (jobs.any { it.bot === b } || leaps.any { it.bot === b }) return
        val recore = eeOf(6) ?: return
        released[5] = true
        toRecore(b, recore, phase.section, phase.n)
    }

    private val allInAt = IntArray(7) { -1 }

    private fun inSectionNow(c: DungeonClass?, into: Int): Boolean {
        val p = posOf(c) ?: botOf(c)?.takeIf { it.entity != null }?.pos ?: return false
        if (into >= 5) return GoldorPhase.CORE_BOX.contains(p)
        return GoldorPhase.dtZone(p) >= into
    }

    private fun waitStatus(b: Bot, into: Int): String {
        val waits = waitsFor[into]
        val who = (waits?.mapNotNull { whoFor(it, into) } ?: bots.filter { it !== b }.map { it.clazz }) + Players.playerClasses()
        return who.distinct().filter { it != b.clazz }.joinToString(" ") { c ->
            val name = Players.playerOf(c)?.gameProfile?.name ?: Roles.label(c)
            if (leapt(c, b, into)) "§a+$name§7" else "§c-$name§7"
        } + if (waits != null) " (list: ${waits.joinToString(" ")})" else " (everyone)"
    }

    private fun dbgHolds(phase: GoldorPhase) {
        for (b in bots) {
            val ee = (1..5).firstOrNull { eeOf(it)?.owner == b.clazz }
            val state = when {
                b.ghost != null -> "§dghost§7: ${b.ghost!!.status}"
                b.hold -> "§6holding§7 (EE$ee: ${ee?.let { waitStatus(b, it) } ?: "?"})"
                b.to != null -> "moving to ${b.to!!.short()}"
                else -> "standing"
            }
            dbg("§e${b.name}§7 in S${b.inSection}: $state; next ${next(b)?.job ?: "none"}")
        }
    }

    internal fun whoFor(token: String, into: Int): DungeonClass? =
        if (token.all { it.isDigit() }) "S$into T$token".let { j -> Players.playerDoing(j)?.let(Players::classOf) ?: P3Plan.doer(j)?.takeIf { !Players.isPlayerClass(it) } ?: P3Plan.roleDoerBot(j) }
        else eeOf(into(token) ?: return null)?.owner

    private fun leapt(c: DungeonClass?, onto: Bot, into: Int): Boolean = when {
        c == null || c == onto.clazz -> true
        Players.isPlayerClass(c) -> c in youOn[into]
        c in youOn[into] -> true
        botOf(c)?.legs != null -> botOf(c)!!.legs!!.let { onto.clazz.name in it.leaptOnto || !it.expectsLeap(onto.clazz.name) }
        else -> botOf(c)?.let { o ->
            val g = o.ghost
            if (g != null) o.pos.distanceTo(onto.pos) < 3.0 || g.leaptOnto(onto.clazz.name, eeArrivedN[into].coerceAtLeast(0)) || !g.expectsLeap(onto.clazz.name, into)
            else o.inSection >= into && leaps.none { l -> l.bot === o }
        } ?: true
    }

    private fun preleapOpen(into: Int, n: Int) = n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)

    private fun youAtEarlyEnter(phase: GoldorPhase) {
        val into = phase.section + 1
        if (into > 4) return
        val ee = eeOf(into)?.takeIf { it.byYou } ?: return
        if (youArrived[into]) return
        val p = ee.player ?: return
        if (p.position().distanceTo(ee.spot) > 3.0) return
        youArrived[into] = true
        Sim.asPlayer(p) { Sim.note("§aAt your ${ee.label}§7: the party leaps to you.") }
    }

    internal fun holding(phase: GoldorPhase): Boolean {
        if (!P3Plan.waitForYou) return false
        val ee = eeOf(phase.section + 1)?.takeIf { it.byYou && it.into <= 4 } ?: return false
        if (youArrived[ee.into]) return false
        if (!holdNoted[ee.into] && phase.stations.count { it.section == phase.section && !it.done } == 1) {
            holdNoted[ee.into] = true
            Sim.asPlayer(ee.player) { Sim.note("§7The party holds S${phase.section}'s last job until you're at your §e${ee.label}§7 spot (Wait for you, in the menu's Early Enters).") }
        }
        return true
    }

    private var coreIn = false

    private fun coreBot() = eeOf(5)?.takeIf { !it.byYou }?.let { botOf(it.owner) }

    private fun core(phase: GoldorPhase) {
        val n = phase.n
        coreIn = false
        val cb = coreBot()
        if (cb != null && eeArrived[5] && !released[5] && (cb.hold || cb.ghost != null)) { leapOntoCoreEe(cb, n); return }
        if (cb?.ghost != null) { if (recoredN >= 0) leapIntoCore(phase); return }
        if (cb == null || !eeArrived[5] || recored(cb)) leapIntoCore(phase)
    }

    private fun leapOntoCoreEe(cb: Bot, n: Int) {
        coreEeLeaps = true
        var i = 0
        for (b in bots) {
            if (b === cb || b.ghost != null) continue
            b.hold = false
            if (b.inSection < 5) { b.inSection = 5; leaps += Leap(b, n + 2 + gapTicks() * i++) { cb.asTarget() } }
        }
        dbg("core open: everyone leaps onto §e${cb.name}§7 by the core first, then it recores")
    }

    private fun intoCoreWhenRecored(phase: GoldorPhase) {
        if (coreIn || phase.section < 5) return
        val cb = coreBot() ?: return
        if (cb.ghost != null) { if (recoredN >= 0 || phase.n - sectionN[5] >= 400) leapIntoCore(phase); return }
        if (released[5] && recored(cb)) {
            if (recoredN < 0) { recoredN = phase.n; GhostCapture.event("recore", cb.clazz.name) }
            leapIntoCore(phase)
        }
    }

    private fun recored(cb: Bot) = cb.hold && cb.to == null && cb.dig == null

    private fun leapIntoCore(phase: GoldorPhase) {
        coreIn = true
        val n = phase.n
        dbg("everyone leaps into the core")
        val ee = eeOf(5)
        val onto: (() -> Target?)? = when {
            ee == null -> null
            ee.byYou -> ee.player?.takeIf { GoldorPhase.CORE_BOX.inflate(4.0).contains(it.position()) }?.let { p -> { p.asTarget() } }
            else -> botOf(ee.owner)?.let { b -> { b.asTarget() } }
        }
        bots.forEachIndexed { i, b ->
            if (b.ghost != null) return@forEachIndexed
            b.hold = false
            b.inSection = 5
            val spot = CORE_SPOT.add((i - 1.5) * 1.5, 0.0, 2.0 + Random.nextDouble())
            if (onto != null && botOf(ee?.owner) !== b) leaps += Leap(b, n + 1 + i * 2) { onto() }
            val gen = generation
            Fight.later(if (onto != null) 12 + i * 2 else 0, "bots into core") { if (gen == generation) go(b, spot, 0, -1, "the core") }
        }
    }

    private fun gapTicks() = (P3Plan.leapGap * 20).toInt().coerceAtLeast(1)

    private fun next(b: Bot, of: (Job) -> Boolean = { true }) = jobs.filter { it.bot === b && of(it) }.minWithOrNull(compareBy<Job> { it.timeSection }.thenBy { it.sec })

    private fun busy(b: Bot) = jobs.any { it.bot === b && it.at >= 0 }

    internal fun walkOn(b: Bot, n: Int) {
        if (b.ghost != null || b.hold) return
        val j = (if (lateForEe(b, planned)) next(b) { before(it, planned) } else next(b)) ?: return
        if (j.job.startsWith("gate") && inReach(b, j.job)) return
        if (j.at >= 0 || sectionOf(j.job) <= b.inSection) go(b, spotOf(j.job), n, j.at, "${j.job}${if (j.at < 0) " (its section not started)" else ""}", presetStand(j.job)?.let { it.yaw to it.pitch }, lead = leadOf(j.job))
    }

    private fun leadOf(job: String) = if (job.length == 5 && job.startsWith("S") && job[3] == 'T') TERMINAL_LEAD else 2
    private const val TERMINAL_LEAD = 40

    private fun inReach(b: Bot, job: String): Boolean {
        val g = job.removePrefix("gate ").toIntOrNull()
        return if (g != null) GoldorPhase.GATE_BOXES.getOrNull(g)?.let { it.distanceToSqr(b.pos) <= 100.0 } ?: false
        else b.pos.distanceTo(spotOf(job)) <= 2.5
    }

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.substring(1, 2).toInt()

    internal fun spotOf(job: String): Vec3 = presetStand(job)?.let { Vec3(it.x, it.y, it.z) } ?: STANDS[job] ?: job.removePrefix("gate ").toIntOrNull()?.let { GATES.getOrNull(it) } ?: CORE_SPOT

    private fun presetStand(job: String): Spots.Spot? = P3Plan.preset().defaults["stand/$job"]

    fun partyLine(b: Bot, text: String) = "§9Party §8> §b[MVP§c+§b] ${b.name}§f: $text"

    private fun hold(b: Bot, n: Int) {
        val key = when {
            leaps.any { it.bot === b && it.at - n in 0..10 } || n - b.leaptAt < 20 -> "leap"
            n < b.routeHeldUntil -> b.routeHeld ?: "breaker"
            n - b.blinkAt < 15 -> "hyperion"
            else -> "breaker"
        }
        setHeld(b, key)
    }

    fun standAt(spots: List<Vec3>) {
        clear()
        if (!botsOn) return
        bots().forEachIndexed { i, b -> spawn(b, spots[i % spots.size]); place(b) }
    }

    fun startP4(fromP3: Boolean) {
        if (!botsOn) return
        leaps.clear(); jobs.clear(); bots.forEach { it.ghost = null }
        val gen = generation
        bots().forEachIndexed { i, b ->
            val spot = P4_SPOTS.getValue(b.clazz)
            val land = {
                if (gen == generation) {
                    if (b.entity == null || b.entity!!.isRemoved) spawn(b, spot)
                    b.pos = spot; b.to = null; b.hold = false; b.yaw = 180f; b.pitch = 0f
                    b.heldKey = ""; setHeld(b, restingItem(b.clazz))
                    place(b)
                }
            }
            if (fromP3 && b.entity?.isRemoved == false) Fight.later(40 + 6 * i, "bot into P4") { land() } else land()
        }
    }

    private val P4_SPOTS = mapOf(
        DungeonClass.MAGE to Vec3(54.5, 64.0, 101.5), DungeonClass.HEALER to Vec3(59.5, 64.0, 111.5),
        DungeonClass.BERSERK to Vec3(50.5, 64.0, 110.5), DungeonClass.ARCHER to Vec3(47.5, 64.0, 107.5),
        DungeonClass.TANK to Vec3(61.5, 64.0, 117.5),
    )

    private fun startPos(from: Int): Vec3 = when (from) {
        1 -> STANDS.getValue("S1 SS").add(Random.nextDouble(-1.5, 0.0), 0.0, Random.nextDouble(-1.5, 1.5))
        2 -> GoldorPhase.GATE_CENTRES[1].add(Random.nextDouble(-2.0, 2.0), -3.0, 3.0)
        3 -> GoldorPhase.GATE_CENTRES[2].add(-3.0, -3.0, Random.nextDouble(-2.0, 2.0))
        else -> STRIP.add(Random.nextDouble(-4.0, 4.0), 0.0, 0.0)
    }

    val STANDS: Map<String, Vec3> = mapOf(
        "S1 T1" to Vec3(110.56, 113.0, 75.07), "S1 T2" to Vec3(108.91, 119.0, 79.53), "S1 T3" to Vec3(92.45, 112.0, 92.74), "S1 T4" to Vec3(92.99, 122.0, 100.87),
        "S1 east lever" to Vec3(106.74, 122.0, 111.7), "S1 west lever" to Vec3(94.46, 122.0, 111.43), "S1 SS" to Vec3(108.3, 120.0, 94.0),
        "S2 T1" to Vec3(69.69, 109.0, 122.4), "S2 T2" to Vec3(59.57, 120.0, 125.24), "S2 T3" to Vec3(45.86, 109.0, 122.43), "S2 T4" to Vec3(39.5, 109.0, 140.53), "S2 T5" to Vec3(39.69, 124.0, 125.53),
        "S2 low lever" to Vec3(27.33, 124.0, 127.68), "S2 high lever" to Vec3(23.3, 132.0, 138.47), "S2 Lights" to Vec3(60.6, 132.0, 139.0),
        "S3 T1" to Vec3(0.89, 109.0, 112.44), "S3 T2" to Vec3(1.0, 119.0, 93.6), "S3 T3" to Vec3(18.7, 121.5, 91.3), "S3 T4" to Vec3(0.45, 109.0, 78.24),
        "S3 west lever" to Vec3(2.44, 122.0, 55.68), "S3 east lever" to Vec3(14.67, 122.0, 55.44), "S3 Arrows" to Vec3(1.40, 120.0, 77.39),
        "S4 T1" to Vec3(41.12, 109.0, 32.96), "S4 T2" to Vec3(43.96, 121.0, 31.14), "S4 T3" to Vec3(66.85, 109.0, 32.04), "S4 T4" to Vec3(72.78, 115.0, 45.45),
        "S4 low lever" to Vec3(83.48, 120.0625, 35.81), "S4 high lever" to Vec3(85.15, 126.0, 44.7), "S4 Target" to Vec3(63.5, 127.0, 35.5),
    )
    val GATES = arrayOf(Vec3.ZERO, Vec3(95.8, 123.9, 121.0), Vec3(19.3, 123.6, 127.9), Vec3(12.4, 116.8, 52.7))
    val STRIP = Vec3(54.6, 115.0, 51.5)
    val CORE_SPOT = Vec3(54.5, 115.0, 58.0)
    private val CORE_EE get() = eeOf(5)?.spot ?: STRIP
}
