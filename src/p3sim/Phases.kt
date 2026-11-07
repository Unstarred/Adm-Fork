package com.engineerclient.p3sim

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

class BossWither(val name: String, at: Vec3, inv: Int = 1, armoured: Boolean = true, tagDelay: Int = 0) {
    val e: WitherBoss = SimWither(Sim.level).also { w ->
        w.setNoAi(true); w.isSilent = true; w.isPermanentlyInvulnerable = true; w.setNoGravity(true)
        w.invulnerableTicks = inv
        w.health = if (armoured) 1f else w.maxHealth
        w.snapTo(at.x, at.y, at.z, 0f, 0f)
        BossBar.hideOwn(w)
        Sim.spawn(w)
    }
    private var tag: ArmorStand? = null
    private var speech: ArmorStand? = null
    private var speechText = ""
    private var speechUntil = -1
    private var removed = false
    var pos: Vec3 = at
        private set

    init {
        live[name] = this
        if (tagDelay <= 0) showTag() else Fight.later(tagDelay, "$name name") { if (!removed) showTag() }
    }

    private fun showTag() {
        tag = marker(HypixelPack.text("§e\uFD3E §r§8\uE085§r§5\uE073 §r§c§l$name§r §e\uFD3F"), TAG_Y)
        if (Fight.serverTick < speechUntil) showSpeech()
    }

    private fun marker(text: String, dy: Double) = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level).also { s ->
        s.isInvisible = true; s.setNoGravity(true); s.isPermanentlyInvulnerable = true; s.isSilent = true
        Station.setMarker(s)
        s.setCustomName(Sim.legacy(text))
        s.isCustomNameVisible = true
        s.snapTo(pos.x, pos.y + dy, pos.z, 0f, 0f)
        Sim.spawn(s)
    }

    fun speak(line: String) {
        if (removed) return
        val text = "§4§l$line"
        speechText = text
        speechUntil = Fight.serverTick + if (name == "Goldor") GOLDOR_SPEECH_TICKS else SPEECH_TICKS
        speech?.discard(); speech = null
        if (tag != null) showSpeech()
    }

    private fun showSpeech() {
        speech?.discard()
        val s = marker(speechText, SPEECH_Y)
        speech = s
        Fight.later(speechUntil - Fight.serverTick, "$name speech end") { if (speech === s) { s.discard(); speech = null } }
    }

    fun armour(on: Boolean) { if (e.health > 0f) e.health = if (on) 1f else e.maxHealth }
    val armoured get() = e.health > 0f && e.health <= e.maxHealth / 2

    fun dieAnim() { e.health = 0f; tag?.discard(); speech?.discard() }

    fun moveTo(p: Vec3, faceTo: Vec3? = null) {
        pos = p
        val f = faceTo ?: p
        val yaw = if (faceTo != null) Math.toDegrees(Math.atan2(-(f.x - p.x), f.z - p.z)).toFloat() else e.yRot
        e.snapTo(p.x, p.y, p.z, yaw, 0f)
        e.yHeadRot = yaw; e.yBodyRot = yaw
        tag?.snapTo(p.x, p.y + TAG_Y, p.z, 0f, 0f)
        speech?.snapTo(p.x, p.y + SPEECH_Y, p.z, 0f, 0f)
    }

    fun step(target: Vec3, speed: Double, face: Vec3? = target): Boolean {
        val d = target.subtract(pos)
        val len = d.length()
        if (len <= speed) { moveTo(target, face); return true }
        moveTo(pos.add(d.scale(speed / len)), face)
        return false
    }

    fun remove() {
        removed = true
        e.discard(); tag?.discard(); speech?.discard()
        if (live[name] === this) live.remove(name)
    }

    companion object {
        const val TAG_Y = 3.6875
        const val SPEECH_Y = 4.09375
        const val SPEECH_TICKS = 41
        const val GOLDOR_SPEECH_TICKS = 61
        private val live = HashMap<String, BossWither>()

        fun speak(name: String, line: String) { live[name]?.takeIf { !it.removed && !it.e.isRemoved }?.speak(line) }
    }
}

class SimWither(level: Level) : WitherBoss(EntityTypes.WITHER, level) {
    override fun checkDespawn() {}
}

object BossBar {
    private var bar: ServerBossEvent? = null

