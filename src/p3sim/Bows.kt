package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.projectile.ProjectileDeflection
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object Bows {
    const val TERMINATOR = "TERMINATOR"
    const val SPIRIT = "ITEM_SPIRIT_BOW"
    const val MOSQUITO = "MOSQUITO_BOW"
    const val LAST_BREATH = "LAST_BREATH"
    const val EXPLOSIVE = "EXPLOSIVE_BOW"
    val DRAWN = setOf(LAST_BREATH, EXPLOSIVE)
    val SHORTBOWS = setOf(TERMINATOR, SPIRIT, MOSQUITO)

    private const val NASTY_BITE_COOLDOWN = 10

    private class Aim(val pos: Vec3, val yaw: Float, val pitch: Float, val crouch: Boolean)

    private fun aim(p: ServerPlayer) = Aim(p.position(), p.yRot, p.xRot, p.isShiftKeyDown)

    private class Click(val bow: String, val left: Boolean, val aim: Aim, val power: Float = 1f)
    private val clicks: ArrayList<Click> by perPlayer { ArrayList() }
    private class Later(val at: Int, val run: () -> Unit)
    private val later: ArrayList<Later> by perPlayer { ArrayList() }
    private val history: ArrayDeque<Aim> by perPlayer { ArrayDeque() }

    private var shotReady by perPlayer { 0 }
    private var biteReady by perPlayer { 0 }
    private var pendingShot: String? by perPlayer { null }
    private var pendingBite by perPlayer { false }

    private val live: ArrayList<SimArrow> by perPlayer { ArrayList() }

    fun reset() {
        clicks.clear(); later.clear(); history.clear()
        shotReady = 0; biteReady = 0; pendingShot = null; pendingBite = false
        live.forEach { if (!it.isRemoved) it.discard() }; live.clear()
        Hydra.reset()
    }

    fun start() {
        clicks.clear(); later.clear(); pendingShot = null; pendingBite = false
        Hydra.start()
    }

    fun click(p: ServerPlayer, bow: String, left: Boolean) {
        val a = aim(p)
        Fight.afterPing("shortbow") { if (!p.isRemoved) clicks += Click(bow, left, a) }
    }

    fun release(p: ServerPlayer, bow: String, power: Float) {
        val a = aim(p)
        Fight.afterPing("bow") { if (!p.isRemoved) clicks += Click(bow, false, a, power) }
    }

    fun tick() {
        val p = Sim.player ?: return
        val now = Fight.serverTick
        Hydra.tick(p, now)
        val held = SimItems.idOf(p.mainHandItem)
        pendingShot?.let { bow -> if (now >= shotReady) { pendingShot = null; if (held == bow) fire(p, bow, false, past(p)) } }
        if (pendingBite && now >= biteReady) { pendingBite = false; if (held == MOSQUITO) fire(p, MOSQUITO, true, past(p)) }
        for (c in clicks) {
            if (c.bow !in SHORTBOWS) { fire(p, c.bow, false, c.aim, c.power); continue }
            if (c.bow == MOSQUITO && c.left) { if (now >= biteReady) fire(p, MOSQUITO, true, past(p)) else pendingBite = true }
            else if (now >= shotReady) fire(p, c.bow, false, past(p)) else pendingShot = c.bow
        }
        clicks.clear()
        if (later.isNotEmpty()) {
            val due = later.filter { it.at <= now }
            later.removeAll(due.toSet())
            due.forEach { EngineerClient.safely("p3sim bow") { it.run() } }
        }
        live.removeAll { it.isRemoved }
        live.filter { it.tickCount > 100 }.forEach { it.discard() }
        history.addLast(aim(p))
        while (history.size > 40) history.removeFirst()
    }

    private fun past(p: ServerPlayer): Aim {
        val d = Fight.pingTicks
        return if (d <= 0 || history.isEmpty()) aim(p) else history[(history.size - d).coerceAtLeast(0)]
    }

    private fun fire(p: ServerPlayer, bow: String, nasty: Boolean, a: Aim, power: Float = 1f) {
        val now = Fight.serverTick
        val r = java.util.Random()
        val noise = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(0.0075)
        val duplexRoll = Random.nextDouble()
        val duplex = if (duplexRoll < 0.39) 3 else if (duplexRoll < 0.91) 4 else 5
        var dupIdx = 0
        val dupShift = if (Random.nextDouble() < 42.0 / 64.0) 0 else if (Random.nextBoolean()) 1 else -1
        val plan = ShotPlan.plan(bow, a.pos, a.yaw, a.pitch, a.crouch, Hydra.stacks, noise, duplex, 3.0 * power)
        for (arrow in plan) {
            if (arrow.delay == 0) launch(arrow.from, arrow.at, arrow.v, owner = if (arrow.owned) p else null,
                onHit = if (bow == EXPLOSIVE && arrow.owned) ({ h -> explode(h.location) }) else null)?.let { if (arrow.owned && bow in DRAWN && power >= 1f) it.isCritArrow = true }
            else {
                val delay = arrow.delay + (if (bow in ShotPlan.DUPLEX && dupIdx++ == 1) dupShift else 0)
                later += Later(now + delay) {
                    launch(arrow.from, arrow.at, arrow.v)
                    if (bow in ShotPlan.DUPLEX) sound(SoundEvents.ARROW_SHOOT, SoundSource.MASTER, 0.5f, 0.7f,
                        Vec3(Mth.floor(arrow.at.x) + 0.5, Mth.floor(arrow.at.y) + 0.5, Mth.floor(arrow.at.z) + 0.5))
                }
            }
        }
        sound(SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1f, 1f / (Random.nextFloat() * 0.4f + 1.2f) + 0.5f * power)
        SimItems.quiverShot()
        if (bow !in SHORTBOWS) return
        val stack = p.mainHandItem
        if (nasty) { biteReady = now + NASTY_BITE_COOLDOWN; p.cooldowns.addCooldown(stack, NASTY_BITE_COOLDOWN) }
        else { shotReady = now + P3Sim.shortbowCooldown; p.cooldowns.addCooldown(stack, P3Sim.shortbowCooldown) }
    }

    fun launch(from: Vec3, at: Vec3, v: Vec3, owner: ServerPlayer? = null, onHit: ((HitResult) -> Unit)? = null): SimArrow? {
        val level = Sim.level
        if (from != at) {
            val block = level.clip(ClipContext(from, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner ?: Sim.player!!))
            val end = if (block.type == HitResult.Type.MISS) at else block.location
            val boss = level.getEntitiesOfClass(LivingEntity::class.java, AABB(from, end).inflate(1.0)) { isBoss(it) }
                .filter { !immune(it) }
                .mapNotNull { w -> w.boundingBox.inflate(0.3).clip(from, end).map { w to it }.orElse(null) }
                .minByOrNull { it.second.distanceToSqr(from) }
            if (boss != null) { hit(EntityHitResult(boss.first, boss.second), onHit); return null }
            if (block.type != HitResult.Type.MISS) {
                val pitch = Mth.floor(1.2f / (Random.nextFloat() * 0.2f + 0.9f) * 63f) / 63f
                level.playSound(null, from.x, from.y, from.z, SoundEvents.ARROW_HIT, SoundSource.NEUTRAL, 1f, pitch)
                hit(block, onHit)
                return null
            }
        }
        val shooter = Sim.acting ?: owner
        val all = Sim.toAll
        val a = SimArrow(level, at.x, at.y, at.z) { _, h -> Sim.asPlayer(shooter, all) { hit(h, onHit) } }
        a.immune = ::immune
        if (owner != null) a.setOwner(owner)
        a.pickup = AbstractArrow.Pickup.DISALLOWED
        a.deltaMovement = v
        val yRot = Math.toDegrees(atan2(v.x, v.z)).toFloat()
        val xRot = Math.toDegrees(atan2(v.y, sqrt(v.x * v.x + v.z * v.z))).toFloat()
        a.yRot = yRot; a.xRot = xRot; a.yRotO = yRot; a.xRotO = xRot
        Sim.spawn(a)
        live += a
        return a
    }

    fun immune(e: Entity): Boolean = e is LivingEntity && e.invulnerableTime > 10

    private fun isBoss(e: Entity) = e is WitherBoss && e.isAlive && e.entityTags().contains(Sim.TAG)

    private fun hit(h: HitResult, onHit: ((HitResult) -> Unit)?) {
        when (h) {
            is BlockHitResult -> {
                (Fight.phase as? GoldorPhase)?.devices?.target?.hit(h.blockPos)
            }
            is EntityHitResult -> {
                Hydra.hit(Fight.serverTick)
                sound(SoundEvents.ARROW_HIT_PLAYER, SoundSource.NEUTRAL, 1f, 0.8f)
            }
        }
        onHit?.invoke(h)
    }

    private fun explode(at: Vec3) {
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 0.8f + Random.nextFloat() * 0.18f, at, SoundSource.MASTER)
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        (Fight.phase as? GoldorPhase)?.let { g -> g.gateNear(at, 2.0).takeIf { it > 0 }?.let { g.blowGate(it, Sim.me) } }
    }

    private fun sound(s: SoundEvent, source: SoundSource, volume: Float, pitch: Float, at: Vec3? = null) {
        val p = Sim.player ?: return
        Sim.level.playSound(null, at?.x ?: p.x, at?.y ?: p.y, at?.z ?: p.z, s, source, volume, Mth.floor(pitch * 63f) / 63f)
    }

    object Hydra {
        var stacks by perPlayer { 0 }
            private set
        private var lastGain by perPlayer { -100 }
        private var nextLoss by perPlayer { Int.MAX_VALUE }
        private var period by perPlayer { 0 }
        private var grid by perPlayer { Random.nextInt(20) }
        private var shown by perPlayer { 0 }
        private val pieces: Int get() = P3Sim.terrorPieces
        private fun periodNow(): Int = ((if (pieces >= 4) 10 else if (pieces == 3) 7 else 4) + 1) * 20

        fun reset() { stacks = 0; lastGain = -100; nextLoss = Int.MAX_VALUE; shown = 0; grid = Random.nextInt(20); start() }

        fun start() { stacks = if (pieces > 0) P3Sim.hydraStart else 0; period = periodNow(); nextLoss = Fight.serverTick + period }

        fun hit(now: Int) {
            if (pieces == 0 || now - lastGain < 4) return
            lastGain = now; period = periodNow(); nextLoss = now + period
            if (stacks < 10) stacks++
        }

        fun tick(p: ServerPlayer, now: Int) {
            if (pieces == 0) stacks = 0
            val phase = (now - grid).mod(20)
            if (pieces > 0 && stacks > 0 && now >= nextLoss) { stacks--; nextLoss += period }
            if (phase % 10 != 0) return
            if (stacks > 0) p.sendSystemMessage(Component.literal(if (stacks >= 10) "§6§l10⁑§r" else "§6$stacks⁑"), true)
            else if (shown > 0) p.sendSystemMessage(Component.empty(), true)
            shown = stacks
        }
    }
}

