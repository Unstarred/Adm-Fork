package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import kotlin.random.Random

object Lava {
    private const val HIGH_PITCH = -40.7f
    private const val VY = 2.25
    private const val VY_HIGH = 3.0375
    private const val COOLDOWN = 4

    private class Seen(val ns: Long, val inLava: Boolean, val pitch: Float)
    private val seen: ArrayDeque<Seen> by perPlayer { ArrayDeque() }
    private var bouncedAt by perPlayer { -1000 }
    private var hurtAt by perPlayer { -1000 }
    fun reset() { seen.clear(); bouncedAt = -1000; hurtAt = -1000 }

    fun tick(p: ServerPlayer) {
        if (p.isSpectator || p.isCreative) { reset(); return }
        val now = Fight.serverTick
        if (p.remainingFireTicks > 0) p.clearFire()
        val nowNs = System.nanoTime()
        seen.addLast(Seen(nowNs, inLava(p), p.xRot))
        while (seen.size > 40) seen.removeFirst()
        if (now % 2 != 0) return
        val half = P3Sim.ping / 2.0
        val target = nowNs - (half * 1e6).toLong()
        val s = seen.lastOrNull { it.ns <= target } ?: seen.first()
        if (!s.inLava) return
        if (now - bouncedAt < COOLDOWN + 2 * Math.round(half / 50.0).toInt()) return
        val sinceLast = now - bouncedAt
        bouncedAt = now
        val vy = if (s.pitch <= HIGH_PITCH) VY_HIGH else VY
        Fight.afterMs(if (half > 0) Fight.pingMs() / 2 else 0.0, "lava bounce") { if (!p.isRemoved) bounce(p, vy, sinceLast) }
    }

    private fun inLava(p: ServerPlayer): Boolean {
        val l = Sim.level
        for (x in Math.floor(p.x - 0.2).toInt()..Math.floor(p.x + 0.2).toInt())
            for (y in Math.floor(p.y + 0.4).toInt()..Math.floor(p.y + 1.4).toInt())
                for (z in Math.floor(p.z - 0.2).toInt()..Math.floor(p.z + 0.2).toInt())
                    if (l.getFluidState(BlockPos(x, y, z)).`is`(FluidTags.LAVA)) return true
        return false
    }

    private fun silentChance(sinceLast: Int, sinceHurt: Int) = when {
        sinceHurt < 10 -> 0.75
        sinceLast < 50 -> 0.07
        sinceLast < 150 -> 0.16
        sinceLast < 200 -> 0.20
        sinceLast < 250 -> 0.32
        else -> 0.47
    }

    private fun bounce(p: ServerPlayer, vy: Double, sinceLast: Int) {
        p.deltaMovement = Vec3(0.0, vy, 0.0)
        p.syncVelocity = true
        p.fallDistance = 0.0
        p.clearFire()
        val now = Fight.serverTick
        if (Random.nextDouble() < silentChance(sinceLast, now - hurtAt)) return
        hurtAt = now
        val hurt = {
            if (!p.isRemoved) {
                p.connection.send(ClientboundHurtAnimationPacket(p))
                Sim.sound(SoundEvents.PLAYER_HURT, 1f, 1f, p.position(), SoundSource.PLAYERS)
            }
        }
        if (Random.nextDouble() < 0.4) Fight.later(1, "lava hurt") { hurt() } else hurt()
    }
}
