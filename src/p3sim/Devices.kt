package com.engineerclient.p3sim

import com.engineerclient.practice.SimonSaysDevice
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.RedstoneLampBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

class Devices(val phase: GoldorPhase) {
    val ss = SimonSays()
    val lights = Lights()
    val arrows = Arrows()
    val target = Target()

    fun start() { ss.place(); lights.place(); arrows.place(); target.place() }
    fun stop() { arrows.remove() }
    fun tick() { ss.tick(); target.tick() }

    private fun station(label: String) = phase.stations.first { it.kind == Station.Kind.DEVICE && it.label == label }

    fun shownDone(label: String) = when (label) {
        "SS" -> ss.clear()
        "Lights" -> lights.solve()
        "Arrows" -> arrows.solve()
        "Target" -> target.clear()
        else -> Unit
    }

    fun use(pos: BlockPos): Boolean = ss.use(pos) || lights.use(pos)

    inner class SimonSays {
        private val START = BlockPos(110, 121, 91)
        private fun buttonAt(cell: Int) = BlockPos(110, 123 - cell / 4, 92 + cell % 4)
        private fun lampAt(cell: Int) = BlockPos(111, 123 - cell / 4, 92 + cell % 4)
        private val BUTTON: BlockState get() = B.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.WEST)

        private val pressers = HashMap<String, ServerPlayer?>()

        private val device = SimonSaysDevice(object : SimonSaysDevice.Io {
            override fun newSequence() = this@SimonSays.newSequence().toIntArray()
            override fun lamp(cell: Int, on: Boolean) = light(cell, on)
            override fun button(cell: Int, up: Boolean, pressed: Boolean) =
                Blocks.set(buttonAt(cell), if (up) BUTTON.setValue(ButtonBlock.POWERED, pressed) else B.AIR.defaultBlockState())
            override fun completed(by: String) {
                Sim.asPlayer(pressers[by]) { station("SS").complete(by) }
            }
        })

        private val done get() = station("SS").done

        fun place() {
            Blocks.set(START, BUTTON)
            clear()
        }

        fun clear() {
            device.clear()
            pressers.clear()
            for (c in 0 until 16) { light(c, false); Blocks.set(buttonAt(c), B.AIR.defaultBlockState()) }
        }

        fun tick() = device.tick()

        private fun light(cell: Int, on: Boolean) = Blocks.set(lampAt(cell), if (on) B.SEA_LANTERN.defaultBlockState() else B.OBSIDIAN.defaultBlockState())

        fun use(pos: BlockPos): Boolean {
            if (pos == START) {
                val ping = Fight.pingMs()
                val at = System.nanoTime() / 1e6 + ping
                Fight.afterMs(ping, "ss start") { pressStart(at) }
                return true
            }
            val cell = (0 until 16).firstOrNull { buttonAt(it) == pos } ?: return false
            Fight.afterPing("ss press") { press(cell) }
            return true
        }

