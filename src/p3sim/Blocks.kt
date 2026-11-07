package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

object Blocks {
    private val touched = LinkedHashSet<BlockPos>()

    fun set(pos: BlockPos, state: BlockState) {
        val level = SimServer.level ?: return
        touched += pos.immutable()
        Arena.set(level, pos, state)
    }

    fun set(x: Int, y: Int, z: Int, state: BlockState) = set(BlockPos(x, y, z), state)

    fun get(pos: BlockPos): BlockState? = SimServer.level?.getBlockState(pos)

    fun restoreAll() {
        val level = SimServer.level ?: return
        anims.clear()
        done.clear()
        conveyor = null
        val hits: Set<BlockPos> = clientHits ?: emptySet()
        val sync = HashSet<BlockPos>(touched).also { it += hits }
        clientHits = null
        touched.forEach { Arena.restore(level, it) }
        touched.clear()
        val fixed = Arena.resync(level)
        if (fixed > 0) EngineerClient.logger.info("[p3sim] restore: {} more arena blocks put back", fixed)
        resend(hits)
        Fight.later(10, "ghost block sync") { resend(sync) }
        Fight.later(40, "ghost block sync") { resend(hits) }
    }

    private var clientHits: HashSet<BlockPos>? = null

    fun clientHit(pos: BlockPos) { (clientHits ?: HashSet<BlockPos>().also { clientHits = it }) += pos.immutable() }

    fun resend(positions: Collection<BlockPos>) {
        val level = SimServer.level ?: return
        if (positions.isEmpty()) return
        val bySection = positions.groupBy { SectionPos.of(it) }
        val packets = bySection.mapNotNull { (sp, ps) ->
            val chunk = level.getChunkSource().getChunkNow(sp.x(), sp.z()) ?: return@mapNotNull null
            val si = chunk.getSectionIndexFromSectionY(sp.y())
            if (si < 0 || si >= chunk.sections.size) return@mapNotNull null
            val shorts = ShortOpenHashSet()
            ps.forEach { shorts.add(SectionPos.sectionRelativePos(it)) }
            ClientboundSectionBlocksUpdatePacket(sp, shorts, chunk.getSection(si))
        }
        for (p in Sim.players()) packets.forEach { p.connection.send(it) }
    }

    class Frame(val dt: Int, val pos: BlockPos, val state: BlockState, val chance: Float = 1f)
    class Anim(val name: String, val event: String, val frames: List<Frame>) {
        val positions: Set<BlockPos> by lazy { frames.mapTo(HashSet()) { it.pos } }
        val length: Int get() = frames.lastOrNull()?.dt ?: 0
    }

    private val library: Map<String, Anim> by lazy {
        val out = HashMap<String, Anim>()
        for (file in listOf("anims-p3.json", "anims-p124.json")) EngineerClient.safely("p3sim $file") {
            val text = Blocks::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$file")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@safely
            val root = JsonParser.parseString(text).asJsonObject
            for ((name, v) in root.entrySet()) {
                if (name.startsWith("_") || !v.isJsonObject) continue
                val o = v.asJsonObject
                val fr = o.getAsJsonArray("frames") ?: continue
                val states = HashMap<String, BlockState>()
                val frames = fr.map { e ->
                    val a = e.asJsonArray
                    val s = a[4].asString
                    Frame(a[0].asInt, BlockPos(a[1].asInt, a[2].asInt, a[3].asInt), states.getOrPut(s) { Arena.parse(if (':' in s) s else "minecraft:$s") }, if (a.size() > 5) a[5].asFloat else 1f)
                }.sortedBy { it.dt }
                out[name] = Anim(name, o.get("event")?.asString ?: "", frames)
            }
        }
        out
    }

    fun anim(name: String): Anim? = library[name]

