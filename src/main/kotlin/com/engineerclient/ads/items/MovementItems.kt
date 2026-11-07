package com.engineerclient.ads.items

import com.engineerclient.EngineerClient
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Rotations
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.BasePressurePlateBlock
import net.minecraft.world.level.block.BaseTorchBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LightBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.TallGrassBlock
import net.minecraft.world.level.block.TripWireBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.Base64
import kotlin.math.floor
import kotlin.random.Random
import com.engineerclient.ads.arena.Station
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.core.perPlayer
import com.engineerclient.ads.fight.Fight

object MovementItems {
    private fun push(p: ServerPlayer, v: Vec3) {
        p.deltaMovement = v
        pendingMotion[p] = v
    }

    internal val pendingMotion = LinkedHashMap<ServerPlayer, Vec3>()

    fun flushMotion() {
        if (pendingMotion.isEmpty()) return
        pendingMotion.forEach { (p, v) -> if (!p.isRemoved) p.connection.send(ClientboundSetEntityMotionPacket(p.id, v)) }
        pendingMotion.clear()
    }

    internal fun bonzo(p: ServerPlayer) {
        val gap = Fight.serverTick - bonzoLast
        if (gap < 4) { EngineerClient.logger.info("[ec] bonzo: refused, {} ticks after the last (4-tick gate)", gap); return }
        val resend = when { gap < 8 -> 0.5; gap > 10 -> 0.81; else -> 0.65 }
        bonzoLast = Fight.serverTick
        if (Random.nextDouble() < resend) p.connection.send(ClientboundContainerSetSlotPacket(0, p.inventoryMenu.incrementStateId(), 36 + p.inventory.selectedSlot, p.getItemInHand(InteractionHand.MAIN_HAND).copy()))
        val pos = SimItems.clickPos ?: p.position()
        val dir = Teleports.look(p)
        val o = pos.add(0.0, 1.0, 0.0).add(dir.scale(0.2))
        val s0 = floor32(pos.add(dir.scale(0.2)).add(0.0, -0.5, 0.0))
        val stand = ArmorStand(Sim.level, s0.x, s0.y, s0.z)
        stand.isInvisible = true
        stand.isSilent = true
        stand.setNoGravity(true)
        Station.setMarker(stand)
        Station.setLeverFlags(stand)
        stand.yRot = p.yRot; stand.xRot = p.xRot
        stand.setItemSlot(EquipmentSlot.HEAD, balloonHead())
        Sim.spawn(stand)
        Sim.sound(SoundEvents.GHAST_AMBIENT, 1f, (88 + Random.nextInt(26)) / 63f, pos, SoundSource.HOSTILE)
        val startsInside = bonzoSolid(o)
        fun fly(j: Int) {
            Fight.later(1, "bonzo flight") {
                if (stand.isRemoved) return@later
                val k = j - 1
                if (k >= 1 && k <= BONZO_STEPS && (if (startsInside) k == 2 else bonzoSolid(o.add(dir.scale(BONZO_SPEED * k))))) {
                    bonzoBurst(p, o.add(dir.scale(BONZO_SPEED * k)), stand); return@later
                }
                if (j > BONZO_STEPS) { EngineerClient.logger.info("[ec] bonzo: no block within reach, no burst"); stand.discard(); return@later }
                val q = o.add(dir.scale(BONZO_SPEED * j))
                stand.setPos(q.x, q.y - 1.5, q.z)
                fly(j + 1)
            }
        }
        fly(1)
    }

