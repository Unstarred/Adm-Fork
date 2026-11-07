package com.engineerclient.splits

import com.engineerclient.DeadPlayers
import com.engineerclient.EngineerClient
import com.engineerclient.misc.Witherborn
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Practice
import com.engineerclient.p3sim.StormEnd
import com.engineerclient.practice.Recore
import com.engineerclient.storm.StormCrush
import com.engineerclient.storm.StormCrushView
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.texture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.Base64

object DungeonSplits : Module(
    name = "Sub Splits",
    category = Category.custom("ADM", 860, 10),
    description = "The blood rush sub splits, room by room; Storm's: his steps with the lightning, every pad read and how late each crush came (and his crush hitbox with Show Crush Info); and Goldor's: each leap into the core after S4 and Goldor starting to move. The splits themselves are Odin's Splits (Look: ADM); every other section's sub splits and the Scorecard are in Devgineer Client.",
    key = null,
) {

    private val CONTROL_CODES = Regex("§.")
    private val tracker = SplitTracker()
    private val subs = SubSplitTracker()
    private val blood = BloodRunDetail()

    private var serverTicks = 0
    private fun now() = Stamp(System.currentTimeMillis(), serverTicks)

    private var bestsF7 by StringSetting("Sub Split Bests F7", "", 2048, desc = "", placeholder = "").hide()
    private var bestsM7 by StringSetting("Sub Split Bests M7", "", 2048, desc = "", placeholder = "").hide()

    private var recentF7 by StringSetting("Recent Splits F7", "", 8192, desc = "", placeholder = "").hide()
    private var recentM7 by StringSetting("Recent Splits M7", "", 8192, desc = "", placeholder = "").hide()
    private const val RECENT = 20
    private const val RECENT_MIN = 5

    private val moments = BossMoments()

    private val bloodHud = registerSetting(
        HUD("BR Sub Split", "What happened inside Blood Rush, room by room.", true, 780, 229, 0.9f) { example ->
            if (example) return@HUD draw(this, listOf(
                "§70.52s §8| \t§c1.73s \t§5Hallway: \t§62.31s",
                "\t§411.73s \t§dDino: \t§622.31s",
            ))
            draw(this, bloodLines())
        }
    )

    private fun hudOn() = bloodHud.value.enabled

    private val bloodLevel = registerSetting(SelectorSetting("Blood Rush Detail", BloodRunDetail.Level.COMPACT, desc = "How much the Blood Rush sub-split HUD shows. Debug adds every extra moment known about it."))
        .withDependency { hudOn() }
    private val totalRow = registerSetting(
        BooleanSetting("Blood Rush Total Row", true, desc = "The averages row at the bottom of the compact blood rush splits.")
    ).withDependency { hudOn() && bloodLevel.value == BloodRunDetail.Level.COMPACT }
    private val bloodHideInBoss = registerSetting(
        BooleanSetting("Blood Rush Hide In Boss", false, desc = "Hides the blood rush sub splits once you are in the boss.")
    ).withDependency { hudOn() }

    private val storm = StormDetail()
    private val crushView = StormCrushView()
    enum class StormLevel { COMPACT, DETAILED }
    private val stormHud = registerSetting(
        HUD("Storm Sub Split", "Storm's phase: each step (Opening, Crush, Pin, Flight, Crush, Kill, Animation) graded, and under each what happened in it - Giga Lightning, his lightning line, leaving his spot, every pad read, each crush with the pillar and whether it came on the first check it could, breaking free, reaching Yellow. Shown until the run ends.", true, 330, 10, 0.9f) { example ->
            if (example) return@HUD draw(this, listOf(
                "§bStorm §f41.30s",
                "§aOpening §732.40s",
                " §8· §eGiga Lightning §726.00s", " §8· §eLightning line §727.45s", " §8· §5Purple pad §7read 1 §729.95s", " §8· §bLeft his spot §8(4.95s after the line) §732.40s",
                "§6Crush §a0.55s", " §8· §6Crushed by §5Purple §8t660 §a(first check) §733.00s",
                "§cPin §a0.10s", "§bFlight §e4.50s", " §8· §eReached Yellow §737.60s",
                "§6Crush §a0.95s", "§cKill §a0.15s", "§aAnimation §73.10s",
            ))
            draw(this, stormLines())
        }
    )
    private fun stormOn() = stormHud.value.enabled
    private val stormLevel = registerSetting(SelectorSetting("Storm Detail", StormLevel.DETAILED, desc = "Compact: the steps on one line. Detailed: a line per step, with what happened in it under each."))
        .withDependency { stormOn() }
    private val showCrushInfo = registerSetting(
        BooleanSetting("Show Crush Info", false, desc = "Storm's crush hitbox: from his lightning on, at every 20-tick crush check with him within 5 blocks of a pillar, where the check had him - green if he was inside that pillar's crush zone, red if not, and by how much - and each check in the Storm sub split.")
    )
    private val hitboxSeconds = registerSetting(NumberSetting("Hitbox Seconds", 5, 1..10, 1, desc = "How long each check's hitbox stays up.", unit = "s"))
        .withDependency { showCrushInfo.enabled }
    private val hitboxMargins = registerSetting(BooleanSetting("Hitbox Margins", true, desc = "Over each hitbox, how far in or out of the zone sideways, and how far his head was above or below the pillar's bottom."))
        .withDependency { showCrushInfo.enabled }
    private val pillarOutline = registerSetting(BooleanSetting("Pillar Outline", true, desc = "The square the hitbox has to fit inside, at the height of the pillar's bottom, with each hitbox."))
        .withDependency { showCrushInfo.enabled }
    private val insideColor = registerSetting(ColorSetting("Inside Color", Color(85, 255, 85, 0.25f), true, desc = "Hitbox colour when the check had him inside the zone."))
        .withDependency { showCrushInfo.enabled }
    private val outsideColor = registerSetting(ColorSetting("Outside Color", Color(255, 85, 85, 0.25f), true, desc = "Hitbox colour when the check had him outside it."))
        .withDependency { showCrushInfo.enabled }

    private val core = GoldorCore()
    private val goldorHud = registerSetting(
        HUD("Goldor Sub Split", "Watched from S4's start: when each player got into the core, timed from the core opening (teal and negative if they were in before it opened, grey with ~ if they weren't in view just before), and when Goldor started moving. Shown until the run ends.", true, 750, 10, 1f) { example ->
            if (example) return@HUD draw(this, listOf("§eCore", "§7Teammate §3-2.40s", "§fYou §a0.05s", "§7Another §8~0.50s", "§7Last §61.35s", "§eGoldor moved §f1.40s §8(+0.05s after the last in)"))
            draw(this, core.lines(serverTicks, mc.player?.name?.string, core.counted(aliveNames())))
        }
    )

    private var coreGoldor: Triple<Int, Vec3, Int>? = null

    private val barriers = mutableListOf<Pair<Int, Int>>()
    private val cleared = mutableListOf<Pair<Int, Int>>()
    private val keysSeen = HashSet<Int>()
    private val crystalsSeen = HashSet<Int>()
    private val watchedMobs = HashSet<Int>()

    private fun resetRun() {
        bestsChecked.clear(); realRun = true
        synchronized(lagSamples) { lagShown = false; lagSamples.clear() }
        tracker.reset(); subs.reset(); blood.reset(); moments.reset()
        core.reset(); coreGoldor = null
        storm.reset(); crushView.reset()
        goldorAt = null; goldorMoved = false; necronId = null; necronDiedAt = null; watcherAt = null
        barriers.clear(); cleared.clear(); keysSeen.clear(); crystalsSeen.clear(); watchedMobs.clear(); necronTnt.clear()
    }

    fun simStart(label: String, termsDone: Int = 0) {
        resetRun()
        realRun = false
        val before = { l: String ->
            val ms = SimOdinSplits.odinName(l)?.let { SimOdinSplits.target(it) } ?: SplitPace.ref(l)?.ms ?: 0L
            SplitTracker.Clock(ms, (ms / 50).toInt())
        }
        val head = (1..termsDone).mapNotNull { SplitPace.subRef("terms.s$it") }
        val headClock = SplitTracker.Clock(head.sumOf { it.ms }, head.sumOf { it.ticks }.toInt())
        if (label == SplitTracker.TERMS && termsDone == 0) {
            val storm = SimOdinSplits.odinName(SplitTracker.STORM)
            if (storm != null) {
                val whole = SimOdinSplits.target(storm).takeIf { it > 0 } ?: SplitPace.ref(SplitTracker.STORM)?.ms ?: 41_300L
                SimOdinSplits.start(storm, (whole - StormEnd.LEAD * 50L).coerceAtLeast(0L))
            }
        } else SimOdinSplits.odinName(label)?.let { SimOdinSplits.start(it, headClock.ms) }
        if (termsDone > 0) {
            val at = now()
            tracker.startAt(label, before, at, headClock)
            if (head.size == termsDone) {
                val back = IntArray(termsDone + 1)
                for (s in termsDone downTo 1) back[s - 1] = back[s] + head[s - 1].ticks.toInt()
                subs.startTerms(termsDone + 1, back.map { at.minus(it) })
            }
        } else tracker.startAt(label, before)
    }

    fun simStop() {
        resetRun()
        SimOdinSplits.stop()
    }

    fun install() {
        ClientTickEvents.END_CLIENT_TICK.register {
            val want = enabled || Recore.let { it.enabled && it.pasteLast }
            val on = this in EventBus.activeSubscribers
            if (want && !on) EventBus.subscribe(this) else if (!want && on) EventBus.unsubscribe(this)
        }
    }

    private fun Stamp.minus(ticks: Int) = Stamp(realMs - ticks * 50L, tick - ticks)

    init {
        bloodHud.value.enabled = false; stormHud.value.enabled = false; goldorHud.value.enabled = false
        on<LevelEvent.Load> {
            resetRun()
            serverTicks = 0
        }

        on<TickEvent.Server> { countServerTick() }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) { onSystemChat() }

        on<BlockUpdateEvent> { onBlockUpdate() }

        on<TickEvent.End> { onTickEnd() }

        on<EntityEvent.Add> {
            val e = entity
            when {
                e is EndCrystal && open(SplitTracker.MAXOR) && crystalsSeen.add(e.id) -> if (e.y >= 231) subs.onTopCrystal(now())
                e is PrimedTnt && open(SplitTracker.NECRON) -> onNecronTnt(now())
                e is Player && open(SplitTracker.BLOOD) && e.name.string !in teamNames() -> {
                    if (watchedMobs.add(e.id)) subs.onBloodMobSpawn(now())
                }
            }
        }
        on<RenderExtractEvent> {
            if (enabled && showCrushInfo.enabled) with(crushView) { render(hitboxSeconds.value, hitboxMargins.enabled, pillarOutline.enabled, insideColor.value, outsideColor.value) }
        }

        on<EntityEvent.Remove> {
            if (entity is WitherBoss && open(SplitTracker.NECRON) && entity.id == necronId && distanceTo(entity) <= 48) {
                necronDead(now().minus(NECRON_GONE))
            }
            if (Witherborn.isBoss(entity) && open(SplitTracker.MAXOR) && distanceTo(entity) <= 48) subs.onMaxorDead(now())
            watchedMobs.remove(entity.id)
        }
    }

    private fun TickEvent.Server.countServerTick() {
        val arrived = System.currentTimeMillis()
        EngineerClient.mc.execute {
            serverTicks++
            val now = serverTicks
            EngineerClient.safely("sub splits server tick") { subs.onServerTick(); sampleLag(Stamp(arrived, now)) }
            EngineerClient.safely("storm detail") {
                storm.onServerTick(now)
                val t = storm.phase(now)
                if (enabled && showCrushInfo.enabled && storm.checking && t != null && StormCrush.isCheck(t))
                    crushView.check(now)?.let { (v, armed) -> storm.onCheck(now, v, armed) }
            }
        }
    }

    private fun ClientboundSystemChatPacket.onSystemChat() {
        if (overlay) return
        val text = content.string.replace(CONTROL_CODES, "")
        val at = now()
        EngineerClient.mc.execute {
            EngineerClient.safely("splits chat") {
                if (!DungeonUtils.inDungeons) return@safely
                if (P3Sim.inSim && Practice.active) return@safely
                tracker.onChat(text, at)
                moments.onChat(text, at)
                subs.onChat(text, at)
                blood.onChat(text, at)
                storm.onChat(text, at.tick, DungeonUtils.floor?.name == "M7")
                if (text == CORE_OPEN) core.onCoreOpen(at.tick)
                else if (text.startsWith(NECRON_LINE)) core.onNecron()
            }
        }
    }

    private fun BlockUpdateEvent.onBlockUpdate() {
        if (blood.active) {
            if (updated.block == Blocks.BARRIER && old.block != Blocks.BARRIER) barriers += pos.x to pos.z
            else if (old.block == Blocks.BARRIER && updated.isAir) cleared += pos.x to pos.z
        }
        if (open(SplitTracker.TERMS) && old.block == Blocks.BARRIER && updated.isAir) {
            val section = SECTION_DOORS.indexOfFirst { (x, z) -> pos.x in x && pos.z in z }
            if (section >= 0) sectionDoor[section]++
        }
        if (storm.active && updated.block == Blocks.MOVING_PISTON && pos.y in StormCrush.FLOOR..StormCrush.TOP) {
            StormCrush.PILLARS.firstOrNull { pos.x == it.columnX && pos.z == it.columnZ }?.let {
                storm.onPillarStep(it, serverTicks); crushView.onStep(it, serverTicks)
            }
        }
        if (open(SplitTracker.MAXOR) && pos.x == 73 && pos.y == 221 && pos.z == 73 && updated.block == Blocks.BEDROCK) {
            subs.onMaxorKilled(now())
        }
    }

    private fun TickEvent.End.onTickEnd() {
        EngineerClient.safely("sim odin splits") { SimOdinSplits.tick() }
        if (!DungeonUtils.inDungeons) return
        EngineerClient.safely("split bests") { recordSplitBests() }
        if (barriers.size >= DoorBlocks.DOOR_BLOCKS) door(barriers) { at, a, b -> blood.onDoorStart(at, a, b) }
        if (cleared.size >= DoorBlocks.DOOR_BLOCKS) door(cleared) { at, a, b -> blood.onDoorDown(at, a, b) }
        barriers.clear(); cleared.clear()
        for (i in sectionDoor.indices) {
            if (sectionDoor[i] >= SECTION_DOOR_BLOCKS) {
                subs.onSectionDoor(now(), i + 1)
            }
            sectionDoor[i] = 0
        }

        if (open(SplitTracker.STORM)) bossWither(level, "Storm")?.positionCodec?.base?.let {
            subs.onStormPosition(now(), it.x, it.y, it.z)
            storm.onPosition(serverTicks, it.x, it.y, it.z)
        }
        if (open(SplitTracker.NECRON)) bossWither(level, "Necron")?.let { necron ->
            necronId = necron.id
            subs.onNecronPosition(now(), necron.positionCodec.base.distanceTo(NECRON_MID))
        }

        if (blood.active) for (e in level.entitiesForRendering()) {
            if (e !is ArmorStand || e.id in keysSeen) continue
            val name = e.customName?.string ?: continue
            if (KEY.containsMatchIn(name)) {
                keysSeen += e.id
                blood.onKeySpawned(now(), distanceTo(e))
            }
        }

        val inCore = if (subs.watchingCore || open(SplitTracker.GOLDOR)) everyoneInCore(level) else false
        if (inCore == true) {
            subs.onEveryoneInCore(now())
            moments.onEveryoneInCore(now())
        }
        if (open(SplitTracker.GOLDOR) && moments.waitingForCore) watchGoldor(level, trusted = inCore == null) else goldorAt = null

        subs.s4Start?.let { core.onS4(it.tick) }
        if (core.active) watchCore(level)

        if (open(SplitTracker.BLOOD) && moments.waitingForWatcher) watchWatcher(level)
    }

    private fun everyoneInCore(level: ClientLevel): Boolean? {
        val alive = DungeonUtils.dungeonTeammates.filter { !DeadPlayers.isDead(it) }
        if (alive.isEmpty()) return false
        var unseen = false
        for (mate in alive) {
            val p = mate.entity ?: level.players().firstOrNull { it.name.string == mate.name }
            if (p == null) { unseen = true; continue }
            if (!(p.x >= 39 && p.x < 71 && p.y < 155.5 && p.z >= 54 && p.z < 118)) return false
        }
        return if (unseen) null else true
    }

    private fun watchCore(level: ClientLevel) {
        if (core.look(serverTicks)) {
            val me = mc.player
            val alive = aliveNames()
            if (me != null && me.name.string in alive) core.observe(me.name.string, inCoreBox(me.x, me.y, me.z), serverTicks)
            for (mate in DungeonUtils.dungeonTeammates) {
                if (mate.name !in alive || mate.name == me?.name?.string) continue
                val p = mate.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == mate.name }
                val at = p?.positionCodec?.base
                core.observe(mate.name, at?.let { inCoreBox(it.x, it.y, it.z) }, serverTicks)
            }
            for (mate in DungeonUtils.dungeonTeammates) if (DeadPlayers.isDead(mate)) core.onDeath(mate.name, serverTicks)
            me?.name?.string?.let { if (DeadPlayers.isDead(it)) core.onDeath(it, serverTicks) }
            pasteLastIn()
        }
        if (core.openTick == null || core.goldorMoved != null) return
        val g = coreGoldor
        if (g == null) { bossWither(level, "Goldor")?.let { coreGoldor = Triple(it.id, it.positionCodec.base, serverTicks) }; return }
        val e = level.getEntity(g.first) ?: run { coreGoldor = null; return }
        val at = e.positionCodec.base
        if (at == g.second) return
        val dx = at.x - g.second.x; val dz = at.z - g.second.z
        val d = Math.sqrt(dx * dx + dz * dz)
        val ticks = (serverTicks - g.third).coerceAtLeast(1)
        if (d < 8 && d / ticks > GOLDOR_GO) core.onGoldorMoved(g.third)
        else coreGoldor = Triple(e.id, at, serverTicks)
    }

    private fun pasteLastIn() {
        if (!Recore.let { it.enabled && it.pasteLast } || core.pasted || mc.hasSingleplayerServer() || P3Sim.inSim) return
        val me = mc.player?.name?.string ?: return
        val mates = DungeonUtils.dungeonTeammates.filter { it.name != me }
        if (mates.size != 4 || mates.any { it.isDead }) return
        val alive = core.counted(aliveNames())
        if (core.lastIn(alive) == null || core.openTick == null) return
        core.pasted = true
        core.pasteLine(alive)?.let { sendCommand("pc $it") }
    }

    private fun aliveNames(): List<String> {
        val me = mc.player?.name?.string
        val mates = DungeonUtils.dungeonTeammates
        val out = mates.filter { !it.isDead }.mapTo(ArrayList()) { it.name }
        if (me != null && mates.none { it.name == me }) out += me
        return out.distinct()
    }

    private fun inCoreBox(x: Double, y: Double, z: Double) = x >= 39 && x < 71 && y >= 110 && y < 155.5 && z >= 54 && z < 118

    private val necronTnt = ArrayDeque<Stamp>()

    private fun onNecronTnt(at: Stamp) {
        necronTnt.addLast(at)
        while (necronTnt.first().tick < at.tick - (DEATH_BURST_TICKS - 1)) necronTnt.removeFirst()
        if (necronTnt.size < DEATH_BURST_TNT) return
        val first = necronTnt.first()
        necronTnt.clear()
        necronDead(first)
    }

    private fun necronDead(at: Stamp) {
        if (necronDiedAt != null) return
        necronDiedAt = at
        if (DungeonUtils.floor?.name?.startsWith("M") == true) subs.onNecronDeath(at)
    }

    private var necronId: Int? = null
    private var necronDiedAt: Stamp? = null

    private val DEATH_BURST_TNT = 3
    private val DEATH_BURST_TICKS = 2
    private val NECRON_GONE = 20

    private fun bossWither(level: ClientLevel, name: String): WitherBoss? {
        val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>().filter { Witherborn.isBoss(it) }
        val tag = level.entitiesForRendering().firstOrNull { it is ArmorStand && it.customName?.string?.contains(name) == true }
        val anchor = tag ?: mc.player ?: return null
        return withers.minByOrNull { it.distanceToSqr(anchor) }
    }

    private var goldorAt: Pair<Int, Vec3>? = null
    private var goldorMoved = false

    private fun watchGoldor(level: ClientLevel, trusted: Boolean) {
        if (goldorMoved) return
        val at = goldorAt
        if (at == null) { bossWither(level, "Goldor")?.let { goldorAt = it.id to it.position() }; return }
        val e = level.getEntity(at.first) ?: run { goldorAt = null; return }
        val d = e.position().distanceTo(at.second)
        if (d > 8) goldorAt = e.id to e.position()
        else if (d > 0.1) {
            if (trusted) {
                moments.onEveryoneInCore(now())
                subs.onEveryoneInCore(now())
            }
            goldorMoved = true
        }
    }

    private fun open(label: String) = tracker.split(label)?.stop == null && tracker.split(label) != null

    private fun teamNames(): Set<String> =
        DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name }.also { set -> mc.player?.let { set += it.name.string } }

    private fun door(blocks: List<Pair<Int, Int>>, sink: (Stamp, BloodRunDetail.MapRoom?, BloodRunDetail.MapRoom?) -> Unit) {
        for (d in DoorBlocks.doors(blocks)) {
            val a = room(d.a.first, d.a.second); val b = room(d.b.first, d.b.second)
            sink(now(), a, b)
        }
    }

    private fun room(x: Int, z: Int): BloodRunDetail.MapRoom? {
        if (x !in 0..5 || z !in 0..5) return null
        val r = DungeonScan.tiles[x + z * 6].room ?: return null
        return BloodRunDetail.MapRoom(
            id = System.identityHashCode(r).toString(),
            name = r.name ?: return null,
            fairy = r.type == RoomType.FAIRY,
            entrance = r.type == RoomType.ENTRANCE,
        )
    }

    private const val LINE_HEIGHT = 10

    private val SECTION_DOORS = listOf(92..108 to 120..125, 15..20 to 124..140, 0..16 to 47..52)
    private const val SECTION_DOOR_BLOCKS = 100
    private val sectionDoor = IntArray(3)
    private val NECRON_MID = Vec3(54.0, 66.0, 76.0)
    private const val GOLDOR_GO = 0.25
    private const val CORE_OPEN = "The Core entrance is opening!"
    private const val NECRON_LINE = "[BOSS] Necron: "
    private val KEY = Regex("""(?:Wither|Blood) Key""")

    private var watcherAt: Pair<Int, Vec3>? = null

    private fun watchWatcher(level: ClientLevel) {
        val handle = moments.watcherHandle ?: return
        val prev = watcherAt
        val e = (prev?.let { level.getEntity(it.first) }
            ?: level.entitiesForRendering().firstOrNull { it is Zombie && isWatcherHead(it) })
        if (e == null) { watcherAt = null; return }
        watcherAt = e.id to e.position()
        if (prev == null || prev.first != e.id || serverTicks - handle.tick < 45) return
        if (e.position().distanceTo(prev.second) > 0.001) {
            moments.onWatcherMoved(now())
            subs.onWatcherMoved(now())
        }
    }

    private val WATCHER_SKINS = listOf("5662b6fb4b8b", "2739d7f4e66a", "bf6e1e7ed365", "4cec40008e1c", "b37dd18b5983", "f5f0d78fe38d", "51967db5e319", "9fd61e8055f6", "e5c1dc47a04c")

    private fun isWatcherHead(e: Entity): Boolean {
        val head = (e as? LivingEntity)?.getItemBySlot(EquipmentSlot.HEAD) ?: return false
        val tex = head.texture ?: return false
        val decoded = runCatching { String(Base64.getDecoder().decode(tex)) }.getOrNull() ?: return false
        return WATCHER_SKINS.any { it in decoded }
    }

    private fun distanceTo(e: Entity): Double = mc.player?.distanceTo(e)?.toDouble() ?: 0.0

    private fun bloodLines(): List<String> {
        if (bloodHideInBoss.enabled && DungeonUtils.inBoss) return emptyList()
        return blood.lines(bloodLevel.value, now(), totalRow.enabled)
    }

    private fun stormLines(): List<String> {
        val split = tracker.split(SplitTracker.STORM) ?: return emptyList()
        val now = now()
        val floor = DungeonUtils.floor?.name
        val bests = bestsView(floor)
        val steps = subs.forSplit(SplitTracker.STORM)
        val ids = subs.idsForSplit(SplitTracker.STORM)
        val graded = steps.mapIndexed { i, st ->
            val stop = st.stop ?: now
            val ms = stop.realMs - st.start.realMs
            val ticks = (stop.tick - st.start.tick).toLong()
            val id = ids.getOrElse(i) { "" }
            val value = SubSplitGrades.value(id, ms, ticks, (stop.tick - split.start.tick).toLong())
            val own = st.label.take(2).replace('&', '§')
            val grade = SubSplitGrades.colour(id, value, st.stop != null, validBest(id, bests[id], floor), floor, own)
            val real = SubSplitGrades.clock(id) == SubSplitGrades.Clock.REAL
            Triple(st, grade + SplitFormat.seconds(if (real) ms else ticks * 50), SplitFormat.seconds(if (real) ticks * 50 else ms))
        }
        if (graded.isEmpty()) return emptyList()
        if (stormLevel.value == StormLevel.COMPACT) return listOf("§bStorm: " + graded.joinToString(" §8| ") { it.second })

        val stop = split.stop ?: now
        val out = mutableListOf("§bStorm §f" + SplitFormat.seconds((stop.tick - split.start.tick) * 50L))
        val start = storm.start
        val moments = storm.moments()
        fun at(m: StormDetail.Moment) = (start ?: (split.start.tick - 1)) + m.tick
        moments.filter { at(it) < graded.first().first.start.tick }.forEach { out += moment(it) }
        graded.forEachIndexed { i, (st, time, other) ->
            out += st.label.take(2).replace('&', '§') + st.label.drop(2) + " " + time + " §8(§7" + other + "§8)"
            val end = graded.getOrNull(i + 1)?.first?.start?.tick ?: Int.MAX_VALUE
            moments.filter { at(it) >= st.start.tick && at(it) < end }.forEach { out += moment(it) }
        }
        return out
    }

    private fun moment(m: StormDetail.Moment) = " §8· " + m.text + " §7" + StormDetail.secs(m.tick)

    fun pace(now: Stamp = now()): SplitPace.Clocks? {
        if (DungeonUtils.floor?.name != "F7") return null
        val splits = tracker.splits()
        if (splits.isEmpty()) return null
        return SplitPace.pace(splits, { label -> subs.forSplit(label).zip(subs.idsForSplit(label)) { s, id -> SplitPace.Sub(id, s) } }, now,
            { label -> OdinSplitsLook.paceTarget(label, false) })
    }

    private val bestsChecked = HashSet<String>()

    private var realRun = true

    private val keepsTimes get() = realRun && !P3Sim.inSim

    private fun recordSplitBests() {
        val floor = DungeonUtils.floor?.name?.takeIf { it == "F7" || it == "M7" } ?: return
        if (!keepsTimes) return
        val splits = tracker.splits()
        splits.forEachIndexed { i, split ->
            val stop = split.stop ?: return@forEachIndexed
            val next = splits.getOrNull(i + 1)
            if (floor == "M7" && split.label == SplitTracker.NECRON && next?.label != SplitTracker.DRAGONS) return@forEachIndexed
            if (!bestsChecked.add(split.label)) return@forEachIndexed
            val order = SplitPace.ALL.indexOf(split.label)
            val whole = if (next != null) SplitPace.ALL.indexOf(next.label) == order + 1
                else split.label == SplitTracker.DRAGONS || (floor == "F7" && split.label == SplitTracker.NECRON)
            val id = SplitPace.SPLIT_IDS[split.label] ?: return@forEachIndexed
            if (!whole) return@forEachIndexed
            recordBest(floor, id, SubSplitGrades.value(id, stop.realMs - split.start.realMs, (stop.tick - split.start.tick).toLong()), true)
            recordRecent(floor, id, SplitPace.Clocks(stop.realMs - split.start.realMs, (stop.tick - split.start.tick).toLong()))
        }
    }

    private val lagSamples = ArrayDeque<Long>()
    private var lagShown = false

    fun lag(): Long? = synchronized(lagSamples) { lagSamples.minOrNull()?.takeIf { lagShown } }

    private fun sampleLag(at: Stamp) {
        val splits = tracker.splits().takeIf { it.isNotEmpty() } ?: return
        val ms = SplitPace.lag(splits, at)
        synchronized(lagSamples) {
            lagSamples.addLast(ms)
            while (lagSamples.size > 20) lagSamples.removeFirst()
            if ((lagSamples.minOrNull() ?: 0) >= 50) lagShown = true
        }
    }

    fun bestOf(floor: String, id: String): Long? = validBest(id, bestsView(floor)[id], floor)

    fun recordBest(floor: String, id: String, value: Long, finished: Boolean) {
        if (!finished || !keepsTimes || !SubSplitGrades.canBeBest(id, value, floor)) return
        if (value >= (validBest(id, bestsView(floor)[id], floor) ?: Long.MAX_VALUE)) return
        val bests = bests(floor)
        bests[id] = value
        saveBests(floor, bests)
    }

    private fun validBest(id: String, best: Long?, floor: String?): Long? = best?.takeIf { SubSplitGrades.canBeBest(id, it, floor) }

    private fun recentText(floor: String?) = when (floor) { "F7" -> recentF7; "M7" -> recentM7; else -> "" }

    private val recentParsed = HashMap<String, Pair<String, Map<String, List<SplitPace.Clocks>>>>()
    private fun recentView(floor: String): Map<String, List<SplitPace.Clocks>> {
        val text = recentText(floor)
        recentParsed[floor]?.let { (t, m) -> if (t === text) return m }
        return recent(floor).also { recentParsed[floor] = text to it }
    }

    private fun recent(floor: String?): MutableMap<String, MutableList<SplitPace.Clocks>> =
        recentText(floor).split(',').mapNotNull { e ->
            val (k, v) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            k.trim() to v.split('|').mapNotNull { c ->
                val (ms, ticks) = c.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                SplitPace.Clocks(ms.toLongOrNull() ?: return@mapNotNull null, ticks.toLongOrNull() ?: return@mapNotNull null)
            }.toMutableList()
        }.toMap(HashMap())

    private fun recordRecent(floor: String, id: String, c: SplitPace.Clocks) {
        val all = recent(floor)
        val list = all.getOrPut(id) { mutableListOf() }
        list += c
        while (list.size > RECENT) list.removeAt(0)
        val text = all.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> k + "=" + v.joinToString("|") { "${it.ms}:${it.ticks}" } }
        when (floor) { "F7" -> recentF7 = text; "M7" -> recentM7 = text; else -> return }
        ModuleManager.saveConfigurations()
    }

    fun typical(floor: String, label: String): SplitPace.Clocks? {
        val id = SplitPace.SPLIT_IDS[label] ?: return null
        val view = recentView(floor)
        val cache = typicalCache.takeIf { it.first === view } ?: (view to HashMap<String, SplitPace.Clocks?>()).also { typicalCache = it }
        return cache.second.getOrPut(id) {
            val list = view[id]?.takeIf { it.size >= RECENT_MIN } ?: return@getOrPut null
            fun median(v: List<Long>) = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
            SplitPace.Clocks(median(list.map { it.ms }), median(list.map { it.ticks }))
        }
    }
    private var typicalCache: Pair<Map<String, List<SplitPace.Clocks>>, HashMap<String, SplitPace.Clocks?>> = emptyMap<String, List<SplitPace.Clocks>>() to HashMap()

    private fun bestsText(floor: String?) = when (floor) { "F7" -> bestsF7; "M7" -> bestsM7; else -> "" }

    private fun bestsView(floor: String?): Map<String, Long> = SubSplitGrades.bestsView(bestsText(floor))

    private fun bests(floor: String?): MutableMap<String, Long> = SubSplitGrades.parseBests(bestsText(floor))

    private fun saveBests(floor: String?, bests: Map<String, Long>) {
        val text = SubSplitGrades.formatBests(bests)
        when (floor) { "F7" -> bestsF7 = text; "M7" -> bestsM7 = text; else -> return }
        ModuleManager.saveConfigurations()
    }

    private fun draw(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        if (lines.any { '\t' in it }) return table(gfx, lines, right = setOf(0, 1, 3))
        lines.forEachIndexed { i, line -> gfx.text(line, 0, i * LINE_HEIGHT, Colors.WHITE, shadow = true) }
        return lines.maxOf { mc.font.width(it) } to lines.size * LINE_HEIGHT
    }

    private fun table(gfx: GuiGraphicsExtractor, lines: List<String>, right: Set<Int>): Pair<Int, Int> {
        val rows = lines.map { it.split('\t') }
        val cols = rows.maxOf { it.size }
        val widths = IntArray(cols) { c -> rows.maxOf { r -> r.getOrNull(c)?.let(mc.font::width) ?: 0 } }
        val starts = IntArray(cols)
        for (c in 1 until cols) starts[c] = starts[c - 1] + widths[c - 1]
        rows.forEachIndexed { i, row ->
            val y = i * LINE_HEIGHT
            row.forEachIndexed { c, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val x = if (c in right) starts[c] + widths[c] - mc.font.width(cell) else starts[c]
                gfx.text(cell, x, y, Colors.WHITE, shadow = true)
            }
        }
        return starts[cols - 1] + widths[cols - 1] to lines.size * LINE_HEIGHT
    }
}
