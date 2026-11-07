package com.engineerclient.ads.fight

import com.engineerclient.EngineerClient
import com.engineerclient.index
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import com.engineerclient.ads.arena.Arena
import com.engineerclient.ads.arena.Blocks
import com.engineerclient.ads.arena.Spots
import com.engineerclient.ads.bots.Party
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.core.Stats
import com.engineerclient.ads.items.MovementItems
import com.engineerclient.ads.party.Players

class P4Necron(val fromP3: Boolean = false) : Fight.Phase("P4") {
    override val restart get() = Fight.Start.P4
    private lateinit var necron: BossWither

    companion object {
        private val MID = Vec3(54.0, 66.0, 76.0)
        private const val INTRO = 42
        private const val V1 = 20
        private const val L1 = 80; private const val B1 = L1 + 18
        private const val FRENZY = 8
        private val TAUNT1 = max(3 * INTRO + 62, B1)
        private val ARGH1 = TAUNT1 + 82
        private val SPACE = ARGH1 + 62
        private const val V2 = V1 + 155
        private const val L2 = V2 + 60; private const val B2 = L2 + 5
        private const val BREAK = V2 + 100
        private val THROES = ARGH1 + 40
        private val TNT = ARGH1 + 100
        val GONE = ARGH1 + 119
        private val STATS = ARGH1 + 146
        const val HEAD_Y = 2.97
        val PITCH = Math.toRadians(11.8)
        const val FRENZY_RANGE = 12.0
        val TAUNTS = listOf("Sometimes when you have a problem, you just need to destroy it all and start again.", "WITNESS MY RAW NUCLEAR POWER!")
        val PLATFORMS = mapOf("N" to Vec3(54.0, 63.0, 42.0), "NW" to Vec3(28.0, 63.0, 50.0), "NE" to Vec3(80.0, 63.0, 50.0), "SW" to Vec3(28.0, 63.0, 102.0), "SE" to Vec3(80.0, 63.0, 102.0))
        val PILLARS = listOf(Triple(92, 76, 0.75), Triple(82, 48, 0.73), Triple(26, 48, 0.72), Triple(54, 38, 0.71), Triple(26, 104, 0.70), Triple(82, 104, 0.69), Triple(16, 76, 0.52))
        const val CLASSES = "Healer,Berserk,Archer,Tank,Mage"
    }

    private val side = Random.nextDouble(45.0, 75.0).let { if (Random.nextBoolean()) it else -it }.let { Math.toRadians(it) }
        .let { Vec3(-sin(it), Random.nextDouble(-0.9, 0.9), cos(it)) }
    private val taunt1 = TAUNTS.random()
    private val platform = PLATFORMS.keys.random()
    private val barPhase = Random.nextInt(20)
    private val spawned = ArrayList<Entity>()
    private var tnts = emptyList<Entity>()