    fun extra(file: String, key: String): JsonObject? = try {
        Blocks::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$file")?.use {
            JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject(key)
        }
    } catch (t: Throwable) { null }

    private class Playing(val anim: Anim, val start: Int, var next: Int = 0) {
        val skip = BooleanArray(anim.frames.size) { anim.frames[it].chance < 1f && Random.nextFloat() >= anim.frames[it].chance }
        val debris = anim.name.startsWith("gate") || anim.name.startsWith("door") || anim.name == "core"
        var debrisNext = 0
    }
    private val anims = ArrayList<Playing>()
    private val done = HashSet<String>()

    fun play(name: String, skip: Int = 0, exclude: Set<BlockPos> = emptySet(), delay: Int = 0) {
        val a = library[name] ?: run { EngineerClient.logger.warn("[p3sim] no animation {}", name); return }
        done += name
        if (name == "core") Fight.later(maxOf(delay, 1), "core bats") { coreBats() }
        val p = Playing(a, Fight.serverTick - skip + delay)
        if (exclude.isNotEmpty()) a.frames.forEachIndexed { i, f -> if (f.pos in exclude) p.skip[i] = true }
        anims += p
        advance(p)
        if (name == STRIP) conveyor = Conveyor(p.start + a.length + STRIP_PERIOD)
    }

    private fun coreBats() {
        val gold = B.GOLD_BLOCK.defaultBlockState()
        repeat(35) {
            val b = Bat(EntityTypes.BAT, Sim.level)
            b.isSilent = true; b.isPermanentlyInvulnerable = true; b.isInvisible = true; b.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false))
            b.setNoAi(true); b.setNoGravity(true)
            val x = 52.0 + Random.nextDouble() * 4.0
            val y = 114.0 + Random.nextDouble() * 6.0
            b.snapTo(x, y, 54.5, Random.nextFloat() * 360f, 0f)
            Sim.spawn(b)
            val block = CarriedBlock(Sim.level, gold)
            block.snapTo(x, y, 54.5, 0f, 0f)
            Sim.spawn(block)
            Fight.later(1, "core bat carry") { if (!b.isRemoved && !block.isRemoved) block.startRiding(b, true, false) }
            for (k in 7..30) Fight.later(k, "core bat fall") { if (!b.isRemoved) b.snapTo(x, y - 0.5 * (k - 6), 54.5, b.yRot, 0f) }
            Fight.later(31, "core bat gone") { block.discard(); b.discard() }
        }
    }

    fun finish(name: String, exclude: Set<BlockPos> = emptySet()) {
        val a = library[name] ?: return
        if (!done.add(name)) return
        val p = Playing(a, 0)
        a.frames.forEachIndexed { i, f -> if (!p.skip[i] && f.pos !in exclude) set(f.pos, f.state) }
    }

    fun stop(name: String) {
        anims.removeAll { it.anim.name == name }
        if (name == STRIP) conveyor = null
    }

    fun isPlaying(name: String) = anims.any { it.anim.name == name }

    fun tick() {
        if (anims.isNotEmpty()) {
            anims.toList().forEach { advance(it) }
            anims.removeAll { it.next >= it.anim.frames.size }
        }
        EngineerClient.safely("p3sim strip") { conveyorTick() }
    }

    private fun advance(p: Playing) {
        val now = Fight.serverTick - p.start
        val f = p.anim.frames
        if (p.debris) while (p.debrisNext < f.size && f[p.debrisNext].dt <= now + 1) {
            val fr = f[p.debrisNext]
            if (!p.skip[p.debrisNext] && fr.state.isAir) debris(fr.pos)
            p.debrisNext++
        }
        while (p.next < f.size && f[p.next].dt <= now) { if (!p.skip[p.next]) set(f[p.next].pos, f[p.next].state); p.next++ }
    }

    private var debrisTick = -1
    private var debrisCount = 0
    private const val DEBRIS_PER_TICK = 48

    private fun debris(pos: BlockPos) {
        val level = SimServer.level ?: return
        val s = level.getBlockState(pos)
        if (s.isAir || !s.fluidState.isEmpty) return
        if (debrisTick != Fight.serverTick) { debrisTick = Fight.serverTick; debrisCount = 0 }
        if (debrisCount >= DEBRIS_PER_TICK) return
        debrisCount++
        val e = Debris(level, s)
        e.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, 0f, 0f)
        e.deltaMovement = Vec3.ZERO
        Sim.spawn(e)
    }

    fun prepare(start: Fight.Start) {
        if (start == Fight.Start.P1) return
        finish(STRIP)
        set(73, 221, 73, B.BEDROCK.defaultBlockState())
        for (y in 222..224) set(73, y, 73, B.STAINED_GLASS.red().defaultBlockState())
        finish("p1end")
        if (start == Fight.Start.P2) return
        stormPillars()
        if (start != Fight.Start.P4) return
        for (a in listOf("p3start", "gate12", "door1", "ss_s1done", "gate23", "door2", "gate34", "door3", "core")) finish(a)
    }

    private fun stormPillars() {
        val p = extra("anims-p124.json", "pillars") ?: return
        val origins = p.getAsJsonObject("origins")
        val footprint = p.getAsJsonArray("footprint").map { val a = it.asJsonArray; a[0].asInt to a[1].asInt }
        for (name in listOf("Purple", "Yellow")) {
            val o = origins.getAsJsonObject(name)
            val x0 = o.get("x").asInt; val z0 = o.get("z").asInt
            for ((dx, dz) in footprint) {
                for (y in 175 until PILLAR_BOTTOM) set(x0 + dx, y, z0 + dz, B.AIR.defaultBlockState())
                for (y in PILLAR_BOTTOM..189) set(x0 + dx, y, z0 + dz, B.POLISHED_DIORITE.defaultBlockState())
            }
        }
    }

    private class Conveyor(var next: Int)
    private var conveyor: Conveyor? = null
    private val stripRows: List<List<BlockPos>> by lazy {
        val pos = library[STRIP]?.positions ?: emptySet()
        val rows = (70..76).map { 224 to it } + (225..226).flatMap { y -> (72..74).map { y to it } }
        rows.map { (y, z) -> (34..112).map { BlockPos(it, y, z) }.filter { it in pos && !(it.x == 73 && it.z == 73) } }
    }

    private fun conveyorTick() {
        val c = conveyor ?: return
        val maxor = Fight.phase as? P1Maxor
        if (maxor == null || maxor.status() == "Maxor dead") { stop(STRIP); return }
        if (Fight.serverTick < c.next || isPlaying(STRIP)) return
        c.next = Fight.serverTick + STRIP_PERIOD
        val level = SimServer.level ?: return
        for (row in stripRows) {
            val inRow = row.toHashSet()
            val old = row.associateWith { level.getBlockState(it) }
            for (p in row) {
                val west = p.west()
                val s = when {
                    west in inRow -> old.getValue(west)
                    p.y != 224 -> B.AIR.defaultBlockState()
                    p.x == row.first().x -> old[p.offset(6, 0, 0)] ?: B.WOOL.gray().defaultBlockState()
                    else -> B.WOOL.gray().defaultBlockState()
                }
                set(p, s)
            }
        }
    }

    private const val STRIP = "p1strip"
    private const val STRIP_PERIOD = 10
    private const val PILLAR_BOTTOM = 183
}

class Debris(level: Level, state: BlockState) :
    FallingBlockEntity(EntityTypes.FALLING_BLOCK, level) {
    override fun shouldBeSaved() = false
    init {
        runCatching {
            FallingBlockEntity::class.java.getDeclaredField("blockState").apply { isAccessible = true }.set(this, state)
        }
    }
    override fun tick() {
        if (++time > 200) { discard(); return }
        applyGravity()
        move(MoverType.SELF, deltaMovement)
        if (onGround()) { discard(); return }
        deltaMovement = deltaMovement.scale(0.98)
    }
}

class CarriedBlock(level: Level, state: BlockState) :
    FallingBlockEntity(EntityTypes.FALLING_BLOCK, level) {
    override fun shouldBeSaved() = false
    init {
        runCatching {
            FallingBlockEntity::class.java.getDeclaredField("blockState").apply { isAccessible = true }.set(this, state)
        }
        setNoGravity(true)
    }
    override fun tick() {}
}
