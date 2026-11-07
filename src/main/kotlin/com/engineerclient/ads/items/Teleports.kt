package com.engineerclient.ads.items

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.core.perPlayer
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.GoldorPhase

/**
 * The teleports, and the rest of the Hyperion's Wither Impact: its cast gate, Wither Shield and Implosion.
 *
 * Teleports follow Hypixel's measured rules: etherwarp is a voxel DDA
 * from the sneak eye over 61 blocks onto a block with room above (+0.5, +1.05, +0.5), blinks step
 * whole blocks (AOTV 12, Hyperion 10) checking every quarter; look kept, velocity zeroed.
 */
object Teleports {
    internal fun look(p: Player): Vec3 {
        val yaw = Math.toRadians(p.yRot.toDouble()); val pitch = Math.toRadians(p.xRot.toDouble())
        return Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    internal fun state(x: Int, y: Int, z: Int): BlockState = Sim.level.getBlockState(BlockPos(x, y, z))

    private fun collides(s: BlockState, pos: BlockPos) = !s.getCollisionShape(Sim.level, pos, CollisionContext.empty()).isEmpty

    /** The top of a cell's collision under its centre (0 = none). */
    private fun top(x: Int, y: Int, z: Int): Double {
        val pos = BlockPos(x, y, z)
        val shape = state(x, y, z).getCollisionShape(Sim.level, pos, CollisionContext.empty())
        if (shape.isEmpty) return 0.0
        var best = 0.0
        for (b in shape.toAabbs()) if (b.minX <= 0.5 && b.maxX >= 0.5 && b.minZ <= 0.5 && b.maxZ >= 0.5) best = maxOf(best, b.maxY)
        return if (best > 0) best else shape.max(Direction.Axis.Y)
    }

    private fun passThrough(s: BlockState) = s.block is SkullBlock || s.block is WallSkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock || s.block is ButtonBlock || s.block is LeverBlock
    private fun stopsAnyway(s: BlockState) = s.block is SignBlock || s.block is AbstractBannerBlock || s.block is TripWireHookBlock

    /** Etherwarp's ray stops at this cell. */
    private fun rayStops(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (passThrough(s)) return false
        if (stopsAnyway(s)) return true
        return collides(s, BlockPos(x, y, z))
    }

    /** A body fits in this cell. */
    private fun bodyPasses(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (stopsAnyway(s)) return true
        if (s.block is SkullBlock || s.block is WallSkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock) return false
        return !collides(s, BlockPos(x, y, z))
    }

    fun etherwarp(p: ServerPlayer, range: Double = 61.0) {
        // Cast from where you clicked: Hypixel reads the use packet after the move packets sent before it, never after the ones that
        // follow. A client that moves you at once (Odin's zero-ping etherwarp) has already sent the landing spot when the delayed cast
        // runs, so a position taken then was a second warp from there.
        val seen = SimItems.clickPos ?: p.position()
        val eye = Vec3(seen.x, seen.y + 1.27, seen.z)
        val dir = look(p)
        val hit = dda(eye, dir, range)
        if (hit == null) { etherwarpRefused?.invoke("no block within ${range.toInt()}"); return }
        val (x, y, z) = hit
        val s = state(x, y, z)
        val above = state(x, y + 1, z)
        // A standing banner is a floor to warp onto (2026-10-03_02-36-19 k1770: onto a black banner, landing on its cell + 1.05).
        val banner = s.block is BannerBlock
        val bad = (stopsAnyway(s) && !banner) || s.block is SkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock ||
            above.block is SkullBlock || above.block is WallSkullBlock || above.block is LadderBlock || above.block is FlowerPotBlock || (top(x, y, z) < 0.03 && !banner)
        val need = if (top(x, y, z) > 1.0) 3 else 2
        // A chest is room enough to warp into (2026-10-03_01-39-09 k1239, 2026-10-16_22-15-09 k337: onto the block under one; no refusal seen).
        fun room(cy: Int) = bodyPasses(x, cy, z) || state(x, cy, z).block is ChestBlock
        // A refused warp is silent: of 578 sneaking AOTV clicks that did not warp (ADR and the Dungeon Recorder, 139 F7 runs), 576 said nothing.
        val why = when {
            bad -> "the target ${s.block.descriptionId.substringAfterLast('.')} or above it ${above.block.descriptionId.substringAfterLast('.')}"
            (1..need).any { !room(y + it) } -> "no room above ${s.block.descriptionId.substringAfterLast('.')}: " + (1..need).joinToString { state(x, y + it, z).block.descriptionId.substringAfterLast('.') }
            rayStops(floor(eye.x).toInt(), floor(eye.y).toInt(), floor(eye.z).toInt()) -> "your eye in a block"
            else -> null
        }
        if (why != null) { etherwarpRefused?.invoke("$why at $x $y $z"); return }
        SimItems.etherPuff(p.position())
        SimItems.tpKeepLook(p, x + 0.5, y + 1.05, z + 0.5)
        // Etherwarp is ender_dragon.hurt 1/0.54 only, HOSTILE; enderman.teleport is the blink's.
        Sim.sound(SoundEvents.ENDER_DRAGON_HURT, 1f, 0.54f, p.position(), SoundSource.HOSTILE)
    }

    /** Amanatides-Woo DDA with the corner guard; the cell the ray stops in, or null. */
    private fun dda(eye: Vec3, dir: Vec3, range: Double): Triple<Int, Int, Int>? {
        var bx = floor(eye.x).toInt(); var by = floor(eye.y).toInt(); var bz = floor(eye.z).toInt()
        val sx = sign(dir.x).toInt(); val sy = sign(dir.y).toInt(); val sz = sign(dir.z).toInt()
        val dx = if (dir.x != 0.0) abs(1 / dir.x) else Double.MAX_VALUE
        val dy = if (dir.y != 0.0) abs(1 / dir.y) else Double.MAX_VALUE
        val dz = if (dir.z != 0.0) abs(1 / dir.z) else Double.MAX_VALUE
        fun first(o: Double, b: Int, s: Int, d: Double) = if (s > 0) (b + 1 - o) * d else if (s < 0) (o - b) * d else Double.MAX_VALUE
        var tx = first(eye.x, bx, sx, dx); var ty = first(eye.y, by, sy, dy); var tz = first(eye.z, bz, sz, dz)
        repeat(250) {
            val t = minOf(tx, ty, tz)
            if (t > range) return null
            val cx = tx <= t + 1e-4; val cy = ty <= t + 1e-4; val cz = tz <= t + 1e-4
            if ((if (cx) 1 else 0) + (if (cy) 1 else 0) + (if (cz) 1 else 0) >= 2) {
                if (cx && rayStops(bx + sx, by, bz)) return Triple(bx + sx, by, bz)
                if (cy && rayStops(bx, by + sy, bz)) return Triple(bx, by + sy, bz)
                if (cz && rayStops(bx, by, bz + sz)) return Triple(bx, by, bz + sz)
            }
            if (cx) { bx += sx; tx += dx }
            if (cy) { by += sy; ty += dy }
            if (cz) { bz += sz; tz += dz }
            if (rayStops(bx, by, bz)) return Triple(bx, by, bz)
        }
        return null
    }

    private fun blinkBlocks(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (s.block is SignBlock || s.block is AbstractBannerBlock) return false
        if (rayStops(x, y, z) && top(x, y, z) >= 0.5) return true
        return top(x, y - 1, z) > 1.0
    }

    private fun feetPass(x: Int, y: Int, z: Int) = (bodyPasses(x, y, z) || top(x, y, z) < 0.5) && top(x, y - 1, z) <= 1.0

    /** AOTV (12) / Hyperion (10): whole-block steps, every quarter checked; "There are blocks in the way!" when cut short. */
    fun blink(p: ServerPlayer, range: Int) {
        val eye = (SimItems.clickPos ?: p.position()).add(0.0, p.eyeHeight.toDouble(), 0.0)
        val dir = look(p)
        var last = 0
        var px = floor(eye.x).toInt(); var pz = floor(eye.z).toInt()
        var cut = false
        loop@ for (i in 1..range) {
            for (k in 1..4) {
                val q = eye.add(dir.scale((i - 1) + k * 0.25))
                if (blinkBlocks(floor(q.x).toInt(), floor(q.y).toInt(), floor(q.z).toInt())) { cut = true; break@loop }
            }
            val c = eye.add(dir.scale(i.toDouble()))
            val cx = floor(c.x).toInt(); val cy = floor(c.y).toInt(); val cz = floor(c.z).toInt()
            if (cx != px && cz != pz && blinkBlocks(px, cy, cz) && blinkBlocks(cx, cy, pz)) { cut = true; break }
            last = i; px = cx; pz = cz
        }
        // A landing needs room for the whole body: under a 1-high roof (a hole one block tall) the server stops at the sample before it
        // (PrecisionSnipes' blink lab, "roof1": both casts short of the roofed cell).
        fun feetAt(i: Int): Int { val c = eye.add(dir.scale(i.toDouble())); val cx = floor(c.x).toInt(); val cy = floor(c.y).toInt(); val cz = floor(c.z).toInt(); return if (feetPass(cx, cy - 1, cz)) cy - 1 else cy }
        fun roomy(i: Int): Boolean {
            val c = eye.add(dir.scale(i.toDouble())); val cx = floor(c.x).toInt(); val cz = floor(c.z).toInt(); val f = feetAt(i)
            // The head's part of the body (feet + 1 to + 1.8, 0.6 wide) clear of every collision.
            return Sim.level.noCollision(p, AABB(cx + 0.2, f + 1.0, cz + 0.2, cx + 0.8, f + 1.8, cz + 0.8).deflate(1e-3))
        }
        while (last > 0 && !roomy(last)) { last--; cut = true }
        if (last == 0) { Sim.chat("§cThere are blocks in the way!"); return }
        val c = eye.add(dir.scale(last.toDouble()))
        val cx = floor(c.x).toInt(); val cz = floor(c.z).toInt()
        val feetY = feetAt(last)
        if (cx == floor(p.x).toInt() && cz == floor(p.z).toInt() && (feetY == floor(p.y + 0.05).toInt() || Vec3(cx + 0.5, feetY.toDouble(), cz + 0.5).distanceTo(p.position()) < 1.5)) {
            Sim.chat("§cThere are blocks in the way!"); return
        }
        if (cut) Sim.chat("§cThere are blocks in the way!")
        SimItems.tpKeepLook(p, cx + 0.5, feetY.toDouble(), cz + 0.5)
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, p.position(), SoundSource.HOSTILE)
    }