    override fun start() {
        if (!fromP3 && Sim.players().isNotEmpty()) {
            Blocks.finish("p3end")
            val s = Spots.P4
            Sim.forEachPlayer { p -> Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch) }
        }
        Party.startP4(fromP3)
        necron = BossWither("Necron", MID)
        Sim.boss("Necron", "You went further than any human before, congratulations.")
        BossBar.show("§c§lNecron", 0f)
        pillars = Pillars.plan()
        storm = Storm.plan()
    }

    override fun stop() {
        spawned.forEach { it.discard() }; spawned.clear()
        if (::necron.isInitialized) necron.remove()
    }

    override fun tick() {
        when (t) {
            INTRO -> Sim.boss("Necron", "I'm afraid, your journey ends now.")
            2 * INTRO -> Sim.boss("Necron", "Goodbye.")
            3 * INTRO -> Sim.boss("Necron", "That's a very impressive trick. I guess I'll have to handle this myself.")
            TAUNT1 -> Sim.boss("Necron", taunt1)
            ARGH1 -> Sim.boss("Necron", "ARGH!")
            SPACE -> Sim.boss("Necron", "Let's make some space!")
            BREAK -> breakPlatform()
            L2 -> skulls()
            TNT -> tnts = List(10) { spawn(PrimedTnt(Sim.level, necron.pos.x, necron.pos.y, necron.pos.z, null)) }
            TNT + 62 -> tnts.forEach { it.discard() }
            GONE - 20 -> necron.dieAnim()
            GONE -> necron.remove()
            STATS -> stats(first = true)
            STATS + 3 -> { stats(first = false); Sim.note("Done. §fMenu§7 to go again.") }
        }
        move()
        if (t % 20 == barPhase) BossBar.progress(round(health() * 100) / 100f)
        if (t >= V1 && t < V1 + 80 && (t - V1) % 10 == 0) fire(0.0)
        if (t >= V2 && t < V2 + 80 && (t - V2) % 10 == 0) fire(yawTo(PLATFORMS.getValue(platform)))
        if (t % 20 == FRENZY && (t in B1 + 1 until V2 || t in B2 + 1 until THROES)) frenzy()
        if (t >= THROES && (t - THROES) % 5 == 0 && (t - THROES) / 5 < 24) throe((t - THROES) / 5)
        pillars.tick(t)
        storm.tick(t)
    }

    private fun health(): Float = when {
        t < L1 -> 0f
        t < B1 -> 1f - 0.2f * (t - L1) / (B1 - L1)
        t < V2 - 5 -> 0.8f
        t < V2 + 10 -> 0.8f - 0.55f * (t - V2 + 5) / 15
        t < L2 -> 0.25f
        t < B2 -> 0.25f - 0.2f * (t - L2) / (B2 - L2)
        t < THROES -> 0.05f
        else -> 0f
    }

    private fun move() {
        val target = PLATFORMS.getValue(platform)
        when {
            t in L1 until L1 + 7 -> necron.moveTo(necron.pos.add(side.scale(0.25)), necron.pos.add(side.x, 0.0, side.z))
            t in L1 + 7 until L1 + 10 -> {}
            t in L1 + 10 until B1 -> necron.step(chase(), 0.49)
            t in L2 until B2 -> necron.step(chase(), 0.63)
            t == B1 || t == B2 -> {
                necron.moveTo(MID, closest(MID))
                Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 10f, 31 / 63f, MID)
            }
            t in V1 until V1 + 80 -> necron.moveTo(MID, MID.add(0.0, 0.0, 10.0))
            t in V2 until L2 -> necron.moveTo(MID, target)
            t < GONE && t % 5 == 0 -> necron.moveTo(necron.pos, closest(necron.pos))
        }
    }

    private fun chase(): Vec3 = closest(necron.pos).add(0.0, 2.0, 0.0)

    private fun yawTo(p: Vec3) = Math.atan2(-(p.x - MID.x), p.z - MID.z)

    private fun <T : Entity> spawn(e: T): T { Sim.spawn(e); spawned += e; return e }

    private fun fire(yaw: Double) {
        val dir = Vec3(-sin(yaw) * cos(PITCH), -sin(PITCH), cos(yaw) * cos(PITCH))
        val from = necron.pos.add(0.0, HEAD_Y, 0.0)
        spawn(Fireball(Sim.level).apply { snapTo(from.x, from.y, from.z, 0f, 0f); deltaMovement = dir.scale(0.5) })
    }

    private fun skulls() {
        val at = closest(necron.pos).add(0.0, 1.0, 0.0)
        val d = at.subtract(necron.pos)
        val right = Vec3(-d.z, 0.0, d.x).let { if (it.lengthSqr() < 1e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
        for (s in listOf(-1.3, 1.3)) {
            val from = necron.pos.add(right.scale(s)).add(0.0, 2.2, 0.0)
            val dir = at.subtract(from).normalize()
            spawn(Skull(Sim.level).apply { snapTo(from.x, from.y, from.z, 0f, 0f); deltaMovement = dir.scale(0.1) })
        }
    }

    private fun breakPlatform() {
        Data.platforms[platform]?.forEach { (pos, s) -> Blocks.set(pos, s) }
    }

    private fun frenzy() {
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 30f, 31 / 63f, necron.pos)
        Sim.sound(SoundEvents.WITHER_AMBIENT, 30f, 44 / 63f, necron.pos)
        Sim.forEachPlayer { p ->
            if (p.position().distanceTo(MID) <= FRENZY_RANGE && !MovementItems.cloaked) Sim.chat("§cNecron's§r§7 Nuclear Frenzy hit you for §r§c57,600§r§7 damage.")
        }
    }

    private fun throe(i: Int) {
        Sim.sound(SoundEvents.WITHER_HURT, 15f, 1f, necron.pos)
        if (i % 2 == 0) {
            Sim.sound(SoundEvents.GENERIC_EXPLODE, 15f, 31 / 63f, necron.pos)
            val at = necron.pos.add(0.0, 1.5, 0.0)
            Sim.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun stats(first: Boolean) = Sim.forEachPlayer { p -> stats(first, p) }

    private fun stats(first: Boolean, p: ServerPlayer) {
        val bar = "§a§l" + "▬".repeat(64)
        val classes = CLASSES.split(",")
        val mine = classes[Party.CLASSES.indexOf(Players.classOf(p)).coerceIn(0, 4)]
        val total = Stats.runTicks()
        val scoreLine = "Team Score: §r§a${score} §r§f(§r§b§lS+§r§f)"
        val defeated = if (total > 0) "§r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a%02dm %02ds".format(total / 1200, total / 20 % 60) else null
        val out = ArrayList<String>()
        out += bar
        if (first) {
            out += centre("§r§cThe Catacombs §r§8- §r§eFloor VII"); out += ""; out += centre(scoreLine)
            defeated?.let { out += centre(it) }
            out += centre("§6> §e§lEXTRA STATS §6<")
            if (bits > 0) out += centre("§r§8+§r§b$bits Bits")
            out += centre("§r§8+§r§3${xp(cataXp)} Catacombs Experience")
            out += centre("§r§8+§r§3${xp(cataXp * 0.76)} $mine Experience")
            for (c in classes.filter { it != mine }.shuffled()) out += centre("§r§8+§r§3${xp(cataXp * 0.19)} $c Experience §r§b(Team Bonus)")
        } else {
            out += centre("§r§cThe Catacombs §r§8- §r§eFloor VII Stats"); out += ""; out += centre(scoreLine)
            defeated?.let { out += centre(it) }
            out += ""
            out += centre("Total Damage as $mine: §r§a${"%,d".format(java.util.Locale.US, Random.nextLong(600_000_000, 900_000_000))}")
            out += centre("Ally Healing: §r§a${"%,d".format(java.util.Locale.US, if (mine == "Healer") Random.nextInt(50_000, 150_000) else Random.nextInt(800, 4000))}")
            out += centre("Enemies Killed: §r§a${Random.nextInt(120, 180)}")
            out += centre("Deaths: §r§c0")
            out += centre("Secrets Found: §r§b${Random.nextInt(3, 13)}")
            out += ""
        }
        out += bar
        out.forEach { Sim.chat(it) }
    }

    private val score = Random.nextInt(300, 312)
    private val cataXp = Random.nextDouble(46_500.0, 50_000.0)
    private val bits = if (Random.nextDouble() < 0.72) Random.nextInt(45, 80) else 0

    private fun xp(v: Double) = "%,.1f".format(Locale.US, v).removeSuffix(".0")

    private fun centre(text: String): String {
        var w = 0; var bold = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) { val f = text[i + 1]; if (f == 'l') bold = true else if (f in "0123456789abcdefr") bold = false; i += 2; continue }
            w += when (c) {
                '!', ',', '.', ':', ';', '\'', '|' -> 2
                'i' -> 2; 'l', '`' -> 3; ' ', 'I', '[', ']', '"' -> 4
                't', 'f', 'k', '(', ')', '<', '>', '*', '{', '}', '☠' -> 5
                '@' -> 7
                else -> 6
            } + if (bold) 1 else 0
            i++
        }
        return "§f" + " ".repeat(ceil((160 - w / 2.0) / 4).toInt().coerceAtLeast(0)) + text
    }

    fun status() = if (t < GONE) "Necron ${t / 20}s" else "Necron dead"

    private class Fireball(level: ServerLevel) : LargeFireball(EntityTypes.FIREBALL, level) {
        private var life = 100
        override fun tick() { super.tick(); if (--life <= 0) discard() }
        override fun onHit(result: HitResult) {
            if (result.type == HitResult.Type.BLOCK) {
                val l = level() as ServerLevel
                l.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
                l.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4f, Random.nextInt(38, 52) / 63f)
            }
            discard()
        }
        override fun canHitEntity(entity: Entity) = false
        override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float) = false
    }

    private class Skull(level: ServerLevel) : WitherSkull(EntityTypes.WITHER_SKULL, level) {
        private var life = 100
        override fun tick() { super.tick(); if (--life <= 0) discard() }
        override fun onHit(result: HitResult) {
            (level() as ServerLevel).sendParticles(ParticleTypes.SMOKE, x, y, z, 6, 0.2, 0.2, 0.2, 0.01)
            discard()
        }
        override fun canHitEntity(entity: Entity) = false
        override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float) = false
    }

    private lateinit var pillars: Pillars
    private lateinit var storm: Storm

    private class Pillars(val frames: List<Triple<Int, BlockPos, BlockState>>) {
        private var next = 0
        fun tick(t: Int) {
            while (next < frames.size && frames[next].first <= t) { Blocks.set(frames[next].second, frames[next].third); next++ }
        }

        companion object {
            fun plan(): Pillars {
                val first = PILLARS.random()
                val out = ArrayList<Triple<Int, BlockPos, BlockState>>()
                out += pillar(first.first, first.second, L1 + 15 + Random.nextInt(-3, 5))
                var at = L2 + Random.nextInt(-2, 5)
                for (p in PILLARS.shuffled()) if (p !== first && Random.nextDouble() < p.third) out += pillar(p.first, p.second, at++)
                out.sortBy { it.first }
                return Pillars(out)
            }

            private fun pillar(x: Int, z: Int, start: Int): List<Triple<Int, BlockPos, BlockState>> {
                val side = z == 76
                val t = if (side) Data.sideFall else Data.cornerFall
                val (sx, sz) = if (side) 16 to 76 else 82 to 48
                val flipX = (x - 54) * (sx - 54) < 0; val flipZ = (z - 76) * (sz - 76) < 0
                return t.mapNotNull { (dt, p, s) ->
                    if (x == 54 && p.y < 64) return@mapNotNull null
                    val dx = p.x - sx; val dz = p.z - sz
                    val nx = if (x == 54) x + dx else x + if (flipX) -dx else dx
                    val nz = if (x == 54) z + dz else z + if (flipZ) -dz else dz
                    Triple(start + dt, BlockPos(nx, p.y, nz), s)
                }
            }
        }
    }

    private class Storm(val bolts: List<Int>, val frames: List<Triple<Int, BlockPos, BlockState>>, val centre: Vec3) {
        private var nextBolt = 0; private var next = 0
        fun tick(t: Int) {
            while (next < frames.size && frames[next].first <= t) { Blocks.set(frames[next].second, frames[next].third); next++ }
            while (nextBolt < bolts.size && bolts[nextBolt] <= t) {
                nextBolt++
                val a = Random.nextDouble(Math.PI * 2); val r = 12 * sqrt(Random.nextDouble())
                val bolt = LightningBolt(EntityTypes.LIGHTNING_BOLT, Sim.level)
                bolt.setVisualOnly(true)
                bolt.snapTo(centre.x + r * cos(a), 63.0, centre.z + r * sin(a), 0f, 0f)
                Sim.spawn(bolt)
            }
        }

        companion object {
            fun plan(): Storm {
                val (x0, z0, kind) = Data.columns.randomOrNull() ?: return Storm(emptyList(), emptyList(), Vec3.ZERO)
                val s = Data.storms[kind] ?: return Storm(emptyList(), emptyList(), Vec3.ZERO)
                val start = 22
                val frames = ArrayList<Triple<Int, BlockPos, BlockState>>()
                for ((dt, y, st) in s.frames) for (dx in 0..2) for (dz in 0..2) frames += Triple(start + dt, BlockPos(x0 + dx, y, z0 + dz), st)
                return Storm(s.bolts.map { start + it }, frames, Vec3(x0 + 1.5, 63.0, z0 + 1.5))
            }
        }
    }

    private object Data {
        class StormData(val bolts: List<Int>, val frames: List<Triple<Int, Int, BlockState>>)

        private val root by lazy {
            runCatching {
                P4Necron::class.java.getResourceAsStream("/assets/engineerclient/p3sim/p4-necron.json")!!.use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
            }.onFailure { EngineerClient.logger.warn("[p3sim] p4-necron.json", it) }.getOrNull()
        }

        private val states = HashMap<String, BlockState>()
        private fun state(s: String) = states.getOrPut(s) { Arena.parse(if (':' in s) s else "minecraft:$s") }

        val platforms: Map<String, List<Pair<BlockPos, BlockState>>> by lazy {
            root?.getAsJsonObject("platforms")?.entrySet()?.associate { (k, v) ->
                k to v.asJsonArray.map { e -> val a = e.asJsonArray; BlockPos(a[0].asInt, a[1].asInt, a[2].asInt) to state(a[3].asString) }
            } ?: emptyMap()
        }

        val columns: List<Triple<Int, Int, String>> by lazy {
            root?.getAsJsonObject("columns")?.entrySet()?.map { (_, v) -> val a = v.asJsonArray; Triple(a[0].asInt, a[1].asInt, a[2].asString) } ?: emptyList()
        }

        val storms: Map<String, StormData> by lazy {
            val lantern = state("sea_lantern"); val iron = state("iron_block")
            root?.getAsJsonObject("storms")?.entrySet()?.associate { (k, v) ->
                val o = v.asJsonObject
                k to StormData(o.getAsJsonArray("bolts").map { it.asInt },
                    o.getAsJsonArray("frames").map { e -> val a = e.asJsonArray; Triple(a[0].asInt, a[1].asInt, if (a[2].asString == "s") lantern else iron) })
            } ?: emptyMap()
        }

        private fun fall(x: Int, z: Int): List<Triple<Int, BlockPos, BlockState>> {
            val f = Blocks.anim("p4")?.frames ?: return emptyList()
            val mine = f.filter { fr ->
                !fr.state.isAir && PILLARS.minBy { (px, pz) -> (fr.pos.x - px) * (fr.pos.x - px) + (fr.pos.z - pz) * (fr.pos.z - pz) }.let { it.first == x && it.second == z }
            }
            val t0 = mine.minOfOrNull { it.dt } ?: return emptyList()
            return mine.map { Triple(it.dt - t0, it.pos, it.state) }
        }

        val cornerFall by lazy { fall(82, 48) }
        val sideFall by lazy { fall(16, 76) }
    }
}
