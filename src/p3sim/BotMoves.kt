package com.engineerclient.p3sim

import com.engineerclient.p3sim.BotBody.place
import com.engineerclient.p3sim.BotBody.swing
import com.engineerclient.p3sim.Party.Bot
import com.engineerclient.p3sim.Party.Target
import com.engineerclient.p3sim.Party.curPhase
import com.engineerclient.p3sim.Party.dbg
import com.engineerclient.p3sim.Party.dbgN
import com.engineerclient.p3sim.Party.digOn
import com.engineerclient.p3sim.Party.generation
import com.engineerclient.p3sim.Party.leadIn
import com.engineerclient.p3sim.Party.partyLine
import com.engineerclient.p3sim.Party.short
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

object BotMoves {
    private const val WALK = 0.85
    private const val BLINK = 10.0

    private const val MIN_LEAD = 12

    internal fun go(b: Bot, to: Vec3, at: Int, due: Int, why: String, face: Pair<Float, Float>? = null, lead: Int = 0) {
        if (b.legs != null) return
        b.face = face
        b.lead = lead
        if (b.to != to) dbg("§e${b.name}§7 -> $why (${to.short()}, ${"%.0f".format(b.pos.distanceTo(to))} blocks${if (due >= 0) ", due in ${(due - dbgN) / 20.0}s" else ""}${if (at > dbgN) ", leaves in ${(at - dbgN) / 20.0}s" else ""})")
        b.to = to; b.goAt = at; b.due = due
    }

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
                val step = if (flat > 0.01) d.multiply(1.0, 0.0, 1.0).scale(minOf(WALK, flat) / flat) else Vec3.ZERO
                val next = b.pos.add(step)
                val g = ground(next.x, next.z, b.pos.y)
                val walkable = g != null && !(to.y - b.pos.y > 1.25 && flat < 2.0) && !(g < b.pos.y - 4 && to.y > g + 3) &&
                    (flat > 0.01 || g < b.pos.y - 0.01)
                when {
                    (late || !walkable) && n >= b.blinkReady -> blink(b, to, n, if (b.due in 0..n) 2 else if (late) 4 else 8)
                    walkable -> fall(b, next.x, next.z, g!!)
                    else -> ground(b.pos.x, b.pos.z, b.pos.y)?.let { fall(b, b.pos.x, b.pos.z, it) }
                }
            }
        } else if (to == null && !b.hold) {
            ground(b.pos.x, b.pos.z, b.pos.y)?.takeIf { it < b.pos.y - 0.01 }?.let { fall(b, b.pos.x, b.pos.z, it) }
        }
        place(b)
    }

    internal const val HURRY = 4.0
    private val ROUTE_ITEMS = setOf("STARRED_BONZO_STAFF", "BONZO_STAFF", "JERRY_STAFF", "SUPERBOOM_TNT", "HYPERION", "ASPECT_OF_THE_VOID", "ENDER_PEARL")

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
        val ry = r.yaw[i]
        if (!ry.isNaN()) {
            b.yaw += Mth.wrapDegrees(ry - b.yaw).coerceIn(-40f, 40f)
            b.pitch += (r.pitch[i] - b.pitch).coerceIn(-30f, 30f)
        } else if (Math.hypot(p.x - b.pos.x, p.z - b.pos.z) > 0.05) b.yaw = Math.toDegrees(Math.atan2(-(p.x - b.pos.x), p.z - b.pos.z)).toFloat()
        val rise = p.y - b.pos.y
        r.held[i]?.takeIf { it in ROUTE_ITEMS }?.let { h ->
            b.routeHeld = h; b.routeHeldUntil = n + 8
            if (rise > 1.0 && b.vy <= 0.5 && h != "HYPERION") swing(b)
        }
        b.vy = rise
        b.pos = p
        return true
    }

    private fun pace(b: Bot, left: Double, n: Int): Double {
        if (b.due < 0 || left <= 0) return 1.0
        val min = minOf(b.lead, MIN_LEAD)
        if (n + left <= b.due - min) return 1.0
        val by = b.due - min - n
        return if (by <= 0) HURRY else (left / by).coerceIn(1.0, HURRY)
    }

    private fun closed(id: Int): Boolean {
        if (leadIn) return true
        val ph = curPhase ?: return false
        return when (id) {
            in 1..3 -> !ph.gateIsDown(id)
            Routes.CORE_DOOR -> ph.section < 5
            else -> ph.section <= id - 4
        }
    }

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

    internal const val WALL = Double.MAX_VALUE

    private fun column(bx: Int, bz: Int, y: Double): Double? {
        val level = Sim.level
        var cy = Math.floor(y + 1.25).toInt()
        val bottom = cy - 40
        while (cy > bottom) {
            val pos = BlockPos(bx, cy, bz)
            val state = level.getBlockState(pos)
            if (state.fluidState.`is`(FluidTags.LAVA)) return WALL
            val shape = state.getCollisionShape(level, pos)
            if (!shape.isEmpty) {
                val top = cy + shape.max(Direction.Axis.Y)
                if (top > y + 1.25) return WALL
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

    internal fun leapTo(b: Bot, to: Target, n: Int) {
        val at = to.pos
        b.pos = at; b.to = null; b.vy = 0.0; b.leaptAt = n; b.route = null; b.routeTo = null
        to.yaw?.let { b.yaw = it }
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, at)
        val name = to.name ?: return
        if (name == Sim.hostName) GhostCapture.event("landed", b.clazz.name)
        val gen = generation
        Fight.later(1, "leap announce") { if (gen == generation) Sim.chat(partyLine(b, "Leaped to $name!")) }
    }
}
