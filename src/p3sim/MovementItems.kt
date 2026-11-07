package com.engineerclient.p3sim

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

/**
 * The movement items, Bonzo's Staff and the Jerry-chine Gun, with the Wither Cloak's Creeper Veil and the Pet Rod's
 * cast: what each does once [SimItems]' hooks have taken the click.
 */
object MovementItems {
    /**
     * Sets [p]'s motion to [v] (a SET, as Hypixel's). The packet goes straight to the player at the end of this
     * [Fight.tick], the burst's own tick: hurtMarked would wait for the next tick's entity tracker. Several pushes in
     * one tick are one packet carrying the last vector. hurtMarked stays off, so no duplicate.
     */
    private fun push(p: ServerPlayer, v: Vec3) {
        p.deltaMovement = v
        pendingMotion[p] = v
    }

    internal val pendingMotion = LinkedHashMap<ServerPlayer, Vec3>()

    /** Sends this tick's [push]es (Fight.tick's last step). */
    fun flushMotion() {
        if (pendingMotion.isEmpty()) return
        pendingMotion.forEach { (p, v) -> if (!p.isRemoved) p.connection.send(ClientboundSetEntityMotionPacket(p.id, v)) }
        pendingMotion.clear()
    }

    /**
     * Bonzo's Staff, as measured on Hypixel: a hidden projectile flies from feet+1.0+0.2d
     * at 0.75 a tick (no gravity), drawn as a marker stand 1.5 below it; the firework bursts on the first 0.75
     * lattice point whose block cell isn't passable, k+1 ticks after the stand, and in that tick your motion is
     * *replaced* by 1.5 flat away from it and 0.5 up iff it is within 3.5 of feet+1.5. Hard 4-tick gate.
     * The burst's sound is the client's own.
     */
    internal fun bonzo(p: ServerPlayer) {
        // Hard 4-tick gate: never 1-3 ticks apart; a refused click sets nothing.
        val gap = Fight.serverTick - bonzoLast
        if (gap < 4) { EngineerClient.logger.info("[ec] bonzo: refused, {} ticks after the last (4-tick gate)", gap); return }
        // The held item is sent again on ~77% of accepted clicks, by idle time: ~50% under 8 ticks since the last
        // balloon, ~81% over 10. First, before the stand bundle.
        val resend = when { gap < 8 -> 0.5; gap > 10 -> 0.81; else -> 0.65 }
        bonzoLast = Fight.serverTick
        if (Random.nextDouble() < resend) p.connection.send(ClientboundContainerSetSlotPacket(0, p.inventoryMenu.incrementStateId(), 36 + p.inventory.selectedSlot, p.getItemInHand(InteractionHand.MAIN_HAND).copy()))
        // P = the last move packet's position (the server's at the click, asClicked), d = the look the click aims with:
        // a block click's is the last move packet's (use_item_on has none), an air click's the use_item's.
        val pos = SimItems.clickPos ?: p.position()
        val dir = Teleports.look(p)
        // Projectile pos(k) = P + (0,1,0) + 0.2d + 0.75k d, feet+1.0 sneaking too.
        val o = pos.add(0.0, 1.0, 0.0).add(dir.scale(0.2))
        // Stand S0 = floor32(P + 0.2d - 0.5y) = pos(0) - 1.5y, a marker (flags 32/18),
        // rotated to the look the server used.
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
        // Then the ghast sound, at P (after the bundle, same tick).
        Sim.sound(SoundEvents.GHAST_AMBIENT, 1f, (88 + Random.nextInt(26)) / 63f, pos, SoundSource.HOSTILE)
        // The burst lattice index k: first k >= 1 whose cell isn't passable; k = 2 when pos(0)'s cell is already
        // solid (e.g. fired from inside a ghost block). Overshoot is the lattice, 0..0.75.
        // Each cell is tested in its own flight tick, so a block that changes mid-flight counts (a gate, a Dungeonbreaker
        // hole); on a still world it's the same k as testing them all at the click.
        val startsInside = bonzoSolid(o)
        fun fly(j: Int) {
            Fight.later(1, "bonzo flight") {
                if (stand.isRemoved) return@later
                // Step k = j - 1 bursts in tick j: k+1 ticks after the stand (no clamp).
                val k = j - 1
                if (k >= 1 && k <= BONZO_STEPS && (if (startsInside) k == 2 else bonzoSolid(o.add(dir.scale(BONZO_SPEED * k))))) {
                    bonzoBurst(p, o.add(dir.scale(BONZO_SPEED * k)), stand); return@later
                }
                // No block within ~128: the stand flies on and goes with no burst.
                if (j > BONZO_STEPS) { EngineerClient.logger.info("[ec] bonzo: no block within reach, no burst"); stand.discard(); return@later }
                // Drawn 1.5 below the projectile (the vanilla tracker sends updates on its 3-tick cadence, the
                // first about spawn+4, as Hypixel's).
                val q = o.add(dir.scale(BONZO_SPEED * j))
                stand.setPos(q.x, q.y - 1.5, q.z)
                fly(j + 1)
            }
        }
        fly(1)
    }