    /**
     * Wither Impact casts at most every other server tick: a click the tick after a cast is discarded, blink and Implosion both (the
     * AOTV answers every tick's click). DR clears: every run of a click each tick cast every other tick (72..75 cast 73 and 76); over
     * 598 clusters of clicks 1-3 ticks apart, casts counted by your explode sounds, "2 ticks apart" misses by 149 casts in all,
     * "each tick" by 245 and "3 ticks apart" by 236. The rest is Hypixel's ticks drifting from yours.
     */
    internal var lastHype by perPlayer { -100 }

    /**
     * Set by a replay: whether Hypixel cast for the Hyperion click being handled (the recording's explode at you), or null for the rule.
     * Which of two clicks a tick apart Hypixel takes depends on how its ticks fell against yours, which a replay can only read.
     */
    @Volatile var replayCast: (() -> Boolean?)? = null

    /** Set by a replay: told why an etherwarp did not warp (for its report). */
    @Volatile var etherwarpRefused: ((String) -> Unit)? = null

    /** Set by a replay: whether the Hyperion click being handled, made inside an entity it also clicked, cast without its teleport. */
    @Volatile var replayStill: (() -> Boolean)? = null

    internal fun hypeReady(): Boolean {
        val now = Fight.serverTick
        if (now - lastHype < 2) return false
        lastHype = now
        return true
    }

