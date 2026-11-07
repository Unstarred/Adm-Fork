package com.engineerclient.ads.plan

import com.engineerclient.EngineerClient
import com.engineerclient.features.practice.TermInfo
import com.google.gson.GsonBuilder
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.io.File
import java.util.Locale
import com.engineerclient.ads.arena.Blocks
import com.engineerclient.ads.arena.Spots
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.core.Stats

object Practice {
    class Task(val id: String, val label: String) { @Volatile var at = -1 }

    @Volatile var mode: String? = null
        private set
    @Volatile var section = 0
        private set
    @Volatile var tasks: List<Task> = emptyList()
        private set
    @Volatile var ticks = 0
        private set
    @Volatile var endTicks = -1
        private set
    @Volatile var endMs = 0L
        private set

    val active get() = mode != null

    class Setup(val name: String, val section: Int, val start: Spots.Spot, val jobs: List<String>, val checkpoints: List<Spots.Spot> = emptyList(), val delay: Double? = null,
                val item: String? = null, val mines: List<BlockPos> = emptyList(), val restartMs: Long = 250)

    const val FIRST_BOW = "first bow"
    private const val BREAKER = "DUNGEONBREAKER"
    private const val BONZO = "STARRED_BONZO_STAFF"

    private val HEE3_MINES get() = listOf(15, 16).flatMap { x -> listOf(93, 94).map { BlockPos(x, 122, it) } }

    private fun coded(section: Int, name: String, code: String, item: String?, jobs: List<String>? = null, restartMs: Long = 250, start: Spots.Spot? = null): Setup? {
        val c = PracticeCode.decode(code) ?: return null.also { EngineerClient.logger.warn("[p3sim] practice preset {}: bad code {}", name, code) }
        val picked = jobs ?: P3Plan.allJobs().filter { it in c.jobs }
        return Setup(name, section, start ?: c.start ?: return null, picked, c.checkpoints, c.delay, item, restartMs = restartMs)
    }

    private var codedPresets: List<Setup>? = null
    private fun codedPresets(): List<Setup> = codedPresets ?: listOfNotNull(
        coded(1, "123", "\$b4RW/r\$6-sy!h8", BREAKER),
        coded(1, "43 bl", "Pu0y8Q2!sz]64YV!", BREAKER),
        coded(1, "i3 21", "yq&oxU6Wr3h49kw", BREAKER),
        coded(1, "i4", "1F?x8!EwzD)T=q", FIRST_BOW, restartMs = 700, start = p(63.07, 127.00, 35.08, -12.25f, -0.99f)),
        coded(2, "ee3", "4VT^EC9D6Qi+=DN9E5yAi", BONZO),
        coded(2, "cee3", "6tNQ?NDFPO??+/!(m8Y", BONZO, start = p(36.03, 131.00, 139.01, 92.64f, 3.45f)),
        coded(2, "i2 -> gbl 4", "2)n^DVlEAIg)].{gDhkKGT7", BREAKER),
        coded(2, "i2 -> 53", "c0Ogu/8N9bm\$+d", BREAKER),
        coded(2, "i2 -> 2", "AwwF19X[hNEXi4GvPpxJ", BONZO),
        coded(2, "ee2 -> 4 rl", "4VV3(]4y?RkzMM!0O2j8W", BREAKER),
        coded(2, "ee2 -> 53", "cVx+bCagN)P7c-", BONZO),
        coded(2, "ee2 -> 23", "cVx+bCagN)P7V}", BREAKER),
        coded(2, "ee2 -> 53 core", "4VV3(]4y?}3?tk}Z1vXtL", BONZO),
        coded(2, "ee2 -> 23 core", "4VV3(]4y?}3?s)SzW9E7R", BREAKER, jobs = listOf("S2 T2", "S2 T3")),
        coded(2, "3 -> core", "6qVDsKr0.Ni]JM)q5m4", BREAKER),
        coded(2, "2 -> core", "6t431)u?S&6POjy(s7/", BREAKER),
        coded(2, "2 -> 23", "cUIyVshnIF6L[7", BREAKER),
        coded(2, "5 -> 53", "c6Ecf4/uscbw2n", BREAKER),
        coded(4, "1", "A&XXR!e.OWkVS+.gHRxy", BREAKER),
        coded(4, "2", "1FiRWUShfWt!!}", BREAKER),
        coded(4, "3", "1FiRWUShfWt!\$F", BREAKER),
        coded(4, "4", "1FiRWUShfWt!\$V", BREAKER),
        coded(4, "bl", "cN^89NinzJlbXT", BREAKER),
        coded(4, "3 bl", "!4&OC5EOpIxz5%4", BREAKER),
        coded(4, "4 bl", "!4&OC5EOpIxz8YZ", BREAKER),
        coded(4, "1 -> 2", "1F30?WCBnGmO}}", BREAKER),
        coded(4, "3 -> 3 bl", "!V8vZu}h7d{Y0r.", BREAKER),
        coded(4, "3 -> bl", "!V8vZu}h7d{Y0r.", BONZO, jobs = listOf("S4 low lever", "S4 high lever")),
        coded(4, "4 -> 4 bl", "!gOrno7IArJ3^&j", BREAKER),
        coded(4, "4 -> bl", "cl7&FG8D3dpRat", BONZO),
    ).also { codedPresets = it }

