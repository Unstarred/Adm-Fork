package com.engineerclient.p3sim

import com.engineerclient.p3sim.BotBody.place
import com.engineerclient.p3sim.BotBody.profile
import com.engineerclient.p3sim.BotBody.restingItem
import com.engineerclient.p3sim.BotBody.setHeld
import com.engineerclient.p3sim.BotBody.spawn
import com.engineerclient.p3sim.BotBody.swing
import com.engineerclient.p3sim.BotLegs.Legs
import com.engineerclient.p3sim.BotLegs.leadFrame
import com.engineerclient.p3sim.BotLegs.legLead
import com.engineerclient.p3sim.BotLegs.legTick
import com.engineerclient.p3sim.BotLegs.legsFor
import com.engineerclient.p3sim.BotMoves.WALL
import com.engineerclient.p3sim.BotMoves.arrive
import com.engineerclient.p3sim.BotMoves.go
import com.engineerclient.p3sim.BotMoves.ground
import com.engineerclient.p3sim.BotMoves.leapTo
import com.engineerclient.p3sim.BotMoves.move
import com.engineerclient.p3sim.DeviceBots.i4Bot
import com.engineerclient.p3sim.DeviceBots.ssBot
import com.engineerclient.p3sim.GhostBots.GhostBody
import com.engineerclient.p3sim.GhostBots.GhostWorld
import com.engineerclient.p3sim.GhostBots.trackYou
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

/**
 * The other four of the party: one bot per class you're not, named after its class, in leap menu
 * slots 1-4 ([P3Plan.botOrder]).
 *
 * In P3 the plan runs ([Roles]): every job that isn't a player's is done at the preset's time, exactly (a bot not
 * there by then is put there; with the Bots setting off nobody's shown and the jobs are still done on time). The bots
 * are only the look of it: the early enterer goes to its spot after its last job, the others pre-leap onto it
 * (onto you once you're at yours) and walk on to their next terminals; a section's last job waits
 * for you if you're early-entering the next one; at the core, everyone leaps in at once.
 */
object Party {
    /** Hypixel's five classes, in their usual order. */
    val CLASSES by lazy { listOf(DungeonClass.HEALER, DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.TANK, DungeonClass.MAGE) }

    /** Mining through to the core ([digCore]), blocks a tick: a sprint with the Dungeonbreaker out. */
    private const val DIG = 0.6
    /** The core's S2 side (CORE_BOX's north edge), and where the core early enterer starts mining in, just north of it at x 54.5. */
    private const val CORE_N = 118.0

    class Bot(val clazz: DungeonClass, val slot: Int) {
        val name = Roles.label(clazz)
        var entity: Mannequin? = null
        var pos: Vec3 = Vec3.ZERO
        var yaw = 0f
        var pitch = 0f
        /** Which way it turns once it's where it's going (an early-enter spot's), or null: as it walked. */
        var face: Pair<Float, Float>? = null
        /** Where it's walking to (null: standing), from n = [goAt]. */
        var to: Vec3? = null
        var goAt = 0
        /** When it must be there (n; -1: no hurry): it goes faster to make it, like etherwarping. */
        var due = -1
        /** At this terminal doing it (others see "already using"). */
        var working: Station? = null
        /** The section it's in (pre-leapt into the next one: that one). */
        var inSection = 0
        /** Stays where it is until its section starts ("hold"). */
        var hold = false
        /** Vertical speed while falling, blocks a tick. */
        var vy = 0.0
        /** The next tick it may blink; when it last blinked and leapt (what it holds follows them). */
        var blinkReady = 0
        var blinkAt = -100
        var leaptAt = -100
        /** What it holds ([setHeld]'s key): the stack is only replaced when that changes. */
        var heldKey = ""
        /** Playing your best run as this class instead of the bot's role (P3 from S1 only). */
        var ghost: GhostPlayer? = null
        /** Ticks before [due] it wants to be there (a terminal: time to do it). */
        var lead = 0
        /** Going as a real player went ([Routes]): the route to [routeTo] and how far along it is (ticks at a player's pace); null: none (it walks). */
        var route: Routes.Route? = null
        var routeTo: Vec3? = null
        var routeT = 0.0
        /** What the route has it hold (a Bonzo staff, a Superboom...) until n = [routeHeldUntil]. */
        var routeHeld: String? = null
        var routeHeldUntil = -1
        /** Mining through to these, in order ([digCore]: the core early enterer's way in from S2); null: not. */
        var dig: ArrayDeque<Vec3>? = null
        /** Where it set off for in the lead-in ([startLeadIn]); null: it didn't. */
        var leadTo: Vec3? = null
        /** The lead-in as you moved in it, a frame a tick ([legLead]); null: it walks to [leadTo]. */
        var leadFrames: List<GhostRun.Frame>? = null
        /** Moving as you did: your best run as its class, timed to the plan ([legTick]); null: it moves on its own. */
        var legs: Legs? = null
    }

    /** An early enter as this run has it: whose (a ghost's: as in its run, on its spot), where. [into] 6: the recore. */
    class Ee(val into: Int, val label: String, val spot: Vec3, val yaw: Float, val pitch: Float, val owner: DungeonClass?) {
        /** A player's (yours, or a party player's: no bot does it). */
        val byYou get() = owner != null && Players.isPlayerClass(owner)
        /** The player doing it, if a player is. */
        val player get() = Players.playerOf(owner)
    }

    /** One of the party as a player sees it: another player, or a bot. */
    class Mate(val name: String, val clazz: DungeonClass, val player: ServerPlayer?, val bot: Bot?) {
        val pos: Vec3 get() = player?.position() ?: bot!!.pos
        val yaw: Float get() = player?.yRot ?: bot!!.yaw
        val pitch: Float get() = player?.xRot ?: bot!!.entity?.xRot ?: bot!!.pitch
        /** There to leap to: a player, or a bot standing in the fight. */
        val here: Boolean get() = player != null || bot?.entity != null
        val profile: GameProfile get() = player?.gameProfile ?: profile(bot!!)
    }

    /** Everyone but [viewer], players and bots, in [viewer]'s leap order (slots 1-4). */
    fun mates(viewer: ServerPlayer?): List<Mate> {
        // Not yet in (no class until their hello): everyone, in class order.
        val classless = viewer != null && !Players.hasClass(viewer)
        val mine = viewer?.let { Players.classOf(it) } ?: P3Sim.ownClass
        val all = bots()
        return (if (classless) CLASSES else P3Plan.orderFor(mine)).mapNotNull { c ->
            val pl = Players.playerOf(c)?.takeIf { it !== viewer }
            if (pl != null) Mate(pl.gameProfile.name, c, pl, null)
            else all.firstOrNull { it.clazz == c }?.let { Mate(it.name, c, null, it) }
        }
    }

