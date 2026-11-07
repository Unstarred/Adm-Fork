package com.engineerclient.ads.bots

import com.engineerclient.ads.bots.BotBody.place
import com.engineerclient.ads.bots.BotBody.swing
import com.engineerclient.ads.bots.Party.Bot
import com.engineerclient.ads.bots.Party.Target
import com.engineerclient.ads.bots.Party.curPhase
import com.engineerclient.ads.bots.Party.dbg
import com.engineerclient.ads.bots.Party.dbgN
import com.engineerclient.ads.bots.Party.digOn
import com.engineerclient.ads.bots.Party.generation
import com.engineerclient.ads.bots.Party.leadIn
import com.engineerclient.ads.bots.Party.partyLine
import com.engineerclient.ads.bots.Party.short
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.fight.Fight

/**
 * How a bot gets where it's sent ([go]) on its own: walking, Hyperion blinks, a real player's route ([Routes]),
 * falling, the floor it stands on, and its leaps onto the others ([leapTo]).
 */
object BotMoves {
    /**
     * Bot walking speed, blocks a tick: teammates' sustained 1 s ground speed in P3 is 0.60 / 0.85 /
     * 1.21 (p10 / median / p90, measured from recorded runs).
     */
    private const val WALK = 0.85
    /**
     * A Hyperion blink, 10 blocks along the look. The few teleports teammates make in P3 that aren't
     * leaps are these (Hyperion held, ~10 blocks): nearly all their distance is
     * walked or leapt. A bot blinks only where it can't walk (a wall, a climb) or is late.
     */
    private const val BLINK = 10.0

    /** The least time at its terminal a bot takes (a fast solver) before it hurries instead. */
    private const val MIN_LEAD = 12

    /** Off to [to] (from n = [at], there by [due] less [lead] ticks; -1: a player's pace), for [why]. */
    internal fun go(b: Bot, to: Vec3, at: Int, due: Int, why: String, face: Pair<Float, Float>? = null, lead: Int = 0) {
        if (b.legs != null) return
        b.face = face
        b.lead = lead
        if (b.to != to) dbg("§e${b.name}§7 -> $why (${to.short()}, ${"%.0f".format(b.pos.distanceTo(to))} blocks${if (due >= 0) ", due in ${(due - dbgN) / 20.0}s" else ""}${if (at > dbgN) ", leaves in ${(at - dbgN) / 20.0}s" else ""})")
        b.to = to; b.goAt = at; b.due = due
    }

    /**
     * A tick of getting there, as teammates do: walking on the
     * blocks at [WALK] (up steps and 1-block climbs, falling off edges), and a Hyperion [BLINK]
     * toward it where walking can't (a wall, somewhere higher, lava) - every 8 ticks - or won't make
     * it in time - every 4, every 2 (Hyperion's own limit) once it's due already. P3's floors are
     * islands over lava that players cross with jumps and Bonzo/Jerry boosts (rises of 1-1.6 a tick);
     * the blinks stand in for those (no walking path exists between most job spots on the sim's arena).
     */
    internal fun move(b: Bot, n: Int) {
        b.dig?.let { way -> digOn(b, way, n); place(b); return }
        val to = b.to
        if (to != null && n >= b.goAt && follow(b, to, n)) { place(b); return }
        if (to != null && n >= b.goAt) {
            val d = to.subtract(b.pos)
            val len = d.length()
            val flat = Math.hypot(d.x, d.z)
            if (flat > 0.01) { b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat(); b.pitch = 0f }
            val late = b.due >= 0 && (b.due <= n || len / (b.due - n) > WALK * 1.15)
            if (len <= WALK && Math.abs(d.y) <= 1.25) arrive(b, to)
            else {
                // The walk this tick: where it'd stand (null: it can't - a wall, or no floor it should drop to).
                val step = if (flat > 0.01) d.multiply(1.0, 0.0, 1.0).scale(minOf(WALK, flat) / flat) else Vec3.ZERO
                val next = b.pos.add(step)
                val g = ground(next.x, next.z, b.pos.y)
                // Not: into a wall, under somewhere higher, off a drop it would have to climb back, or nowhere nearer.
                val walkable = g != null && !(to.y - b.pos.y > 1.25 && flat < 2.0) && !(g < b.pos.y - 4 && to.y > g + 3) &&
                    (flat > 0.01 || g < b.pos.y - 0.01)
                when {
                    (late || !walkable) && n >= b.blinkReady -> blink(b, to, n, if (b.due in 0..n) 2 else if (late) 4 else 8)
                    walkable -> fall(b, next.x, next.z, g!!)
                    else -> ground(b.pos.x, b.pos.z, b.pos.y)?.let { fall(b, b.pos.x, b.pos.z, it) }
                }
            }
        } else if (to == null && !b.hold) {
            // Standing in the air (leapt onto someone mid-jump): it drops, as anyone does.
            ground(b.pos.x, b.pos.z, b.pos.y)?.takeIf { it < b.pos.y - 0.01 }?.let { fall(b, b.pos.x, b.pos.z, it) }
        }
        place(b)
    }

