package com.engineerclient.p3sim

import com.engineerclient.rotation.P3Sections
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.monster.Giant
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

class GoldorPhase(val from: Int, val arrived: Boolean = false) : Fight.Phase("P3") {
    override val restart get() = when (from) { 2 -> Fight.Start.S2; 3 -> Fight.Start.S3; 4 -> Fight.Start.S4; 5 -> Fight.Start.CORE; else -> Fight.Start.P3 }

    val stations = Station.all()
    val practice = Practice.section == from && from in 1..4
    var section = 1
        private set
    val n get() = t + nOffset
    private var nOffset = 0
    private val sectionStart = IntArray(6)
    private val sectionEnd = IntArray(6) { -1 }
    private val gateDown = BooleanArray(5)
    private val gateAt = IntArray(5) { -1 }
    private val doorOpen = BooleanArray(5)
    private var autoGateAt = IntArray(5) { -1 }
    private var coreAt = -1
    private var everyoneInAt = -1
    private var deadAt = -1
    private var frenzyHits by perPlayer { 0 }
    private var arrivedAt = -1
    private var necronAt = -1
    private var p3endAt = -1
    private var handOff = false
    private var deaths = 0
    private var dtEarly = false
    private val lines = ArrayDeque<String>()
    private var lastLine = -1000
    private var forgiveAt = -1
    private val tauntAt = HashMap<Int, String>()
    private var lastTaunt: String? = null

    private val goldorReached = BooleanArray(6)
    private fun goldorSeg() = GoldorPhase.Goldor.segment(goldor.s) + 1

    val devices = Devices(this)
    private val arenaReplay = ArenaFixes.replay()
    val goldor = Goldor()

    fun station(section: Int, label: String) = stations.first { it.section == section && it.label == label }
    fun count(section: Int) = stations.count { it.section == section && it.done }

    private var holding = false
    val isHolding get() = holding
    private var holdFor = 0
    private var held = false