    /** A player joined or left, or took a class: the bots follow at once (one a player took leaves the fight). */
    fun rosterChanged() {
        bots()
        // P1's carriers and P2's pad roles: whoever's left takes over a removed bot's part.
        (Fight.phase as? P1Maxor)?.botsChanged()
        (Fight.phase as? P2Storm)?.botsChanged()
        val ph = Fight.phase as? GoldorPhase ?: return
        if (!planOn || botsFor !== ph) return
        // Mid-P3, a class nobody plays any more (a swap, someone leaving, the host's menu) has its bot back at once, on its
        // role's jobs; then whatever a removed bot had and no player does goes to the bots.
        for (c in P3Plan.botOrder()) if (!Players.isPlayerClass(c) && botOf(c)?.entity == null) takeOver(ph, c, null, P3Plan.plan().jobsOf[c].orEmpty().toSet())
        adoptOrphans(ph)
    }

    /** The P3 the bots were made for (null: none, or bots were off then). */
    private var botsFor: GoldorPhase? = null

    /** Every job left undone that no player has and no bot holds: its picked or role's bot, else the least busy. */
    private fun adoptOrphans(phase: GoldorPhase) {
        val ghostJobs = bots.flatMap { it.ghost?.jobs.orEmpty() }.toSet()
        // Not one holding on an early-enter spot (its jobs wait for the release, and might never come).
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

    /** The players' classes' positions: who stands where. */
    internal fun posOf(c: DungeonClass?): Vec3? = Players.playerOf(c)?.position()
    private val ees = arrayOfNulls<Ee>(7)

    /** Who early-enters into [into] this run, and where (null: nobody). */
    fun eeOf(into: Int): Ee? = ees.getOrNull(into)

    /**
     * The plan's early enters, but a ghost's are its run's: a ghost that early-entered somewhere does
     * it again (unless it's yours now); a ghost whose class the plan has there but that didn't, doesn't.
     */
    private fun resolveEes() {
        val plan = P3Plan.plan()
        for (into in 2..6) {
            val k = if (into == 6) 5 else into
            val base = P3Plan.earlyEnters.firstOrNull { it.into == into }
            val owner = GhostPlayer.eeOwner(plan.ee[k]?.name, Players.playerClasses().map { it.name }.toSet(),
                bots.filter { it.ghost?.earlyEnters(k) == true }.map { it.clazz.name }, bots.filter { it.ghost != null }.map { it.clazz.name }.toSet())
            val c = CLASSES.firstOrNull { it.name == owner }
            // The one set in Roles for this skill and class, else the preset's own, else the Early Enters tab's.
            val spot = base?.let { P3Plan.eeSpot(it) }
            val g = bots.firstOrNull { it.clazz == c }?.ghost
            // A ghost's spot is where it stood in its run (the recore: the plan's).
            val f = if (into == 6) null else g?.eeSpot(k)
            // A party player's: their own spot (their plan's).
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

    /** The tick of the section in progress, for debug lines. */
    internal var dbgN = 0
    private var dbgS = 0
    internal var curPhase: GoldorPhase? = null

    /** A debug line (the menu's Debug bots): section and seconds into it first. */
    internal fun dbg(text: String) {
        val sec = (dbgN - sectionN[dbgS.coerceIn(0, 5)]) / 20.0
        val line = "§8[bots S$dbgS %.2f] §7$text".format(sec)
        if (P3Sim.debugBots) Sim.chat(line) else Recorder.event(line)
    }

    /** The bots' state for [Recorder]. */
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

    /**
     * The bots, in leap slot order (rebuilt when your class or the order changes): one per class no player plays. A
     * player taking a class mid-fight takes its bot's place at once (the bot and its jobs go); a class a player left gets
     * its bot back at the next start.
     */
    fun bots(): List<Bot> {
        val order = P3Plan.botOrder()
        val want = order.filter { !Players.isPlayerClass(it) }
        if (bots.map { it.clazz } == want) return bots
        // Mid-P3 the bots' jobs and leaps are kept even when none has a body (Bots off: the plan still does their jobs).
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

    /**
     * A player left mid-P3 ([theirs]: the jobs they had): their class's bot is back where they stood, at once, and does
     * what they left undone (this section's now, later ones on the plan's times).
     */
    fun takeOver(phase: GoldorPhase, c: DungeonClass, at: Vec3?, theirs: Set<String>) {
        if (!planOn || botsFor !== phase || Players.isPlayerClass(c)) return
        // Its bot may be there already without a body (a full party's roster change makes one).
        val had = botOf(c)
        if (had?.entity != null) return
        val order = P3Plan.botOrder()
        val b = had ?: Bot(c, order.indexOf(c) + 1).also { bots += it; bots.sortBy { b -> order.indexOf(b.clazz) } }
        b.inSection = phase.section
        // A player's early enter it now owns: done if its section has begun (the player was on it), or there already if
        // the player stood on it, so the bot doesn't go back and hold for leaps that came long ago.
        for (into in 2..5) if (eeOf(into)?.owner == c) {
            if (into <= phase.section || youArrived[into]) {
                eeArrived[into] = true
                if (eeArrivedN[into] < 0) eeArrivedN[into] = phase.n
            }
            if (into <= phase.section) released[into] = true
        }
        val plan = P3Plan.plan()
        // Not a player's, nor a job some bot already has.
        // (The one leaving is still listed while they go: theirs count as nobody's.)
        val others = Sim.players().filter { Players.classOf(it) != c }
        val todo = theirs.filter { j -> others.none { j in Players.jobsOf(it) } && jobs.none { it.job == j } && phase.stations.firstOrNull { it.id == j }?.done != true }
        put(b, at ?: todo.firstOrNull { (phase.stations.firstOrNull { s -> s.id == it }?.section ?: 0) == phase.section }?.let { spotOf(it) } ?: Sim.host?.position() ?: CORE_SPOT)
        for (job in todo) {
            val st = phase.stations.firstOrNull { it.id == job }
            if (st == null && job.startsWith("gate") && phase.gateIsDown(job.removePrefix("gate ").toIntOrNull() ?: 0)) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < phase.section) continue
            val (ts, sec) = plan.times[job] ?: (s to 5.0)
            // Its time already begun: now (a second to react).
            if (ts <= phase.section) jobs += Job(job, b, phase.section, 0.0).also { it.at = phase.n + 20 }
            else jobs += Job(job, b, ts, sec)
        }
        dbg("§e${b.name}§7 takes over the ${c.name}'s jobs: ${jobs.filter { it.bot === b }.joinToString { it.job }}")
        // Into the core with the rest if they're in; else off to its first job.
        if (coreIn) { b.inSection = 5; go(b, CORE_SPOT.add(0.0, 0.0, 2.0), phase.n, -1, "the core") } else walkOn(b, phase.n)
    }

    fun bot(slot: Int) = bots().getOrNull(slot - 1)
    internal fun botOf(c: DungeonClass?) = bots.firstOrNull { it.clazz == c }

    /** Bumped by every clear: anything queued before a restart is dropped. */
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

    /** Storm's death to "Who dares" ([StormEnd]): the bots are off from the landing to where they start P3. */
    internal var leadIn = false

    /**
     * The lead-in began, [ticks] before P3 does: each bot sets off from the SS landing, as players do, for its first
     * S1 job's spot, or its early enter's when that comes first (the EE2 bot: straight to its spot), there by P3's
     * start. A ghost, and the i4 bot (on the S4 device from the start), stay as they were.
     * With Real Bot Movement and a lead-in of yours as its class in the skill's legs file ([legLead]), it moves as you did
     * instead, from where you started.
     */
    fun startLeadIn(ticks: Int) {
        if (!botsOn) return
        leadIn = true
        resolveEes()
        val plan = P3Plan.plan()
        for (b in bots()) {
            // The i4 bot is on the S4 device's plate from the start (the i3 bot on the S3 device's).
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

    /** A lead-in tick ([t]: ticks since Storm's death line). */
    fun tickLeadIn(t: Int) { if (leadIn) for (b in bots) { if (b.leadFrames != null) leadFrame(b, t) else { move(b, t); hold(b, t) } } }

    /** A later section's device done from S1's start (i3, i4): its bot starts on it, as its player does. */
    private val EARLY_DEVICES get() = setOf("S3 Arrows", "S4 Target")

    /** [b]'s first job, when it's a later section's device done in S1 (as startP3 starts it on it); null: it isn't. */
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
        // Its early enter first (nothing of its own timed before it). Not the core: that's from S2, through the core ([digCore]).
        for (k in 2..4) {
            val ee = eeOf(k) ?: continue
            if (ee.owner == c && !ee.byYou && mine.none { time(it).first < k }) return ee.spot
        }
        val first = mine.filter { time(it).first == 1 }.minWithOrNull(compareBy({ time(it).second }, { it })) ?: return null
        if (first in EARLY_DEVICES) return null
        return spotOf(first)
    }

    fun busyAt(st: Station) = bots.any { it.working === st }

    /** Everyone (the bots) inside [box]. */
    fun allIn(box: AABB) = !botsOn || bots.all { it.entity == null || box.contains(it.pos) }

    // ------------------------------------------------------------------ P3

    /** A bot's job: [job] (a station id or "gate k") done [sec] s into section [timeSection]; [at]: that n, once the section has started. */
    internal class Job(val job: String, val bot: Bot, val timeSection: Int, var sec: Double) { var at = -1 }

    internal val jobs = ArrayList<Job>()
    /** Leaps queued: [bot] onto whoever [onto] is at n = [at]; [youAt]: a leap onto you at your early-enter spot, which waits while you're not on it. */
    private class Leap(val bot: Bot, val at: Int, val youAt: Vec3? = null, val onto: () -> Target?)
    /** Who a leap lands on, and where: by who it is, not by who stands there (leapers land on the same spot). */
    internal class Target(val pos: Vec3, val name: String? = null, val yaw: Float? = null)
    internal fun Bot.asTarget() = Target(pos, name, yaw)
    internal fun ServerPlayer.asTarget() = Target(position(), gameProfile.name, yRot)
    private val leaps = ArrayList<Leap>()
    /** The plan's "2 onto 1" moves of the sections begun: a leap onto someone at their terminal instead of the early enterer. */
    internal val ontoLeaps = ArrayList<Roles.Move>()
    private var planned = 0
    /** Per section entered early (5 = core): you're on your spot; the bot's on its; when the pre-leap is due; the job whose bot holds. */
    private val youArrived = BooleanArray(6)
    internal val eeArrived = BooleanArray(6)
    private val preleapAt = IntArray(6)
    private val eeSpotBy = IntArray(6)
    private val holdJob = arrayOfNulls<String>(6)
    /** Whose leaps each early enterer waits for before moving on ("1" = that section's T1's doer, "ee3"...); null: everyone free. */
    private val waitsFor = arrayOfNulls<List<String>>(6)
    /** You've been on the early enterer (leapt onto it). */
    private val youOn = Array(6) { HashSet<DungeonClass>() }
    private val holdNoted = BooleanArray(6)
    /** The early enterer into each section has moved on (everyone it waited for leapt). */
    internal val released = BooleanArray(6)
    /** When everyone was first on each early enterer (-1: not yet). */
    internal val readyAt = IntArray(6) { -1 }
    /** Ticks an early enterer waits after the last leap before moving on (0.3 s). */
    private const val RELEASE_DELAY = 6
    /**
     * An early enterer's section has begun and everyone it waits for is in it, but not all by leaping onto it (a leap onto
     * someone else, a walk in): it moves on after this long anyway.
     */
    private const val IN_GRACE = 40
    private var lastLeap = 0
    internal val sectionN = IntArray(6)
    /** n each early enterer got on its spot (bot or ghost), and you on yours (-1: not yet); n you left yours. */
    internal val eeArrivedN = IntArray(6) { -1 }
    internal val youArrivedN = IntArray(6) { -1 }
    internal val youLeftN = IntArray(6) { -1 }
    /** n the core early enterer stood on the recore in the core (you: were in the core), -1: not yet. */
    internal var recoredN = -1
    /** n you were first in the core once it was open. */
    internal var youInCoreN = -1
    /** Everyone's leaping onto the core early enterer by the core (the core open). */
    private var coreEeLeaps = false

    /** Bots in P3: the setting, never in practice (only your part is left there). */
    internal val botsOn get() = P3Sim.bots && !Practice.active
    /** The plan runs (every job no player has, done at its time; shown by bots or not): any P3 but practice. */
    private val planOn get() = !Practice.active

    /** [b] at [at]: a body only with the Bots setting on (off: its jobs are still done on time, nobody shown). */
    private fun put(b: Bot, at: Vec3) { if (P3Sim.bots) spawn(b, at) else { b.pos = at; b.entity = null } }

    fun startP3(phase: GoldorPhase) {
        // From the lead-in: where each bot got to, and where it was going.
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
        // No early enter into the section you start in (or before).
        for (i in 0..from) eeArrived[i] = true
        val plan = P3Plan.plan()
        // A ghost does its run's jobs; the bots everything else that isn't yours.
        val ghostJobs = bots.flatMap { it.ghost?.jobs.orEmpty() }.toSet()
        val crew = bots.filter { it.ghost == null }
        // Every job that isn't yours: its role's bot (a stack: the first listed), or the least busy one.
        for (job in P3Plan.allJobs()) {
            if (Players.isPlayerJob(job) || job in ghostJobs) continue
            val st = phase.stations.firstOrNull { it.id == job }
            if (st?.done == true) continue
            if (st == null && job.startsWith("gate") && phase.gateIsDown(job.removePrefix("gate ").toIntOrNull() ?: 0)) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < from) continue
            var (ts, sec) = plan.times[job] ?: (s to 5.0)
            // The bot picked for it in the menu (right click), else its role's.
            val bot = P3Plan.doer(job)?.let { c -> botOf(c)?.takeIf { it.ghost == null } }
                ?: plan.owners[job]?.firstNotNullOfOrNull { c -> botOf(c)?.takeIf { it.ghost == null } }
                ?: crew.minByOrNull { b -> jobs.count { it.bot === b && it.timeSection == ts } } ?: continue
            if (P3Plan.skill == P3Plan.RANDOM) sec = P3Plan.botMin + Random.nextDouble() * (P3Plan.botMax - P3Plan.botMin).coerceAtLeast(0.0)
            if (ts < from) { ts = from; sec = 0.5 }
            jobs += Job(job, bot, ts, sec)
        }
        spread(plan)
        // Helper: your jobs you made stacks (right click) are done by their bot too, at the plan's time (whoever's first).
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
        // The same for a joiner's stacks, as their game sent them.
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
        // Helper: bots help on your stacks (both try; whoever's first).
        if (P3Plan.helper) for (h in P3Plan.preset().helps) {
            // Whoever has these jobs (the host or a joiner).
            if (h.section < from || Sim.players().none { pl -> Players.jobsOf(pl).let { js -> h.yours in js && h.jobs.all { it in js } } }) continue
            val c = P3Plan.doer(h.by)?.takeIf { !Players.isPlayerClass(it) } ?: continue
            val bot = botOf(c)?.takeIf { it.ghost == null } ?: continue
            for (j in h.jobs) if (phase.stations.firstOrNull { it.id == j }?.done != true) {
                jobs += Job(j, bot, h.section, h.at)
                dbg("helper: §e${bot.name}§7 gets $j by ${h.at}s into S${h.section}")
            }
        }
        for (b in bots) {
            b.inSection = from
            b.dig = null
            // A ghost: where you stood at Goldor's first line.
            val g = b.ghost
            if (g != null) { val f = g.run.frame(0); b.yaw = f.yaw; b.pitch = f.pitch; spawn(b, f.p.vec()); g.tick(phase.n); place(b); continue }
            // Your legs as its class: from where the first one starts.
            val lg = legsFor(b, from)
            val f = lg?.pieces?.firstOrNull { it.frames.isNotEmpty() }?.frames?.first()
            // Its lead-in ended near there: on from where it got to (blended onto your route).
            if (lg != null && f != null) { b.legs = lg; b.yaw = f.yaw; b.pitch = f.pitch; put(b, walked[b.clazz]?.first?.takeIf { it.distanceTo(f.p.vec()) <= 3.0 } ?: f.p.vec()); place(b); continue }
            val core = eeOf(5)?.owner == b.clazz && from >= 3
            // Starting from S3/S4: the core early enterer is already on its spot (by the core), holding.
            if (core) { b.inSection = 5; eeArrived[5] = true; b.hold = from < 5; eeOf(5)?.let { b.yaw = it.yaw; b.pitch = it.pitch } }
            val first = next(b)?.takeIf { sectionOf(it.job) == from }
            val start = when {
                core -> CORE_EE
                // The i4 bot starts on the S4 device, as an i4 player does (nobody walks there from S1); the i3 bot on S3's.
                from == 1 && next(b)?.let { it.job in EARLY_DEVICES && it.timeSection == 1 } == true -> STANDS.getValue(next(b)!!.job)
                // A job due in the first second (a lever at 0.35 s): it starts on it, as its player does.
                from == 1 && next(b)?.let { it.timeSection == 1 && it.sec <= 1.0 } == true -> spotOf(next(b)!!.job)
                from == 1 -> startPos(1)
                first != null -> spotOf(first.job)
                else -> eeOf(from)?.spot ?: startPos(from)
            }
            // Walked there in the lead-in: where it got to; not there yet, it goes on (its job's due now; an early
            // enter's spot: earlyEnterBots sends it).
            val w = walked[b.clazz]
            if (w != null && !core && next(b)?.job !in EARLY_DEVICES) {
                put(b, w.first)
                if (w.first.distanceTo(w.second) > 0.5 && w.second == start) go(b, start, phase.n, phase.n, "where it starts P3 (still on its way)")
            } else put(b, start)
        }
    }

    /** The bots that play your best runs instead (the menu's Plan tab), P3 from S1 with a run saved for this skill. */
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

    /** [b] (a bot or a ghost) is on its early-enter spot into [into]: the party can leap onto it. */
    internal fun arrivedOnEe(b: Bot, into: Int, n: Int) {
        eeArrived[into] = true
        if (eeArrivedN[into] < 0) eeArrivedN[into] = n
        GhostCapture.event("ee", b.clazz.name, into)
        val ee = eeOf(into) ?: return
        if (P3Sim.debugBots) dbg("§a${b.name} is on its ${ee.label} spot§7, holding") else Sim.note("§e${b.name}§7 is on ${ee.label}.")
        // The preset's blocks this early enterer mines there (Theoretical: the bers, S3's 3rd term ledge).
        P3Plan.preset().mines[if (into == 5) "core" else "ee$into"]?.let { blocks ->
            for (pos in blocks) if (!Sim.level.getBlockState(pos).isAir) {
                Sim.level.levelEvent(2001, pos, Block.getId(Sim.level.getBlockState(pos)))
                Blocks.set(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
            }
        }
    }

    /** A bot with several jobs at one time does them evenly spread since its previous one (in role order). */
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
            // The core's open: the bots playing your runs go in with the rest (everyone leaps in).
            if (s >= 5) for (b in bots) if (b.legs != null) {
                b.legs = null; b.to = null
                // The core early enterer holding by the core keeps holding: everyone leaps onto it, then it recores
                // ([releaseEarlyEnterers] needs it held, else nobody is ever released into the core).
                if (b.hold && b === coreBot() && eeArrived[5] && !released[5]) { b.inSection = 5; continue }
                b.hold = false; b.inSection = 4
            }
            sectionStarted(phase, s)
        }
        // Jobs someone else (you, on a stack) already did: their bots go on to their next instead
        // (one about to leap: once it lands, not walking off ahead of its leap).
        val beaten = HashSet<Bot>()
        jobs.removeAll { j ->
            (phase.stations.firstOrNull { it.id == j.job }?.done == true || (j.job.startsWith("gate") && phase.gateIsDown(sectionOf(j.job)))).also { if (it) beaten += j.bot }
        }
        beaten.filter { b -> leaps.none { it.bot === b } }.forEach { walkOn(it, n) }
        youAtEarlyEnter(phase)
        // Leaps that are due.
        leaps.filter { n >= it.at }.forEach { l ->
            // Onto you at your early enter: only while you're in position.
            if (l.youAt != null && Sim.players().none { it.position().distanceTo(l.youAt) <= 3.0 }) return@forEach
            leaps.remove(l)
            // Playing your run: it leaps where you did.
            if (l.bot.legs != null) return@forEach
            l.onto()?.let { dbg("§e${l.bot.name}§7 leaps (${it.pos.short()})"); leapTo(l.bot, it, n); walkOn(l.bot, n) }
        }
        // Jobs that are due (a section's last held while you're on your way to your early enter).
        val held = holding(phase)
        val finished = ArrayList<Bot>()
        jobs.removeAll { j ->
            if (j.at < 0 || n < j.at) return@removeAll false
            // The plan's time is the time: a bot that isn't there yet (holding on an early enter, on its way, late) is put there.
            if (!inReach(j.bot, j.job) && j.bot.legs == null) {
                dbg("§e${j.bot.name}§7 wasn't at ${j.job} on time: put there")
                j.bot.hold = false; j.bot.dig = null
                arrive(j.bot, spotOf(j.job))
            }
            val st = phase.stations.firstOrNull { it.id == j.job }
            if (st != null) {
                if (st.section == s && held && phase.stations.count { it.section == s && !it.done } == 1) return@removeAll false
                // A lever's line comes on the swing that pulls it (1 tick); a device's last click swings too.
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
        // Working: at its terminal for the last 2 s before it's done (opening it swings: a right click on its stand).
        for (b in bots) {
            if (b.ghost != null) continue
            val was = b.working
            b.working = next(b)?.takeIf { it.at >= 0 && it.at - n <= 40 && b.pos.distanceTo(spotOf(it.job)) <= 2.5 }?.let { j -> phase.stations.firstOrNull { it.id == j.job && it.kind == Station.Kind.TERMINAL } }
            if (b.working != null && b.working !== was) swing(b)
            if (b.legs == null) hold(b, n)
        }
    }

    /**
     * The ghosts play on. One whose run ended outside the core (it shouldn't: runs are kept once
     * you're in) walks in as a bot would; once the core's open, everyone leaps onto a core early
     * enterer ghost that's on its spot.
     */
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

    /** Section [s] began: its times start, its moves are set; anyone not in it leaps onto whoever early-entered it, or walks. */
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
                        // Back into the section: onto someone still working in it (else you).
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
        // Nobody early-enters S4 (there's no EE4): everyone leaps onto the core early enterer holding by the core, and walks on from there.
        val coreHold = if (s == 4 && ee == null) coreBot()?.takeIf { it.ghost == null && eeArrived[5] && it.hold } else null
        val coreYou = s == 4 && ee == null && eeOf(5)?.let { it.byYou && it.player?.position()?.let { p -> p.distanceTo(it.spot) <= 3.0 } == true } == true
        // Your early enter into it: the ones still out (busy till the section before ended) leap onto you there, as onto a bot's.
        val eeYou = ee?.takeIf { it.byYou && s > phase.from && it.player?.position()?.let { p -> p.distanceTo(it.spot) <= 3.0 } == true }
        val onto = eeBot ?: coreHold
        var i = 0
        for (b in bots) {
            // A ghost goes as you went; a bot playing your legs, as its legs go.
            if (b.ghost != null || b.legs != null) continue
            // Holding for a later early enter (the core bot by the core through S4): it keeps holding.
            if (b.hold && b.inSection > s && eeArrived[b.inSection.coerceAtMost(5)] && !released[b.inSection.coerceAtMost(5)]) continue
            // The core early enterer with nothing left before the core: it goes (or keeps going) through the core, no leap into this section.
            if (b !== onto && onWayToCore(b)) continue
            if (b.hold && b !== onto) dbg("§e${b.name}§7 stops holding (section $s started)")
            b.hold = false
            // Holding (or on its way) unless it already moved on in the section before.
            if (b === onto) { b.hold = eeArrived[s] && !released[s]; continue }
            if (b.inSection >= s) { walkOn(b, n); continue }
            // The EE4 bot with nothing left in S3 goes straight to its spot from where it is (Theoretical's archer: S2's
            // 3rd term to the core), no leap into S3.
            if (s == 3 && eeOf(4)?.let { !it.byYou && it.owner == b.clazz } == true && jobs.none { it.bot === b && sectionOf(it.job) <= 3 }) { b.inSection = s; continue }
            // Leaping onto someone at their terminal: once they're there ([ontoLeapsTick]).
            if (ontoFor(b.clazz, s) != null) continue
            b.inSection = s
            if (onto != null) leaps += Leap(b, n + 2 + gapTicks() * i++) { onto.asTarget() }
            else if (eeYou != null) leaps += Leap(b, n + 2 + gapTicks() * i++, eeYou.spot) { eeYou.player?.asTarget() }
            else if (coreYou) leaps += Leap(b, n + 2 + gapTicks() * i++) { eeOf(5)?.player?.asTarget() }
            else walkOn(b, n)
        }
    }

    /** [b] early-enters section [s] (in progress) and isn't on its spot yet. */
    private fun lateForEe(b: Bot, s: Int): Boolean {
        if (s > 4 || eeArrived[s]) return false
        val ee = eeOf(s)?.takeIf { !it.byYou } ?: return false
        return botOf(ee.owner) === b && b.ghost == null
    }

    /** A job its early enterer into [into] does before going to its spot (one of the sections before, or timed in them: a pre-done device). */
    private fun before(j: Job, into: Int) = sectionOf(j.job) < into || j.timeSection < into

    /** "ee2" -> 2, "core" -> 5. */
    private fun into(token: String) = if (token == "core") 5 else token.removePrefix("ee").toIntOrNull()

    /** The bot early-entering into section [into] walks to its spot once its jobs before are done; it's in when it's there. */
    private fun earlyEnterBots(phase: GoldorPhase) {
        val s = phase.section
        // Each bot to its earliest early enter only (the mage's EE2 before its core); different bots go at once (the core
        // bot doesn't wait for the EE3 bot to get to its spot).
        val sent = HashSet<Bot>()
        // The section in progress too: an early enterer that didn't make it before its section started still goes and waits.
        for (into in s..5) {
            if (into == s && (into > 4 || eeArrived[into])) continue
            val ee = eeOf(into) ?: continue
            if (ee.byYou) continue
            val b = botOf(ee.owner)?.takeIf { it !in sent } ?: continue
            sent += b
            // A ghost, or a bot playing your run: gets there as you did.
            if (b.ghost != null || b.legs != null) continue
            if (into > s && (b.inSection >= into || busy(b) || b.hold)) continue
            // Not before its jobs in the sections before (the EE3 bot does its S2 ones first, not straight from S1); the core's
            // own S4 ones come after the leaps onto it as S4 starts.
            if (jobs.any { it.bot === b && before(it, if (into == 5) 4 else into) }) continue
            if (b.pos.distanceTo(ee.spot) < 0.5) {
                b.inSection = into; b.hold = true
                b.yaw = ee.yaw; b.pitch = ee.pitch
                arrivedOnEe(b, into, phase.n)
                continue
            }
            if (into == 5 && digCore(b, ee, phase)) continue
            // Straight there once free, at etherwarp pace: on the spot (ready for leaps) as early as it can be.
            // By the preset's "spot" time if it has one, else at a player's pace (the old walker: etherwarp pace).
            if (b.to != ee.spot) go(b, ee.spot, phase.n, if (P3Sim.realMoves) eeSpotBy[into] else phase.n, "its ${ee.label} spot", ee.yaw to ee.pitch)
        }
    }

    /** [b] is the core early enterer with nothing left before the core: on its way there (from S2, through the core), never leapt into S3/S4. */
    private fun onWayToCore(b: Bot) = eeOf(5)?.let { !it.byYou && it.owner == b.clazz } == true && b.inSection < 5 && jobs.none { it.bot === b && before(it, 4) }

    /**
     * The core early enterer goes in from S2, never by S3: to the core's S2-side wall, then mines straight into the core,
     * through its middle and out of its door to its spot in S4. Only from S1/S2 (or the gap north of the core); one already
     * in S3 or S4 goes as it would. True: it's on that way.
     */
    private fun digCore(b: Bot, ee: Ee, phase: GoldorPhase): Boolean {
        if (b.dig != null) return true
        val zone = GoldorPhase.dtZone(b.pos)
        if (!(zone == 1 || zone == 2 || (zone == -1 && b.pos.z > CORE_N))) return false
        val wall = Vec3(54.5, ground(54.5, CORE_N + 3.0, 125.0)?.takeIf { it != WALL } ?: 120.0, CORE_N + 3.0)
        val way = listOf(Vec3(54.5, 115.0, CORE_N - 1.0), Vec3(54.5, 115.0, 56.0), ee.spot)
        if (b.pos.distanceTo(wall) > 0.6) {
            // There by the preset's spot time less the mining (else a player's pace).
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

    /** A tick of mining on along [way]: straight at its next point, through whatever's there (broken as it goes, back after the regen). */
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
                    // What the Dungeonbreaker can't break it just goes through.
                    if (s.isAir || s.getDestroySpeed(level, pos) < 0 || s.`is`(net.minecraft.world.level.block.Blocks.BARRIER) ||
                        s.`is`(net.minecraft.world.level.block.Blocks.BEDROCK) || s.block is CommandBlock) continue
                    level.levelEvent(2001, pos, Block.getId(s))
                    Blocks.set(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
                    broke = true
                    // Back after the regen, as the Dungeonbreaker's are; not once the core's open (its door would come back).
                    Fight.later(Math.round(P3Sim.breakerRegen * 20).toInt(), "bot dig regen") {
                        if (level.getBlockState(pos).isAir && (Fight.phase as? GoldorPhase)?.let { it.section < 5 } == true) Blocks.set(pos, s)
                    }
                }
        if (broke) swing(b)
        if (way.isEmpty()) { b.dig = null; b.face?.let { b.yaw = it.first; b.pitch = it.second } }
    }

    /** The pre-leap onto the early enterer of the next section: everyone free, one after another; the busy ones after their last job. */
    private fun preleaps(phase: GoldorPhase) {
        val n = phase.n
        val into = phase.section + 1
        if (into > 5) return
        val ee = eeOf(into) ?: return
        val eeBot = if (ee.byYou) null else botOf(ee.owner)
        // Not onto the core early enterer while it mines back in (to the recore) or does its S4 jobs: once it's there.
        val open = if (ee.byYou) youArrived[into] else eeBot != null && eeArrived[into] && eeBot.dig == null && (into < 5 || eeBot.hold) &&
            n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)
        if (!open) return
        val target: () -> Target? = if (eeBot != null) ({ eeBot.asTarget() }) else ({ ee.player?.asTarget() })
        for (b in bots) {
            // The core early enterer goes from S2 through the core, never onto the next section's early enterer.
            if (b === eeBot || b.ghost != null || b.inSection >= into || busy(b) || onWayToCore(b) || ontoFor(b.clazz, into) != null) continue
            b.inSection = into
            lastLeap = maxOf(n + 1, lastLeap + if (eeBot != null) 2 else gapTicks())
            if (holdJob[into] != null && jobs.any { it.bot === b && it.job == holdJob[into] }) b.hold = true
            leaps += Leap(b, lastLeap, if (ee.byYou) ee.spot else null, target)
        }
    }

    /** [c]'s "onto" move into section [into] ("2 onto 1": that section's T2 doer leaps onto its T1 doer), if it has one. */
    private fun ontoFor(c: DungeonClass, into: Int) = ontoLeaps.firstOrNull { it.section + 1 == into && whoFor(it.who, into) == c }

    /** Whoever [m] leaps onto, once they're at their terminal (within 3 blocks of its stand); null: not there yet. */
    internal fun ontoTarget(m: Roles.Move): Target? {
        val into = m.section + 1
        val t = m.args.firstOrNull() ?: return null
        val c = whoFor(t, into) ?: return null
        val stand = spotOf("S$into T$t")
        Players.playerOf(c)?.let { return if (it.position().distanceTo(stand) <= 3.0) it.asTarget() else null }
        return botOf(c)?.takeIf { it.pos.distanceTo(stand) <= 3.0 }?.asTarget()
    }

    /** "2 onto 1": the leaper (a bot, done with its jobs before that section) leaps once whoever it's onto is at their terminal. */
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

    /**
     * An early enterer (a bot) moves on from its spot once everyone it waits for has leapt onto it
     * (the preset's "waits"; else everyone, you included); at the latest 10 s into the section it entered.
     */
    private fun releaseEarlyEnterers(phase: GoldorPhase) {
        val n = phase.n
        val s = phase.section
        // You're on an early enterer once you're within 3 blocks of it (by position: a leap lands
        // you on it) while it holds on its spot; on it before it got there doesn't count.
        for (into in s..(s + 1).coerceAtMost(5)) {
            val b = eeOf(into)?.takeIf { !it.byYou }?.let { botOf(it.owner) } ?: continue
            if (!b.hold || !eeArrived[into] || released[into]) continue
            for (pl in Sim.players()) {
                val c = Players.classOf(pl)
                if (c !in youOn[into] && pl.position().distanceTo(b.pos) < 3.0) { youOn[into] += c; dbg("§a${pl.gameProfile.name} is on ${b.name}§7 (EE$into)") }
            }
            // S4 (nobody early-enters it): the bots that leapt onto the core early enterer as it started are on it too, by
            // where they landed, so it recores once all four are, not after S4.
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
            // You're waited for if you're one who leaps onto it (on SS you leap to the 2nd term instead).
            val players = Players.playerClasses().filter { it != b.clazz }
            val needed = if (waits == null) players else waits.mapNotNull { whoFor(it, into) }.filter { it in players }
            // In once its section's begun: leapt onto it, or in the section anyway (leapt onto someone else, walked in).
            fun there(c: DungeonClass?) = leapt(c, b, into) || into == s && inSectionNow(c, into)
            val ok = when {
                // Never without you (a safety net at 30 s); the bots get 10 s.
                into == s && n - sectionN[s] >= 600 -> true
                needed.any { !there(it) } -> { allInAt[into] = -1; false }
                // Everyone's in the section, not all through this early enterer (someone leapt wrong): a short wait, then on.
                needed.any { !leapt(it, b, into) } -> {
                    if (allInAt[into] < 0) { allInAt[into] = n; dbg("§e${b.name}§7: everyone's in S$into, not all by EE$into: moving on in ${IN_GRACE / 20.0}s") }
                    n - allInAt[into] >= IN_GRACE
                }
                into == s && n - sectionN[s] >= 200 -> true
                waits != null -> waits.all { t -> there(whoFor(t, into)) }
                // No list: everyone - every bot (busy ones too, once they're done and leapt) and you.
                else -> (into == s || preleapOpen(into, n)) &&
                    bots.all { it === b || there(it.clazz) } && players.all { there(it) }
            }
            // Everyone's on: it moves 0.3 s later (as a player reacts).
            if (!ok) { readyAt[into] = -1; continue }
            if (readyAt[into] < 0) { readyAt[into] = n; dbg("§e${b.name}§7: everyone's on EE$into${if (b.ghost == null) ", moving in ${RELEASE_DELAY / 20.0}s" else ""}") }
            // A ghost moves on when you did after the last leap onto you ([GhostPlayer]: its eeLeft).
            if (b.ghost != null) continue
            if (n - readyAt[into] < RELEASE_DELAY) continue
            released[into] = true
            dbg("§c${b.name} moves on from EE$into§7: ${waitStatus(b, into)}${if (into == s) ", ${(n - sectionN[s]) / 20.0}s into S$s" else ""}")
            // The core early enterer goes into the core (recore) and waits there: everyone leaps onto it in. With S4 jobs of
            // its own (Near Optimal's mage) those first ([recoreAfterJobs]).
            val recore = if (into == 5) eeOf(6) else null
            if (recore != null && jobs.none { it.bot === b }) toRecore(b, recore, s, n)
            else { b.hold = false; walkOn(b, n) }
        }
    }

    /** [b] (the core early enterer) to the recore: the core still shut (S4), it turns round and mines back in through the door, as it came out. */
    private fun toRecore(b: Bot, recore: Ee, s: Int, n: Int) {
        b.hold = true
        if (s >= 5) { go(b, recore.spot, n, -1, "the recore (in the core)", recore.yaw to recore.pitch); return }
        b.to = null; b.due = -1; b.face = recore.yaw to recore.pitch
        b.dig = ArrayDeque(listOf(Vec3(54.5, 115.0, 56.0), recore.spot))
        dbg("§e${b.name}§7 mines back into the core to the recore (${recore.spot.short()})")
    }

    /**
     * The core early enterer with S4 jobs of its own (Near Optimal's mage: its 4th) does them once the leaps onto it as S4
     * starts are in (or once their time came), then recores.
     */
    private fun recoreAfterJobs(phase: GoldorPhase) {
        if (coreIn || phase.section < 4) return
        val b = coreBot()?.takeIf { it.ghost == null && it.legs == null && eeArrived[5] && !it.hold && it.to == null && it.dig == null } ?: return
        if (jobs.any { it.bot === b } || leaps.any { it.bot === b }) return
        val recore = eeOf(6) ?: return
        released[5] = true
        toRecore(b, recore, phase.section, phase.n)
    }

    /** When everyone an early enterer waits for was first in its section without all having leapt onto it (-1: not). */
    private val allInAt = IntArray(7) { -1 }

    /** [c] (a player, else its bot) is in section [into] or past it now (into 5: in the core). */
    private fun inSectionNow(c: DungeonClass?, into: Int): Boolean {
        val p = posOf(c) ?: botOf(c)?.takeIf { it.entity != null }?.pos ?: return false
        if (into >= 5) return GoldorPhase.CORE_BOX.contains(p)
        return GoldorPhase.dtZone(p) >= into
    }

    /** Debug: who an early enterer has (+) and hasn't (-) had leap onto it. */
    private fun waitStatus(b: Bot, into: Int): String {
        val waits = waitsFor[into]
        val who = (waits?.mapNotNull { whoFor(it, into) } ?: bots.filter { it !== b }.map { it.clazz }) + Players.playerClasses()
        return who.distinct().filter { it != b.clazz }.joinToString(" ") { c ->
            val name = Players.playerOf(c)?.gameProfile?.name ?: Roles.label(c)
            if (leapt(c, b, into)) "§a+$name§7" else "§c-$name§7"
        } + if (waits != null) " (list: ${waits.joinToString(" ")})" else " (everyone)"
    }

    /** Debug, every 2 s: what each bot is doing, and who the holding early enterers wait for. */
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

    /** "3" -> who does S[into] T3; "ee3" / "core" -> who early-enters there. */
    internal fun whoFor(token: String, into: Int): DungeonClass? =
        // A terminal: the player doing it (anyone's own jobs), else its bot.
        if (token.all { it.isDigit() }) "S$into T$token".let { j -> Players.playerDoing(j)?.let(Players::classOf) ?: P3Plan.doer(j)?.takeIf { !Players.isPlayerClass(it) } ?: P3Plan.roleDoerBot(j) }
        else eeOf(into(token) ?: return null)?.owner

    /**
     * [c] has leapt onto [onto] (you: been within 3 blocks of it). A ghost: it's on it, or leapt onto
     * it since it got there, or its run has no leap onto it there to wait for.
     */
    private fun leapt(c: DungeonClass?, onto: Bot, into: Int): Boolean = when {
        c == null || c == onto.clazz -> true
        Players.isPlayerClass(c) -> c in youOn[into]
        // A bot seen landing on it (the core early enterer in S4).
        c in youOn[into] -> true
        // Playing its legs: it leapt onto it, or its legs have no leap onto it to come.
        botOf(c)?.legs != null -> botOf(c)!!.legs!!.let { onto.clazz.name in it.leaptOnto || !it.expectsLeap(onto.clazz.name) }
        else -> botOf(c)?.let { o ->
            val g = o.ghost
            if (g != null) o.pos.distanceTo(onto.pos) < 3.0 || g.leaptOnto(onto.clazz.name, eeArrivedN[into].coerceAtLeast(0)) || !g.expectsLeap(onto.clazz.name, into)
            else o.inSection >= into && leaps.none { l -> l.bot === o }
        } ?: true
    }

    private fun preleapOpen(into: Int, n: Int) = n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)

    /** You're at your early enter: the party leaps onto you (pre-leaps() does it). */
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

    /** Is a section's last job held for you (you early-enter the next one and aren't there yet). */
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

    /** Everyone leapt into the core (onto the recore). */
    private var coreIn = false

    /** The core early enterer is a bot (null: you, or nobody). */
    private fun coreBot() = eeOf(5)?.takeIf { !it.byYou }?.let { botOf(it.owner) }

    /**
     * The core opened. The core early enterer (a bot) still holding by the core: everyone not on it
     * leaps onto it first; once all are (you too) it goes in (recore, [releaseEarlyEnterers]) and then
     * they leap in onto it ([intoCoreWhenRecored]). Else straight in. A ghost there: as above once
     * it's on its spot ([ghosts]); in once it's in (it recored).
     */
    private fun core(phase: GoldorPhase) {
        val n = phase.n
        coreIn = false
        val cb = coreBot()
        if (cb != null && eeArrived[5] && !released[5] && (cb.hold || cb.ghost != null)) { leapOntoCoreEe(cb, n); return }
        if (cb?.ghost != null) { if (recoredN >= 0) leapIntoCore(phase); return }
        // Not on its spot yet (straight in onto it), or already in (on the recore): in now; walking in, or doing its S4 jobs
        // first: once it's there.
        if (cb == null || !eeArrived[5] || recored(cb)) leapIntoCore(phase)
    }

    /** Everyone not on the core early enterer [cb] leaps onto it, by the core. */
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

    /**
     * In the core (section 5): once the core bot has gone in and stands on the recore, everyone leaps
     * onto it (a ghost: once it was in the core, as you were; 20 s on, in anyway).
     */
    private fun intoCoreWhenRecored(phase: GoldorPhase) {
        if (coreIn || phase.section < 5) return
        val cb = coreBot() ?: return
        if (cb.ghost != null) { if (recoredN >= 0 || phase.n - sectionN[5] >= 400) leapIntoCore(phase); return }
        if (released[5] && recored(cb)) {
            if (recoredN < 0) { recoredN = phase.n; GhostCapture.event("recore", cb.clazz.name) }
            leapIntoCore(phase)
        }
    }

    /** The core early enterer is on the recore (holding there, not on its way or at its S4 jobs). */
    private fun recored(cb: Bot) = cb.hold && cb.to == null && cb.dig == null

    /** Everyone leaps in at once onto whoever's in the core (the core early enterer), or walks in. Ghosts go as you did. */
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

    /** Has a job due in the section in progress (or earlier). */
    private fun busy(b: Bot) = jobs.any { it.bot === b && it.at >= 0 }

    /** Off to the next job if it can be got to now (its time has started, or its section is where the bot is), unless holding. */
    internal fun walkOn(b: Bot, n: Int) {
        if (b.ghost != null || b.hold) return
        // Late for its early enter: only to a job from before that keeps it from going there (then earlyEnterBots sends it).
        val j = (if (lateForEe(b, planned)) next(b) { before(it, planned) } else next(b)) ?: return
        // A gate it can throw at from here: it stays.
        if (j.job.startsWith("gate") && inReach(b, j.job)) return
        if (j.at >= 0 || sectionOf(j.job) <= b.inSection) go(b, spotOf(j.job), n, j.at, "${j.job}${if (j.at < 0) " (its section not started)" else ""}", presetStand(j.job)?.let { it.yaw to it.pitch }, lead = leadOf(j.job))
    }

    /**
     * How long before its time a job's doer is there: a terminal ~2 s (players reach theirs a median
     * 46 ticks before its line, p25 35), a lever or device as it's done (medians 2-4 ticks).
     */
    private fun leadOf(job: String) = if (job.length == 5 && job.startsWith("S") && job[3] == 'T') TERMINAL_LEAD else 2
    private const val TERMINAL_LEAD = 40

    /** Near enough to do [job]: on its stand, or for a gate within 10 blocks of its wall (a Superboom's throw). */
    private fun inReach(b: Bot, job: String): Boolean {
        val g = job.removePrefix("gate ").toIntOrNull()
        return if (g != null) GoldorPhase.GATE_BOXES.getOrNull(g)?.let { it.distanceToSqr(b.pos) <= 100.0 } ?: false
        else b.pos.distanceTo(spotOf(job)) <= 2.5
    }

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.substring(1, 2).toInt()

    internal fun spotOf(job: String): Vec3 = presetStand(job)?.let { Vec3(it.x, it.y, it.z) } ?: STANDS[job] ?: job.removePrefix("gate ").toIntOrNull()?.let { GATES.getOrNull(it) } ?: CORE_SPOT

    /** The skill's own stand for [job] (Roles' "stand/<job>"), with how the bot faces there. */
    private fun presetStand(job: String): Spots.Spot? = P3Plan.preset().defaults["stand/$job"]

    /** A bot's party chat line, as Hypixel shows one (the bots are MVP+, as the leap lines colour them). */
    fun partyLine(b: Bot, text: String) = "§9Party §8> §b[MVP§c+§b] ${b.name}§f: $text"

    /**
     * What a bot holds, as teammates do: in P3 the Dungeonbreaker (teammates' rest
     * slot: 37-67% of P3 by class), the Infinileap from ~0.5 s before a leap to 1 s after (the head
     * comes out a median 8-19 ticks before), a Hyperion after a blink.
     */
    private fun hold(b: Bot, n: Int) {
        val key = when {
            leaps.any { it.bot === b && it.at - n in 0..10 } || n - b.leaptAt < 20 -> "leap"
            n < b.routeHeldUntil -> b.routeHeld ?: "breaker"
            n - b.blinkAt < 15 -> "hyperion"
            else -> "breaker"
        }
        setHeld(b, key)
    }

    // ------------------------------------------------------------------ outside P3

    /** The bots standing still at [spots] (P1, P2: leap targets). */
    fun standAt(spots: List<Vec3>) {
        clear()
        if (!botsOn) return
        bots().forEachIndexed { i, b -> spawn(b, spots[i % spots.size]); place(b) }
    }

    /**
     * P4: where teammates stand once they're down at Necron (measured 10 s in):
     * on the floor at y 64 around (45-62, 104-120), the Mage often right at (54, 101). From P3 they
     * drop in from the core over ~3 s (at 3 s most are still at y 69-75), so they land one by one.
     */
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

    // ------------------------------------------------------------------ places

    /**
     * Where a player stands to do each job: the most central of the spots players were on when its line
     * came (ADR runs, all five players). Levers are pulled mid-jump on the way past; a lever's is
     * the most-used floor spot in reach of it (eyes within 4 of the lever).
     */
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
