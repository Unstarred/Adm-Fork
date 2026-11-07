package com.engineerclient.features.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.PacketEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.SimonSays
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import java.util.concurrent.ConcurrentHashMap

object OdinSimonSays {

    private val better = BooleanSetting("Better Solver", true,
        desc = "ADM's solver: handles skips after a fail, 2-light skips, flashes before the show and lag. Odin still draws, blocks and announces from it.")

    @JvmStatic
    fun active(): Boolean = SimonSays.enabled && better.value

    private val solver = SimonSaysSolver<BlockPos>()

    val shownRound: Int get() = solver.answer.size
    val pressedInRound: Int get() = solver.next
    @Volatile private var tick = 0L
    private val lampLit = ConcurrentHashMap<BlockPos, Boolean>()
    private val buttonTick = ConcurrentHashMap<BlockPos, Long>()

    private val GRID = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }

    private val odinOrder by lazy { SimonSays::class.java.getDeclaredField("clickInOrder").apply { isAccessible = true } }
    private val odinNeeded by lazy { SimonSays::class.java.getDeclaredField("clickNeeded").apply { isAccessible = true } }

    fun install() {
        SimonSays.registerSetting(better)
        on<TickEvent.Server> { tick++ }
        on<LevelEvent.Load> { solver.reset(); lampLit.clear(); buttonTick.clear() }
        on<PacketEvent.Receive> { onPacket() }
        on<TickEvent.End> {
            if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            EngineerClient.safely("ss solver tick") { solver.serverTick(tick - 1); sync() }
        }
        on<BlockUpdateEvent> {
            if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            if (pos.x != 110 || pos.y !in 120..123 || pos.z !in 92..95) return@on
            EngineerClient.safely("ss solver") {
                val level = EngineerClient.mc.level ?: return@safely
                val packetTick = buttonTick[pos] ?: tick
                when {
                    updated.block is ButtonBlock && old.block !is ButtonBlock ->
                        if (GRID.count { level.getBlockState(it).block is ButtonBlock } >= 8) solver.buttonsUp(packetTick)
                    updated.isAir && !old.isAir ->
                        if (GRID.count { level.getBlockState(it).isAir } > 8) solver.buttonsGone(packetTick)
                    updated.block is ButtonBlock && old.block is ButtonBlock &&
                        updated.getValue(BlockStateProperties.POWERED) && !old.getValue(BlockStateProperties.POWERED) ->
                        solver.pressed(pos.east(), tick)
                    else -> return@safely
                }
                sync()
            }
        }
        EventBus.subscribe(this)
    }

    private fun PacketEvent.Receive.onPacket() {
        val changes = when (val p = packet) {
            is ClientboundBlockUpdatePacket -> if (onDevice(p.pos)) listOf(p.pos.immutable() to p.blockState) else return
            is ClientboundSectionBlocksUpdatePacket -> ArrayList<Pair<BlockPos, BlockState>>().also { l ->
                p.runUpdates { pos, state -> if (onDevice(pos)) l += pos.immutable() to state }
            }.ifEmpty { return }
            else -> return
        }
        val at = tick
        val lamps = changes.filter { it.first.x == 111 }.map { (pos, state) ->
            val lit = state.block == Blocks.SEA_LANTERN
            Triple(pos, lit, lampLit.put(pos, lit) ?: !lit)
        }
        for ((pos, _) in changes) if (pos.x == 110) buttonTick[pos] = at
        if (lamps.isEmpty()) return
        val handle = Runnable {
            if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@Runnable
            EngineerClient.safely("ss solver lamps") {
                for ((pos, lit, was) in lamps) solver.lamp(pos, lit, was, at)
                sync()
            }
        }
        if (EngineerClient.mc.isSameThread) handle.run() else EngineerClient.mc.execute(handle)
    }

    private fun onDevice(pos: BlockPos) = (pos.x == 110 || pos.x == 111) && pos.y in 120..123 && pos.z in 92..95

    private fun sync() {
        @Suppress("UNCHECKED_CAST")
        val order = odinOrder.get(null) as ArrayList<BlockPos>
        order.clear(); order.addAll(solver.answer)
        odinNeeded.setInt(null, solver.next)
    }
}