    /**
     * The balloon bursts at [bTrue]: firework at floor32(bTrue) with one entity_event 17 and no server sound,
     * gone with the stand at burst+1; your boost in the same tick from the unquantised point.
     */
    private fun bonzoBurst(p: ServerPlayer, bTrue: Vec3, stand: ArmorStand) {
        val colours = { Random.nextInt(0x1000000) }
        val rocket = ItemStack(Items.FIREWORK_ROCKET)
        rocket.set(DataComponents.FIREWORKS, Fireworks(0, listOf(FireworkExplosion(
            FireworkExplosion.Shape.SMALL_BALL, IntArrayList(intArrayOf(colours(), colours())), IntArrayList(), false, false))))
        val b32 = floor32(bTrue)
        // The rocket is packets to you only, never a level entity: a firework's tracking range (64) dropped far
        // bursts, and a ticking one launches and explodes by itself. Add (zero motion), its item, event 17 exactly once
        // (the client draws the burst and plays the blast itself), and gone with the stand at burst+1.
        val fw = FireworkRocketEntity(Sim.level, b32.x, b32.y, b32.z, rocket)
        val conn = p.connection
        conn.send(ClientboundAddEntityPacket(fw.id, fw.uuid, b32.x, b32.y, b32.z, 0f, 0f,
            EntityTypes.FIREWORK_ROCKET, 0, Vec3.ZERO, 0.0))
        fw.entityData.nonDefaultValues?.let { conn.send(ClientboundSetEntityDataPacket(fw.id, it)) }
        conn.send(ClientboundEntityEventPacket(fw, 17.toByte()))
        liveRockets += fw.id
        Fight.later(1, "bonzo cleanup") { liveRockets.rem(fw.id); conn.send(ClientboundRemoveEntitiesPacket(fw.id)); stand.discard() }
        // The server's position of you (from the move packets that arrived), for the gate and the push.
        val pos = Fight.seenPos(p)
        // Boost iff the burst is within 3.5 of feet+1.5 (no random roll).
        val dist = bTrue.distanceTo(pos.add(0.0, 1.5, 0.0))
        EngineerClient.logger.info("[ec] bonzo: burst at {} {} {}, {} from you (seen {} {} {}, live {} {} {}): {}",
            "%.2f".format(bTrue.x), "%.2f".format(bTrue.y), "%.2f".format(bTrue.z), "%.2f".format(dist),
            "%.2f".format(pos.x), "%.2f".format(pos.y), "%.2f".format(pos.z), "%.2f".format(p.x), "%.2f".format(p.y), "%.2f".format(p.z),
            if (dist > 3.5) "no boost (over 3.5)" else "boost")
        if (dist > 3.5) return
        // SET 1.5 * unit(P.xz - B.xz), vy 0.5; exactly over it: (0, 0.5, 0).
        val dx = pos.x - bTrue.x; val dz = pos.z - bTrue.z
        val h = Math.sqrt(dx * dx + dz * dz)
        push(p, if (h > 0.0) Vec3(1.5 * dx / h, 0.5, 1.5 * dz / h) else Vec3(0.0, 0.5, 0.0))
    }

    /** floor(v*32)/32 per axis: the 1/32 grid Hypixel's packets put stand and firework on. */
    private fun floor32(v: Vec3) = Vec3(floor(v.x * 32) / 32, floor(v.y * 32) / 32, floor(v.z * 32) / 32)

