package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.boss.wither.WitherBoss
import java.util.concurrent.ConcurrentHashMap

object Witherborn {
    private const val NEAR = 4.5

    private val minions: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val seen: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun register() {
        onReceive<ClientboundAddEntityPacket>(priority = Int.MAX_VALUE) {
            if (type == EntityTypes.WITHER) onSpawn(id, x, y, z)
        }
        on<LevelEvent.Load> { minions.clear(); seen.clear() }
        EventBus.subscribe(this)
    }

    @JvmStatic
    fun onSpawn(id: Int, x: Double, y: Double, z: Double): Boolean {
        if (!seen.add(id)) return id in minions
        val players = runCatching { EngineerClient.mc.level?.players()?.toList() }.getOrNull() ?: return false
        val near = players.any { it.distanceToSqr(x, y, z) <= NEAR * NEAR }
        if (near) minions += id
        return near
    }

    @JvmStatic
    fun isMinion(e: Entity): Boolean = e.id in minions || (e is WitherBoss && e.scale < 0.9f)

    @JvmStatic
    fun isBoss(e: Entity?): Boolean = e is WitherBoss && !isMinion(e)
}