    override fun start() {
        holdFor = if (practice) Practice.holdTicks else 0
        if (holdFor > 0) {
            holding = true; held = true
            Sim.player?.let { player ->
                val spot = Practice.startSpot()
                Sim.tp(player, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
                SimItems.giveHotbar(player, p3 = true)
                Practice.holdStartItem(player)
            }
            Practice.hold(holdFor)
            return
        }
        begin()
    }

    private fun begin() {
        stations.forEach { it.spawnStands() }
        devices.start()
        Stats.reset(from, practice)
        val startN = when (from) { 2 -> 252; 3 -> 433; 4 -> 629; 5 -> 797; else -> 0 }
        nOffset = startN - t
        arenaReplay?.begin(startN)
        for (s in 1 until from.coerceAtMost(5)) {
            stations.filter { it.section == s }.forEach { doneAlready(it) }
            gateDown[s] = true; doorOpen[s] = true
            if (s <= 3) {
                if (!practice) Blocks.finish("gate${s}${s + 1}")
                Blocks.finish("door$s", exclude = if (practice) gateBlocks(s) else emptySet())
            }
            sectionEnd[s] = startN
        }
        if (from >= 2) Blocks.finish("p3start")
        if (practice) {
            val jobs = Practice.practiceJobs()
            for (s in from..Practice.lastSection().coerceIn(from, 4)) {
                stations.filter { it.section == s && it.id !in jobs }.forEach { doneAlready(it) }
                if (s <= 3 && "gate $s" !in jobs) gateDown[s] = true
            }
        }
        section = from.coerceAtMost(5)
        sectionStart[section] = startN
        goldor.spawn(startN, bar = !(from == 1 && arrived))
        Party.startP3(this)
        GhostCapture.start(this)
        everyoneToSpawn()
        if (from == 1 && arrived) nOffset = -(LEAD_IN - 1)
        startLines(startN)
        if (practice) {
            Practice.begin(from, t)
            if (count(from) >= Station.total(from)) sectionDone(from)
        }
    }

    private fun everyoneToSpawn() {
        Sim.forEachPlayer { player ->
            if (!arrived) {
                if (!held || !Sim.isHost(player)) {
                    val spot = if (practice && Sim.isHost(player)) Practice.startSpot() else Spots.p3Start(from)
                    Sim.tp(player, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
                    SimItems.giveHotbar(player, p3 = true)
                    if (practice && Sim.isHost(player)) Practice.holdStartItem(player)
                }
            } else {
                val inv = player.inventory
                val hype = (0..8).firstOrNull { SimItems.idOf(inv.getItem(it)) == "HYPERION" }
                val boom = (9 until inv.containerSize).firstOrNull { SimItems.idOf(inv.getItem(it)) == "SUPERBOOM_TNT" }
                if (hype != null && boom != null) {
                    val h = inv.getItem(hype); inv.setItem(hype, inv.getItem(boom)); inv.setItem(boom, h)
                    player.inventoryMenu.broadcastChanges()
                }
            }
        }
    }

    private fun startLines(startN: Int) {
        if (from == 1) {
          val opening = {
            if (arrived) BossBar.show("§c§lGoldor", 1f)
            say("Who dares trespass into my domain?")
            lines += listOf("Little ants, plotting and scheming, thinking they are invincible...",
                "I won't let you break the factory core, I gave my life to my Master.", "No one matches me in close quarters.")
            val k = Random.nextInt(100).let { if (it < 2) 0 else if (it < 43) 1 else if (it < 93) 2 else 3 }
            repeat(k) { tauntAt[20 + Random.nextInt(220)] = taunt() }
            Blocks.play("p3start")
          }
          if (arrived) Fight.later(LEAD_IN, "who dares") { if (Fight.phase === this) opening() } else opening()
        } else if (from == 5) {
            PartyClient.send("termStart", 5)
            openCore()
        } else {
            PartyClient.send("termStart", from)
            maybeTaunt()
            if (!practice) Sim.note("Starting at §fS$from§7 (n = $startN, the median fast run's).")
        }
    }

    private fun doneAlready(st: Station) {
        st.done = true; st.doneBy = "-"; st.refreshStands()
        if (st.kind == Station.Kind.DEVICE) devices.shownDone(st.label)
        st.lever?.let { l -> Blocks.get(l)?.takeIf { b -> b.hasProperty(LeverBlock.POWERED) }?.let { b -> Blocks.set(l, b.setValue(LeverBlock.POWERED, true)) } }
    }

    override fun stop() {
        GhostCapture.stopped(this)
        Terminals.closeAll()
        devices.stop()
        val g = goldor
        if (necronAt >= 0) Fight.later(P4Necron.GONE + 1, "goldor body") { g.remove() } else g.remove()
    }

    override fun tick() {
        if (holding) {
            if (t < holdFor) { Practice.holdTick(t, holdFor); return }
            holding = false
            begin()
        }
        val n = n
        if (n == -1) goldor.tick(this)
        if (n < 0) return
        devices.tick()
        Sim.forEachPlayer { innerChamber(it) }
        tauntAt.remove(n)?.let { if (section <= 4) say(it) }
        dialogue()
        if (n % 20 == Fight.refreshPhase) stations.forEach { if (it.kind != Station.Kind.LEVER) it.refreshStands() }
        for (s in 1..3) if (autoGateAt[s] >= 0 && n >= autoGateAt[s] && !gateDown[s]) blowGate(s, null)
        if (section <= 4) {
            if (n % 60 == 58) dtEarly = Random.nextInt(98) < 26
            if ((n % 60 == 58 && dtEarly) || (n % 60 == 59 && !dtEarly)) deathTick()
        }
        goldor.tick(this)
        if (n > 0 && n % 20 == 0) BossBar.resend()
        if (section in 1..4 && goldorSeg() == section && !(section == 4 && goldor.firstLap)) goldorReached[section] = true
        arenaReplay?.tick(n)
        if (section == 5) coreTick()
        Party.tickP3(this)
        if (practice) Practice.tick(this)
        GhostCapture.tick(this)
        if (handOff) { handOff = false; handDialogueOver(); Fight.begin(P4Necron(fromP3 = true)) }
    }

    private var lastIn: Vec3? by perPlayer { null }
    private var innerAt by perPlayer { -100 }
    private fun innerChamber(p: ServerPlayer) {
        val was = lastIn; val now = p.position(); lastIn = now
        if (section < 4 || everyoneInAt >= 0 || goldor.flying || was == null || !CORE_BOX.contains(was) || CORE_BOX.contains(now)) return
        Sim.tp(p, 54.5, 115.0, 58.3)
        lastIn = Vec3(54.5, 115.0, 58.3)
        if (n - innerAt < 20) return
        innerAt = n
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f, Vec3(54.5, 115.0, 58.3), SoundSource.HOSTILE)
        Sim.chat("§cA mystical force prevents you from leaving the inner chamber!")
    }

    private fun say(line: String) {
        if (lines.isEmpty() && n >= lastLine + 62) speak(line) else lines += line
    }

    private fun gsay(line: String, stand: Boolean = true) = Sim.boss("Goldor", line, goldor.position, stand)

    private fun speak(line: String) {
        gsay(line)
        lastLine = n
        if (line == "....") forgiveAt = n + FORGIVE_AFTER
    }

    private fun dialogue() {
        if (lines.isNotEmpty() && n >= lastLine + 62) speak(lines.removeFirst())
        if (forgiveAt in 0..n) { forgiveAt = -1; gsay("Necron, forgive me."); Fight.later(12, "goldor rearm") { goldor.reArmour() } }
    }

    private fun handDialogueOver() {
        var at = lastLine
        for (line in lines) {
            at = maxOf(at + 62, n)
            val dt = at - n
            Fight.later(dt, "goldor line") { gsay(line) }
            if (line == "....") forgiveAt = at + FORGIVE_AFTER
        }
        lines.clear()
        if (forgiveAt >= 0) { val dt = forgiveAt - n; forgiveAt = -1; Fight.later(dt, "goldor forgive") { gsay("Necron, forgive me."); Fight.later(12, "goldor rearm") { goldor.reArmour() } } }
    }

    private fun taunt(): String = TAUNT_POOL.filter { it != lastTaunt }.random().also { lastTaunt = it }

    private fun maybeTaunt() {
        if (section in 2..4 && Random.nextDouble() < 0.13) tauntAt[n + 20 + Random.nextInt(180)] = taunt()
    }

    fun complete(st: Station, by: String, twice: Boolean = false) {
        if (st.done || holding) return
        val inProgress = st.section == section
        val early = st.kind == Station.Kind.DEVICE && st.section > section
        if (!inProgress && !early) return
        st.done = true
        st.doneBy = by
        st.doneAt = n
        val what = when (st.kind) { Station.Kind.TERMINAL -> "activated a terminal!"; Station.Kind.LEVER -> "activated a lever!"; Station.Kind.DEVICE -> "completed a device!" }
        val shown = section.coerceAtMost(4)
        val k = count(shown)
        val line = progressLine(by, what, k, Station.total(shown))
        if (st.kind == Station.Kind.TERMINAL) Terminals.closeFor(st, except = Sim.players().firstOrNull { it.gameProfile.name == by })
        if (Sim.isPlayerName(by)) Stats.done(by!!)
        if (by == Sim.hostName) GhostCapture.event("done", st.id)
        Sim.everyone {
            repeat(if (twice) 2 else 1) { announce(line) }
            if (inProgress && count(section) >= Station.total(section)) sectionDone(section)
        }
    }

    private fun announce(line: String) {
        Sim.chatStyled(line)
        Sim.title("", line.replace("§r", ""), 0, 40, 0)
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = SoundSource.BLOCKS)
    }