    fun show(name: String, progress: Float = 1f) {
        val b = bar ?: ServerBossEvent(UUID.randomUUID(), Sim.legacy(name),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS).also { bar = it }
        b.name = Sim.legacy(name)
        b.progress = progress.coerceIn(0f, 1f)
        val all = Sim.players()
        if (b.players.toSet() != all.toSet()) { b.removeAllPlayers(); all.forEach { b.addPlayer(it) } }
    }

    fun showTo(p: ServerPlayer) { bar?.let { if (p !in it.players) it.addPlayer(p) } }

    fun progress(f: Float) { bar?.progress = f.coerceIn(0f, 1f) }

    fun resend() {
        val b = bar ?: return
        for (p in b.players) {
            p.connection.send(ClientboundBossEventPacket.createUpdateNamePacket(b))
            p.connection.send(ClientboundBossEventPacket.createUpdateStylePacket(b))
            p.connection.send(ClientboundBossEventPacket.createUpdateProgressPacket(b))
        }
    }
    val progress: Float get() = bar?.progress ?: 0f

    fun hide() { bar?.removeAllPlayers(); bar = null }

    private val ownField by lazy {
        WitherBoss::class.java.declaredFields.firstOrNull { ServerBossEvent::class.java.isAssignableFrom(it.type) }?.apply { isAccessible = true }
    }

    fun hideOwn(w: WitherBoss) { runCatching { (ownField?.get(w) as? ServerBossEvent)?.isVisible = false } }
}

internal fun closest(p: Vec3): Vec3 {
    val players = Sim.players().map { it.position() }
    val bots = Party.bots().filter { it.entity != null }.map { it.pos }
    return (players + bots).minByOrNull { it.distanceToSqr(p) } ?: p
}

fun lookingAt(at: Vec3, degrees: Double, range: Double): Boolean {
    val p = Sim.player ?: return false
    val eye = p.eyePosition
    val to = at.subtract(eye)
    if (to.length() > range) return false
    val look = p.getViewVector(1f)
    val cos = look.dot(to.normalize())
    return cos >= Math.cos(Math.toRadians(degrees))
}

class P1Maxor : Fight.Phase("P1") {
    override val restart get() = Fight.Start.P1
    private lateinit var maxor: BossWither

    private inner class Top(val side: Int, stands: Boolean) {
        val e = spawnCrystal(TOPS[side])
        var name: ArmorStand? = null
        var click: ArmorStand? = null
        init { if (stands) labels() }
        fun labels() {
            if (name != null || e.isRemoved) return
            name = label(TOPS[side].add(0.0, 0.125, 0.0), "§bEnergy Crystal")
            click = label(TOPS[side].add(0.0, -0.25, 0.0), "§e§lCLICK HERE")
        }
        fun owns(x: Entity) = x === e || x === name || x === click
        fun remove() { e.discard(); name?.discard(); click?.discard() }
    }

    private inner class Pylon(val side: Int) {
        var crystal: EndCrystal? = null
        var placedAt = -1
        var top: ArmorStand? = null
        var bottom: ArmorStand? = null
        val placed get() = crystal != null
        fun open() {
            if (top != null) return
            top = label(PYLONS[side].add(0.0, -0.875, 0.0), "§cEnergy Crystal Missing")
            bottom = label(PYLONS[side].add(0.0, -1.25, 0.0), "§e§lCLICK HERE")
        }
        fun active() { rename(top, "§aCrystal Active"); rename(bottom, "") }
        fun missing() { rename(top, "§cEnergy Crystal Missing"); rename(bottom, "§e§lCLICK HERE") }
        fun owns(x: Entity) = x === crystal || x === top || x === bottom
        fun clear() { crystal?.discard(); crystal = null }
        fun remove() { clear(); top?.discard(); bottom?.discard(); top = null; bottom = null }
    }

    private inner class Carrier(val bot: Party.Bot, val side: Int) {
        var holding = false
        var leaveAt = 5 + Random.nextInt(20)
        var pylon = side
        var fetch = side
    }

    private fun lastOpen() = if (lastHit < 0) OPEN else maxOf(OPEN, lastHit + 40)

