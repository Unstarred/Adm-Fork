package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

class Station(
    val kind: Kind,
    val section: Int,
    val at: Vec3,
    val label: String,
    val lever: BlockPos? = null,
) {
    enum class Kind { TERMINAL, LEVER, DEVICE }

    var done = false
    var doneBy: String? = null
    var doneAt = -1
    var term: Terminals.Term? = null
    var top: ArmorStand? = null
    var bottom: ArmorStand? = null

    val id: String get() = "S$section $label"

    fun nextType(): Terminals.Type = Fight.forcedTerminal ?: Terminals.randomType()

    fun complete(by: String, twice: Boolean = false) {
        (Fight.phase as? GoldorPhase)?.complete(this, by, twice)
    }

    fun spawnStands() {
        val level = Sim.level
        fun stand(y: Double, name: String, marker: Boolean): ArmorStand {
            val s = SimStand(level)
            s.snapTo(at.x, y, at.z, 0f, 0f)
            s.isInvisible = true
            s.isPermanentlyInvulnerable = true
            s.isSilent = true
            s.setCustomName(if (name.isEmpty()) null else Sim.legacy(name))
            s.isCustomNameVisible = name.isNotEmpty()
            if (marker) { setMarker(s); setLeverFlags(s) }
            return Sim.spawn(s)
        }
        when (kind) {
            Kind.TERMINAL -> { top = stand(at.y + TOP_DY, INACTIVE, false); bottom = stand(at.y + BOTTOM_DY, CLICK_HERE, false) }
            Kind.DEVICE -> { top = stand(at.y, "§cInactive", false); bottom = stand(at.y - 0.375, "§cDevice", false) }
            Kind.LEVER -> { top = stand(at.y, "§cNot Activated", true) }
        }
        refreshStands()
    }

    fun refreshStands() {
        fun name(s: ArmorStand?, n: String) {
            s ?: return
            val c = if (n.isEmpty()) null else Sim.legacy(n)
            if (s.customName?.string != c?.string || (s.customName == null) != (c == null)) {
                s.setCustomName(c); s.isCustomNameVisible = c != null
            }
        }
        if (kind == Kind.TERMINAL) top?.let {
            val y = at.y + if (done) BOTTOM_DY else TOP_DY
            if (abs(it.y - y) > 1e-3) it.snapTo(at.x, y, at.z, 0f, 0f)
        }
        val ps = Sim.players()
        val far = kind != Kind.LEVER && ps.isNotEmpty() && ps.none { it.position().distanceTo(at) < 25.0 }
        if (far) { name(top, ""); name(bottom, ""); return }
        when (kind) {
            Kind.TERMINAL -> if (done) { name(top, "§aTerminal Active"); name(bottom, "") } else { name(top, INACTIVE); name(bottom, CLICK_HERE) }
            Kind.DEVICE -> if (done) { name(top, "§aDevice"); name(bottom, "§aActive") } else { name(top, "§cInactive"); name(bottom, "§cDevice") }
            Kind.LEVER -> name(top, if (done) "§aActivated" else "§cNot Activated")
        }
    }

    fun clearStands() {
        top?.discard(); bottom?.discard(); top = null; bottom = null
    }

    fun owns(e: Entity) = e === top || e === bottom

    companion object {
        const val TOP_DY = 0.02
        const val BOTTOM_DY = -0.355
        const val INACTIVE = "§cInactive Terminal"
        const val CLICK_HERE = "§e§lCLICK HERE"

        private val markerMethod by lazy { ArmorStand::class.java.getDeclaredMethod("setMarker", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
        fun setMarker(s: ArmorStand) { runCatching { markerMethod.invoke(s, true) } }

        fun setLeverFlags(s: ArmorStand) { runCatching { s.entityData.set(ArmorStand.DATA_CLIENT_FLAGS, 18.toByte()) } }

        private fun t(section: Int, n: Int, x: Double, y: Double, z: Double) = Station(Kind.TERMINAL, section, Vec3(x, y, z), "T$n")
        private fun l(section: Int, label: String, x: Int, y: Int, z: Int) = Station(Kind.LEVER, section, Vec3(x + 0.5, y + 0.688, z + 0.5), label, BlockPos(x, y, z))
        private fun d(section: Int, label: String, x: Double, y: Double, z: Double) = Station(Kind.DEVICE, section, Vec3(x, y, z), label)

        fun all(): List<Station> = listOf(
            t(1, 1, 110.5, 112.0, 73.5), t(1, 2, 110.5, 118.0, 79.5), t(1, 3, 90.5, 111.0, 92.5), t(1, 4, 90.5, 121.0, 101.5),
            l(1, "east lever", 106, 124, 113), l(1, "west lever", 94, 124, 113),
            d(1, "SS", 110.5, 119.0, 91.5),
            t(2, 1, 68.5, 108.0, 122.5), t(2, 2, 59.5, 119.0, 123.5), t(2, 3, 47.5, 108.0, 122.5), t(2, 4, 39.5, 107.0, 142.5), t(2, 5, 40.5, 123.0, 123.5),
            l(2, "low lever", 27, 124, 127), l(2, "high lever", 23, 132, 138),
            d(2, "Lights", 60.5, 131.0, 142.5),
            t(3, 1, -1.5, 108.0, 112.5), t(3, 2, -1.5, 118.0, 93.5), t(3, 3, 18.5, 122.0, 93.5), t(3, 4, -1.5, 108.0, 77.5),
            l(3, "west lever", 2, 122, 55), l(3, "east lever", 14, 122, 55),
            d(3, "Arrows", -1.5, 119.0, 74.5),
            t(4, 1, 41.5, 108.0, 30.5), t(4, 2, 44.5, 120.0, 30.5), t(4, 3, 67.5, 108.0, 30.5), t(4, 4, 72.5, 114.0, 47.5),
            l(4, "low lever", 84, 121, 34), l(4, "high lever", 86, 128, 46),
            d(4, "Target", 63.5, 126.0, 34.5),
        )

        fun total(section: Int) = if (section == 2) 8 else 7
    }
}

open class SimStand(level: Level) : ArmorStand(EntityTypes.ARMOR_STAND, level) {
    override fun travel(travelVector: Vec3) {}
}

class TransientStand(level: Level) : SimStand(level) {
    var trackSquare = 0
    override fun shouldBeSaved(): Boolean = false
    override fun broadcastToPlayer(player: ServerPlayer): Boolean =
        (trackSquare <= 0 || (Math.abs(player.x - x) <= trackSquare && Math.abs(player.z - z) <= trackSquare)) && super.broadcastToPlayer(player)
}

object Corpse {
    fun spawn(p: ServerPlayer, ticks: Int) {
        val made = ArrayList<Entity>()
        EngineerClient.safely("p3sim corpse") {
            val m = Mannequin(EntityTypes.MANNEQUIN, Sim.level)
            m.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(p.gameProfile))
            m.isPermanentlyInvulnerable = true
            m.setNoGravity(true)
            m.isSilent = true
            for (slot in listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
                m.setItemSlot(slot, p.getItemBySlot(slot).copy())
            m.pose = Pose.SLEEPING
            m.snapTo(p.x, p.y, p.z, p.yRot, 0f)
            made += Sim.spawn(m)
            fun stand(x: Double, y: Double, name: String?, head: ItemStack? = null, invisible: Boolean = true) {
                val s = SimStand(Sim.level)
                s.snapTo(x, y, p.z, 0f, 0f)
                s.isInvisible = invisible
                s.isPermanentlyInvulnerable = true
                s.isSilent = true
                Station.setMarker(s)
                if (name != null) { s.setCustomName(Sim.legacy(name)); s.isCustomNameVisible = true }
                if (head != null) s.setItemSlot(EquipmentSlot.HEAD, head)
                made += Sim.spawn(s)
            }
            stand(p.x, p.y - 1.28, "§a${p.gameProfile.name}")
            stand(p.x, p.y - 1.66, "§c§lDEAD")
            stand(p.x - 1.0, p.y, null, ItemStack(Items.PLAYER_HEAD), invisible = false)
        }
        Fight.later(ticks, "corpse gone") { made.forEach { it.discard() } }
    }
}