class SimArrow(level: Level, x: Double, y: Double, z: Double, private val whenHit: (SimArrow, HitResult) -> Unit) :
    Arrow(level, x, y, z, ItemStack(Items.ARROW), null) {

    var immune: (Entity) -> Boolean = { false }
    private var removeAt = -1

    override fun tick() {
        if (removeAt >= 0 && tickCount >= removeAt) { discard(); return }
        super.tick()
    }

    override fun canHitEntity(entity: Entity): Boolean = removeAt < 0 && entity.isAlive &&
        (entity is WitherBoss && entity.entityTags().contains(Sim.TAG))

    override fun findHitEntities(from: Vec3, to: Vec3): Collection<EntityHitResult> =
        ProjectileUtil.getManyEntityHitResult(level(), this, from, to, boundingBox.expandTowards(deltaMovement).inflate(1.0), { canHitEntity(it) }, 0.3f, ClipContext.Block.COLLIDER, false, true)

    override fun hitTargetOrDeflectSelf(hitResult: HitResult): ProjectileDeflection {
        if (removeAt >= 0) return ProjectileDeflection.NONE
        if (hitResult.type == HitResult.Type.ENTITY) {
            setNoGravity(true)
            if (immune((hitResult as EntityHitResult).entity)) {
                val speed = 0.29 + Random.nextDouble() * 0.03
                val yaw = Math.toRadians(yRot.toDouble() + (if (Random.nextBoolean()) 172.0 else -172.0))
                deltaMovement = Vec3(Math.sin(yaw) * speed, 0.0, Math.cos(yaw) * speed)
                yRot = Math.toDegrees(yaw).toFloat(); yRotO = yRot
                removeAt = tickCount + 9 + Random.nextInt(2)
                return ProjectileDeflection.NONE
            }
            EngineerClient.safely("p3sim arrow hit") { whenHit(this, hitResult) }
            deltaMovement = Vec3.ZERO
            removeAt = tickCount + 1 + Random.nextInt(2)
            return ProjectileDeflection.NONE
        }
        val d = super.hitTargetOrDeflectSelf(hitResult)
        if (hitResult.type == HitResult.Type.BLOCK) {
            EngineerClient.safely("p3sim arrow hit") { whenHit(this, hitResult) }
            discard()
        }
        return d
    }
}