    private val tops = arrayOfNulls<Top>(2)
    private val pylons = arrayOf(Pylon(0), Pylon(1))
    private val carriers = ArrayList<Carrier>()
    private var lure: Party.Bot? = null
    private val carryingBy = perPlayer { 0 }
    private var carrying by carryingBy
    private var crystalSlot by perPlayer { -1 }
    private val placeTimes = ArrayList<Int>()
    private val pending = HashMap<Int, String>()
    private var open = false
    private var chargeAt = -1
    private var beacon = false
    private var hits = 0
    private var lastHit = -1000
    private var stunned = false
    private var enrageAt = -1
    private var moveAt = MOVE
    private var stunLines = 0
    private var tauntAt = -1
    private var killAt = -1
    private var health = 1f
    private var healthFloor = 1f
    private var healthRate = 0f
    private val lights = HashMap<Int, MutableList<Pair<BlockPos, Boolean>>>()
    private var centreLit = false
    private val TAUNTS_A = listOf(
        "YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!", "YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
        "I HOPE YOU LIKE EXPLOSIONS TOO!", "MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!",
    )
    private val TAUNTS_SKULL = listOf("How about you taste some rapid fire Wither Skulls!", "Time for me to blast you away for good!", "Eat Wither Skulls, scum!")
    private val skullTauntAt = if (Random.nextInt(100) < 11) 436 + Random.nextInt(12) else -1
    private val clicked = HashSet<Int>()
    private var handed = false
    private var belowAt = -1

    override fun start() {
        if (Sim.players().isEmpty()) return
        val s = Spots.P1
        Sim.forEachPlayer { p ->
            Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
            SimItems.giveHotbar(p, p3 = false)
        }
        BossBar.show("§c§lMaxor", 1f)
        Sim.boss("Maxor", "WELL! WELL! WELL! LOOK WHO'S HERE!", at = null)
        stripStart(); Blocks.play("p1strip", exclude = COLUMN)
        Party.standAt(listOf(Vec3(71.5, 221.0, 16.5), Vec3(75.5, 221.0, 16.5), Vec3(69.5, 221.0, 18.5), Vec3(77.5, 221.0, 18.5)))
        val bots = if (P3Sim.bots) Party.bots().filter { it.entity != null } else emptyList()
        fun of(c: DungeonClass) = bots.firstOrNull { it.clazz == c }
        of(DungeonClass.BERSERK)?.let { carriers += Carrier(it, 0) }
        of(DungeonClass.MAGE)?.let { carriers += Carrier(it, 1) }
        lure = of(DungeonClass.TANK)
        healer = of(DungeonClass.HEALER)
        archer = of(DungeonClass.ARCHER)
    }

    private var healer: Party.Bot? = null
    private var archer: Party.Bot? = null

    override fun stop() {
        tops.forEach { it?.remove() }
        pylons.forEach { it.remove() }
        if (::maxor.isInitialized && !handed) maxor.remove()
        Blocks.stop("p1strip")
        Sim.forEachPlayer { if (carrying > 0) { carrying = 0; giveBackMenu() } }
    }

    private fun check() = t % 10 == 0