    /**
     * A Hyperion Implosion's hit on a boss wither, for the chat line (no damage model in the sim):
     * real one-enemy lines are ~30-43M (median ~34M; 2 enemies 64-84M).
     */
    private const val IMPLOSION_DAMAGE = 34_000_000.0

    internal var lastCure by perPlayer { -1000 }

    /** Wither Shield: absorption back to 16 two ticks on; every ~5 s the cure sound and a ring of 16 witch particles. */
    private fun witherShield(p: ServerPlayer) {
        Fight.later(2, "wither shield") {
            if (p.isRemoved) return@later
            // 26.1.2 clamps absorption to MAX_ABSORPTION: make room for the 16 first.
            p.getAttribute(Attributes.MAX_ABSORPTION)?.let { if (it.baseValue < 16.0) it.baseValue = 16.0 }
            p.absorptionAmount = 16f
        }
        val now = Fight.serverTick
        if (now - lastCure < 100) return
        lastCure = now
        Sim.sound(SoundEvents.ZOMBIE_VILLAGER_CURE, 1f, 0.6984127f, p.position(), SoundSource.HOSTILE)
        val look = p.lookAngle
        var right = Vec3(-look.z, 0.0, look.x)
        right = if (right.lengthSqr() < 1e-6) Vec3(1.0, 0.0, 0.0) else right.normalize()
        val up = look.cross(right).normalize()
        val c = Vec3(p.x, p.y + 1.5, p.z).add(look.scale(0.5))
        for (i in 0 until 16) {
            val a = i * Math.PI * 2 / 16
            val q = c.add(right.scale(0.7 * Math.cos(a))).add(up.scale(0.7 * Math.sin(a)))
            Fight.later(Random.nextInt(0, 5), "shield ring") { Sim.level.sendParticles(ParticleTypes.WITCH, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0) }
        }
    }