        private fun pressStart(ms: Double) {
            Blocks.set(START, BUTTON.setValue(ButtonBlock.POWERED, true))
            Fight.later(2, "ss start up") { if (phase === Fight.phase) Blocks.set(START, BUTTON) }
            if (done || phase.section != 1) return
            val who = Sim.me
            pressers[who] = Sim.acting
            if (device.start(who, ms) == SimonSaysDevice.Start.RESET) Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f)
        }

        private fun newSequence(): List<Int> {
            val luck = P3Sim.ssLuck
            val x = Math.pow(3000.0, Math.abs(luck))
            val n = (x.toInt() + if (Random.nextDouble() < x - x.toInt()) 1 else 0).coerceAtLeast(1)
            val draws = List(n) { (0 until 16).shuffled().take(4) }
            return if (luck > 0) draws.minBy { path(it) } else draws.maxBy { path(it) }
        }

        private fun path(s: List<Int>) = s.zipWithNext().sumOf { (a, b) -> Math.hypot((a % 4 - b % 4).toDouble(), (a / 4 - b / 4).toDouble()) }

        private fun press(cell: Int) {
            val who = Sim.me
            pressers[who] = Sim.acting
            val r = device.press(cell, who)
            if (r == SimonSaysDevice.Press.NONE) return
            Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f)
            if (r != SimonSaysDevice.Press.WRONG) return
            val plain = Random.nextDouble() < 0.5 + 0.5 * P3Sim.ssLuck
            val stale = { device.after(1 + Random.nextInt(4)) { device.buttonsBack() } }
            if (plain) device.after(3 + 26) { device.showRound(2); stale() }
            else {
                val gap = listOf(4, 5, 5, 6, 6, 6, 6, 7, 7, 8).random()
                device.after(3 + 34 - gap) { device.showRound(1); stale() }
                device.after(3 + 34) { device.showRound(2); stale() }
            }
        }
    }

    inner class Lights {
        private val right = setOf(58 to 133, 58 to 136, 60 to 134, 60 to 135, 62 to 133, 62 to 136)
        private val levers = (58..62).flatMap { x -> (133..136).map { y -> x to y } }
        private val on = HashSet<Pair<Int, Int>>()

        fun place() { on.clear(); on += right; draw() }

        fun solve() { on.clear(); on += right; draw() }

        private fun lamp(x: Int, y: Int) = (x to y) in on || (x - 1 to y) in on || (x + 1 to y) in on || (x to y - 1) in on || (x to y + 1) in on
        private fun allLit() = levers.all { (x, y) -> lamp(x, y) }

        private fun draw(lampOffDelay: Int = 0) = levers.forEach { (x, y) ->
            val lever = BlockPos(x, y, 142)
            val st = Blocks.get(lever)
            if (st != null && st.hasProperty(LeverBlock.POWERED)) Blocks.set(lever, st.setValue(LeverBlock.POWERED, (x to y) in on))
            val lampPos = BlockPos(x, y, 143)
            val lit = lamp(x, y)
            if (lit || lampOffDelay <= 0) Blocks.set(lampPos, B.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, lit))
            else Fight.later(lampOffDelay, "lamp off") {
                if (phase === Fight.phase && !lamp(x, y)) Blocks.set(lampPos, B.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, false))
            }
        }

        fun isLever(pos: BlockPos) = pos.z == 142 && (pos.x to pos.y) in levers

        fun use(pos: BlockPos, left: Boolean = false): Boolean {
            if (!isLever(pos)) return false
            Fight.afterPing("lights lever") {
                val st = station("Lights")
                if (st.done) return@afterPing
                var credit = false
                Fight.later(1, "lights lever") {
                    if (phase !== Fight.phase || st.done) return@later
                    val wasLit = allLit()
                    val k = pos.x to pos.y
                    if (!left) {
                        if (!on.remove(k)) on += k
                        draw(if (Random.nextInt(6) == 0) 4 else 3)
                        Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (k in on) 0.5873016f else 0.4920635f, Vec3.atCenterOf(pos), SoundSource.BLOCKS)
                    }
                    credit = phase.section in 1..2 && (wasLit || (!left && allLit()))
                }
                Fight.later(2, "lights credit") { if (credit && phase === Fight.phase && !st.done) st.complete(Sim.me, twice = left) }
            }
            return true
        }
    }

    fun leftClick(pos: BlockPos): Boolean {
        phase.leverAt(pos)?.let { st -> Fight.afterPing("lever") { phase.pullLever(st, Sim.me, left = true) }; return true }
        return lights.use(pos, left = true)
    }

    inner class Arrows {
        private val frames = HashMap<Int, ItemFrame>()
        private var solution = SOLUTIONS[0]

        fun place() {
            remove()
            val b = idleBoard?.takeIf { it.frames.values.none { f -> f.isRemoved } } ?: newBoard()
            idleBoard = null
            solution = SOLUTIONS[b.layout]
            frames.putAll(b.frames)
        }

        fun remove() { frames.values.forEach { it.discard() }; frames.clear() }

        private fun lightWall() {
            for (i in frames.keys) Blocks.set(-3, 120 + i % 5, 75 + i / 5, B.SEA_LANTERN.defaultBlockState())
        }

        fun solve() {
            lightWall()
            var delay = 0
            for ((i, f) in frames.entries.sortedBy { it.key }.map { it.key to it.value }) {
                if (solution[i] < 0 || f.rotation == solution[i]) continue
                Fight.later(delay++, "arrows solve") {
                    if (phase !== Fight.phase || !frames.containsValue(f)) return@later
                    f.setRotation(solution[i])
                    Sim.sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f, f.position(), SoundSource.PLAYERS)
                }
            }
        }

        fun use(frame: ItemFrame): Boolean {
            val i = frames.entries.firstOrNull { it.value === frame }?.key ?: return false
            Fight.afterPing("arrow") {
                val st = station("Arrows")
                if (st.done) return@afterPing
                if (solution[i] < 0) {
                    frame.setRotation((frame.rotation + 1) % 8)
                    Sim.sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f, frame.position(), SoundSource.PLAYERS)
                    return@afterPing
                }
                frame.setRotation((frame.rotation + 1) % 8)
                Sim.sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f, frame.position(), SoundSource.PLAYERS)
                if (phase.section in 1..3 && frames.all { (j, f) -> solution[j] < 0 || f.rotation == solution[j] }) { lightWall(); st.complete(Sim.me) }
            }
            return true
        }

        fun owns(e: Entity) = frames.values.any { it === e }
    }

    inner class Target {
        val PLATE = BlockPos(63, 127, 35)
        private val blocks = (0 until 9).map { BlockPos(64 + (it % 3) * 2, 126 + (it / 3) * 2, 50) }
        private var lit = -1
        private var hits = 0
        private var order = (0 until 9).shuffled()
        private var grid = Random.nextInt(10)
        private var shownOnce = false

        fun place() { reset(); blocks.forEach { Blocks.set(it, B.DYED_TERRACOTTA.blue().defaultBlockState()) } }
        fun clear() { lit = -1; blocks.forEach { Blocks.set(it, B.DYED_TERRACOTTA.blue().defaultBlockState()) } }

        private fun reset() { lit = -1; hits = 0; order = (0 until 9).shuffled(); grid = Random.nextInt(10); shownOnce = false }

        private val current get() = if (hits < 9) order[hits] else -1

        private var held = false
        private var offAt = -1

        fun onPlate(): Boolean {
            if (rawOnPlate()) { held = true; offAt = -1 }
            else if (held) {
                if (offAt < 0) offAt = phase.t + 1 + Random.nextInt(10)
                if (phase.t >= offAt) { held = false; offAt = -1 }
            }
            return held
        }

        private var plateBy: String? = null

        var botOn: String? = null

        val shown: BlockPos? get() = if (lit >= 0) blocks[lit] else null

        private fun rawOnPlate(): Boolean {
            botOn?.let { plateBy = it; return true }
            val p = Sim.players().firstOrNull { pl ->
                val b = pl.boundingBox
                b.maxX > PLATE.x && b.minX < PLATE.x + 1 && b.maxZ > PLATE.z && b.minZ < PLATE.z + 1 && b.minY >= PLATE.y - 0.01 && b.minY < PLATE.y + 0.25
            } ?: return false
            plateBy = p.gameProfile.name
            return true
        }

        fun tick() {
            val st = station("Target")
            onPlate()
            if (st.done || phase.section > 4 || (phase.t + 1 - grid).mod(10) != 0) return
            if (onPlate()) {
                if (current >= 0 && lit != current) light()
            } else if (lit >= 0 || hits > 0) {
                if (lit >= 0) Blocks.set(blocks[lit], B.DYED_TERRACOTTA.blue().defaultBlockState())
                reset()
            }
        }

        private fun light() {
            lit = current
            shownOnce = true
            Blocks.set(blocks[lit], B.EMERALD_BLOCK.defaultBlockState())
        }

        fun hit(pos: BlockPos) {
            val st = station("Target")
            if (st.done || current < 0 || pos != blocks[current]) return
            if (!shownOnce) return
            if (lit < 0 && hits == 0 && !onPlate()) return
            if (lit == current) { Blocks.set(pos, B.DYED_TERRACOTTA.blue().defaultBlockState()); lit = -1 }
            hits++
            if (hits >= 9) { clear(); st.complete(plateBy ?: Sim.me) }
        }

        val targets get() = blocks
    }

    companion object {
        class Board(val layout: Int, val frames: Map<Int, ItemFrame>)

        private var idleBoard: Board? = null

        fun showIdle() {
            idleBoard?.frames?.values?.forEach { it.discard() }
            idleBoard = null
            val right = setOf(58 to 133, 58 to 136, 60 to 134, 60 to 135, 62 to 133, 62 to 136)
            for (x in 58..62) for (y in 133..136) {
                val lever = BlockPos(x, y, 142)
                val st = Blocks.get(lever)
                if (st != null && st.hasProperty(LeverBlock.POWERED)) Blocks.set(lever, st.setValue(LeverBlock.POWERED, (x to y) in right))
                val lit = listOf(x to y, x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { it in right }
                Blocks.set(BlockPos(x, y, 143), B.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, lit))
            }
            idleBoard = newBoard()
        }

        private fun newBoard(): Board {
            val layout = (1 until SOLUTIONS.size).random()
            val solution = SOLUTIONS[layout]
            val frames = HashMap<Int, ItemFrame>()
            for (i in 0 until 25) {
                val wool = EXTRAS[layout][i]
                if (solution[i] < 0 && wool == null) continue
                val pos = BlockPos(-2, 120 + i % 5, 75 + i / 5)
                val f = ItemFrame(EntityTypes.ITEM_FRAME, Sim.level, pos, Direction.EAST)
                f.isPermanentlyInvulnerable = true
                if (solution[i] >= 0) {
                    f.setItem(ItemStack(Items.ARROW), false)
                    f.setRotation(solution[i])
                } else f.setItem(
                    if (wool == true) Terminals.named(Items.WOOL.lime(), "Start", color = ChatFormatting.GREEN)
                    else Terminals.named(Items.WOOL.red(), "End", color = ChatFormatting.RED), false)
                frames[i] = Sim.spawn(f)
            }
            for (i in 0 until 25) {
                val lit = i in frames && (solution[i] < 0 || Random.nextInt(8) == 0)
                Blocks.set(-3, 120 + i % 5, 75 + i / 5, (if (lit) B.SEA_LANTERN else B.DYED_TERRACOTTA.blue()).defaultBlockState())
            }
            frames.entries.filter { solution[it.key] >= 0 }.minByOrNull { (j, _) -> val dy = j % 5; val dz = 4 - j / 5; dy * dy + dz * dz }
                ?.let { (j, f) -> f.setRotation((solution[j] + 7) % 8) }
            return Board(layout, frames)
        }

        val SOLUTIONS = listOf(
            listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1),
            listOf(-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1),
            listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3),
            listOf(5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1),
            listOf(5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
            listOf(7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
            listOf(-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
            listOf(-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1),
            listOf(-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
        )

        val EXTRAS: List<Map<Int, Boolean>> = listOf(
            mapOf(2 to false, 22 to false),
            mapOf(5 to true, 15 to true, 14 to false),
            mapOf(12 to true, 2 to false, 22 to false),
            mapOf(4 to true, 24 to true, 12 to false),
            mapOf(20 to true, 12 to false),
            mapOf(20 to true, 4 to false),
            mapOf(20 to true, 22 to true, 24 to true, 0 to false, 2 to false, 4 to false),
            mapOf(20 to true, 0 to false),
            mapOf(20 to true, 22 to true, 24 to true, 1 to false, 3 to false),
        )
    }
}
