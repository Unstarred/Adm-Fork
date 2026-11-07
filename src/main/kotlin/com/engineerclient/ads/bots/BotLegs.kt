package com.engineerclient.ads.bots

import com.engineerclient.ads.bots.BotBody.place
import com.engineerclient.ads.bots.BotBody.setHeld
import com.engineerclient.ads.bots.BotMoves.HURRY
import com.engineerclient.ads.bots.BotMoves.fall
import com.engineerclient.ads.bots.BotMoves.ground
import com.engineerclient.ads.bots.Party.Bot
import com.engineerclient.ads.bots.Party.CLASSES
import com.engineerclient.ads.bots.Party.arrivedOnEe
import com.engineerclient.ads.bots.Party.botOf
import com.engineerclient.ads.bots.Party.botsOn
import com.engineerclient.ads.bots.Party.dbg
import com.engineerclient.ads.bots.Party.eeArrived
import com.engineerclient.ads.bots.Party.eeOf
import com.engineerclient.ads.bots.Party.generation
import com.engineerclient.ads.bots.Party.jobs
import com.engineerclient.ads.bots.Party.ontoLeaps
import com.engineerclient.ads.bots.Party.ontoTarget
import com.engineerclient.ads.bots.Party.partyLine
import com.engineerclient.ads.bots.Party.released
import com.engineerclient.ads.bots.Party.spotOf
import com.engineerclient.ads.bots.Party.vec
import com.engineerclient.ads.bots.Party.whoFor
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import java.io.File
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.party.Players
import com.engineerclient.ads.plan.Roles
import com.engineerclient.ads.plan.P3Plan

/**
 * Real Bot Movement's legs: a bot moving as you did, your recorded movements as its class from the skill's legs
 * file, played piece by piece on the plan's times.
 */
object BotLegs {
    /**
     * One piece of a bot's legs ([Legs]): [type] "move" (your movement, [frames], into [id]: the anchor it ends at), "job"
     * ([id]: held there till it's done), "leap" ([id]: onto that class), "ee" (on early-enter spot [k] till everyone's leapt
     * on) or "sec" (held till section [k] starts). A move that ended in the air has [land]: how you came down after it,
     * played while the bot holds at what comes next.
     */
    class Piece(val type: String, val frames: List<GhostRun.Frame>, val id: String, val k: Int, val land: List<GhostRun.Frame> = emptyList())

    /**
     * A bot's role played as you move: the fastest of each of your movements between its anchors (from every recorded run,
     * standing still cut out), one after another ([legTick]). [i]: the piece it's on; [t]: how far into a move piece (ticks);
     * [from]: where the bot was as that piece began (blended out over its first ticks); [leaptOnto]: the classes it's leapt onto.
     */
    class Legs(val pieces: List<Piece>) {
        var i = 0
        var t = 0.0
        var from: Vec3? = null
        /** How far into the last move's [Piece.land] it is. */
        var land = 0
        val leaptOnto = HashSet<String>()
        /** Still has a leap onto class [c] to come. */
        fun expectsLeap(c: String) = pieces.drop(i).any { it.type == "leap" && it.id == c }
    }

    /** [b] where you were [t] ticks into your lead-in (its last frame after), holding what you held. */
    internal fun leadFrame(b: Bot, t: Int) {
        val fr = b.leadFrames ?: return
        val f = fr[t.coerceIn(0, fr.size - 1)]
        b.pos = f.p.vec(); b.yaw = f.yaw; b.pitch = f.pitch; b.to = null; b.vy = 0.0
        setHeld(b, f.held.ifEmpty { "breaker" })
        place(b)
    }

    /**
     * [b]'s legs ([Legs]) from the skill's legs file, P3 from S1 only; null: Real Movement is off, or there are none for its
     * class (it moves on its own).
     */
    internal fun legsFor(b: Bot, from: Int): Legs? {
        if (!P3Sim.realMoves || !botsOn || from != 1) return null
        val pieces = legPieces(b.clazz.name)?.takeIf { it.isNotEmpty() } ?: return null
        dbg("§e${b.name}§7 moves as your legs: ${pieces.count { it.type == "move" && it.frames.isNotEmpty() }} pieces" +
            pieces.filter { it.type == "move" && it.frames.isEmpty() }.let { m -> if (m.isEmpty()) "" else " (§cnone recorded§7 into ${m.joinToString { it.id }})" })
        return Legs(pieces)
    }

    /** The skill's legs file read ("file@modified" -> pieces and lead-in by class), kept till the file changes. */
    private var legsRead: Pair<String, Map<String, Pair<List<Piece>, List<GhostRun.Frame>>>>? = null

    /**
     * Class [clazz]'s pieces from `config/adm/p3sim-legs/<skill>.json` (built from your recorded runs outside the
     * game): `{"classes": {"MAGE": {"pieces": [...], "lead": [...]}}}`, each piece `{"t": "move", "to": "job S2 T4", "f":
     * [[x, y, z, yaw, pitch, held], ...]}`, `{"t": "job", "id": "S2 T4"}`, `{"t": "leap", "onto": "BERSERK"}`, `{"t": "ee",
     * "k": 3}` or `{"t": "sec", "k": 3}`; "lead": your lead-in ([legLead]).
     */
    private fun legPieces(clazz: String): List<Piece>? = legsFile()?.get(clazz)?.first

