package com.engineerclient.p3sim

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

object Teleports {
    internal fun look(p: Player): Vec3 {
        val yaw = Math.toRadians(p.yRot.toDouble()); val pitch = Math.toRadians(p.xRot.toDouble())
        return Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    internal fun state(x: Int, y: Int, z: Int): BlockState = Sim.level.getBlockState(BlockPos(x, y, z))

    private fun collides(s: BlockState, pos: BlockPos) = !s.getCollisionShape(Sim.level, pos, CollisionContext.empty()).isEmpty

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

    private fun rayStops(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (passThrough(s)) return false
        if (stopsAnyway(s)) return true
        return collides(s, BlockPos(x, y, z))
    }

    private fun bodyPasses(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (stopsAnyway(s)) return true
        if (s.block is SkullBlock || s.block is WallSkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock) return false
        return !collides(s, BlockPos(x, y, z))
    }

    fun etherwarp(p: ServerPlayer, range: Double = 61.0) {
        val seen = SimItems.clickPos ?: p.position()
        val eye = Vec3(seen.x, seen.y + 1.27, seen.z)
        val dir = look(p)
        val hit = dda(eye, dir, range)
        if (hit == null) { etherwarpRefused?.invoke("no block within ${range.toInt()}"); return }
        val (x, y, z) = hit
        val s = state(x, y, z)
        val above = state(x, y + 1, z)
        val banner = s.block is BannerBlock
        val bad = (stopsAnyway(s) && !banner) || s.block is SkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock ||
            above.block is SkullBlock || above.block is WallSkullBlock || above.block is LadderBlock || above.block is FlowerPotBlock || (top(x, y, z) < 0.03 && !banner)
        val need = if (top(x, y, z) > 1.0) 3 else 2
        fun room(cy: Int) = bodyPasses(x, cy, z) || state(x, cy, z).block is ChestBlock
        val why = when {
            bad -> "the target ${s.block.descriptionId.substringAfterLast('.')} or above it ${above.block.descriptionId.substringAfterLast('.')}"
            (1..need).any { !room(y + it) } -> "no room above ${s.block.descriptionId.substringAfterLast('.')}: " + (1..need).joinToString { state(x, y + it, z).block.descriptionId.substringAfterLast('.') }
            rayStops(floor(eye.x).toInt(), floor(eye.y).toInt(), floor(eye.z).toInt()) -> "your eye in a block"
            else -> null
        }
        if (why != null) { etherwarpRefused?.invoke("$why at $x $y $z"); return }
        SimItems.etherPuff(p.position())
        SimItems.tpKeepLook(p, x + 0.5, y + 1.05, z + 0.5)
        Sim.sound(SoundEvents.ENDER_DRAGON_HURT, 1f, 0.54f, p.position(), SoundSource.HOSTILE)
    }

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
        fun feetAt(i: Int): Int { val c = eye.add(dir.scale(i.toDouble())); val cx = floor(c.x).toInt(); val cy = floor(c.y).toInt(); val cz = floor(c.z).toInt(); return if (feetPass(cx, cy - 1, cz)) cy - 1 else cy }
        fun roomy(i: Int): Boolean {
            val c = eye.add(dir.scale(i.toDouble())); val cx = floor(c.x).toInt(); val cz = floor(c.z).toInt(); val f = feetAt(i)
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



    internal var lastHype by perPlayer { -100 }

    @Volatile var replayCast: (() -> Boolean?)? = null

    @Volatile var etherwarpRefused: ((String) -> Unit)? = null

    @Volatile var replayStill: (() -> Boolean)? = null

    internal fun hypeReady(): Boolean {
        val now = Fight.serverTick
        if (now - lastHype < 2) return false
        lastHype = now
        return true
    }

    private const val IMPLOSION_DAMAGE = 34_000_000.0

    internal var lastCure by perPlayer { -1000 }

    private fun witherShield(p: ServerPlayer) {
        Fight.later(2, "wither shield") {
            if (p.isRemoved) return@later
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

    internal fun implode(p: ServerPlayer) {
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, true, true, p.x, p.y, p.z, 8, 0.0, 0.0, 0.0, 8.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 1f, p.position(), SoundSource.BLOCKS)
        witherShield(p)
        if (Fight.phase is GoldorPhase) return
        val box = AABB(p.x - 6, p.eyeY - 6, p.z - 6, p.x + 6, p.eyeY + 7, p.z + 6)
        val n = Sim.level.getEntitiesOfClass(WitherBoss::class.java, box) { it.isAlive }.size
        if (n == 0) return
        Sim.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.492f, p.position(), SoundSource.PLAYERS)
        val dmg = (1..n).sumOf { IMPLOSION_DAMAGE * (0.9 + Random.nextDouble() * 0.25) }
        val shown = "%,.1f".format(Locale.ROOT, dmg).removeSuffix(".0")
        Sim.chat("§7Your Implosion hit §r§c$n §r§7${if (n == 1) "enemy" else "enemies"} for §r§c$shown §r§7damage.")
    }
}