object ShotPlan {
    class Planned(val from: Vec3, val at: Vec3, val v: Vec3, val owned: Boolean, val delay: Int)

    private const val G = 0.05
    private const val RIGHT = 0.01745

    fun look(yaw: Float, pitch: Float): Vec3 {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    fun roty(v: Vec3, deg: Double): Vec3 {
        val a = Math.toRadians(deg); val c = cos(a); val s = sin(a)
        return Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c)
    }

    val DUPLEX = setOf(Bows.MOSQUITO, Bows.LAST_BREATH)

    private val HYDRA = doubleArrayOf(-8.0, 8.0)
    private val SIDES = doubleArrayOf(5.5, -5.5, 5.5, -5.5)
    private val HYDRA_C = DoubleArray(2) { cos(Math.toRadians(HYDRA[it])) }
    private val HYDRA_S = DoubleArray(2) { sin(Math.toRadians(HYDRA[it])) }
    private val SIDES_C = DoubleArray(4) { cos(Math.toRadians(SIDES[it])) }
    private val SIDES_S = DoubleArray(4) { sin(Math.toRadians(SIDES[it])) }

    class Arrows {
        var n = 0
        val from = DoubleArray(3 * MAX)
        val at = DoubleArray(3 * MAX)
        val v = DoubleArray(3 * MAX)
        val dFrom = DoubleArray(MAX)
        val dAt = DoubleArray(MAX)
        val dV = DoubleArray(9 * MAX)
        val delay = IntArray(MAX)
        val owned = BooleanArray(MAX)

