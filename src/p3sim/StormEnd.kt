package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

class StormEnd : Fight.Phase("Storm end") {
    override val restart get() = Fight.Start.P3
    private var body: BossWither? = null

    override fun start() {
        Sim.forEachPlayer { p ->
            val spot = Spots.p3Start(1)
            Sim.tp(p, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
            SimItems.giveHotbar(p, p3 = true)
        }
        Party.standAt(SS_LANDING)
        Party.startLeadIn(LEAD - GoldorPhase.LEAD_IN)
        val b = BossWither("Storm", DEATH_AT, armoured = false)
        body = b
        StormFx.line(b, "I should have known that I stood no chance.")
        BossBar.show("§c§lStorm", 0.45f)
        StormCorpse(b).start()
    }

    override fun tick() {
        Party.tickLeadIn(t)
        when (t) {
            4 -> BossBar.progress(0f)
            SON -> StormFx.line(body, "At least my son died by your hands.")
            LEAD - GoldorPhase.LEAD_IN -> Fight.begin(GoldorPhase(1, arrived = true))
        }
    }

    companion object {
        const val LEAD = 62
        const val SON = 42
        val DEATH_AT = Vec3(44.6, 172.9, 65.2)
        val SS_LANDING = listOf(Vec3(108.3, 120.0, 94.0), Vec3(107.4, 120.0, 92.8), Vec3(108.3, 120.0, 95.4), Vec3(107.0, 120.0, 94.6))
    }
}

internal class StormCorpse(private val body: BossWither) {

    private val at = body.pos
    private var n = 0
    private var yaw = body.e.yRot.toDouble()
    private var nextHurt = 0
    private val bolts = HashMap<Int, MutableList<Vec3>>()

    init {
        for (s in SPIRALS) {
            val a0 = Random.nextDouble(360.0)
            for (j in 0 until 20) {
                val a = Math.toRadians(a0 + 90.0 * j)
                bolts.getOrPut(s + j * STEP) { ArrayList() } += Vec3(at.x + 2.0 * j * cos(a), at.y, at.z + 2.0 * j * sin(a))
            }
        }
    }

    fun start() = step()

    private fun step() {
        tick()
        n++
        if (n <= LAST) Fight.later(1, "storm corpse") { step() }
    }

    private fun tick() {
        if (n < DEAD) {
            yaw += 40.0
            val r = Math.toRadians(yaw)
            body.moveTo(at, at.add(-sin(r), 0.0, cos(r)))
            if (n == nextHurt) { Sim.sound(SoundEvents.WITHER_HURT, 15f, 1f, at, SoundSource.HOSTILE); nextHurt += Random.nextInt(9, 12) }
        }
        when (n) {
            0, 4 -> Sim.sound(SoundEvents.GENERIC_EXPLODE, 2f, 0.6f, at, SoundSource.BLOCKS)
            12 -> Sim.sound(SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 3f, 0.9f, at)
            DEAD -> body.dieAnim()
            GONE -> body.remove()
        }
        bolts[n]?.forEach { StormFx.bolt(it.x, it.z, it.y, thunder = true) }
    }

    companion object {
        const val DEAD = 60
        const val GONE = 80
        val SPIRALS = listOf(0, 20, 40, 60, 75, 90, 105, 120, 135, 150, 160, 170, 180, 190, 200, 210, 220, 230, 240, 250, 260, 270, 280, 290)
        const val STEP = 4
        val LAST = SPIRALS.last() + 19 * STEP
    }
}

internal object StormFx {
    fun line(w: BossWither?, text: String) {
        Sim.chat("§4[BOSS] Storm§r§c: $text")
        Sim.sound(SoundEvents.WITHER_AMBIENT, 5f, 1.19f, w?.pos)
        w ?: return
        val s = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level)
        s.isInvisible = true; s.setNoGravity(true); s.isPermanentlyInvulnerable = true; s.isSilent = true
        Station.setMarker(s)
        s.setCustomName(Sim.legacy("§4§l$text"))
        s.isCustomNameVisible = true
        s.snapTo(w.pos.x, w.pos.y + SPEECH_Y, w.pos.z, 0f, 0f)
        Sim.spawn(s)
        speech += s to w
        Fight.later(40, "storm speech") { s.discard(); speech.removeIf { it.first === s } }
    }

    private val speech = ArrayList<Pair<ArmorStand, BossWither>>()
    private const val SPEECH_Y = 4.1

    fun follow() {
        speech.removeIf { it.first.isRemoved }
        speech.forEach { (s, w) -> s.snapTo(w.pos.x, w.pos.y + SPEECH_Y, w.pos.z, 0f, 0f) }
    }

    fun clearSpeech() { speech.forEach { it.first.discard() }; speech.clear() }

    fun bolt(x: Double, z: Double, fromY: Double, thunder: Boolean = false): Vec3 {
        val p = Vec3(x, ground(x, z, fromY), z)
        val b = LightningBolt(EntityTypes.LIGHTNING_BOLT, Sim.level)
        b.setVisualOnly(true)
        b.snapTo(p.x, p.y, p.z, 0f, 0f)
        Sim.spawn(b)
        if (thunder) Sim.sound(SoundEvents.LIGHTNING_BOLT_THUNDER, 10000f, 0.8f + Random.nextFloat() * 0.2f, p, SoundSource.WEATHER)
        return p
    }

    fun ground(x: Double, z: Double, fromY: Double): Double {
        val bx = floor(x).toInt(); val bz = floor(z).toInt()
        var y = floor(fromY).toInt()
        repeat(15) {
            if (Sim.level.getBlockState(BlockPos(bx, y, bz)).`is`(BlockTags.BLOCKS_MOTION)) return y + 1.0
            y--
        }
        return fromY
    }

    fun dmg(v: Double): String = String.format(Locale.US, "%,.1f", v).removeSuffix(".0")

    fun hit(what: String, v: Double, trueDamage: Boolean = false) =
        Sim.chat("§cStorm's§r§7 $what hit you for §r§c${dmg(v)}§r§7 ${if (trueDamage) "true " else ""}damage.")

    fun burst(at: Vec3) = Sim.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
}

internal class StormFireball(level: Level, private val onImpact: (Vec3) -> Unit) : LargeFireball(EntityTypes.FIREBALL, level) {
    override fun onHit(result: HitResult) {
        if (level().isClientSide || isRemoved) return
        onImpact(result.location)
        discard()
    }

    override fun tick() { super.tick(); if (tickCount > 200) discard() }
}

internal class StormSkull(level: Level) : WitherSkull(EntityTypes.WITHER_SKULL, level) {
    override fun onHit(result: HitResult) {
        if (level().isClientSide || isRemoved) return
        StormFx.burst(result.location)
        discard()
    }

    override fun tick() { super.tick(); if (tickCount > 200) discard() }
}