    override fun tick() {
        when (t) {
            5 -> {
                maxor = BossWither("Maxor", Vec3(73.0, 226.0, 53.0), inv = 200, tagDelay = 17)
                maxor.speak("WELL! WELL! WELL! LOOK WHO'S HERE!")
                for (i in 0..1) tops[i] = Top(i, stands = false)
            }
            28 -> tops.forEach { it?.labels() }
            42 -> Sim.boss("Maxor", "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.")
            84 -> Sim.boss("Maxor", "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.")
            OPEN -> {
                open = true
                Blocks.set(BlockPos(73, 221, 73), B.AIR.defaultBlockState()); Blocks.set(BlockPos(73, 222, 73), B.AIR.defaultBlockState())
                pylons.forEach { it.open() }
                Sim.note("The pylons take crystals now: pick one up on top, right-click a pylon (or let the bots).")
            }
            BEACON -> { beacon = true; Blocks.set(BlockPos(73, 221, 73), B.BEACON.defaultBlockState()) }
        }
        lights.remove(t)?.forEach { (p, on) -> lamp(p, on) }
        if (!::maxor.isInitialized) return
        if ((t + 3) % 20 == 0) BossBar.progress(health)
        if (killAt >= 0) { killed(); return }
        bots()
        if (open && t % 10 == 1) {
            pending.forEach { (i, by) -> place(i, by) }
            pending.clear()
        }
        if (chargeAt >= 0 && t == chargeAt) Sim.chat("§aThe Energy Laser is charging up!")
        if (check()) column()
        if (t == tauntAt) Sim.boss("Maxor", TAUNTS_A.random())
        if (t == skullTauntAt && !stunned && !inAbility()) Sim.boss("Maxor", TAUNTS_SKULL.random())
        if (stunned && t == enrageAt) {
            Sim.chat("§c⚠ Maxor is enraged! ⚠")
            stunned = false; moveAt = t + 1 + Random.nextInt(5); tauntAt = t + 161 + Random.nextInt(4)
            health = 0.25f
            maxor.armour(true)
        }
        if (t == BEACON - 1 && tauntAt < 0) tauntAt = BEACON + 161
        if (stunned) {
            maxor.armour(Random.nextInt(25) == 0)
            health = maxOf(healthFloor, health - healthRate)
        }
        val target = closest(maxor.pos).add(0.0, 1.0, 0.0)
        if (stunned) maxor.moveTo(maxor.pos, maxor.pos.add(0.0, 0.0, 1.0))
        else if (t >= moveAt) { val d = maxor.pos.distanceTo(target); if (d > 3.0) maxor.step(target, min(0.9, 0.24 + 0.02 * d)) else maxor.moveTo(maxor.pos, target) }
        else maxor.moveTo(maxor.pos, target)
        if (check() && charged() && beacon && inBeam()) hit()
    }

    private fun charged() = chargeAt >= 0 && t >= chargeAt

    private fun inBeam() = maxor.pos.y >= 225.0 && Math.hypot(maxor.pos.x - BEAM.x, maxor.pos.z - BEAM.z) <= 3.5

    private fun inAbility() = tauntAt >= 0 && t >= tauntAt - 1 && t < tauntAt + 62

    private fun spawnCrystal(at: Vec3): EndCrystal {
        val c = EndCrystal(EntityTypes.END_CRYSTAL, Sim.level)
        c.setShowBottom(false)
        c.isPermanentlyInvulnerable = true
        c.snapTo(at.x, at.y, at.z, 0f, 0f)
        return Sim.spawn(c)
    }