        internal fun add(fx: Double, fy: Double, fz: Double, ax: Double, ay: Double, az: Double, vx: Double, vy: Double, vz: Double,
                         dFrom: Double, dAt: Double, owned: Boolean, delay: Int): Int {
            val i = n++
            from[3 * i] = fx; from[3 * i + 1] = fy; from[3 * i + 2] = fz
            at[3 * i] = ax; at[3 * i + 1] = ay; at[3 * i + 2] = az
            v[3 * i] = vx; v[3 * i + 1] = vy; v[3 * i + 2] = vz
            this.dFrom[i] = dFrom; this.dAt[i] = dAt; this.owned[i] = owned; this.delay[i] = delay
            return i
        }

        companion object {
            const val MAX = 9
        }
    }

    fun plan(bow: String, pos: Vec3, yaw: Float, pitch: Float, crouch: Boolean, stacks: Int, noise: Vec3, duplexDelay: Int, speed: Double = 3.0): List<Planned> {
        val a = Arrows()
        planInto(a, bow, pos.x, pos.y, pos.z, yaw, pitch, crouch, stacks, noise.x, noise.y, noise.z, duplexDelay, speed)
        return List(a.n) { i ->
            Planned(Vec3(a.from[3 * i], a.from[3 * i + 1], a.from[3 * i + 2]), Vec3(a.at[3 * i], a.at[3 * i + 1], a.at[3 * i + 2]),
                Vec3(a.v[3 * i], a.v[3 * i + 1], a.v[3 * i + 2]), a.owned[i], a.delay[i])
        }
    }