    private fun bonzoBurst(p: ServerPlayer, bTrue: Vec3, stand: ArmorStand) {
        val colours = { Random.nextInt(0x1000000) }
        val rocket = ItemStack(Items.FIREWORK_ROCKET)
        rocket.set(DataComponents.FIREWORKS, Fireworks(0, listOf(FireworkExplosion(
            FireworkExplosion.Shape.SMALL_BALL, IntArrayList(intArrayOf(colours(), colours())), IntArrayList(), false, false))))
        val b32 = floor32(bTrue)
        val fw = FireworkRocketEntity(Sim.level, b32.x, b32.y, b32.z, rocket)
        val conn = p.connection
        conn.send(ClientboundAddEntityPacket(fw.id, fw.uuid, b32.x, b32.y, b32.z, 0f, 0f,
            EntityTypes.FIREWORK_ROCKET, 0, Vec3.ZERO, 0.0))
        fw.entityData.nonDefaultValues?.let { conn.send(ClientboundSetEntityDataPacket(fw.id, it)) }
        conn.send(ClientboundEntityEventPacket(fw, 17.toByte()))
        liveRockets += fw.id
        Fight.later(1, "bonzo cleanup") { liveRockets.rem(fw.id); conn.send(ClientboundRemoveEntitiesPacket(fw.id)); stand.discard() }
        val pos = Fight.seenPos(p)
        val dist = bTrue.distanceTo(pos.add(0.0, 1.5, 0.0))
        EngineerClient.logger.info("[ec] bonzo: burst at {} {} {}, {} from you (seen {} {} {}, live {} {} {}): {}",
            "%.2f".format(bTrue.x), "%.2f".format(bTrue.y), "%.2f".format(bTrue.z), "%.2f".format(dist),
            "%.2f".format(pos.x), "%.2f".format(pos.y), "%.2f".format(pos.z), "%.2f".format(p.x), "%.2f".format(p.y), "%.2f".format(p.z),
            if (dist > 3.5) "no boost (over 3.5)" else "boost")
        if (dist > 3.5) return
        val dx = pos.x - bTrue.x; val dz = pos.z - bTrue.z
        val h = Math.sqrt(dx * dx + dz * dz)
        push(p, if (h > 0.0) Vec3(1.5 * dx / h, 0.5, 1.5 * dz / h) else Vec3(0.0, 0.5, 0.0))
    }

    private fun floor32(v: Vec3) = Vec3(floor(v.x * 32) / 32, floor(v.y * 32) / 32, floor(v.z * 32) / 32)

    private fun bonzoSolid(at: Vec3): Boolean {
        val s = Teleports.state(floor(at.x).toInt(), floor(at.y).toInt(), floor(at.z).toInt())
        val b = s.block
        if (s.isAir || b is LiquidBlock || b is BaseFireBlock || b is LeverBlock || b is ButtonBlock ||
            b is SignBlock || b is BaseTorchBlock || b is TripWireBlock ||
            b is TripWireHookBlock || b is BasePressurePlateBlock || b is CarpetBlock ||
            b is AbstractSkullBlock || b is TallGrassBlock ||
            b is LightBlock || b is LadderBlock || b is VineBlock) return false
        return true
    }

    private fun isGlassPane(s: BlockState) =
        s.block is IronBarsBlock && BuiltInRegistries.BLOCK.getKey(s.block).path.endsWith("glass_pane")

    private val BALLOON_HASHES = listOf(
        "f1f99e7c394e13d199d1d22cb082d209068cd7a4de6ca09edff3fc61660ae5cf", "52dd11da04252f76b6934bc26612f54f264f30eed74df89941209e191bebc0a2",
        "7c6cc5aed49056977d89348978c0aaaaa54c968a3b411bbaba5c9970b405297", "7399ff9c40fdfb7b115addddff59c344eaa11acb0c64c6839b5f8407e36d239d",
        "a2dd1389a97fd4c86b11a1a2abad61389404d837d2caf097c91d27768363dc5a", "ac96d34a3cced4e4bba25119f74ea31f50f43f90cca2fbcbc6bae8625d60dede",
        "28c544b288f899d494f953ace7743a15fdb22d24f58f9ca298f35429fdc27b63", "cc5ec2dfbf2cf4e21bd011c784d9fc4ad7a88182bed27afd91b53b4513ce0aba",
        "3491c8fb426979da2d6327b3705a4cb9c91c2d5536223233addde21d5f58c940")

    private fun balloonHead(): ItemStack {
        val hash = BALLOON_HASHES[Random.nextInt(BALLOON_HASHES.size)]
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$hash\"}}}"
        val s = SimItems.head(Base64.getEncoder().encodeToString(json.toByteArray()), "Player Head")
        s.remove(DataComponents.CUSTOM_NAME)
        return s
    }

    internal var bonzoLast by perPlayer { -100 }
    internal val liveRockets = IntArrayList()
    private const val BONZO_SPEED = 0.75
    private const val BONZO_STEPS = 170