    /** Class [clazz]'s lead-in from the legs file: where you were each tick from Storm's death line to P3; null: none. */
    internal fun legLead(clazz: String): List<GhostRun.Frame>? = legsFile()?.get(clazz)?.second?.takeIf { it.isNotEmpty() }

    private fun legsFile(): Map<String, Pair<List<Piece>, List<GhostRun.Frame>>>? {
        val f = File(Minecraft.getInstance().gameDirectory, "config/adm/p3sim-legs/${P3Plan.skillName()}.json")
        if (!f.exists()) return null
        val key = "${f.path}@${f.lastModified()}"
        fun frames(arr: JsonArray?) = arr?.map { fe ->
            val a = fe.asJsonArray
            GhostRun.Frame(P(a[0].asDouble, a[1].asDouble, a[2].asDouble), a[3].asFloat, a[4].asFloat, if (a.size() > 5) a[5].asString else "")
        }.orEmpty()
        val read = legsRead?.takeIf { it.first == key } ?: runCatching {
            val o = JsonParser.parseString(f.readText()).asJsonObject.getAsJsonObject("classes")
            key to o.entrySet().associate { (c, v) ->
                // A broken class is left out (its bot plays its role), not the whole file.
                c to runCatching {
                    val pieces = v.asJsonObject.getAsJsonArray("pieces")?.map { pe ->
                        val p = pe.asJsonObject
                        val type = p.get("t").asString
                        val id = when (type) { "job" -> p.get("id").asString; "leap" -> p.get("onto").asString; "move" -> p.get("to")?.asString ?: ""; else -> "" }
                        Piece(type, frames(p.getAsJsonArray("f")), id, p.get("k")?.asInt ?: 0, frames(p.getAsJsonArray("land")))
                    }.orEmpty()
                    pieces to frames(v.asJsonObject.getAsJsonArray("lead"))
                }.onFailure { Sim.note("§cLegs file ${f.name}: ${c} left out (${it.message})") }.getOrNull()
            }.filterValues { it != null }.mapValues { it.value!! }
        }.onFailure { Sim.note("§cLegs file ${f.name} can't be read: ${it.message}") }
            // A broken file is read once per change (not again on every call), as no legs.
            .getOrElse { key to emptyMap() }.also { legsRead = it }
        return read?.second
    }

    /** Ticks a piece blends from where the bot really is onto its recorded start (a leap landing elsewhere, the piece before's end). */
    private const val LEG_BLEND = 6.0

    /**
     * A tick of [b] playing its legs: a move piece at your pace (faster when its next job's time needs it), then whatever
     * holds it: a job (stood on till it's done at the plan's time), its early-enter spot (till everyone it waits for has
     * leapt on: [releaseEarlyEnterers]), a section start, or a leap (once whoever it's onto is on their early-enter spot).
     */
    internal fun legTick(b: Bot, phase: GoldorPhase) {
        val lg = b.legs ?: return
        val n = phase.n
        var steps = 0
        while (steps++ < 10) {
            val p = lg.pieces.getOrNull(lg.i) ?: break
            when (p.type) {
                "move" -> {
                    if (p.frames.isEmpty()) {
                        // Never recorded: straight onto where it's going (its job's stand, its early-enter spot).
                        ontoNext(b, lg)
                        lg.i++; continue
                    }
                    if (lg.from == null) lg.from = b.pos
                    val last = (p.frames.size - 1).toDouble()
                    lg.t = minOf(last, lg.t + legSpeed(b, lg, n))
                    showFrame(b, lg, p, n)
                    if (lg.t >= last) { lg.i++; lg.t = 0.0; lg.from = null; lg.land = 0 }
                    place(b)
                    return
                }
                "job" -> { if (jobs.any { it.bot === b && it.job == p.id }) break; lg.i++ }
                "sec" -> { if (phase.section < p.k) break; lg.i++ }
                "ee" -> {
                    if (eeOf(p.k)?.owner != b.clazz) { lg.i++; continue }
                    b.inSection = maxOf(b.inSection, p.k)
                    if (!eeArrived[p.k]) arrivedOnEe(b, p.k, n)
                    if (released[p.k] || phase.section > p.k) { b.hold = false; lg.i++; continue }
                    b.hold = true
                    break
                }
                "leap" -> {
                    val c = CLASSES.firstOrNull { it.name == p.id }
                    if (c == null || c == b.clazz) { lg.i++; continue }
                    // Onto someone at their terminal (the plan's "2 onto 1"): once they're there; else onto an early enterer on its spot.
                    val om = ontoLeaps.firstOrNull { whoFor(it.who, it.section + 1) == b.clazz && whoFor(it.args.firstOrNull() ?: "", it.section + 1) == c }
                    if (om != null) { if (ontoTarget(om) == null) break } else if (!leapReady(c, phase)) break
                    lg.i++
                    val to = Players.playerOf(c)?.position() ?: botOf(c)?.pos ?: continue
                    landLeap(b, lg, c, to, n)
                    return
                }
                else -> lg.i++
            }
        }
        settle(b, lg)
        place(b)
    }

