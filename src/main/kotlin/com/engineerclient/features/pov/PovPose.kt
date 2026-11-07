package com.engineerclient.features.pov

import com.engineerclient.mixin.LivingEntityAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

object PovPose {

    enum class Mode { VANILLA, RAW, CUSTOM }

    fun interface Restore {
        fun restore()
    }

    private val NONE = Restore {}

    private val interpolator = PovInterpolator()

    private const val TICK_NANOS = 50_000_000L

    fun begin(entity: LivingEntity, mode: Mode, ticks: Int, partialTick: Float): Restore =
        when (mode) {
            Mode.VANILLA -> NONE
            Mode.RAW -> apply(entity, packetTarget(entity))
            Mode.CUSTOM -> {
                if (ticks <= 0) {
                    apply(entity, packetTarget(entity))
                } else {
                    val at = System.nanoTime() - ticks * TICK_NANOS
                    val pose = interpolator.poseAt(entity.uuid, at) ?: packetTarget(entity)
                    apply(entity, pose)
                }
            }
        }

    fun onClientTick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            interpolator.clear()
            return
        }
        val self = mc.player
        val now = System.nanoTime()
        for (player in level.players()) {
            if (player === self) continue
            interpolator.sample(player.uuid, now, packetTarget(player))
        }
        interpolator.evict(now)
    }

    fun lastSeen(id: UUID): PovInterpolator.Sample? = interpolator.newest(id)

    fun reset() = interpolator.clear()

    private fun packetTarget(entity: LivingEntity): PovInterpolator.Pose {
        val target = entity.interpolation.target()
        val pos = target?.position() ?: entity.position()
        val pitch = target?.xRot() ?: entity.xRot
        val accessor = entity as LivingEntityAccessor
        val headYaw =
            if (accessor.ec_getLerpHeadSteps() > 0) accessor.ec_getLerpYHeadRot().toFloat()
            else entity.yHeadRot
        return PovInterpolator.Pose(pos.x, pos.y, pos.z, headYaw, pitch)
    }

    private fun apply(entity: LivingEntity, pose: PovInterpolator.Pose): Restore {
        val oldPos = entity.position()
        val oldXo = entity.xo
        val oldYo = entity.yo
        val oldZo = entity.zo
        val oldXRot = entity.xRot
        val oldXRotO = entity.xRotO
        val oldHeadYaw = entity.yHeadRot
        val oldHeadYawO = entity.yHeadRotO

        entity.setPos(pose.x, pose.y, pose.z)
        entity.xo = pose.x
        entity.yo = pose.y
        entity.zo = pose.z
        entity.xRot = pose.pitch
        entity.xRotO = pose.pitch
        entity.yHeadRot = pose.headYaw
        entity.yHeadRotO = pose.headYaw

        return Restore {
            entity.setPos(oldPos.x, oldPos.y, oldPos.z)
            entity.xo = oldXo
            entity.yo = oldYo
            entity.zo = oldZo
            entity.xRot = oldXRot
            entity.xRotO = oldXRotO
            entity.yHeadRot = oldHeadYaw
            entity.yHeadRotO = oldHeadYawO
        }
    }
}