    internal fun jerry(p: ServerPlayer) {
        if (jerryTick != Fight.serverTick) { jerryTick = Fight.serverTick; jerryShots = 0 }
        if (++jerryShots > 3) return
        val f = SimItems.clickPos ?: p.position()
        val u = Teleports.look(p)
        val o = f.add(0.0, 1.1, 0.0)
        fun bullet(k: Int) = o.add(u.scale(0.25 + 0.75 * k))
        val stand = JerryStand(Sim.level, f.x + u.x, f.y + u.y - 0.5, f.z + u.z)
        stand.isInvisible = true
        stand.setSharedFlagOnFire(true)
        stand.isSilent = true
        stand.setNoGravity(true)
        Station.setMarker(stand)
        Station.setLeverFlags(stand)
        stand.yRot = p.yRot; stand.xRot = p.xRot
        stand.setHeadPose(Rotations(p.xRot, 0f, 0f))
        stand.setItemSlot(EquipmentSlot.HEAD, villagerHead())
        Sim.spawn(stand)
        Sim.sound(SoundEvents.VILLAGER_TRADE, 0.5f, (88 + Random.nextInt(26)) / 63f, f, SoundSource.NEUTRAL)
        rapidFire(p)
        fun fly(k: Int) {
            Fight.later(1, "jerry bullet") {
                if (p.isRemoved) { stand.discard(); return@later }
                val b = bullet(k)
                if (k < JERRY_LIFE && !jerrySolid(b)) {
                    val next = bullet(k + 1)
                    stand.setPos(next.x, next.y - 1.6, next.z)
                    fly(k + 1); return@later
                }
                jerryBurst(p, b)
                Fight.later(1, "jerry stand gone") { stand.discard() }
            }
        }
        fly(1)
    }