    private fun progressLine(by: String, what: String, k: Int, total: Int): String =
        "§b$by§r§a $what (§r§c$k§r§a/$total)"

    private val TAUNT_POOL = listOf(
        "Do you really think we won't repair everything? Your impact will be minuscule!", "Come closer!",
        "You are breaking precious materials, unforgivable.", "CLOSER!", "There is no stopping me down there!",
        "I am the death zone, you are smart to flee.", "You can't damage me, you can barely slow me down!",
        "Slowing me down only prolongs your pain!", "Closer to me!", "Stop touching those terminals!",
    )
    private val SECTION_LINES = listOf("The little ants have a brain it seems.", "I will replace that gate with a stronger one!", "YOUR END IS NEAR!!")

    private fun sectionDone(s: Int): Unit = Sim.everyone {
        sectionEnd[s] = n
        if (s == 4) { openCore(); return@everyone }
        if (gateDown[s]) openDoor(s)
        else {
            if (!practice) {
                Sim.chat("§aThe gate will open in 5 seconds!")
                Sim.title("", "§aThe gate will open in 5 seconds!", 0, 40, 0)
            }
            Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = SoundSource.BLOCKS)
            if (!practice) autoGateAt[s] = n + 100
            if (!practice) Fight.later(1, "door top") {
                if (Fight.phase !== this || doorOpen[s]) return@later
                Blocks.anim("door$s")?.frames?.forEach { f ->
                    if (f.dt != 0 || f.pos.y < 118) return@forEach
                    val b = Blocks.get(f.pos)?.block
                    if (b == net.minecraft.world.level.block.Blocks.IRON_BLOCK || b is StairBlock) Blocks.set(f.pos, f.state)
                }
            }
        }
    }

    private fun openDoor(s: Int): Unit = Sim.everyone {
        if (doorOpen[s]) return@everyone
        doorOpen[s] = true
        if (!practice || s < Practice.lastSection()) {
            Blocks.play("door$s", exclude = if (!practice || s > 3) emptySet() else gateBlocks(s), delay = 1)
            if (s == 1) Blocks.play("ss_s1done", delay = 1)
        }
        say(SECTION_LINES.random())
        section = s + 1
        sectionStart[section] = n
        if (section in 2..4 && !goldor.firstLap && goldorSeg() > section) goldorReached[section] = true
        maybeTaunt()
        Stats.section(s, sectionEnd[s].coerceAtLeast(gateAt[s]) - sectionStart[s])
        goldor.sectionEnded(s, n)
        if (count(section) >= Station.total(section)) sectionDone(section)
    }

    private fun gateBlocks(s: Int): Set<BlockPos> =
        (Blocks.anim("gate$s${s + 1}")?.frames?.map { it.pos }.orEmpty() +
            BlockPos.betweenClosedStream(GATE_BOXES[s].inflate(0.5)).map { it.immutable() }.toList()).toSet()

    fun blowGate(s: Int, by: String?): Boolean = Sim.everyone {
        if (s !in 1..3 || gateDown[s] || holding) return@everyone false
        if (by != null && section < s) return@everyone false
        gateDown[s] = true
        gateAt[s] = n
        if (by == Sim.hostName) GhostCapture.event("gate", k = s)
        Sim.chat("§aThe gate has been destroyed!")
        Sim.title("", "§aThe gate has been destroyed!", 0, 40, 0)
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f)
        SimItems.gatePuffs(GATE_CENTRES[s], GATE_BOXES[s])
        if (!practice) Blocks.play("gate$s${s + 1}", delay = 1)
        if (sectionEnd[s] >= 0) openDoor(s)
        true
    }

    fun gateNear(p: Vec3, r: Double): Int {
        for (s in 1..3) if (GATE_BOXES[s].inflate(r).contains(p)) return s
        return 0
    }

    private fun deathTick() = Sim.forEachPlayer { deathTick(it) }

    private fun deathTick(p: ServerPlayer) {
        if (p.isSpectator || p.isCreative || Masks.ghost) return
        val seen = Fight.seenPos(p)
        if (inSafeSpot(seen)) return
        val at = dtZone(seen)
        if (at < 0) return
        if (at < section) return
        if (at == section && !(goldorReached[section] && goldorSeg() != section)) return
        if (P3Sim.deathTicks == 0) return
        deaths++
        Stats.deathTick(p.gameProfile.name)
        val line = {
            Sim.chat("§4[BOSS] Goldor§r§c: What do you think you are doing there!")
            Sim.sound(SoundEvents.WITHER_AMBIENT, 1f, 1f, null, SoundSource.HOSTILE)
        }
        when (P3Sim.deathTicks) {
            1 -> { line(); Sim.title("", "§cDeath tick §7(S$at during S$section)", 0, 25, 5) }
            else -> Masks.hit(p, null, line)
        }
    }

    private fun inSafeSpot(v: Vec3) = CORE_BOX.contains(v) || STRIP.contains(v)

    private fun openCore(): Unit = Sim.everyone {
        section = 5
        coreAt = n
        Sim.chat("§aThe Core entrance is opening!")
        Sim.title("", "§aThe Core entrance is opening!", 0, 40, 0)
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = SoundSource.BLOCKS)
        if (from != 5) Stats.section(4, n - sectionStart[4])
        if (P3Sim.termsOnly && !practice && from != 5) Fight.later(1, "stop after terms") { Sim.note("Terms done: stopped §7(Stop After Terms)."); Fight.end() }
        if (!practice) Blocks.play("core", delay = 1)
        Stats.p3(n)
    }

    private fun everyoneIn(): Boolean {
        val ps = Sim.players()
        if (ps.isEmpty() || ps.any { !Sim.asPlayer(it) { Masks.ghost } && !CORE_BOX.contains(Fight.seenPos(it)) }) return false
        return Party.allIn(CORE_BOX)
    }

    private fun coreTick() {
        if (everyoneInAt < 0 && n >= coreAt + 4 && everyoneIn()) {
            everyoneInAt = n
            goldor.fly(n)
        }
        if (deadAt < 0 && goldor.flying) {
            if (goldor.arrived && arrivedAt < 0) {
                arrivedAt = n
                say("You have done it, you destroyed the factory...")
                say("But you have nowhere to hide anymore!")
                say("YOU ARE FACE TO FACE WITH GOLDOR!")
            }
            if (n >= goldor.killAt) die()
        }
        goldor.barTick(n)
        if (deadAt < 0 && n % 10 == 7) Sim.forEachPlayer { p ->
            val d = p.position().distanceTo(goldor.position)
            if (Masks.ghost || p.isSpectator || p.isCreative || d !in 2.0..14.0) frenzyHits = 0
            else {
                val hit = ++frenzyHits
                if (hit >= 3) { Masks.hit(p, "Goldor"); frenzyHits = 0 }
                val r = Random
                var dmg = when (hit) { 1 -> 2_000.0 + r.nextDouble(11_000.0); 2 -> 44_000.0 + r.nextDouble(4_000.0); else -> 52_000.0 + r.nextDouble(8_000.0) }
                dmg = if (r.nextInt(100) < 40) Math.floor(dmg) else Math.round(dmg * 10) / 10.0
                Sim.chat("§cGoldor's§r§7 Frenzy hit you for §r§c${StormFx.dmg(dmg)}§r§7 damage.")
                Sim.sound(SoundEvents.GENERIC_EXPLODE, 0.5f, 0.49f)
                Sim.sound(SoundEvents.PLAYER_HURT, 1f, 1f)
            }
        }
        if (deadAt >= 0 && p3endAt < 0 && n >= deadAt + NECRON_AFTER - 2 && !P3Sim.p3Only) { p3endAt = n; Blocks.play("p3end") }
        if (deadAt >= 0 && necronAt < 0 && n >= deadAt + NECRON_AFTER) {
            necronAt = n
            if (P3Sim.p3Only) { Fight.later(1, "stop after goldor") { Sim.note("Goldor done: stopped §7(Stop After Goldor)."); Fight.end() }; return }
            handOff = true
        }
    }

    private fun die() {
        deadAt = n
        say("....")
        goldor.die()
    }

    companion object {
        const val LEAD_IN = 3

        const val NECRON_AFTER = 62
        const val FORGIVE_AFTER = 52

        val DT_ZONES: List<Pair<Int, AABB>> = listOf(
            1 to AABB(90.0, 106.0, 26.0, 114.0, 146.0, 121.0),
            2 to AABB(20.0, 106.0, 122.0, 114.0, 146.0, 146.0),
            3 to AABB(-6.0, 106.0, 51.0, 18.0, 146.0, 146.0),
            4 to AABB(-6.0, 106.0, 26.0, 90.0, 146.0, 50.0),
        )

        fun dtZone(v: Vec3): Int = DT_ZONES.firstOrNull { it.second.contains(v) }?.first ?: -1

        val CORE_BOX = AABB(39.0, 0.0, 54.0, 71.0, 155.5, 118.0)
        val STRIP = AABB(45.0, 100.0, 50.0, 65.0, 160.0, 54.5)
        val GATE_BOXES = arrayOf(AABB.ofSize(Vec3.ZERO, 0.0, 0.0, 0.0), AABB(93.0, 113.0, 121.0, 108.0, 138.0, 125.0), AABB(16.0, 113.0, 125.0, 20.0, 138.0, 140.0), AABB(1.0, 113.0, 48.0, 16.0, 138.0, 52.0))
        val GATE_CENTRES = arrayOf(Vec3.ZERO, Vec3(100.0, 118.0, 122.5), Vec3(17.5, 118.0, 132.0), Vec3(8.0, 118.0, 49.5))
    }

    class Goldor {
        private var boss: BossWither? = null
        private val giants = ArrayList<Giant>()
        var s = START_S
        var firstLap = true
        private var speed = WALK
        private var sprintTo = -1.0
        private var sprintFrom = -1
        private var sprintSec = 0
        var flying = false
            private set
        var arrived = false
            private set
        private var dead = false
        private var flyAt = 0
        private var nextHurt = 0
        private var pos = Vec3.ZERO
        private val armouredRun = Random.nextInt(36) < 23
        private val armourAt = 1 + Random.nextInt(7)
        private var armourOffAt = Int.MAX_VALUE
        private var spawnedN = 0
        private val giantsAt = 11 + Random.nextInt(9)
        private var giantAngle = 0.0
        var killAt = Int.MAX_VALUE
            private set
        val position: Vec3 get() = pos

        fun spawn(n: Int, bar: Boolean = true) {
            firstLap = START_S + walkDist(n) < LOOP
            s = (START_S + walkDist(n)) % LOOP
            pos = trackPos(s)
            spawnedN = n
            boss = BossWither("Goldor", pos, inv = 0, armoured = false)
            if (bar) BossBar.show("§c§lGoldor", 1f)
        }

        private fun spawnGiants() {
            for (i in 0 until 4) {
                val e = SimGiant(Sim.level)
                e.setNoAi(true); e.isSilent = true; e.isPermanentlyInvulnerable = true; e.setNoGravity(true); e.isInvisible = true
                e.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false))
                e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.GOLDEN_SWORD))
                val g = giantPos(i)
                e.snapTo(g.x, g.y, g.z, 0f, 0f)
                giants += Sim.spawn(e)
            }
        }

        private fun giantPos(i: Int): Vec3 {
            val a = Math.toRadians(giantAngle + 90.0 * i)
            return Vec3(pos.x + 2.4 + 3.0 * Math.cos(a), pos.y - 8.0, pos.z - 3.5 + 3.0 * Math.sin(a))
        }

        private fun orbitGiants() {
            giantAngle += 6.0
            giants.forEachIndexed { i, e -> val g = giantPos(i); e.snapTo(g.x, g.y, g.z, (giantAngle + 90.0 * i).toFloat(), 0f) }
        }

        fun reArmour() { if (armouredRun) boss?.armour(true) }

        fun remove() { boss?.remove(); boss = null; giants.forEach { it.discard() }; giants.clear() }

        fun sectionEnded(sec: Int, doorN: Int) {
            if (flying || sec !in 1..3) return
            val inIt = if (sec == 1) s >= S1_ENTRY || s < BOUNDS[1] else segment(s) == sec - 1
            if (inIt && sprintFrom < 0) { sprintFrom = doorN + SPRINT_LAG_MIN + Random.nextInt(SPRINT_LAG_SPREAD); sprintSec = sec }
        }

        fun fly(n: Int) {
            if (flying) return
            flying = true
            flyAt = n
            killAt = n + P3Sim.goldorKill.toInt()
            armourOffAt = killAt - 4 - Random.nextInt(11)
        }

        private fun aura() {
            val k = if (Random.nextDouble() < 0.34) 2 else 1
            repeat(k) {
                val a = Random.nextDouble() * Math.PI * 2
                val r = 3.0 + Random.nextDouble() * 3.0
                Sim.level.sendParticles(ParticleTypes.ANGRY_VILLAGER, pos.x + Math.cos(a) * r, pos.y + 0.5, pos.z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 1.0)
            }
        }

        fun die() { dead = true }

        fun barTick(n: Int) {
            if (!flying || killAt == Int.MAX_VALUE) return
            val dropAt = killAt - 3
            val zeroAt = killAt + 9
            if (n < dropAt) return
            if (n >= zeroAt) { if (!barZeroed) { barZeroed = true; BossBar.progress(0f) }; return }
            if ((n - dropAt) % 4 == 0) BossBar.progress(0.35f * (zeroAt - n) / (zeroAt - dropAt))
        }
        private var barZeroed = false

        fun tick(phase: GoldorPhase) {
            val boss = boss ?: return
            val n = phase.n
            if (dead) return
            aura()
            if (armouredRun && n == spawnedN + armourAt) boss.armour(true)
            if (n >= armourOffAt) { armourOffAt = Int.MAX_VALUE; boss.armour(false) }
            if (n >= giantsAt && giants.isEmpty()) spawnGiants()
            if (flying) {
                if (n >= nextHurt) {
                    nextHurt = n + 2 + Random.nextInt(11)
                    Sim.players().forEach { it.connection.send(ClientboundHurtAnimationPacket(boss.e)) }
                }
                if (!arrived) {
                    val to = CORE_POINT.subtract(pos)
                    val d = to.horizontalDistance()
                    val step = minOf(FLY, d)
                    pos = Vec3(pos.x + to.x / d.coerceAtLeast(1e-6) * step, maxOf(CORE_POINT.y, pos.y - 0.03), pos.z + to.z / d.coerceAtLeast(1e-6) * step)
                    if (d <= FLY) arrived = true
                } else {
                    val v = if (pos.z < CORE_IN_Z) 0.4 else 0.07
                    pos = Vec3(pos.x + (CORE_IN_X - pos.x).coerceIn(-0.1, 0.1), maxOf(116.75, pos.y - 0.006), pos.z + v)
                }
            } else {
                if (sprintFrom in 0..n && sprintTo < 0) {
                    val sec = sprintSec
                    val inIt = if (sec == 1) s >= S1_ENTRY || s < BOUNDS[1] else segment(s) == sec - 1
                    if (inIt) { sprintTo = SPRINT_TO[sec - 1]; speed = SPRINT }
                    sprintFrom = -1
                }
                if (sprintTo >= 0) {
                    val left = ((sprintTo - s) % LOOP + LOOP) % LOOP
                    if (left <= SPRINT) { s = sprintTo; sprintTo = -1.0; speed = WALK } else s += SPRINT
                } else s += walkStep(n)
                if (s >= LOOP) firstLap = false
                s %= LOOP
                pos = trackPos(s)
            }
            val yaw = when {
                arrived -> 0f
                flying -> Math.toDegrees(Math.atan2(-(CORE_POINT.x - pos.x), CORE_POINT.z - pos.z)).toFloat()
                else -> yaw()
            }
            boss.moveTo(pos, pos.add(-Math.sin(Math.toRadians(yaw.toDouble())), 0.0, Math.cos(Math.toRadians(yaw.toDouble()))))
            if (giants.isNotEmpty()) orbitGiants()
        }

        private fun yaw(): Float {
            return when (segment(s)) { 0 -> 0f; 1 -> 90f; 2 -> 180f; else -> -90f }
        }

        companion object {
            const val WALK = 0.06
            const val SLOW_WALK = 0.048
            const val SLOW_TICKS = 194
            fun walkStep(age: Int) = if (age < SLOW_TICKS) SLOW_WALK else WALK
            fun walkDist(age: Int) = if (age <= SLOW_TICKS) SLOW_WALK * age else SLOW_WALK * SLOW_TICKS + WALK * (age - SLOW_TICKS)
            const val SPRINT_LAG_MIN = 48
            const val SPRINT_LAG_SPREAD = 12
            const val SPRINT = 0.60
            const val FLY = 0.80
            private val CORNERS = listOf(Vec3(99.55, 119.0, 40.0), Vec3(99.55, 119.0, 131.7), Vec3(8.4, 118.5, 131.7), Vec3(8.4, 118.0, 40.0))
            private val Y_FROM = doubleArrayOf(119.0, 118.5, 118.0, 118.1)
            private val Y_TO = doubleArrayOf(119.0, 118.5, 118.0, 119.0)
            val BOUNDS = doubleArrayOf(0.0, 90.7, 182.1, 272.8, 364.2)
            const val LOOP = 364.2
            const val START_S = 344.7
            const val S1_ENTRY = 361.5
            val SPRINT_TO = doubleArrayOf(91.2, 182.3, 272.8)
            val CORE_POINT = Vec3(53.6, 117.0, 40.0)
            const val CORE_IN_X = 53.6
            const val CORE_IN_Z = 56.0

            fun segment(s: Double): Int { var i = 0; while (i < 3 && s >= BOUNDS[i + 1]) i++; return i }

            fun trackPos(s: Double): Vec3 {
                val i = segment(s)
                val f = ((s - BOUNDS[i]) / (BOUNDS[i + 1] - BOUNDS[i])).coerceIn(0.0, 1.0)
                val a = CORNERS[i]; val b = CORNERS[(i + 1) % 4]
                return Vec3(a.x + (b.x - a.x) * f, Y_FROM[i] + (Y_TO[i] - Y_FROM[i]) * f, a.z + (b.z - a.z) * f)
            }
        }
    }

    class SimGiant(level: Level) : Giant(EntityTypes.GIANT, level) {
        override fun checkDespawn() {}
    }

    fun leverAt(pos: BlockPos): Station? = stations.firstOrNull { it.lever == pos }

    fun pullLever(st: Station, by: String, left: Boolean = false) {
        val lever = st.lever ?: return
        if (holding) return
        if (st.done) { if (by == Sim.me) refuseLever(lever, left); return }
        if (st.section != section) { if (by == Sim.me && !left) SimItems.vanillaLeverToggle(lever); return }
        val nowOn = !(Blocks.get(lever)?.takeIf { it.hasProperty(LeverBlock.POWERED) }?.getValue(LeverBlock.POWERED) ?: false)
        if (!left) Blocks.get(lever)?.takeIf { it.hasProperty(LeverBlock.POWERED) }?.let { Blocks.set(lever, it.setValue(LeverBlock.POWERED, nowOn)) }
        complete(st, by)
        if (!left) Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (nowOn) 0.5873016f else 0.4920635f, Vec3.atCenterOf(lever), SoundSource.BLOCKS)
        if (left && st.done && by == Sim.me) refuseLever(lever, true)
        if (st.done) Fight.later(1, "lever stand") { if (Fight.phase === this) st.refreshStands() }
    }

    private fun refuseLever(lever: BlockPos, left: Boolean) {
        Sim.chat("§cSomeone has already activated this lever!")
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f, source = SoundSource.HOSTILE)
        if (left) sendBlock(lever)
    }

    private fun sendBlock(pos: BlockPos) {
        Sim.player?.connection?.send(ClientboundBlockUpdatePacket(Sim.level, pos))
    }

    fun useTerminal(st: Station) {
        val p = Sim.player ?: return
        if (holding) { Fight.later(1, "term refusal") { Sim.chatStyled("§cThis Terminal doesn't seem to be responsive at the moment.") }; return }
        if (st.done) { Fight.later(1, "term refusal") { Sim.chatStyled("§cThis Terminal has already been completed!") }; return }
        if (st.section != section) { Fight.later(1, "term refusal") { Sim.chatStyled("§cThis Terminal doesn't seem to be responsive at the moment.") }; return }
        Fight.later(1, "term open") { if (!st.done && st.section == section && !holding) Terminals.open(p, st) }
    }

    fun status(): String = when {
        necronAt >= 0 -> "P3 done"
        deadAt >= 0 -> "Goldor dead"
        section == 5 -> "Core open" + if (everyoneInAt >= 0) ", Goldor flying" else ""
        else -> "S$section ${count(section)}/${Station.total(section)}" + (if (!gateDown[section.coerceAtMost(3)] && section <= 3) ", gate up" else "")
    }

    val deathsTaken get() = deaths
    fun gateIsDown(s: Int) = gateDown.getOrElse(s) { true }

    fun sectionStartN(s: Int) = sectionStart[s]
    fun doorIsOpen(s: Int) = doorOpen.getOrElse(s) { true }
}
