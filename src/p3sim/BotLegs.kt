package com.engineerclient.p3sim

import com.engineerclient.p3sim.BotBody.place
import com.engineerclient.p3sim.BotBody.setHeld
import com.engineerclient.p3sim.BotMoves.HURRY
import com.engineerclient.p3sim.BotMoves.fall
import com.engineerclient.p3sim.BotMoves.ground
import com.engineerclient.p3sim.Party.Bot
import com.engineerclient.p3sim.Party.CLASSES
import com.engineerclient.p3sim.Party.arrivedOnEe
import com.engineerclient.p3sim.Party.botOf
import com.engineerclient.p3sim.Party.botsOn
import com.engineerclient.p3sim.Party.dbg
import com.engineerclient.p3sim.Party.eeArrived
import com.engineerclient.p3sim.Party.eeOf
import com.engineerclient.p3sim.Party.generation
import com.engineerclient.p3sim.Party.jobs
import com.engineerclient.p3sim.Party.ontoLeaps
import com.engineerclient.p3sim.Party.ontoTarget
import com.engineerclient.p3sim.Party.partyLine
import com.engineerclient.p3sim.Party.released
import com.engineerclient.p3sim.Party.spotOf
import com.engineerclient.p3sim.Party.vec
import com.engineerclient.p3sim.Party.whoFor
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import java.io.File

object BotLegs {
    class Piece(val type: String, val frames: List<GhostRun.Frame>, val id: String, val k: Int, val land: List<GhostRun.Frame> = emptyList())

    class Legs(val pieces: List<Piece>) {
        var i = 0
        var t = 0.0
        var from: Vec3? = null
        var land = 0
        val leaptOnto = HashSet<String>()
        fun expectsLeap(c: String) = pieces.drop(i).any { it.type == "leap" && it.id == c }
    }

    internal fun leadFrame(b: Bot, t: Int) {
        val fr = b.leadFrames ?: return
        val f = fr[t.coerceIn(0, fr.size - 1)]
        b.pos = f.p.vec(); b.yaw = f.yaw; b.pitch = f.pitch; b.to = null; b.vy = 0.0
        setHeld(b, f.held.ifEmpty { "breaker" })
        place(b)
    }

    internal fun legsFor(b: Bot, from: Int): Legs? {
        if (!P3Sim.realMoves || !botsOn || from != 1) return null
        val pieces = legPieces(b.clazz.name)?.takeIf { it.isNotEmpty() } ?: return null
        dbg("§e${b.name}§7 moves as your legs: ${pieces.count { it.type == "move" && it.frames.isNotEmpty() }} pieces" +
            pieces.filter { it.type == "move" && it.frames.isEmpty() }.let { m -> if (m.isEmpty()) "" else " (§cnone recorded§7 into ${m.joinToString { it.id }})" })
        return Legs(pieces)
    }

    private var legsRead: Pair<String, Map<String, Pair<List<Piece>, List<GhostRun.Frame>>>>? = null

    private fun legPieces(clazz: String): List<Piece>? = legsFile()?.get(clazz)?.first

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
            .getOrElse { key to emptyMap() }.also { legsRead = it }
        return read?.second
    }

    private const val LEG_BLEND = 6.0

    internal fun legTick(b: Bot, phase: GoldorPhase) {
        val lg = b.legs ?: return
        val n = phase.n
        var steps = 0
        while (steps++ < 10) {
            val p = lg.pieces.getOrNull(lg.i) ?: break
            when (p.type) {
                "move" -> {
                    if (p.frames.isEmpty()) {
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

    private fun ontoNext(b: Bot, lg: Legs) {
        when (val nx = lg.pieces.getOrNull(lg.i + 1)) {
            null -> {}
            else -> when (nx.type) {
                "job" -> b.pos = spotOf(nx.id)
                "ee" -> eeOf(nx.k)?.let { b.pos = it.spot }
            }
        }
    }

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

    private fun settle(b: Bot, lg: Legs) {
        val f = lg.pieces.getOrNull(lg.i - 1)?.takeIf { it.type == "move" }?.land?.getOrNull(lg.land)
        if (f != null) { lg.land++; b.vy = f.p.y - b.pos.y; b.pos = f.p.vec(); b.yaw = f.yaw; b.pitch = f.pitch; return }
        val g = ground(b.pos.x, b.pos.z, b.pos.y)
        if (g != null && g < b.pos.y - 0.01) fall(b, b.pos.x, b.pos.z, g) else b.vy = 0.0
    }

    private fun leapReady(c: DungeonClass, phase: GoldorPhase): Boolean {
        val into = (phase.section..minOf(5, phase.section + 1)).firstOrNull { eeOf(it)?.owner == c && !released[it] } ?: return true
        if (phase.section >= into) return true
        val ee = eeOf(into) ?: return true
        return if (Players.isPlayerClass(c)) ee.player?.position()?.let { it.distanceTo(ee.spot) <= 3.0 } == true else eeArrived[into]
    }

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
        if (n - b.leaptAt > 15) setHeld(b, f0.held.ifEmpty { "breaker" })
    }
}