    /** The fastest a route plays: to be at a job by its time at all (later than that: the job's late). */
    internal const val HURRY = 4.0
    /** What a route shows the bot holding where the player held it (anything else: what it holds anyway). */
    private val ROUTE_ITEMS = setOf("STARRED_BONZO_STAFF", "BONZO_STAFF", "JERRY_STAFF", "SUPERBOOM_TNT", "HYPERION", "ASPECT_OF_THE_VOID", "ENDER_PEARL")

    /**
     * A tick along the way a real player went to [to] ([Routes]: sprints, jumps, lava bounces, boosts,
     * stonks), found when it sets off; false: Real Movement is off or there's no route (it walks). At a
     * player's pace ([pace]), faster only when that would be late.
     */
    private fun follow(b: Bot, to: Vec3, n: Int): Boolean {
        if (!P3Sim.realMoves) return false
        if (b.routeTo != to) {
            b.routeTo = to; b.routeT = 0.0
            val r = Routes.find(b.pos, to, ::closed)
            b.route = r
            if (r == null) dbg("§e${b.name}§7: no real route to ${to.short()}, walking")
            else dbg("§e${b.name}§7: route ${"%.1f".format(r.ticks / 20)}s at a player's pace" +
                if (b.due >= 0) ", ${"%.1f".format((b.due - b.lead - n) / 20.0)}s to be there" else "")
        }
        val r = b.route ?: return false
        b.routeT += pace(b, r.ticks - b.routeT, n)
        if (b.routeT >= r.ticks) { arrive(b, to); return true }
        val p = r.pos(b.routeT)
        val i = r.frameAt(b.routeT)
        // Looking where the player looked there (turning at most 40° a tick), else the way it goes.
        val ry = r.yaw[i]
        if (!ry.isNaN()) {
            b.yaw += Mth.wrapDegrees(ry - b.yaw).coerceIn(-40f, 40f)
            b.pitch += (r.pitch[i] - b.pitch).coerceIn(-30f, 30f)
        } else if (Math.hypot(p.x - b.pos.x, p.z - b.pos.z) > 0.05) b.yaw = Math.toDegrees(Math.atan2(-(p.x - b.pos.x), p.z - b.pos.z)).toFloat()
        // The item the player had out; a boost (rising a block or more in a tick) starts with its click.
        val rise = p.y - b.pos.y
        r.held[i]?.takeIf { it in ROUTE_ITEMS }?.let { h ->
            b.routeHeld = h; b.routeHeldUntil = n + 8
            if (rise > 1.0 && b.vy <= 0.5 && h != "HYPERION") swing(b)
        }
        b.vy = rise
        b.pos = p
        return true
    }

    /**
     * Route ticks to play this tick, [left] still to go: a player's pace if that gets it there [Bot.lead]
     * before its time, or at least [MIN_LEAD] (a terminal: a fast solve); else as fast as it must for
     * that (up to [HURRY]).
     */
    private fun pace(b: Bot, left: Double, n: Int): Double {
        if (b.due < 0 || left <= 0) return 1.0
        val min = minOf(b.lead, MIN_LEAD)
        if (n + left <= b.due - min) return 1.0
        val by = b.due - min - n
        return if (by <= 0) HURRY else (left / by).coerceIn(1.0, HURRY)
    }

    /** An opening bots can't go through yet (by [Routes] id): a gate standing, a door or the core shut. */
    private fun closed(id: Int): Boolean {
        // The lead-in: every gate, door and the core still shut.
        if (leadIn) return true
        val ph = curPhase ?: return false
        return when (id) {
            in 1..3 -> !ph.gateIsDown(id)
            Routes.CORE_DOOR -> ph.section < 5
            else -> ph.section <= id - 4
        }
    }

