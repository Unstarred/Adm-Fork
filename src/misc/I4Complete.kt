package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.alert
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB

object I4Complete {
    private const val TITLE = "§a§li4 Complete!"

    private val PLATE = BlockPos(63, 127, 35)
    private val STAND_BOX = AABB(62.5, 124.5, 33.5, 64.5, 127.5, 35.5)
    private val CONTROL_CODES = Regex("§.")
    private val DEVICE_LINE = Regex("^(.{1,16}) completed a device! \\(\\d/\\d\\)$")

    private var tagWasActive: Boolean? = null
    private var shown = false

    fun register() = EventBus.subscribe(this)

    private fun reset() { tagWasActive = null; shown = false }

    private fun onPlate(): Boolean {
        val b = mc.player?.boundingBox ?: return false
        return b.maxX > PLATE.x && b.minX < PLATE.x + 1 && b.maxZ > PLATE.z && b.minZ < PLATE.z + 1 && b.minY >= PLATE.y - 0.01 && b.minY < PLATE.y + 0.25
    }

    private fun done() {
        if (shown || !RandomStuff.enabled || !onPlate()) return
        shown = true
        mc.execute { alert(TITLE, true) }
    }

    init {
        on<LevelEvent.Load> { reset() }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            if (msg == "[BOSS] Goldor: Who dares trespass into my domain?") { reset(); return@onReceive }
            val m = DEVICE_LINE.matchEntire(msg) ?: return@onReceive
            if (m.groupValues[1] == mc.player?.gameProfile?.name) done()
        }

        on<TickEvent.End> {
            val level = mc.level ?: return@on
            val active = level.getEntitiesOfClass(ArmorStand::class.java, STAND_BOX) { it.hasCustomName() }
                .any { it.customName?.string?.replace(CONTROL_CODES, "") == "Active" }
            val was = tagWasActive
            tagWasActive = active
            if (active && was == false) done()
        }
    }
}
