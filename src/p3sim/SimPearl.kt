package com.engineerclient.p3sim

import com.engineerclient.p3sim.Sim
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

class SimPearl(level: Level, owner: LivingEntity, stack: ItemStack) : ThrownEnderpearl(level, owner, stack) {
    var scripted = false

    override fun onHitEntity(hit: EntityHitResult) {
    }

    override fun onHit(hit: HitResult) {
        val level = level() as? ServerLevel ?: return
        if (isRemoved) return
        repeat(ClearItems.PEARL_PUFFS) {
            level.sendParticles(ParticleTypes.PORTAL, false, true, x, y + random.nextDouble() * 2.0, z, 1, random.nextGaussian(), 0.0, random.nextGaussian(), 0.0)
        }
        if (scripted) { discard(); return }
        val thrower = getOwner() as? ServerPlayer
        if (thrower != null && thrower.isAlive && !thrower.isSleeping && thrower.level() === level) {
            val top = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK && it.direction == Direction.UP }
            land(thrower, top?.let { Vec3(it.blockPos.x + 0.5, it.location.y, it.blockPos.z + 0.5) } ?: oldPosition(), sound = false)
        }
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, position(), SoundSource.HOSTILE)
        discard()
    }

    companion object {
        fun land(thrower: ServerPlayer, to: Vec3, sound: Boolean = true) {
            if (!thrower.isAlive || thrower.isSleeping) return
            val level = thrower.level() as? ServerLevel ?: return
            thrower.teleportTo(level, to.x, to.y, to.z, Relative.ROTATION, 0f, 0f, false)
            thrower.deltaMovement = Vec3.ZERO
            thrower.fallDistance = 0.0
            if (sound) Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, to, SoundSource.HOSTILE)
        }
    }
}