    /** To ([x], [z]) on floor [g]: up onto it at once (a step or jump), down to it at vanilla gravity. */
    internal fun fall(b: Bot, x: Double, z: Double, g: Double) {
        if (g >= b.pos.y - 0.01) { b.pos = Vec3(x, g, z); b.vy = 0.0; return }
        b.vy = (b.vy - 0.08) * 0.98
        b.pos = Vec3(x, maxOf(g, b.pos.y + b.vy), z)
        if (b.pos.y <= g) b.vy = 0.0
    }

    internal fun arrive(b: Bot, to: Vec3) {
        b.pos = to; b.to = null; b.vy = 0.0; b.route = null; b.routeTo = null
        b.face?.let { b.yaw = it.first; b.pitch = it.second }
    }

    /** A Hyperion blink toward [to]: 10 blocks, onto the floor if one's just under where it ends (else it falls from there). */
    private fun blink(b: Bot, to: Vec3, n: Int, gap: Int) {
        b.blinkReady = n + gap
        b.blinkAt = n
        b.vy = 0.0
        val d = to.subtract(b.pos)
        if (d.length() <= BLINK) { arrive(b, to); return }
        val q = b.pos.add(d.normalize().scale(BLINK))
        val g = ground(q.x, q.z, q.y)
        b.pos = if (g != null && g >= q.y - 3) Vec3(q.x, g, q.z) else q
    }

    /**
     * The floor a body at height [y] would stand on at ([x], [z]): stepping up to 1.25 (a jump) or
     * dropping any way down, with 1.8 of room above it, under any part of its feet (a body is 0.6
     * wide: the terminal spots are on stair edges). Null: a wall (no room at that height) or nothing
     * to stand on within 40 blocks.
     */
    internal fun ground(x: Double, z: Double, y: Double): Double? {
        var best: Double? = null
        for (cx in setOf(Math.floor(x - 0.29).toInt(), Math.floor(x + 0.29).toInt()))
            for (cz in setOf(Math.floor(z - 0.29).toInt(), Math.floor(z + 0.29).toInt())) {
                val g = column(cx, cz, y)
                if (g == WALL) return null
                if (g != null && (best == null || g > best)) best = g
            }
        return best
    }

    /** [ground]'s answer for "a wall here". */
    internal const val WALL = Double.MAX_VALUE

    /** One block column's floor for a body at [y] ([WALL]: no room there; null: nothing below). */
    private fun column(bx: Int, bz: Int, y: Double): Double? {
        val level = Sim.level
        var cy = Math.floor(y + 1.25).toInt()
        val bottom = cy - 40
        while (cy > bottom) {
            val pos = BlockPos(bx, cy, bz)
            val state = level.getBlockState(pos)
            // Nobody walks into the lava under P3's floors: it's a blink across.
            if (state.fluidState.`is`(FluidTags.LAVA)) return WALL
            val shape = state.getCollisionShape(level, pos)
            if (!shape.isEmpty) {
                val top = cy + shape.max(Direction.Axis.Y)
                if (top > y + 1.25) return WALL
                // Room for the body above it.
                for (hy in Math.floor(top + 0.01).toInt()..Math.floor(top + 1.79).toInt()) {
                    if (hy == cy) continue
                    val hp = BlockPos(bx, hy, bz)
                    val hs = level.getBlockState(hp).getCollisionShape(level, hp)
                    if (!hs.isEmpty && hy + hs.min(Direction.Axis.Y) < top + 1.8) return WALL
                }
                return top
            }
            cy--
        }
        return null
    }

    /** [b] leaps onto [to]: lands there facing as whoever it leapt to; their mod says so in party chat a tick later. */
    internal fun leapTo(b: Bot, to: Target, n: Int) {
        val at = to.pos
        b.pos = at; b.to = null; b.vy = 0.0; b.leaptAt = n; b.route = null; b.routeTo = null
        to.yaw?.let { b.yaw = it }
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, at)
        // Most teammates' leaps are announced, usually 1 tick after the tp.
        val name = to.name ?: return
        // On you: what an early enterer (you, recording) waits for.
        if (name == Sim.hostName) GhostCapture.event("landed", b.clazz.name)
        val gen = generation
        Fight.later(1, "leap announce") { if (gen == generation) Sim.chat(partyLine(b, "Leaped to $name!")) }
    }
}