    fun planInto(out: Arrows, bow: String, px: Double, py: Double, pz: Double, yaw: Float, pitch: Float, crouch: Boolean, stacks: Int,
                 nx: Double, ny: Double, nz: Double, duplexDelay: Int, speed: Double = 3.0) {
        out.n = 0
        val k = 1 + 0.01 * stacks
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        val cy = cos(yr); val sy = sin(yr); val cp = cos(pr); val sp = sin(pr)
        val dx = -sy * cp; val dy = -sp; val dz = cy * cp
        val ux = (dx + nx) * speed; val uy = (dy + ny) * speed; val uz = (dz + nz) * speed
        val fx = px - cy * RIGHT; val fy = py + (if (crouch) 1.27 else 1.62) - 0.1; val fz = pz - sy * RIGHT
        val p1x = fx + ux; val p1y = fy + uy; val p1z = fz + uz
        val mx = 0.99 * ux * k; val my = (0.99 * uy - G) * k; val mz = 0.99 * uz * k
        val dm = 0.99 * k * speed
        diag(out, out.add(fx, fy, fz, p1x, p1y, p1z, mx, my, mz, 0.0, speed, owned = true, delay = 0), dm)
        if (stacks >= 10) for (h in 0 until 2) {
            val c = HYDRA_C[h]; val s = HYDRA_S[h]
            val i = out.add(p1x, p1y, p1z, p1x, p1y, p1z, mx * c - mz * s, my, mx * s + mz * c, speed, speed, owned = false, delay = 0)
            val d = out.dV; val o = 9 * i
            d[o] = dm * c; d[o + 1] = 0.0; d[o + 2] = -dm * s
            d[o + 3] = 0.0; d[o + 4] = dm; d[o + 5] = 0.0
            d[o + 6] = dm * s; d[o + 7] = 0.0; d[o + 8] = dm * c
        }
        if (bow == Bows.TERMINATOR) {
            val ml = Math.sqrt(mx * mx + my * my + mz * mz)
            val gx = dm * mx / ml; val gy = dm * my / ml; val gz = dm * mz / ml
            for (t in 0 until 4) {
                val c = SIDES_C[t]; val s = SIDES_S[t]
                val rx = dx * c - dz * s; val rz = dx * s + dz * c
                val i = out.add(p1x, p1y - 0.5, p1z, p1x, p1y - 0.5, p1z, rx * ml, dy * ml + G, rz * ml, speed, speed, owned = false, delay = 0)
                val d = out.dV; val o = 9 * i
                d[o] = rx * gx; d[o + 1] = rx * gy; d[o + 2] = rx * gz
                d[o + 3] = dy * gx; d[o + 4] = dy * gy; d[o + 5] = dy * gz
                d[o + 6] = rz * gx; d[o + 7] = rz * gy; d[o + 8] = rz * gz
            }
        }
        if (bow in DUPLEX) repeat(2) { diag(out, out.add(p1x, p1y, p1z, p1x, p1y, p1z, mx, my, mz, speed, speed, owned = false, delay = duplexDelay), dm) }
    }

    private fun diag(out: Arrows, i: Int, d: Double) {
        val o = 9 * i
        for (r in 0 until 9) out.dV[o + r] = 0.0
        out.dV[o] = d; out.dV[o + 4] = d; out.dV[o + 8] = d
    }
}