    private fun label(at: Vec3, text: String): ArmorStand {
        val s = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level)
        s.isInvisible = true; s.setNoGravity(true); s.isPermanentlyInvulnerable = true; s.isSilent = true
        rename(s, text)
        s.snapTo(at.x, at.y, at.z, 0f, 0f)
        return Sim.spawn(s)
    }

    private fun rename(s: ArmorStand?, text: String) {
        s ?: return
        s.setCustomName(Sim.legacy(text))
        s.isCustomNameVisible = text.isNotEmpty()
    }

    fun useCrystal(c: Entity): Boolean {
        val top = tops.firstOrNull { it != null && it.owns(c) }
        if (top != null) {
            if (killAt >= 0) return true
            take(top)
            carrying++
            val me = Sim.me
            Sim.everyone { Sim.chat("§b$me§r§a picked up an §r§bEnergy Crystal§r§a!") }
            holdCrystal()
            return true
        }
        val pylon = pylons.firstOrNull { it.owns(c) } ?: return false
        if (pylon.placed || pylon.side in pending || pylon.side in clicked || killAt >= 0) return true
        if (carrying <= 0) Sim.chat("§cYou do not current have an §r§bEnergy Crystal§r§c! Find one and bring it here!")
        else usePylon(PYLONS[pylon.side])
        return true
    }

    private fun take(top: Top) {
        top.remove()
        tops[top.side] = null
    }

    fun usePylon(at: Vec3): Boolean {
        if (carrying <= 0 || !open || killAt >= 0) return false
        val i = PYLONS.indexOfFirst { Math.hypot(it.x - at.x, it.z - at.z) < 3.5 && Math.abs(it.y - at.y) < 4 }
        if (i < 0 || pylons[i].placed || i in pending || i in clicked) return false
        carrying--
        clicked += i
        Fight.afterPing("maxor place") { clicked -= i; if (killAt < 0) place(i, Sim.me) }
        return true
    }

    private fun holdCrystal() {
        val p = Sim.player ?: return
        val inv = p.inventory
        if (crystalSlot < 0) crystalSlot = (0..8).firstOrNull { SimItems.idOf(inv.getItem(it)) == "SKYBLOCK_MENU" } ?: 8
        inv.setItem(crystalSlot, crystalItem())
        inv.selectedSlot = crystalSlot
        p.connection.send(ClientboundSetHeldSlotPacket(crystalSlot))
        p.inventoryMenu.broadcastChanges()
    }

    private fun giveBackMenu() {
        val p = Sim.player ?: return
        val slot = crystalSlot.takeIf { it >= 0 } ?: return
        crystalSlot = -1
        if (SimItems.idOf(p.inventory.getItem(slot)) == "MAXOR_ENERGY_CRYSTAL") p.inventory.setItem(slot, SimItems.MENU)
        p.inventoryMenu.broadcastChanges()
    }

    private fun crystalItem(): ItemStack {
        val s = ItemStack(Items.NETHER_STAR)
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§bEnergy Crystal").withStyle { it.withItalic(false) })
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", "MAXOR_ENERGY_CRYSTAL"); it.putBoolean("p3sim", true) }))
        return s
    }

    private fun place(i: Int, by: String) {
        val pylon = pylons[i]
        if (pylon.placed) return
        pylon.crystal = spawnCrystal(PYLONS[i])
        pylon.placedAt = t
        if (by == Sim.me && carrying == 0) giveBackMenu()
        val x = 1 + placeTimes.count { t >= it + 28 }
        placeTimes += t
        Sim.everyone { Sim.chat(if (x >= 2) "§a$x/2 Energy Crystals are now active!" else "§c$x§r§a/2 Energy Crystals are now active!") }
        val c = pylon.crystal
        Fight.later(20, "maxor pylon active") { if (Fight.phase === this && pylon.crystal === c && c != null) pylon.active() }
        light(t, i, on = true, centre = !centreLit)
        centreLit = true
        if (pylons.all { it.placed } && chargeAt < 0) chargeAt = t + 28
    }

    private fun light(at: Int, side: Int, on: Boolean, centre: Boolean) {
        FLOORS[side].forEachIndexed { i, p -> queue(at + i, p, on) }
        CEILINGS[side].forEachIndexed { j, p -> queue(at + 19 + j, p, on) }
        if (centre) CENTRE.forEachIndexed { k, p -> queue(at + 24 + k, p, on) }
    }

    private fun queue(at: Int, p: BlockPos, on: Boolean) {
        if (at <= t) lamp(p, on) else { lights.getOrPut(at) { ArrayList() } += p to on }
    }

    private fun lamp(p: BlockPos, on: Boolean) = Blocks.set(p, if (on) B.SEA_LANTERN.defaultBlockState() else B.COAL_BLOCK.defaultBlockState())

    private fun column() {
        val n = placeTimes.count { t >= it + 28 }
        val state = when {
            charged() && beacon -> B.STAINED_GLASS.red()
            n >= 1 -> B.STAINED_GLASS.yellow()
            else -> B.STAINED_GLASS.black()
        }.defaultBlockState()
        for (y in (if (t >= BEACON + 120) 222 else 223)..224) if (Blocks.get(BlockPos(73, y, 73)) != state) Blocks.set(BlockPos(73, y, 73), state)
    }

    private fun stripStart() {
        val blocks = Blocks.extra("anims-p124.json", "p1stripStart")?.getAsJsonArray("blocks") ?: return
        for (e in blocks) {
            val a = e.asJsonArray
            val pos = BlockPos(a[0].asInt, a[1].asInt, a[2].asInt)
            if (pos in COLUMN) continue
            Blocks.set(pos, Arena.parse(a[3].asString))
        }
    }

    private fun bots() {
        lure?.let { if (t >= 5) walk(it, LURE, 0.7) }
        for (c in carriers) {
            if (c.bot.entity == null) continue
            if (!c.holding) {
                if (c.leaveAt == Int.MAX_VALUE && carryingBy.values().all { it == 0 } && open) {
                    val orphan = (0..1).firstOrNull { s -> tops[s] != null && carriers.none { it.side == s } && t >= lastOpen() + 60 }
                    if (orphan != null) { c.fetch = orphan; c.leaveAt = t }
                }
                if (t < c.leaveAt) continue
                val top = tops[c.fetch]
                if (walk(c.bot, TOP_STANDS[c.fetch], 0.7) && top != null) {
                    take(top)
                    c.holding = true
                    Sim.chat("§a${c.bot.name}§r§a picked up an §r§bEnergy Crystal§r§a!")
                }
            } else {
                if (pylons[c.pylon].placed || c.pylon in clicked || (c.pylon in pending && pending[c.pylon] != c.bot.name)) c.pylon = 1 - c.pylon
                if (walk(c.bot, PYLON_STANDS[c.pylon], 0.7) && open && !pylons[c.pylon].placed && c.pylon !in pending && c.pylon !in clicked) {
                    pending[c.pylon] = c.bot.name
                    c.holding = false
                    c.leaveAt = Int.MAX_VALUE
                }
            }
        }
    }

    private fun walk(b: Party.Bot, to: Vec3, speed: Double): Boolean {
        val d = to.subtract(b.pos)
        val len = d.length()
        if (len < 0.05) return true
        if (Math.abs(d.x) + Math.abs(d.z) > 0.01) b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
        b.pos = if (len <= speed) to else b.pos.add(d.scale(speed / len))
        b.entity?.let { e -> e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, 0f); e.yHeadRot = b.yaw; e.yBodyRot = b.yaw }
        return len <= speed
    }

    fun botsChanged() {
        val gone = carriers.filter { it.bot.entity == null }
        if (gone.isEmpty()) return
        val had = gone.count { it.holding }
        carriers.removeAll(gone.toSet())
        if (had > 0 && killAt < 0) {
            val botHeld = carriers.filter { it.holding }.map { it.fetch }
            val missing = (0..1).filter { tops[it] == null && !pylons[it].placed && it !in pending && it !in clicked && it !in botHeld }
            missing.drop(carryingBy.values().sum()).take(had).forEach { tops[it] = Top(it, stands = true) }
        }
        if (lure?.entity == null) lure = null
    }

    fun playerLeft(p: ServerPlayer) = Sim.asPlayer(p) {
        val had = carrying
        if (had <= 0) return@asPlayer
        carrying = 0
        if (killAt >= 0) return@asPlayer
        val botHeld = carriers.filter { it.holding }.map { it.fetch }
        val missing = (0..1).filter { tops[it] == null && !pylons[it].placed && it !in pending && it !in clicked && it !in botHeld }
        missing.drop(carryingBy.values().sum()).take(had).forEach { tops[it] = Top(it, stands = true) }
    }

    private fun hit() {
        hits++
        lastHit = t
        chargeAt = -1
        val at = t
        if (hits == 1) { health = 0.95f; healthFloor = 0.30f + Random.nextFloat() * 0.35f; healthRate = 0.004f + Random.nextFloat() * 0.006f }
        else { health = 0.12f + Random.nextFloat() * 0.08f; healthFloor = 0.01f; healthRate = 0.01f }
        carriers.forEach { if (!it.holding) { it.fetch = it.side; it.leaveAt = at + 15 + Random.nextInt(10) } }
        Fight.later(40, "maxor lines dark") { if (Fight.phase === this) { light(t, 0, false, true); light(t, 1, false, false); centreLit = false } }
        Fight.later(40, "maxor crystals") { if (Fight.phase === this) for (i in 0..1) if (tops[i] == null) tops[i] = Top(i, stands = true) }
        Fight.later(41, "maxor pylons") {
            if (Fight.phase === this && killAt < 0) pylons.forEach { if (it.placed && it.placedAt <= at) { it.clear(); it.missing() } }
            placeTimes.removeAll { it <= at }
        }
        if (hits >= 2) killAt = t + if (P3Sim.bots) 1 + Random.nextInt(9) else 8 + Random.nextInt(8)
        if (inAbility()) {
            val lineAt = tauntAt + 62
            Fight.later(lineAt - t, "maxor late stun") {
                if (Fight.phase === this || handed) stunLine()
                if (Fight.phase === this && killAt < 0) freeze()
            }
        } else {
            freeze()
            Fight.later(STUN_LINE, "maxor stun line") { if (Fight.phase === this || handed) stunLine() }
        }
    }

    private fun stunLine() {
        stunLines++
        Sim.boss("Maxor", if (Random.nextBoolean()) "THAT BEAM! IT HURTS! IT HURTS!!" else "YOU TRICKED ME!")
        val at = t
        if (stunLines == 2 || hits >= 2) Fight.later(82, "too young") { if ((Fight.phase === this || handed) && (killAt < 0 || at + 82 < killAt + 80)) Sim.boss("Maxor", "I'M TOO YOUNG TO DIE AGAIN!") }
    }

    private fun freeze() {
        stunned = true
        if (tauntAt > t) tauntAt = -1
        if (hits >= 2) return
        enrageAt = t + when {
            P3Sim.bots && Random.nextInt(5) != 0 -> 6 + Random.nextInt(7)
            else -> 100 + Random.nextInt(100)
        }.coerceAtMost(240)
    }

    private fun killed() {
        val since = t - killAt
        if (since == 0) {
            health = 0f
            maxor.armour(false)
            Blocks.set(BlockPos(73, 221, 73), B.BEDROCK.defaultBlockState())
            pylons.forEach { it.remove() }
            Blocks.stop("p1strip")
            Sim.forEachPlayer { if (carrying > 0) { carrying = 0; giveBackMenu() } }
        }
        if (since == STORM_AFTER - 3) Blocks.play("p1end", skip = -24)
        if (since == 59) maxor.dieAnim()
        drops(since)
        if (belowAt < 0 && inStormArena()) belowAt = since
        if (since >= STORM_AFTER && belowAt >= 0 && since >= belowAt + 2) {
            handed = true
            val body = maxor
            Fight.later(80 - since, "maxor despawn") { body.remove() }
            Fight.begin(P2Storm())
        }
    }

    private fun inStormArena(): Boolean =
        Sim.players().any { it.y < 196.0 } || Party.bots().any { it.entity != null && it.pos.y < 196.0 }

    private fun drops(since: Int) {
        if (!P3Sim.bots) return
        val order = listOfNotNull(healer, archer, lure) + carriers.map { it.bot }
        val spots = listOf(STORM_HEALER, STORM_ARCHER, Vec3(73.5, 169.0, 60.5), Vec3(73.5, 169.0, 45.5), Vec3(73.5, 169.0, 50.5))
        order.forEachIndexed { i, b -> if (b.entity != null && since >= DROPS.getOrElse(i) { 35 + 5 * i }) walk(b, spots.getOrElse(i) { spots.last() }, 1.5) }
    }

    fun status() = when {
        killAt >= 0 -> "Maxor dead"
        hits > 0 -> "Hits $hits/2" + if (stunned) " (stunned)" else ""
        else -> "Crystals ${pylons.count { it.placed }}/2" + if (carrying > 0) " (carrying)" else ""
    }

    private companion object {
        const val OPEN = 80
        const val BEACON = OPEN + 40
        const val MOVE = 84
        const val STUN_LINE = 6
        const val STORM_AFTER = 62
        val DROPS = listOf(7, 17, 26, 35)
        val TOPS = listOf(Vec3(64.5, 238.375, 50.5), Vec3(82.5, 238.375, 50.5))
        val PYLONS = listOf(Vec3(52.5, 224.375, 41.5), Vec3(94.5, 224.375, 41.5))
        val BEAM = Vec3(73.5, 226.0, 73.5)
        val TOP_STANDS = listOf(Vec3(63.5, 238.0, 49.5), Vec3(83.5, 238.0, 49.5))
        val PYLON_STANDS = listOf(Vec3(51.8, 224.0, 40.6), Vec3(95.9, 224.0, 40.4))
        val LURE = Vec3(73.6, 225.0, 77.2)
        val STORM_HEALER = Vec3(96.5, 165.0, 41.5)
        val STORM_ARCHER = Vec3(36.5, 170.0, 90.5)
        val COLUMN = (221..224).map { BlockPos(73, it, 73) }.toSet()
        val FLOORS = listOf(
            (56..64).map { BlockPos(it, 221, 41) } + (42..51).map { BlockPos(64, 221, it) },
            (45..48).map { BlockPos(94, 221, it) } + (93 downTo 82).map { BlockPos(it, 221, 48) } + (49..51).map { BlockPos(82, 221, it) },
        )
        val CEILINGS = listOf((68..72).map { BlockPos(it, 236, 64) }, (78 downTo 74).map { BlockPos(it, 236, 64) })
        val CENTRE = listOf(BlockPos(73, 236, 64)) + (65..69).map { BlockPos(73, 236, it) }
    }
}