    private fun jerryBurst(p: ServerPlayer, b: Vec3) {
        p.connection.send(ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.VILLAGER_YES),
            SoundSource.MASTER, floor(b.x) + 0.5, floor(b.y) + 0.5, floor(b.z) + 0.5, 0.35f, 1f, Random.nextLong()))
        val d = Fight.seenPos(p).add(0.0, 1.62, 0.0).subtract(b)
        val len = d.length()
        if (len <= 4.0 && len > 0.0) push(p, Vec3(0.5 * d.x / len, 0.6, 0.5 * d.z / len))
        Sim.level.sendParticles(ParticleTypes.POOF, true, true, b.x, b.y, b.z, 1, 0.0, 0.0, 0.0, 0.0)
    }

    private fun jerrySolid(b: Vec3): Boolean {
        val pos = BlockPos.containing(b)
        val s = Sim.level.getBlockState(pos)
        if (isGlassPane(s)) return true
        val shape = s.getCollisionShape(Sim.level, pos, CollisionContext.empty())
        return !shape.isEmpty && shape.toAabbs().any { it.move(pos).contains(b) }
    }

    private fun villagerHead(): ItemStack {
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/41b830eb4082acec836bc835e40a11282bb51193315f91184337e8d3555583\"}}}"
        val s = SimItems.head(Base64.getEncoder().encodeToString(json.toByteArray()), "Villager")
        s.remove(DataComponents.CUSTOM_NAME)
        return s
    }

    internal var jerryTick by perPlayer { -1 }
    private var jerryShots by perPlayer { 0 }
    private const val JERRY_LIFE = 30

    private var rapidShots by perPlayer { 0 }
    internal var rapidLast by perPlayer { -1000 }

    private fun rapidFire(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now - rapidLast > 80) rapidShots = 0
        rapidShots++; rapidLast = now
        p.connection.send(ClientboundSystemChatPacket(
            Component.literal("§b-${14 * rapidShots} Mana (§6Rapid-fire§b)"), true))
    }

    class JerryStand(level: Level, x: Double, y: Double, z: Double) : ArmorStand(level, x, y, z) {
        override fun setSharedFlagOnFire(onFire: Boolean) = super.setSharedFlagOnFire(true)
    }

    var cloakUntil by perPlayer { 0 }
        internal set
    internal var cloakReady by perPlayer { 0 }
    val cloaked get() = Fight.serverTick < cloakUntil
    internal var vitality by perPlayer { 0 }
    private const val VEIL_VITALITY = 122
    private const val VEIL_HIT = 30
    private const val VEIL_COOLDOWN = 100

    private val veil: ArrayList<Creeper> by perPlayer { ArrayList() }
    private var veilAngle by perPlayer { 0.0 }

    class VeilCreeper(level: Level) : Creeper(EntityTypes.CREEPER, level) {
        override fun checkDespawn() {}
        override fun isPickable() = false
        override fun isPushable() = false
        override fun canBeCollidedWith(other: Entity?) = false
    }

    private fun veilPos(p: ServerPlayer, i: Int): Vec3 {
        val a = Math.toRadians(veilAngle + 60.0 * i)
        return Vec3(p.x + 1.5 * Math.cos(a), p.y, p.z + 1.5 * Math.sin(a))
    }

    private fun spawnVeil(p: ServerPlayer) {
        discardVeil()
        veilAngle = 0.0
        for (i in 0 until 6) {
            val e = VeilCreeper(Sim.level)
            e.setNoAi(true); e.isSilent = true; e.isPermanentlyInvulnerable = true; e.setNoGravity(true); e.isInvisible = true
            e.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false))
            e.thunderHit(Sim.level, LightningBolt(EntityTypes.LIGHTNING_BOLT, Sim.level))
            e.clearFire()
            val g = veilPos(p, i)
            e.snapTo(g.x, g.y, g.z, 0f, 0f)
            veil += Sim.spawn(e)
        }
    }

    internal fun discardVeil() { veil.forEach { it.discard() }; veil.clear() }

    private fun endVeil(p: ServerPlayer?, chat: String?, soundNow: Boolean = false) {
        val now = Fight.serverTick
        cloakUntil = now; cloakReady = now + VEIL_COOLDOWN; vitality = 0
        if (chat != null) Sim.chat(chat)
        val sound = { Sim.sound(SoundEvents.SKELETON_HURT, 4f, 1.1904762f, null, SoundSource.HOSTILE) }
        if (soundNow) sound() else Fight.later(1, "veil end sound") { sound() }
        val gone = ArrayList(veil)
        veil.clear()
        Fight.later(2, "veil gone") { gone.forEach { it.discard() } }
    }

    fun veilAbsorbs(): Boolean {
        if (!cloaked) return false
        if (vitality >= VEIL_HIT) { vitality -= VEIL_HIT; return true }
        endVeil(Sim.player, "§cNot enough vitality! §r§dCreeper Veil §r§cDe-activated!")
        return true
    }

    internal fun tickVeil(p: ServerPlayer) {
        if (!cloaked) { if (veil.isNotEmpty() && veil.none { !it.isRemoved }) veil.clear(); return }
        if (Masks.ghost) { endVeil(p, null, soundNow = true); return }
        veilAngle += 3.0
        veil.forEachIndexed { i, e -> val g = veilPos(p, i); e.snapTo(g.x, g.y, g.z, 0f, 0f) }
    }

    internal fun cloak(p: ServerPlayer) {
        val now = Fight.serverTick
        if (cloaked) { endVeil(p, "§dCreeper Veil §r§cDe-activated!"); return }
        if (now < cloakReady) { Sim.chat("§cThis ability is on cooldown for ${(cloakReady - now + 19) / 20}s."); return }
        cloakUntil = Int.MAX_VALUE; vitality = VEIL_VITALITY
        Sim.chat("§dCreeper Veil §r§aActivated!")
        spawnVeil(p)
    }

    internal fun castRod(p: ServerPlayer) {
        val hook = FishingHook(p, Sim.level, 0, 0)
        hook.deltaMovement = p.lookAngle.scale(1.5)
        val stand = ArmorStand(Sim.level, hook.x, hook.y, hook.z)
        stand.isInvisible = true
        stand.isSilent = true
        stand.setNoGravity(true)
        Station.setMarker(stand)
        Sim.spawn(stand)
        p.connection.send(ClientboundSoundPacket(SoundEvents.PLAYER_SPLASH.let { BuiltInRegistries.SOUND_EVENT.wrapAsHolder(it) },
            SoundSource.PLAYERS, 200.0, 300.0, 400.0, 0f, 0f, Random.nextLong()))
        Masks.swapPet(p)
        Sim.spawn(hook)
        p.connection.send(ClientboundSetEntityMotionPacket(hook.id, hook.deltaMovement))
        Fight.later(6, "pet bobber gone") { hook.discard(); stand.discard() }
    }
}