    /** [b] straight onto where the piece after its unrecorded move goes: its job's stand, or its early-enter spot. */
    private fun ontoNext(b: Bot, lg: Legs) {
        when (val nx = lg.pieces.getOrNull(lg.i + 1)) {
            null -> {}
            else -> when (nx.type) {
                "job" -> b.pos = spotOf(nx.id)
                "ee" -> eeOf(nx.k)?.let { b.pos = it.spot }
            }
        }
    }

    /** [b] lands its leap onto [c] at [to]: holding the leap, the sound, and "Leaped to" in party chat a tick later. */
    private fun landLeap(b: Bot, lg: Legs, c: DungeonClass, to: Vec3, n: Int) {
        b.pos = to; b.to = null; b.vy = 0.0; b.leaptAt = n
        lg.leaptOnto += c.name
        setHeld(b, "leap")
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, to)
        val name = Players.playerOf(c)?.gameProfile?.name ?: Roles.label(c)
        if (name == Sim.hostName) GhostCapture.event("landed", b.clazz.name)
        val gen = generation
        Fight.later(1, "leap announce") { if (gen == generation) Sim.chat(partyLine(b, "Leaped to $name!")) }
        place(b)
    }

    /**
     * [b] holding (a job, its early-enter spot, a section start, a leap) is never stood still in mid-air: your landing after
     * the move before plays out first (you did the job falling, or mid-bonzo), then it falls at vanilla gravity onto the floor.
     */
    private fun settle(b: Bot, lg: Legs) {
        val f = lg.pieces.getOrNull(lg.i - 1)?.takeIf { it.type == "move" }?.land?.getOrNull(lg.land)
        if (f != null) { lg.land++; b.vy = f.p.y - b.pos.y; b.pos = f.p.vec(); b.yaw = f.yaw; b.pitch = f.pitch; return }
        val g = ground(b.pos.x, b.pos.z, b.pos.y)
        if (g != null && g < b.pos.y - 0.01) fall(b, b.pos.x, b.pos.z, g) else b.vy = 0.0
    }

    /** A leap onto [c] can go: they aren't an early enterer still to get on their spot (the leap waits for them there). */
    private fun leapReady(c: DungeonClass, phase: GoldorPhase): Boolean {
        // Only the next section's (onto the core early enterer in S2: onto them where they are, not held till the core).
        val into = (phase.section..minOf(5, phase.section + 1)).firstOrNull { eeOf(it)?.owner == c && !released[it] } ?: return true
        if (phase.section >= into) return true
        val ee = eeOf(into) ?: return true
        return if (Players.isPlayerClass(c)) ee.player?.position()?.let { it.distanceTo(ee.spot) <= 3.0 } == true else eeArrived[into]
    }

    /**
     * How far through its pieces [b] goes this tick: 1 (your pace), more when the job it's heading for (with only moves and
     * leaps before it) is due before it would get there at your pace.
     */
    private fun legSpeed(b: Bot, lg: Legs, n: Int): Double {
        var left = (lg.pieces[lg.i].frames.size - 1) - lg.t
        for (k in lg.i + 1 until lg.pieces.size) {
            val p = lg.pieces[k]
            when (p.type) {
                "move" -> left += (p.frames.size - 1).coerceAtLeast(0)
                "leap" -> {}
                "job" -> {
                    val j = jobs.firstOrNull { it.bot === b && it.job == p.id } ?: return 2.0
                    if (j.at < 0) return 1.0
                    val ticks = j.at - n
                    return if (ticks <= 0) 3.0 else (left.toDouble() / ticks).coerceIn(1.0, HURRY)
                }
                else -> return 1.0
            }
        }
        return 1.0
    }

    /** [b] where [p] is at [lg]'s point in it: between its frames, blended in from where the bot was when it began. */
    private fun showFrame(b: Bot, lg: Legs, p: Piece, n: Int) {
        val i = lg.t.toInt().coerceIn(0, p.frames.size - 1)
        val f0 = p.frames[i]
        val f1 = p.frames[(i + 1).coerceAtMost(p.frames.size - 1)]
        val k = lg.t - i
        var pos = if (f0.p.dist(f1.p) > GhostRecording.LEAP_JUMP) f0.p.vec() else Vec3(f0.p.x + (f1.p.x - f0.p.x) * k, f0.p.y + (f1.p.y - f0.p.y) * k, f0.p.z + (f1.p.z - f0.p.z) * k)
        val from = lg.from
        val w = 1.0 - lg.t / LEG_BLEND
        if (from != null && w > 0) { val s = p.frames[0].p; pos = pos.add((from.x - s.x) * w, (from.y - s.y) * w, (from.z - s.z) * w) }
        b.pos = pos; b.yaw = f0.yaw; b.pitch = f0.pitch; b.to = null; b.vy = 0.0
        // Its item as you held it (the leap a moment after landing).
        if (n - b.leaptAt > 15) setHeld(b, f0.held.ifEmpty { "breaker" })
    }
}
