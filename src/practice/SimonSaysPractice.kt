package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.mixin.MinecraftAccessor
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.SimonSays
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.playSoundSettings
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Matrix4f
import java.util.Locale
import java.util.Random

object SimonSaysPractice : Module(
    name = "SS Practice",
    category = Category.custom("ADM", 860, 10),
    description = "Summons F7's first device (Simon Says) in front of you to practice it anywhere. Client side only: the blocks and your clicks never reach the server.",
    key = null,
) {
    private val summonKey by KeybindSetting("Summon Keybind", InputConstants.UNKNOWN, "Summons the device in front of you, and takes it away again. With Infinileap in your hand: /termsim inf instead, the numbers that never ends.").onPress { if (!LeapNumbersSim.open()) summonOrRemove() }
    private val showSpeed by NumberSetting("Show Speed", 1.0, 1.0..3.0, 0.25, desc = "How fast the lights are shown (1x = the game's 8 ticks each). Only the lights: the buttons still come back 10 ticks after the last light's tick (on a skip, on the first show's), as in the game.").withDependency { !instantShow }
    private val instantShow by BooleanSetting("Instant Show", false, desc = "The whole sequence at once, no lights shown (the solver still marks it). Only the show: the buttons still come back 10 ticks after it (on a skip, after the first show), as in the game. Times are as at 1x.")
    private val fullBlockHitboxes by BooleanSetting("Full Block Hitboxes", false, desc = "On the practice device, each button clicks as the whole face of its block (still a button's depth), and the solver boxes cover the whole face too.")
    private val triggerBot by BooleanSetting("Trigger Bot", false, desc = "Practice device only (never the real one): presses the button under your crosshair the moment it's the one to press next.")
    private val follow by BooleanSetting("Follow You", false, desc = "The device stays the same distance in front of you as you move, and turns around when you do (more than 100 degrees from it): to the side it stays where it is. Off: it stays where you summoned it.")
    private val size by NumberSetting("Size", 1.0, 1.0..3.0, 0.5, desc = "How big the device is drawn, and as many times farther away (2x: twice as big, twice as far), so it looks the same from where you summoned it. Clicks reach it as they would at 1x.", unit = "x")

    private val CORRECT_SOUND = Triple("entity.experience_orb.pickup", 1f, 1f)
    private val WRONG_SOUND = Triple("entity.blaze.hurt", 1f, 1f)

    private const val AX = 108; private const val AY = 120; private const val AZ = 94
    private val START = BlockPos(110, 121, 91)
    private val EXTRA get() = BlockPos(110, 120, 91)
    private fun fromButton(n: Int) = BlockPos(110, 119 + n, 91)
    private fun buttonAt(cell: Int) = BlockPos(110, 123 - cell / 4, 92 + cell % 4)
    private fun lampAt(cell: Int) = BlockPos(111, 123 - cell / 4, 92 + cell % 4)

    private val TOP_ROUNDS = doubleArrayOf(1.10, 0.80, 1.05)

    private val FIRST = Colors.MINECRAFT_GREEN.withAlpha(0.5f)
    private val SECOND = Colors.MINECRAFT_GOLD.withAlpha(0.5f)
    private val THIRD = Colors.MINECRAFT_RED.withAlpha(0.5f)

    private val BUTTON: BlockState by lazy { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:stone_button[face=wall,facing=west,powered=false]", false).blockState() }

    private class Placement(val level: ClientLevel, val origin: BlockPos, val forward: Direction, val eye: Vec3) {
        val right: Direction = forward.clockWise
        val rotation: Rotation = when (forward) {
            Direction.SOUTH -> Rotation.CLOCKWISE_90
            Direction.WEST -> Rotation.CLOCKWISE_180
            Direction.NORTH -> Rotation.COUNTERCLOCKWISE_90
            else -> Rotation.NONE
        }
        fun at(dx: Int, dy: Int, dz: Int): BlockPos =
            origin.offset(forward.stepX * dx + right.stepX * dz, dy, forward.stepZ * dx + right.stepZ * dz)
        fun at(real: BlockPos) = at(real.x - AX, real.y - AY, real.z - AZ)
        val blocks = LinkedHashMap<BlockPos, BlockState>()
        val signs = LinkedHashMap<BlockPos, SignBlockEntity>()
        val lightPos: BlockPos = origin.above()
        fun setWorld(pos: BlockPos, state: BlockState) {
            if (state.isAir) blocks.remove(pos) else blocks[pos.immutable()] = state.rotate(rotation)
        }
        fun set(real: BlockPos, state: BlockState) = setWorld(at(real), state)
        fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): AABB {
            fun wx(x: Double, z: Double) = origin.x + 0.5 + forward.stepX * (x - AX - 0.5) + right.stepX * (z - AZ - 0.5)
            fun wz(x: Double, z: Double) = origin.z + 0.5 + forward.stepZ * (x - AX - 0.5) + right.stepZ * (z - AZ - 0.5)
            val y = origin.y - AY
            return AABB(wx(x0, z0), y0 + y, wz(x0, z0), wx(x1, z1), y1 + y, wz(x1, z1))
        }
    }

    private var placed: Placement? = null

    val practicing: Boolean get() = placed != null

    private fun summonOrRemove() {
        if (placed != null) { remove(); EngineerClient.msg("§7SS Practice: removed."); return }
        val player = mc.player ?: return
        val level = mc.level ?: return
        val p = Placement(level, player.blockPosition(), player.direction, player.getEyePosition(1f))
        EngineerClient.safely("ss practice summon") {
            for (y in 119..124) for (z in 91..96) {
                val grid = y in 120..123 && z in 92..95
                p.set(BlockPos(111, y, z), if (grid) Blocks.OBSIDIAN.defaultBlockState() else Blocks.WOOL.black().defaultBlockState())
            }
            p.set(START, BUTTON)
            p.set(EXTRA, BUTTON)
            for (n in 3..4) p.set(fromButton(n), BUTTON)
            sign(p, EXTRA, "Inf", "")
            sign(p, START, "Start", "3x for the skip")
            for (n in 3..4) sign(p, fromButton(n), "Start on r$n", "on repeat")
            for (level in -1..2) {
                p.set(rngButton(level), BUTTON)
                val (a, b) = when (level) { -1 -> "Harder" to "spread out"; 0 -> "Normal RNG" to "random"; 1 -> "Easier" to "tighter"; else -> "Easiest" to "tightest" }
                sign(p, rngButton(level), a, b, Direction.SOUTH)
            }        }
        placed = p
        turned = 0
        gridCells = (0 until 16).map { p.at(buttonAt(it)) }.toSet()
        reset()
        markMode(0)
        markRng()
        EngineerClient.msg("§7SS Practice: summoned. Press the start button §8(left of the grid)§7 to begin, 3 times for the skip. The others are signed. The keybind again takes it away.")
    }

    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private val CONTROL_CODES = Regex("§.")

    private fun remove() {
        placed ?: return
        placed = null
        hit = null
        reset()
    }

    private class Job(val at: Long, val gen: Int, val run: () -> Unit)

    private var tick = 0L
    private var gen = 0
    private val jobs = ArrayList<Job>()
    private fun after(ticks: Int, run: () -> Unit) { jobs += Job(tick + ticks, gen, run) }

    private val device = SimonSaysDevice(object : SimonSaysDevice.Io {
        override fun newSequence() = drawSequence()
        override fun lamp(cell: Int, on: Boolean) {
            light(cell, on)
            if (on && firstLight == 0L) firstLight = tick
        }
        override fun button(cell: Int, up: Boolean, pressed: Boolean) = setButton(cell, up, pressed)
        override fun lampTick(j: Int) = showTick(j)
        override fun opened(round: Int) {
            roundUp = tick
            roundClicks.clear(); lastClickMs = System.currentTimeMillis()
            if (accepted.isNotEmpty()) lastStart = accepted.toList()
        }
        override fun completed(by: String) = onDone()
    })
    private const val YOU = "you"

    private fun showTick(j: Int) = if (instantShow) 0 else Math.round(8 * j / showSpeed).toInt()

    private val rng = Random()
    private val buttonUp = BooleanArray(16)
    private val downUntil = LongArray(16)
    private var firstLight = 0L
    private var roundUp = 0L
    private val rounds = ArrayList<Double>()
    private val splits = ArrayList<List<Double>>()
    private val roundClicks = ArrayList<Double>()
    private var lastClickMs = 0L
    private var shownFaster = 0L
    private var fails = 0

    private var running = false
    private var runStart = 0L
    private val accepted = ArrayList<Long>()

    private fun reset() {
        stopInf()
        fromRound = 0
        gen++
        jobs.clear()
        device.clear()
        running = false
        placed?.let { p -> for (c in 0 until 16) { p.set(lampAt(c), Blocks.OBSIDIAN.defaultBlockState()); setButton(c, false) } }
    }

    private fun beginRun() {
        running = true; runStart = tick
        accepted.clear()
        firstLight = 0L; shownFaster = 0L; rounds.clear(); splits.clear(); fails = 0
    }

    private fun setButton(cell: Int, up: Boolean, pressed: Boolean = false) {
        val p = placed ?: return
        buttonUp[cell] = up
        p.set(buttonAt(cell), if (up) BUTTON.setValue(ButtonBlock.POWERED, pressed) else Blocks.AIR.defaultBlockState())
    }

    private fun light(cell: Int, on: Boolean) {
        placed?.set(lampAt(cell), if (on) Blocks.SEA_LANTERN.defaultBlockState() else Blocks.OBSIDIAN.defaultBlockState())
    }

    private fun drawSequence(): IntArray {
        if (rngLevel == 0) {
            val cells = (0 until 16).shuffled(rng)
            return IntArray(FINAL_ROUND) { cells[it] }
        }
        val k = when (rngLevel) { -1 -> -1.0; 1 -> 1.0; else -> 2.0 }
        val out = ArrayList<Int>()
        out += rng.nextInt(16)
        while (out.size < FINAL_ROUND) {
            val last = out.last()
            val left = (0 until 16).filter { it !in out }
            val w = left.map { c -> Math.exp(-k * Math.hypot((c / 4 - last / 4).toDouble(), (c % 4 - last % 4).toDouble())) }
            var r = rng.nextDouble() * w.sum()
            var pick = left.last()
            for (i in left.indices) { r -= w[i]; if (r <= 0) { pick = left[i]; break } }
            out += pick
        }
        return out.toIntArray()
    }

    private var rngLevel = 0

    private fun rngButton(level: Int) = BlockPos(110, 121 + level, 96)

    private val fullBlock: Boolean get() = fullBlockHitboxes
    private var gridCells: Set<BlockPos> = emptySet()

    private const val FINAL_ROUND = SimonSaysDevice.MAX_ROUNDS

    private fun solverBox(p: Placement, lamp: BlockPos): AABB =
        if (fullBlock) p.box(lamp.x - 0.15, lamp.y + 0.0, lamp.z + 0.0, lamp.x + 0.05, lamp.y + 1.0, lamp.z + 1.0)
        else p.box(lamp.x - 0.15, lamp.y + 0.37, lamp.z + 0.3, lamp.x + 0.05, lamp.y + 0.63, lamp.z + 0.7)

    private fun shapeOf(pos: BlockPos, state: BlockState): VoxelShape =
        fullBlockShape(state, pos) ?: state.getShape(EmptyBlockGetter.INSTANCE, pos)

    private fun fullBlockShape(state: BlockState, pos: BlockPos): VoxelShape? {
        if (!fullBlock || state.block !is ButtonBlock || pos !in gridCells) return null
        val d = if (state.getValue(ButtonBlock.POWERED)) 1.0 else 2.0
        return when (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction.WEST -> Block.box(16 - d, 0.0, 0.0, 16.0, 16.0, 16.0)
            Direction.EAST -> Block.box(0.0, 0.0, 0.0, d, 16.0, 16.0)
            Direction.NORTH -> Block.box(0.0, 0.0, 16 - d, 16.0, 16.0, 16.0)
            else -> Block.box(0.0, 0.0, 0.0, 16.0, 16.0, d)
        }
    }

    private fun markRng() {
        val p = placed ?: return
        for (level in -1..2) p.set(rngButton(level).east(), if (level == rngLevel) Blocks.WOOL.lightGray().defaultBlockState() else Blocks.WOOL.black().defaultBlockState())
    }

    private fun pressRng(level: Int) {
        val p = placed ?: return
        rngLevel = level
        markRng()
        click(p.at(rngButton(level)))
        playSoundSettings(CORRECT_SOUND)
        p.set(rngButton(level), BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(rngButton(level), BUTTON) }
    }

    private var lastStart: List<Long> = listOf(0L, 2L, 4L)

    private fun restart(sound: Boolean = true) {
        if (placed == null) return
        if (Inf.on) { startInf(); return }
        if (fromRound > 0) { startFrom(fromRound, sound); return }
        val presses = lastStart
        reset()
        beginRun()
        if (sound) playSoundSettings(CORRECT_SOUND)
        for (t in presses) after(t.toInt()) {
            accepted += t
            device.start(YOU, 0.0, limit = false)
        }
    }

    private object Inf {
        const val SHOWN = 3
        const val FRESH = 6
        var on = false
        val queue = ArrayList<Int>()
        val bag = ArrayDeque<Int>()
        var lastPressed = -1
        var lastMs = 0L
        val gaps = ArrayList<Long>()
    }

    private fun startInf() {
        placed ?: return
        reset()
        markMode(1)
        Inf.on = true
        Inf.queue.clear(); Inf.bag.clear(); Inf.gaps.clear(); Inf.lastPressed = -1; Inf.lastMs = 0L
        while (Inf.queue.size < Inf.SHOWN) Inf.queue += infPick()
        for (c in 0 until 16) setButton(c, true)
        playSoundSettings(CORRECT_SOUND)
    }

    private fun stopInf() {
        if (!Inf.on) return
        Inf.on = false
        if (Inf.gaps.isEmpty()) return
        val avg = Inf.gaps.average() / 1000.0
        EngineerClient.msg("§7Inf SS: §f${Inf.gaps.size + 1}§7 presses §8· §f${String.format(Locale.ROOT, "%.3f", avg)}s§7 between")
    }

    private fun infPick(): Int {
        if (Inf.bag.isEmpty()) {
            val fresh = (0 until 16).filter { it !in Inf.queue && it != Inf.lastPressed }.shuffled(rng).take(Inf.FRESH)
            Inf.bag += fresh
            Inf.bag += (0 until 16).filter { it !in fresh }.shuffled(rng)
        }
        return Inf.bag.removeFirst()
    }

    private fun infPress(cell: Int) {
        if (cell != Inf.queue.firstOrNull()) { playSoundSettings(WRONG_SOUND); return }
        playSoundSettings(CORRECT_SOUND)
        val now = System.currentTimeMillis()
        if (Inf.lastMs != 0L) Inf.gaps += now - Inf.lastMs
        Inf.lastMs = now
        Inf.lastPressed = cell
        Inf.queue.removeAt(0)
        Inf.queue += infPick()
    }

    private fun markMode(mode: Int) {
        val p = placed ?: return
        val grey = Blocks.WOOL.lightGray().defaultBlockState(); val black = Blocks.WOOL.black().defaultBlockState()
        p.set(START.east(), if (mode == 0) grey else black)
        p.set(EXTRA.east(), if (mode == 1) grey else black)
        for (n in 3..4) p.set(fromButton(n).east(), if (mode == n) grey else black)
    }

    private fun sign(p: Placement, button: BlockPos, line1: String, line2: String, side: Direction = Direction.NORTH) {
        val real = button.east().relative(side)
        p.set(real, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, side))
        val pos = p.at(real)
        val be = SignBlockEntity(pos, p.blocks[pos] ?: return)
        be.setLevel(p.level)
        p.signs[pos] = be
        be.setText(SignText.EMPTY.asMutable()
            .setLine(1, Component.literal(line1))
            .setLine(2, Component.literal(line2)).asImmutable(), SignTextSlot.FRONT)
    }

    private var fromRound = 0

    private fun startFrom(n: Int, sound: Boolean = true) {
        reset()
        fromRound = n
        markMode(n)
        if (sound) playSoundSettings(CORRECT_SOUND)
        after(6) { fromRun() }
    }

    private fun fromRun() {
        device.clear()
        firstLight = 0L; shownFaster = 0L; rounds.clear(); splits.clear(); fails = 0
        device.showRound(fromRound)
    }

    private fun fromDone() {
        for (i in rounds.indices) {
            val r = fromRound + i
            val t = rounds[i]
            val top = topFor(r) ?: continue
            val c = if (t <= top) "§a" else if (t <= top + 0.3) "§e" else "§c"
            val presses = splits.getOrNull(i).orEmpty()
            EngineerClient.msg("§8 r$r   $c${fmt(t)}§8/${fmt(top)} §8| " + presses.withIndex().joinToString(" §8› ") { (j, x) -> pressColour(false, j, x) + fmt(x) })
        }
        if (rounds.size > 1) EngineerClient.msg("§7 r$fromRound-r$FINAL_ROUND clicking: §f${fmt(rounds.sum())}s")
    }

    private fun pressFrom(n: Int) {
        val p = placed ?: return
        startFrom(n)
        click(p.at(fromButton(n)))
        p.set(fromButton(n), BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(fromButton(n), BUTTON) }
    }

    private fun pressExtra() {
        val p = placed ?: return
        startInf()
        click(p.at(EXTRA))
        p.set(EXTRA, BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(EXTRA, BUTTON) }
    }

    private fun pressStart() {
        val p = placed ?: return
        if (Inf.on || fromRound > 0 || device.done) reset()
        if (!running) beginRun()
        val r = device.start(YOU, System.nanoTime() / 1e6)
        if (r == SimonSaysDevice.Start.STARTED || r == SimonSaysDevice.Start.RESET) accepted += tick - runStart
        markMode(0)
        click(p.at(START))
        playSoundSettings(CORRECT_SOUND)
        p.set(START, BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(START, BUTTON) }
    }

    private fun trigger() {
        val p = placed ?: return
        if (mc.gui.screen() != null) return
        val pos = target() ?: return
        val cell = (0 until 16).firstOrNull { p.at(buttonAt(it)) == pos } ?: return
        if (Inf.on) {
            if (!buttonUp[cell] || downUntil[cell] > tick || cell != Inf.queue.firstOrNull()) return
        } else if (!device.pressable(cell) || cell != device.due()) return
        press(cell)
        mc.player?.let { it.swing(InteractionHand.MAIN_HAND, it.mainHandItem.interactAnimation, false) }
    }

    private fun press(cell: Int) {
        val p = placed ?: return
        if (Inf.on) {
            if (!buttonUp[cell] || downUntil[cell] > tick) return
            click(p.at(buttonAt(cell)))
            setButton(cell, true, pressed = true)
            downUntil[cell] = tick + 3
            after(3) { if (buttonUp[cell]) setButton(cell, true) }
            infPress(cell)
            return
        }
        val r = device.press(cell, YOU)
        if (r == SimonSaysDevice.Press.NONE) return
        click(p.at(buttonAt(cell)))
        when (r) {
            SimonSaysDevice.Press.RIGHT, SimonSaysDevice.Press.ROUND -> {
                playSoundSettings(CORRECT_SOUND)
                val now = System.currentTimeMillis()
                roundClicks += (now - lastClickMs) / 1000.0; lastClickMs = now
                if (r == SimonSaysDevice.Press.RIGHT) return
                val n = device.round
                rounds += (tick + 6 - roundUp) / 20.0
                splits += roundClicks.toList()
                shownFaster += 8 * n - showTick(n)
            }
            SimonSaysDevice.Press.WRONG -> {
                playSoundSettings(WRONG_SOUND)
                fails++
                val f = fails
                restart(sound = false)
                fails = f
            }
            else -> {}
        }
    }

    private fun onDone() {
        if (fromRound > 0) {
            fromDone()
            fromRun()
            return
        }
        running = false
        if (firstLight == 0L) return
        val total = (tick + shownFaster - firstLight) / 20.0
        val speed = if (instantShow) " §8(instant, as at 1x)" else if (showSpeed != 1.0) " §8(${fmt(showSpeed).trimEnd('0').trimEnd('.')}x, as at 1x)" else ""
        report(total, rounds, splits, (if (fails > 0) " §c$fails wrong" else "") + speed)
    }

    private fun topFor(n: Int): Double? = TOP_ROUNDS.getOrNull(n - 2)

    private fun report(total: Double, rounds: List<Double>, splits: List<List<Double>>, suffix: String) {
        val colour = when {
            total <= 11.6 -> "§a"
            total <= 11.95 -> "§2"
            total <= 12.6 -> "§e"
            total <= 13.5 -> "§c"
            else -> "§4"
        }
        EngineerClient.msg("§7SS took: $colour${fmt(total)}s$suffix")
        for (i in rounds.indices) {
            val presses = splits.getOrNull(i).orEmpty()
            val skip = i == 0 && presses.size >= 2
            val name = if (skip) "skip" else "r${presses.size}"
            val top = topFor(presses.size)
            val c = if (top == null) "§f" else if (rounds[i] <= top) "§a" else if (rounds[i] <= top + 0.3) "§e" else "§c"
            val vs = if (top == null) "" else "§8/${fmt(top)}"
            EngineerClient.msg("§8 ${name.padEnd(4)} $c${fmt(rounds[i])}$vs §8| " + presses.withIndex().joinToString(" §8› ") { (j, t) -> pressColour(skip, j, t) + fmt(t) })
        }
    }

    private fun pressColour(skip: Boolean, index: Int, t: Double) = when {
        skip -> "§7"
        index == 0 -> if (t <= 0.10) "§a" else if (t <= 0.20) "§e" else "§c"
        else -> if (t <= 0.25) "§2" else if (t <= 0.35) "§e" else "§c"
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    private fun click(pos: BlockPos) {
        val p = placed
        val w = if (p == null) Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) else view(p, 1f).toWorld(Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
        mc.level?.playLocalSound(w.x, w.y, w.z, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, 0.6f, false)
    }

    private class View(val p: Placement, val eye: Vec3, val k: Int, val s: Double) {
        private fun turn(x: Double, z: Double, steps: Int): Pair<Double, Double> {
            var a = x; var b = z
            repeat(steps) { val t = a; a = -b; b = t }
            return a to b
        }
        fun toWorld(v: Vec3): Vec3 {
            val (x, z) = turn(v.x - p.eye.x, v.z - p.eye.z, k)
            return Vec3(eye.x + x * s, eye.y + (v.y - p.eye.y) * s, eye.z + z * s)
        }
        fun toDevice(v: Vec3): Vec3 {
            val (x, z) = turn(v.x - eye.x, v.z - eye.z, (4 - k) % 4)
            return Vec3(p.eye.x + x / s, p.eye.y + (v.y - eye.y) / s, p.eye.z + z / s)
        }
        fun dirToDevice(v: Vec3): Vec3 { val (x, z) = turn(v.x, v.z, (4 - k) % 4); return Vec3(x, v.y, z) }
        fun box(b: AABB) = AABB(toWorld(Vec3(b.minX, b.minY, b.minZ)), toWorld(Vec3(b.maxX, b.maxY, b.maxZ)))
        val lightPos: BlockPos get() = BlockPos.containing(eye)
    }

    private fun view(p: Placement, partialTicks: Float, eye: Vec3? = null): View {
        val player = mc.player
        if (!follow || player == null) return View(p, p.eye, 0, size)
        val facing = Direction.from2DDataValue(p.forward.get2DDataValue() + turned)
        if (Math.abs(Mth.wrapDegrees(player.yRot - facing.toYRot())) > TURN_AROUND) turned = (turned + 2) % 4
        return View(p, eye ?: player.getEyePosition(partialTicks), turned, size)
    }

    private var turned = 0
    private const val TURN_AROUND = 100.0

    private class DeviceBlock(val lightPos: BlockPos) : MovingBlockRenderState() {
        override fun getBrightness(layer: LightLayer, pos: BlockPos): Int =
            lightEngine.getLayerListener(layer).getLightValue(lightPos)
        override fun getRawBrightness(pos: BlockPos, darkening: Int): Int = lightEngine.getRawBrightness(lightPos, darkening)
    }

    private fun draw(p: Placement, v: View, context: LevelRenderContext) {
        val pose = context.poseStack()
        val out = context.submitNodeCollector()
        val cam = mc.gameRenderer.mainCamera().position()
        val level = p.level
        val lightPos = if (follow) v.lightPos else p.lightPos
        val light = LightCoordsUtil.getLightCoords(level, lightPos)
        fun at(pos: BlockPos, draw: () -> Unit) {
            val w = v.toWorld(Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()))
            pose.pushPose()
            pose.translate(w.x - cam.x, w.y - cam.y, w.z - cam.z)
            if (v.k != 0) pose.mulPose(Matrix4f().rotateY(Math.toRadians(-90.0 * v.k).toFloat()))
            if (v.s != 1.0) pose.scale(v.s.toFloat(), v.s.toFloat(), v.s.toFloat())
            draw()
            pose.popPose()
        }
        for ((pos, state) in p.blocks) {
            if (pos in p.signs) continue
            val s = DeviceBlock(lightPos)
            s.randomSeedPos = pos; s.blockPos = pos; s.blockState = state
            s.biome = level.getBiome(pos); s.cardinalLighting = level.cardinalLighting(); s.lightEngine = level.lightEngine
            at(pos) { out.submitMovingBlock(pose, s, 0) }
        }
        val signs = mc.blockEntityRenderDispatcher
        val camera = context.levelState().cameraRenderState
        for ((pos, be) in p.signs) {
            val s = signs.tryExtractRenderState<SignBlockEntity, BlockEntityRenderState>(be, 0f, null, false) ?: continue
            s.lightCoords = light
            at(pos) { signs.submit(s, pose, out, camera) }
        }
        val target = target() ?: return
        val state = p.blocks[target] ?: return
        val width = mc.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth
        at(target) { out.submitShapeOutline(pose, shapeOf(target, state), RenderTypes.linesTranslucent(), ARGB.black(102), width, false) }
    }

    private var hit: BlockHitResult? = null

    private fun target(): BlockPos? {
        val p = placed ?: return null
        return hit?.blockPos?.takeIf { it in p.blocks }
    }

    @JvmStatic
    fun afterPick(partialTicks: Float) {
        hit = null
        val p = placed ?: return
        if (p.level !== mc.level) return
        val player = mc.player ?: return
        if (mc.cameraEntity !== player) return
        EngineerClient.safely("ss practice pick") {
            val v = view(p, partialTicks)
            val worldEye = player.getEyePosition(partialTicks)
            val eye = v.toDevice(worldEye)
            val end = eye.add(v.dirToDevice(player.getViewVector(partialTicks)).scale(player.blockInteractionRange()))
            var best: BlockHitResult? = null
            var bestD = Double.MAX_VALUE
            for ((pos, state) in p.blocks) {
                val r = shapeOf(pos, state).clip(eye, end, pos) ?: continue
                val d = r.location.distanceToSqr(eye)
                if (d < bestD) { bestD = d; best = r }
            }
            val b = best ?: return@safely
            val at = v.toWorld(b.location)
            val game = mc.hitResult
            if (game is EntityHitResult && game.entity !is Player && game.location.distanceToSqr(worldEye) < at.distanceToSqr(worldEye)) return@safely
            hit = b
            mc.hitResult = BlockHitResult.miss(at, b.direction, b.blockPos)
            mc.crosshairPickEntity = null
        }
    }

    private val odinOrder by lazy { SimonSays::class.java.getDeclaredField("clickInOrder").apply { isAccessible = true } }
    private val odinNeeded by lazy { SimonSays::class.java.getDeclaredField("clickNeeded").apply { isAccessible = true } }

    private fun realClick(pos: BlockPos, blocked: Boolean) {
        if (placed != null) return
        if (DungeonUtils.getF7Phase() != M7Phases.P3) return
        val isStart = pos == START
        if (!isStart && (pos.x != 110 || pos.y !in 120..123 || pos.z !in 92..95)) return
        val state = mc.level?.getBlockState(pos) ?: return
        if (state.block !is ButtonBlock || state.getValue(BlockStateProperties.POWERED)) return
        (SimonSays.settings["Custom Click Sounds"] as? BooleanSetting)?.let { if (it.value) it.value = false }
        if (blocked) { playSoundSettings(WRONG_SOUND); return }
        if (isStart) { playSoundSettings(CORRECT_SOUND); return }

        @Suppress("UNCHECKED_CAST")
        val order = odinOrder.get(null) as List<BlockPos>
        val i = odinNeeded.getInt(null)
        val right = order.getOrNull(i) == pos.east()
        playSoundSettings(if (right) CORRECT_SOUND else WRONG_SOUND)
        Real.press(right, last = right && i == order.size - 1, System.currentTimeMillis())
    }

    private object Real {
        var firstLight = 0L
        var roundUp = 0L
        var buttonsUp = false
        var lastPress = 0L
        var fails = 0
        val rounds = ArrayList<Double>()
        val splits = ArrayList<List<Double>>()
        val presses = ArrayList<Double>()

        fun reset() {
            firstLight = 0L; roundUp = 0L; buttonsUp = false; lastPress = 0L; fails = 0
            rounds.clear(); splits.clear(); presses.clear()
        }

        fun block(pos: BlockPos, old: BlockState, new: BlockState) {
            if (pos.y !in 120..123 || pos.z !in 92..95) return
            val now = System.currentTimeMillis()
            when (pos.x) {
                111 -> if (new.block == Blocks.SEA_LANTERN && old.block != Blocks.SEA_LANTERN && (firstLight == 0L || now - firstLight > 30_000)) { reset(); firstLight = now }
                110 -> when {
                    new.block is ButtonBlock && old.block !is ButtonBlock && !buttonsUp -> {
                        buttonsUp = true; roundUp = now; lastPress = now; presses.clear()
                    }
                    new.isAir && old.block is ButtonBlock -> buttonsUp = false
                }
            }
        }

        fun press(right: Boolean, last: Boolean, now: Long) {
            if (firstLight == 0L || roundUp == 0L) return
            if (!right) {
                fails++; rounds.clear(); splits.clear(); presses.clear()
                return
            }
            presses += (now - lastPress) / 1000.0; lastPress = now
            if (!last) return
            rounds += (now - roundUp) / 1000.0 + 0.3
            splits += presses.toList()
            if (presses.size == FINAL_ROUND) {
                report((now + 300 - firstLight) / 1000.0, rounds, splits, suffix = (if (fails > 0) " §c$fails wrong" else "") + " §8(real)")
                reset()
            }
            presses.clear()
        }
    }

    @JvmStatic
    fun onUse(): Boolean {
        val pos = target() ?: return false
        val p = placed ?: return false
        val button = p.blocks[pos]?.block is ButtonBlock
        EngineerClient.safely("ss practice use") {
            val from = (3..4).firstOrNull { pos == p.at(fromButton(it)) }
            val rngPick = (-1..2).firstOrNull { pos == p.at(rngButton(it)) }
            if (pos == p.at(START)) pressStart()
            else if (rngPick != null) pressRng(rngPick)
            else if (pos == p.at(EXTRA)) pressExtra()
            else if (from != null) pressFrom(from)
            else (0 until 16).firstOrNull { p.at(buttonAt(it)) == pos }?.let { press(it) }
        }
        if (button) mc.player?.let { it.swing(InteractionHand.MAIN_HAND, it.mainHandItem.interactAnimation, false) }
        (mc as MinecraftAccessor).`ec$setRightClickDelay`(4)
        return true
    }

    @JvmStatic
    fun onAttack(): Boolean {
        val pos = target() ?: return false
        val p = placed ?: return false
        if ((0 until 16).any { p.at(lampAt(it)) == pos || p.at(buttonAt(it)) == pos }) EngineerClient.safely("ss practice restart") { restart() }
        mc.player?.let { it.swing(InteractionHand.MAIN_HAND, it.mainHandItem.attackAnimation, false) }
        return true
    }

    @JvmStatic
    fun blocksContinueAttack(): Boolean = target() != null

    init {
        on<TickEvent.End> {
            if (triggerBot) EngineerClient.safely("ss trigger bot") { trigger() }
            tick++
            if (jobs.isNotEmpty()) {
                val due = jobs.filter { it.at <= tick }
                jobs.removeAll(due.toSet())
                for (j in due) if (j.gen == gen) EngineerClient.safely("ss practice") { j.run() }
            }
            EngineerClient.safely("ss practice device") { device.tick() }
        }
        on<RenderExtractEvent> {
            val p = placed ?: return@on
            if (p.level !== mc.level) return@on
            val v = view(p, mc.deltaTracker.getGameTimeDeltaPartialTick(false),
                mc.gameRenderer.mainCamera().position().takeIf { mc.options.cameraType.isFirstPerson })
            EngineerClient.safely("ss practice draw") { draw(p, v, context) }
            if (!SimonSays.enabled) return@on
            if (Inf.on) {
                for ((i, cell) in Inf.queue.withIndex()) {
                    val lamp = lampAt(cell)
                    drawStyledBox(v.box(solverBox(p, lamp)), when (i) { 0 -> FIRST; 1 -> SECOND; else -> THIRD }, BoxStyle.FILLED_OUTLINE, true)
                }
                return@on
            }
            val shown = device.shown
            val next = device.next
            for (i in next until shown.size) {
                val colour = when (i) { next -> FIRST; next + 1 -> SECOND; else -> THIRD }
                val lamp = lampAt(shown[i])
                drawStyledBox(v.box(solverBox(p, lamp)), colour, BoxStyle.FILLED_OUTLINE, true)
            }
        }
        on<BlockInteractEvent>(priority = -1) { EngineerClient.safely("ss real sounds") { realClick(pos, isCancelled) } }
        on<BlockUpdateEvent> {
            if (placed != null || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            EngineerClient.safely("ss real times") { Real.block(pos, old, updated) }
        }
        on<LevelEvent.Load> { Real.reset() }
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            if (msg != STORM_DEAD && msg != GOLDOR_START) return@onReceive
            mc.execute { if (placed != null) EngineerClient.safely("ss practice countdown") { remove(); EngineerClient.msg("§7SS Practice: removed (terminals starting).") } }
        }
        on<LevelEvent.Unload> { placed = null; hit = null; gen++; jobs.clear(); device.clear(); running = false }
    }

    override fun onDisable() {
        super.onDisable()
        remove()
    }
}