    /**
     * A balloon cell that stops it: cell level, so stairs, slabs and walls are solid. Passable:
     * air, liquids, fire, levers, buttons, signs, torches, tripwire, pressure plates, carpet, heads, grass, light,
     * ladders and vines (TNT, plants, doors and bars unresolved). Glass panes stop it, a whole cell as any block.
     */
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

    /** A glass pane (any colour): the balloon and the Jerry bullet stop in its whole cell, not just its thin post. */
    private fun isGlassPane(s: BlockState) =
        s.block is IronBarsBlock && BuiltInRegistries.BLOCK.getKey(s.block).path.endsWith("glass_pane")

    /** One of the 9 balloon heads, uniformly random. */
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
    /** Packet-only rockets not yet removed (a stop drops their cleanup: [reset] removes them). */
    internal val liveRockets = IntArrayList()
    private const val BONZO_SPEED = 0.75
    /** ~170 lattice steps (127.5 blocks) with no block: no burst. */
    private const val BONZO_STEPS = 170

    /**
     * Jerry-chine Gun, as measured on Hypixel: an invisible bullet from F+1.1 along the last
     * move packet's look, B_k = O + (0.25 + 0.75k)u at tick k, bursts at the first B_k inside a block (or k = 30).
     * The burst plays villager.yes, then *replaces* your motion with vy 0.6 and 0.5 x the flat part of the unit
     * vector from it to your eye iff it is within 4.0 of feet+1.62, then a poof. Nothing is random but the pitch.
     */
    internal fun jerry(p: ServerPlayer) {
        // At most 3 bullets a server tick (a 4-click tick still fires 3).
        if (jerryTick != Fight.serverTick) { jerryTick = Fight.serverTick; jerryShots = 0 }
        if (++jerryShots > 3) return
        // F = the last move packet's position (asClicked's click position), u = its look (asClicked prior on a
        // use_item, the player's own on a block click).
        val f = SimItems.clickPos ?: p.position()
        val u = Teleports.look(p)
        val o = f.add(0.0, 1.1, 0.0)
        fun bullet(k: Int) = o.add(u.scale(0.25 + 0.75 * k))
        // The bullet's visual: invisible silent marker stand, Villager head, head pose = pitch, at F + u - 0.5y = B_1 - 1.6y;
        // shared flags 33 (invisible + bit 0) and armor-stand flags 18, as Hypixel's.
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
        // villager.trade NEUTRAL 0.5, pitch k/63 for k = 88..113, at your feet in the click tick, after the stand bundle.
        Sim.sound(SoundEvents.VILLAGER_TRADE, 0.5f, (88 + Random.nextInt(26)) / 63f, f, SoundSource.NEUTRAL)
        rapidFire(p)
        fun fly(k: Int) {
            Fight.later(1, "jerry bullet") {
                if (p.isRemoved) { stand.discard(); return@later }
                val b = bullet(k)
                // Check-then-move (max life 30).
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

    /** A bullet bursts at [b]: yes, then the push iff in range, then one poof; the last burst of a tick wins. */
    private fun jerryBurst(p: ServerPlayer, b: Vec3) {
        // villager.yes MASTER 0.35 pitch 1 at floor(B)+0.5, out of range too: straight to you,
        // as a level sound at volume 0.35 reaches only 5.6 blocks and far bursts were silent.
        p.connection.send(ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.VILLAGER_YES),
            SoundSource.MASTER, floor(b.x) + 0.5, floor(b.y) + 0.5, floor(b.z) + 0.5, 0.35f, 1f, Random.nextLong()))
        // d = (feet + 1.62) - B, the eye constant sneaking too; boost iff |d| <= 4.0.
        // Feet = the server's position of you at the burst.
        val d = Fight.seenPos(p).add(0.0, 1.62, 0.0).subtract(b)
        val len = d.length()
        if (len <= 4.0 && len > 0.0) push(p, Vec3(0.5 * d.x / len, 0.6, 0.5 * d.z / len))
        // One poof, count 1, at the exact burst point.
        Sim.level.sendParticles(ParticleTypes.POOF, true, true, b.x, b.y, b.z, 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** A bullet point inside a block's collision shape, or anywhere in a glass pane's cell (Hypixel's exact solidity rule is unverified). */
    private fun jerrySolid(b: Vec3): Boolean {
        val pos = BlockPos.containing(b)
        val s = Sim.level.getBlockState(pos)
        if (isGlassPane(s)) return true
        val shape = s.getCollisionShape(Sim.level, pos, CollisionContext.empty())
        return !shape.isEmpty && shape.toAabbs().any { it.move(pos).contains(b) }
    }

    /** The Jerry stand's head: Hypixel's "Villager" profile skin. */
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

    /** The Rapid-fire action bar: 14 x the shot in the burst, reset after 4 s idle. */
    private fun rapidFire(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now - rapidLast > 80) rapidShots = 0
        rapidShots++; rapidLast = now
        // A system_chat overlay, as Hypixel sends it, not a set_action_bar_text.
        p.connection.send(ClientboundSystemChatPacket(
            Component.literal("§b-${14 * rapidShots} Mana (§6Rapid-fire§b)"), true))
    }

    /** The Jerry bullet's stand: Hypixel's carries shared flag bit 0 (33 = invisible + 1) for its whole life; vanilla's baseTick would clear it. */
    class JerryStand(level: Level, x: Double, y: Double, z: Double) : ArmorStand(level, x, y, z) {
        override fun setSharedFlagOnFire(onFire: Boolean) = super.setSharedFlagOnFire(true)
    }

    /**
     * Creeper Veil as Hypixel runs it: it stays up until you right-click again, run out of vitality, or die
     * (no 10 s expiry, despite the lore). It does NOT stop death ticks (GoldorPhase.deathTick).
     * Vitality: 122, 30 a hit; a hit with under 30 left ends it ("Not enough vitality!"). Cooldown ~5 s from the end.
     */
    var cloakUntil by perPlayer { 0 }
        internal set
    internal var cloakReady by perPlayer { 0 }
    val cloaked get() = Fight.serverTick < cloakUntil
    internal var vitality by perPlayer { 0 }
    private const val VEIL_VITALITY = 122
    private const val VEIL_HIT = 30
    private const val VEIL_COOLDOWN = 100

    /** The 6 invisible powered creepers: a hexagon, radius 1.5, ~3 degrees a tick, at your feet. */
    private val veil: ArrayList<Creeper> by perPlayer { ArrayList() }
    private var veilAngle by perPlayer { 0.0 }

    /** A creeper that stays in the peaceful sim world and can't be hit, pushed or aimed at. */
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
            // Vanilla clears a mob's invisible flag without the effect: a permanent effect keeps it (no particles).
            e.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false))
            // Powered, as on Hypixel: the lightning hit sets the flag; its fire is put out again.
            e.thunderHit(Sim.level, LightningBolt(EntityTypes.LIGHTNING_BOLT, Sim.level))
            e.clearFire()
            val g = veilPos(p, i)
            e.snapTo(g.x, g.y, g.z, 0f, 0f)
            veil += Sim.spawn(e)
        }
    }

    internal fun discardVeil() { veil.forEach { it.discard() }; veil.clear() }

    /** The veil's end: the hurt sound at you, the creepers gone 2 ticks after the chat. [chat] null = ended by death (no line). */
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

    /** A hit on you: true when the veil took it. 30 vitality a hit; too little ends the veil. */
    fun veilAbsorbs(): Boolean {
        if (!cloaked) return false
        if (vitality >= VEIL_HIT) { vitality -= VEIL_HIT; return true }
        endVeil(Sim.player, "§cNot enough vitality! §r§dCreeper Veil §r§cDe-activated!")
        return true
    }

    internal fun tickVeil(p: ServerPlayer) {
        if (!cloaked) { if (veil.isNotEmpty() && veil.none { !it.isRemoved }) veil.clear(); return }
        // Dead (a death tick goes through the veil): it ends a tick later with the sound only.
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

    /**
     * A Pet Rod cast as main sends it: an invisible armour stand where the bobber starts, the silent splash (to you
     * only), player_abilities with the new pet's walking speed, the Autopet line ([Masks.swapPet]), then your fishing_bobber
     * thrown at 1.5 along your look (its motion sent with it), gone ~6 ticks on (2..11 measured).
     */
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