    @Volatile private var setup: Setup? = null
    private val custom get() = setup != null
    private val checkpointsNow get() = setup?.checkpoints.orEmpty()

    private fun p(x: Double, y: Double, z: Double, yaw: Float, pitch: Float) = Spots.Spot("Start", x, y, z, yaw, pitch)

    fun presets(): List<Setup> = codedPresets().filter { it.section == 1 } + listOf(
        Setup("4 3", 1, p(108.20, 120.00, 94.00, -90.41f, 2.79f), listOf("S1 T4", "S1 T3"), delay = 3.0),
        Setup("2 1", 1, p(108.20, 120.00, 94.00, -90.41f, 2.79f), listOf("S1 T2", "S1 T1"), delay = 3.0),
        Setup("ee2", 1, p(108.20, 120.00, 94.00, -90.41f, 2.79f), listOf("S1 west lever", "S1 east lever", "gate 1", "S2 Lights"),
            listOf(Spots.Spot("EE2", 60.53, 132.00, 138.98, 0f)), delay = 3.0),
    ) + codedPresets().filter { it.section == 2 } + listOf(
        Setup("2 -> 1", 2, p(59.47, 120.00, 125.98, -179.50f, 23.01f), listOf("S2 T1"), listOf(Spots.Spot("Checkpoint", 69.70, 109.00, 122.43, 0f)), delay = 0.0),
        Setup("2 -> 3", 2, p(59.47, 120.00, 125.98, -179.50f, 23.01f), listOf("S2 T3"), delay = 0.0),
    ) + listOf(
        Setup("1 bl", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T1", "S3 west lever", "S3 east lever"), delay = 0.0, item = BREAKER),
        Setup("4 bl", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T4", "S3 west lever", "S3 east lever", "gate 3"), delay = 0.0, item = BREAKER),
        Setup("2 dev", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T2", "S3 Arrows"), delay = 0.0, item = BREAKER),
        Setup("3 dev", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T3", "S3 Arrows"), listOf(Spots.Spot("CEE3", 18.70, 121.50, 91.30, 0f)), delay = 0.0, item = BONZO),
        Setup("c -> 1", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 T1"), delay = 0.0, item = BREAKER, mines = HEE3_MINES),
        Setup("c -> 2", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 T2"), delay = 0.0, item = BONZO, mines = HEE3_MINES),
        Setup("c -> i3 4", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 Arrows", "S3 T4"), delay = 0.0, item = BONZO, mines = HEE3_MINES),
        Setup("c -> 3 bl", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 T3", "S3 west lever", "S3 east lever"), delay = 0.0, item = BREAKER, mines = HEE3_MINES),
        Setup("c -> i3 4 bl", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 Arrows", "S3 T4", "S3 west lever", "S3 east lever"), delay = 0.0, item = BONZO, mines = HEE3_MINES),
        Setup("4 -> 4 BL", 3, p(0.97, 109.00, 77.89, 96.99f, 19.23f), listOf("S3 T4", "S3 west lever", "S3 east lever"), delay = 0.0, item = BREAKER),
        Setup("4 -> BL", 3, p(0.97, 109.00, 77.89, 96.99f, 19.23f), listOf("S3 west lever", "S3 east lever"), delay = 0.0, item = BONZO),
    ) + codedPresets().filter { it.section > 3 }

    private val GROUPS get() = mapOf(
        1 to listOf("Terms" to listOf("123", "2 1", "4 3", "43 bl"), "Devs / EE" to listOf("i4", "i3 21", "ee2")),
        2 to listOf(
            "EE2 ->" to listOf("ee2 -> 23", "ee2 -> 53", "ee2 -> 4 rl", "ee2 -> 23 core", "ee2 -> 53 core"),
            "i2 ->" to listOf("i2 -> 2", "i2 -> 53", "i2 -> gbl 4"),
            "To EE3" to listOf("ee3", "cee3"),
            "Term ->" to listOf("2 -> 1", "2 -> 3", "2 -> 23", "5 -> 53", "2 -> core", "3 -> core"),
        ),
        3 to listOf(
            "EE3 ->" to listOf("1 bl", "4 bl", "2 dev", "3 dev"),
            "CEE3 ->" to listOf("c -> 1", "c -> 2", "c -> i3 4", "c -> 3 bl", "c -> i3 4 bl"),
            "4 ->" to listOf("4 -> 4 BL", "4 -> BL"),
        ),
        4 to listOf(
            "Core ->" to listOf("1", "2", "3", "4"),
            "Core -> BL" to listOf("bl", "3 bl", "4 bl"),
            "Term ->" to listOf("1 -> 2", "3 -> 3 bl", "3 -> bl", "4 -> 4 bl", "4 -> bl"),
        ),
    )

    fun presetGroups(s: Int): List<Pair<String, List<Setup>>> {
        val all = presets().filter { it.section == s }
        val byName = all.associateBy { it.name }
        val groups = GROUPS[s].orEmpty().map { (h, names) -> h to names.mapNotNull { byName[it] } }.filter { it.second.isNotEmpty() }
        val listed = groups.flatMap { it.second }.toSet()
        val rest = all.filter { it !in listed }
        return if (rest.isEmpty()) groups else groups + ("Other" to rest)
    }

    fun startPreset(name: String) { presets().firstOrNull { it.name == name }?.let { launch(it) } }

    private var ee: P3Plan.EarlyEnter? = null

    fun start(s: Int) { setup = null; launch("S$s", s) }

    fun startCustom() {
        val at = customStart
        if (at == null || customSection !in 1..4) { Sim.note("Set the custom practice's §fStart Position§7 first (Practice tab)."); return }
        val jobs = (1..4).flatMap { s -> P3Plan.jobsIn(s).filter { it in customJobs } }
        val s = (jobs.map { sectionOf(it) }.filter { it in 1..4 } + customSection).min()
        launch(Setup("Custom", s, at, jobs, checkpoints.toList()))
    }

    private fun launch(set: Setup) { setup = set; launch(set.name, set.section) }

    private fun launch(m: String, s: Int) {
        mode = m; section = s
        tasks = emptyList(); ticks = 0; endTicks = -1
        TermInfo.simClearTime()
        startedMs = System.currentTimeMillis()
        Fight.start(listOf(Fight.Start.S1, Fight.Start.S2, Fight.Start.S3, Fight.Start.S4)[s - 1], practice = true)
        setup?.mines?.forEach { Blocks.set(it, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()) }
        Fight.later(NO_BREAK_TICKS + 1, "practice blocks back") { SimItems.unbreakAll() }
    }

    @Volatile private var startedMs = 0L
    private const val NO_BREAK_MS = 150L
    private const val NO_BREAK_TICKS = 3

    fun noBreaking() = active && System.currentTimeMillis() - startedMs < NO_BREAK_MS

    fun restart() {
        val set = setup
        when {
            set?.name == "Custom" -> startCustom()
            set != null -> launch(set)
            section in 1..4 -> start(section)
        }
    }

    fun clickRestarts() = active && endTicks >= 0 && System.currentTimeMillis() - endMs >= (setup?.restartMs ?: 250)

    fun exit() { mode = null; section = 0; setup = null; tasks = emptyList(); endTicks = -1 }

    fun jobs(s: Int): List<String> {
        val times = P3Plan.plan().times
        return P3Plan.allJobs().filter { P3Plan.isMine(it) && (times[it]?.first ?: sectionOf(it)) == s }
            .sortedBy { times[it]?.second ?: 99.0 }
    }

    fun practiceJobs(): List<String> = setup?.jobs ?: jobs(section)

    fun lastSection(): Int = (practiceJobs().map { sectionOf(it) }.filter { it in 1..4 } + section).max()

    fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.removePrefix("S").substringBefore(' ').toIntOrNull() ?: 0

    fun startSpot(): Spots.Spot = setup?.start ?: when (val s = section) {
        1 -> P3Plan.customSpot("spawn") ?: Spots.p3Start(1)
        else -> P3Plan.earlyEnters.firstOrNull { it.into == s && (s != 4 || it.on) }?.let { P3Plan.eeSpot(it) } ?: Spots.p3Start(s)
    }

    @Volatile var startDelay = 0.0
        private set
    val holdTicks get() = Math.round((setup?.delay ?: startDelay) * 20).toInt()
    fun setStartDelay(v: Double) { startDelay = v.coerceIn(0.0, 5.0); save() }

    private var startT = 0

    fun hold(n: Int) { tasks = emptyList(); ticks = -n; endTicks = -1 }
    fun holdTick(t: Int, n: Int) { ticks = t - n }

    fun begin(s: Int, t: Int) {
        startT = t
        val list = practiceJobs().map { Task(it, label(it)) }.toMutableList()
        ee = null
        if (custom) checkpointsNow.forEachIndexed { i, c -> list += Task("cp $i", if (c.name.startsWith("Checkpoint") || c.name == "set") "Checkpoint ${i + 1}" else c.name) }
        else {
            ee = P3Plan.ee(if (s == 4) 5 else s + 1)?.takeIf { it.byYou }
            ee?.let { list += Task("ee ${it.key}", it.label) }
        }
        tasks = list; ticks = 0; endTicks = -1
        if (list.isEmpty()) Sim.note("Practice §f$mode§7: nothing to do in it.")
        else Sim.note("Practice §f$mode§7: ${list.joinToString("§8, §7") { it.label }}.")
    }

    private fun label(job: String) = when {
        job.startsWith("gate ") -> "Gate"
        else -> job.substringAfter(' ').replaceFirstChar { it.uppercase() }
    }

    fun tick(phase: GoldorPhase) {
        if (endTicks >= 0 || tasks.isEmpty()) return
        val now = phase.t - startT
        ticks = now
        val p = Sim.player
        for ((i, task) in tasks.withIndex()) {
            if (task.at >= 0) continue
            val done = when {
                task.id.startsWith("gate ") -> phase.gateIsDown(task.id.removePrefix("gate ").toInt())
                task.id.startsWith("ee ") -> p != null && tasks.all { it === task || it.at >= 0 } &&
                    ee?.let { onSpot(P3Plan.eeSpot(it), 1.5, p.x, p.y, p.z) } == true
                task.id.startsWith("cp ") -> p != null && tasks.subList(0, i).none { it.id.startsWith("cp ") && it.at < 0 } &&
                    checkpointsNow.getOrNull(task.id.removePrefix("cp ").toInt())?.let { onSpot(it, 1.0, p.x, p.y, p.z) } == true
                else -> phase.stations.firstOrNull { it.id == task.id }?.done == true
            }
            if (done) task.at = now
        }
        if (tasks.all { it.at >= 0 }) finish()
    }

    private fun onSpot(s: Spots.Spot, r: Double, x: Double, y: Double, z: Double): Boolean {
        val dx = x - s.x; val dz = z - s.z
        return dx * dx + dz * dz <= r * r && Math.floor(y * 100 + 1e-6).toLong() == Math.floor(s.y * 100 + 1e-6).toLong()
    }

    private fun finish() {
        val end = tasks.maxOf { it.at }
        ticks = end; endTicks = end; endMs = System.currentTimeMillis()
        Sim.note("Practice §f${mode}§7 ${secs(end)}s${Stats.best("Practice $mode", end)}§7: " +
            tasks.joinToString("§8, §7") { "${it.label} §f${secs(it.at)}" })
    }

    fun secs(ticks: Int) = String.format(Locale.ROOT, "%.2f", ticks / 20.0)

    fun leapSpot(c: DungeonClass): Spots.Spot? {
        val s = ((Fight.phase as? GoldorPhase)?.section ?: section).coerceIn(1, 4)
        return leapSpots()[s - 1][c]
    }

    private fun leapSpots(): List<Map<DungeonClass, Spots.Spot>> = listOf(
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 94.04, 112.00, 99.26, 60.01f, -3.13f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 93.56, 112.00, 93.29, 103.97f, 15.53f),
            DungeonClass.MAGE to Spots.Spot("Mage", 106.77, 122.00, 111.70, 8.30f, -19.24f),
            DungeonClass.TANK to Spots.Spot("Tank", 110.70, 113.00, 75.34, -178.85f, 22.77f),
            DungeonClass.HEALER to Spots.Spot("Healer", 108.30, 120.00, 94.01, -90.08f, -0.99f),
        ),
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 39.12, 109.00, 139.87, -6.83f, 33.13f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 40.78, 109.00, 123.30, -176.88f, 26.63f),
            DungeonClass.MAGE to Spots.Spot("Mage", 59.46, 120.00, 125.78, -179.76f, 22.20f),
            DungeonClass.TANK to Spots.Spot("Tank", 68.46, 109.00, 125.21, -179.43f, 18.33f),
            DungeonClass.HEALER to Spots.Spot("Healer", 24.95, 131.06, 138.57, 90.40f, 18.91f),
        ),
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 1.01, 109.00, 77.81, 95.00f, 19.81f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 15.67, 123.00, 93.65, -90.00f, 19.40f),
            DungeonClass.MAGE to Spots.Spot("Mage", 18.70, 121.50, 91.30, 0.58f, -4.61f),
            DungeonClass.TANK to Spots.Spot("Tank", 1.35, 109.00, 112.32, 87.20f, 18.41f),
            DungeonClass.HEALER to Spots.Spot("Healer", 0.87, 119.00, 93.41, 89.42f, 19.15f),
        ),
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 44.45, 121.00, 31.81, -177.77f, 30.98f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 67.23, 109.00, 33.09, -178.02f, 18.65f),
            DungeonClass.MAGE to Spots.Spot("Mage", 54.53, 115.06, 50.51, -179.57f, 2.38f),
            DungeonClass.TANK to Spots.Spot("Tank", 41.49, 109.00, 33.04, 179.43f, 18.16f),
            DungeonClass.HEALER to Spots.Spot("Healer", 72.46, 115.00, 45.79, 0.18f, 27.20f),
        ),
    )

    @Volatile var customStart: Spots.Spot? = null
    @Volatile var customItem: String? = null
        private set
    @Volatile var customSection = 0
        private set
    val customJobs = LinkedHashSet<String>()
    val checkpoints = ArrayList<Spots.Spot>()

    fun sectionAt(x: Double, y: Double, z: Double): Int {
        val v = Vec3(x, y, z)
        return GoldorPhase.dtZone(v).takeIf { it in 1..4 }
            ?: GoldorPhase.DT_ZONES.minBy { (_, box) -> box.distanceToSqr(v) }.first
    }

    fun customJobsIn(s: Int): Set<String> = P3Plan.jobsIn(s).filter { it in customJobs }.toSet()

    fun setStart(spot: Spots.Spot, item: String? = null): Int {
        val s = sectionAt(spot.x, spot.y, spot.z)
        customSection = s
        customStart = spot
        customItem = item
        save()
        return s
    }

    fun toggleJob(job: String) {
        if (!customJobs.remove(job)) customJobs += job
        save()
    }

    fun clearCustom() { customStart = null; customItem = null; customSection = 0; customJobs.clear(); checkpoints.clear(); leapWaypoints.clear(); startDelay = 0.0; save() }

    fun itemKey(s: ItemStack): String? =
        if (s.isEmpty) null else SimItems.idOf(s) ?: "name:" + s.hoverName.string

    fun holdStartItem(player: ServerPlayer) {
        val set = setup ?: return
        val key = (if (set.name == "Custom") customItem else set.item) ?: return
        val inv = player.inventory
        val slot = (0..8).firstOrNull { val s = inv.getItem(it); if (key == FIRST_BOW) s.item is BowItem else itemKey(s) == key } ?: return
        inv.selectedSlot = slot
        player.connection.send(ClientboundSetHeldSlotPacket(slot))
    }

    fun exportCode(): String = PracticeCode.encode(PracticeCode.Custom(customStart, customJobs.toSet(), checkpoints.toList(), leapWaypoints.toList(), startDelay))

    fun importCode(code: String): Boolean {
        val c = PracticeCode.decode(code) ?: return false
        customStart = c.start
        customItem = null
        customSection = c.start?.let { sectionAt(it.x, it.y, it.z) } ?: 0
        customJobs.clear(); customJobs += c.jobs
        checkpoints.clear(); checkpoints += c.checkpoints
        leapWaypoints.clear(); leapWaypoints += c.leaps
        startDelay = c.delay
        save()
        return true
    }

    val leapWaypoints = ArrayList<Spots.Spot>()

    fun addLeapWaypoint(spot: Spots.Spot): Int {
        if (leapWaypoints.size >= 4) return 0
        leapWaypoints += spot; save()
        return leapWaypoints.size
    }

    fun removeLeapWaypoint() { if (leapWaypoints.isNotEmpty()) leapWaypoints.removeAt(leapWaypoints.size - 1); save() }

    fun leapWaypoint(i: Int): Spots.Spot? = if (setup?.name == "Custom") leapWaypoints.getOrNull(i) else null

    fun addCheckpoint(spot: Spots.Spot) { checkpoints += spot; save() }
    fun removeCheckpoint() { if (checkpoints.isNotEmpty()) checkpoints.removeAt(checkpoints.size - 1); save() }

    private class Saved(val start: List<Double>? = null, val section: Int? = null, val jobs: List<String>? = null, val checkpoints: List<List<Double>>? = null, val startDelay: Double? = null, val leaps: List<List<Double>>? = null, val item: String? = null,
                        val menuTab: String? = null, val menuSection: Int? = null)

    @Volatile var menuTab: String? = null
        private set
    @Volatile var menuSection = 1
        private set

    fun saveMenu(tab: String?, section: Int) {
        if (tab == menuTab && section == menuSection) return
        menuTab = tab; menuSection = section; save()
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(Minecraft.getInstance().gameDirectory, "config/adm/p3sim-practice.json")
    private fun list(p: Spots.Spot) = listOf(p.x, p.y, p.z, p.yaw.toDouble(), p.pitch.toDouble())
    private fun spot(name: String, v: List<Double>) = Spots.Spot(name, v[0], v[1], v[2], v[3].toFloat(), v[4].toFloat())

    fun load() {
        EngineerClient.safely("p3sim practice load") {
            val f = file
            if (!f.exists()) return@safely
            val s = gson.fromJson(f.readText(), Saved::class.java) ?: return@safely
            customStart = s.start?.takeIf { it.size >= 5 }?.let { spot("Start", it) }
            customSection = s.section ?: 0
            customItem = s.item
            customJobs.clear(); s.jobs?.let { customJobs += it }
            startDelay = s.startDelay ?: 0.0
            checkpoints.clear(); s.checkpoints?.forEachIndexed { i, v -> if (v.size >= 5) checkpoints += spot("Checkpoint ${i + 1}", v) }
            leapWaypoints.clear(); s.leaps?.forEachIndexed { i, v -> if (v.size >= 5 && i < 4) leapWaypoints += spot("Leap ${i + 1}", v) }
            menuTab = s.menuTab
            menuSection = s.menuSection?.takeIf { it in 1..4 } ?: 1
        }
    }

    private fun save() {
        EngineerClient.safely("p3sim practice save") {
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(Saved(customStart?.let { list(it) }, customSection, customJobs.toList(), checkpoints.map { list(it) }, startDelay, leapWaypoints.map { list(it) }, customItem,
                menuTab, menuSection)))
        }
    }
}