    /**
     * Implosion: at your final position, every mob whose hitbox is within
     * ±6 x/z, +7 up and -6 down of your eye, through walls, full damage each. The boss withers are
     * the only mobs here; with none in the box there is no message.
     */
    internal fun implode(p: ServerPlayer) {
        // Main's Implosion puff: 8 explosion at your feet, speed 8, no spread, override limiter + always show.
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, true, true, p.x, p.y, p.z, 8, 0.0, 0.0, 0.0, 8.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 1f, p.position(), SoundSource.BLOCKS)
        witherShield(p)
        // A P3 cast never hits Goldor on Hypixel (no hit line, even with him in range).
        if (Fight.phase is GoldorPhase) return
        val box = AABB(p.x - 6, p.eyeY - 6, p.z - 6, p.x + 6, p.eyeY + 7, p.z + 6)
        val n = Sim.level.getEntitiesOfClass(WitherBoss::class.java, box) { it.isAlive }.size
        if (n == 0) return
        // The hit's ding (experience_orb.pickup 1.0/1.492 on the tp tick).
        Sim.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.492f, p.position(), SoundSource.PLAYERS)
        // Hypixel's exact line: "§7Your Implosion hit §r§c1 §r§7enemy for §r§c32,710,591.3 §r§7damage.", a whole number without ".0".
        val dmg = (1..n).sumOf { IMPLOSION_DAMAGE * (0.9 + Random.nextDouble() * 0.25) }
        val shown = "%,.1f".format(Locale.ROOT, dmg).removeSuffix(".0")
        Sim.chat("§7Your Implosion hit §r§c$n §r§7${if (n == 1) "enemy" else "enemies"} for §r§c$shown §r§7damage.")
    }
}
