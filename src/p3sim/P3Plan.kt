package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.io.File

object P3Plan {
    const val RANDOM = 3
    val SKILLS = Roles.PRESETS.take(RANDOM).map { it.name } + "Random" + Roles.PRESETS.drop(RANDOM).map { it.name }
    val THEORETICAL = SKILLS.indexOf("Theoretical")
    private val PROTOTYPE_1 = SKILLS.indexOf("Prototype 1")
    val NEAR_OPTIMAL = SKILLS.indexOf("Near Optimal")

    class EarlyEnter(val key: String, val label: String, val into: Int, var spot: Vec3, var yaw: Float = 0f, var pitch: Float = 0f) {
        val owner: DungeonClass? get() = plan().ee[if (into == 6) 5 else into]
        val on get() = owner != null
        val byYou get() = owner != null && owner == P3Sim.ownClass
    }

    fun defaultEarlyEnters() = listOf(
        EarlyEnter("ee2", "EE2", 2, Vec3(60.6, 132.0, 139.0)),
        EarlyEnter("ee3", "EE3", 3, Vec3(1.9, 109.0, 104.6)),
        EarlyEnter("ee4", "EE4", 4, Vec3(54.5, 115.06, 50.5)),
        EarlyEnter("core", "Core", 5, Vec3(54.5, 115.06, 50.5)),
        EarlyEnter("recore", "Recore", 6, Vec3(54.4, 115.0, 57.6)),
    )

    var skill = 1
    private class Mine(val key: String, val jobs: Set<String>)

    @Volatile private var mineNow = Mine("", emptySet())
    var botMin = 1.0
    var botMax = 9.0
    val earlyEnters = defaultEarlyEnters()
    var waitForYou = true
    var helper = false
    var leapGap = 0.5
    @Volatile var leapOrder: List<DungeonClass> = emptyList()
        private set
    private val leapLock = Any()
    val ghosts = LinkedHashSet<String>()

    private var custom: HashMap<String, Spots.Spot>? = HashMap()
    private fun spots() = custom ?: HashMap<String, Spots.Spot>().also { custom = it }
    private fun customKey(key: String) = "$skill/${P3Sim.ownClass?.name}/$key"

    fun customSpot(key: String): Spots.Spot? = spots()[customKey(key)] ?: defaultSpot(P3Sim.ownClass, key)
    fun hasCustomSpot(key: String) = spots().containsKey(customKey(key))
    fun setCustomSpot(key: String, p: Spots.Spot?) { if (p == null) spots().remove(customKey(key)) else spots()[customKey(key)] = p; save() }

    fun defaultSpot(c: DungeonClass?, key: String): Spots.Spot? = preset().defaults["$key/${c?.name}"] ?: preset().defaults[key] ?: when (key) {
        "spawn" -> if (c == DungeonClass.BERSERK) Spots.Spot("Spawn", 63.5, 127.0, 35.5, -11.18f, 0.33f)
            else earlyDeviceSpawn(c) ?: Spots.Spot("Spawn", 108.3, 120.0, 94.0, 269.6f, 0.57f)
        "ee2" -> Spots.Spot("EE2", 60.5, 132.0, 139.0, 90.47f, 1.64f)
        "ee3" -> Spots.Spot("EE3", 2.0, 109.0, 102.0, -175.24f, 5.83f)
        "ee4" -> Spots.Spot("EE4", 54.5, 115.06, 50.5, 179.91f, 0.57f)
        "core" -> Spots.Spot("Core", 54.5, 115.06, 50.5, 179.91f, 0.57f)
        "recore" -> Spots.Spot("Recore", 52.5, 115.0, 57.6, -159.12f, 2.63f)
        else -> null
    }

    private fun earlyDeviceSpawn(c: DungeonClass?): Spots.Spot? {
        val p = plan()
        val dev = listOf("S4 Target", "S3 Arrows").firstOrNull { j ->
            p.times[j]?.first == 1 && (if (c == P3Sim.ownClass) isMine(j) else p.owners[j]?.contains(c) == true)
        } ?: return null
        val at = Party.STANDS[dev] ?: return null
        val look = Spots.startLook(dev)
        return Spots.Spot("Spawn", at.x, at.y, at.z, look.first, look.second)
    }

    fun eeSpot(e: EarlyEnter): Spots.Spot = customSpot(e.key) ?: (preset().spots[e.key] ?: e.spot).let { Spots.Spot(e.label, it.x, it.y, it.z, e.yaw, e.pitch) }

    fun ghostOn(c: DungeonClass) = c.name in ghosts
    fun toggleGhost(c: DungeonClass) { if (!ghosts.remove(c.name)) ghosts += c.name; save() }

    fun preset(): Roles.Preset = Roles.PRESETS[when {
        skill == RANDOM -> 1
        skill > RANDOM -> (skill - 1).coerceAtMost(Roles.PRESETS.size - 1)
        else -> skill.coerceAtLeast(0)
    }]
    fun plan(): Roles.Plan = Roles.plan(preset())
    fun skillName() = SKILLS[skill.coerceIn(0, SKILLS.size - 1)]

    fun mine(): Set<String> {
        val key = "$skill/${P3Sim.ownClass}"
        val now = mineNow
        if (now.key == key) return now.jobs
        val own = ownMine
        val jobs = LinkedHashSet(if (own != null && key == ownMineFor) own else plan().jobsOf[P3Sim.ownClass].orEmpty())
        mineNow = Mine(key, jobs)
        return jobs
    }

    fun isMine(job: String) = job in mine()
    fun toggle(job: String) {
        val jobs = LinkedHashSet(mine())
        if (!jobs.remove(job)) jobs += job
        mineNow = Mine(mineNow.key, jobs)
        save()
    }
    fun resetMine() {
        mineNow = Mine("", emptySet()); mine()
        val prefix = "$skill/${P3Sim.ownClass?.name}/"
        pickMap().keys.removeIf { it.startsWith(prefix) }; stackSet().removeIf { it.startsWith(prefix) }
        save()
    }
    fun chooseSkill(i: Int) { skill = i.coerceIn(0, SKILLS.size - 1); mine(); save() }

    fun ee(into: Int) = earlyEnters.firstOrNull { it.into == into && it.on }

    fun isStack(job: String) = (plan().owners[job]?.size ?: 0) > 1

    fun doer(job: String): DungeonClass? = if (isMine(job)) P3Sim.ownClass else picked(job) ?: roleDoer(job)

    fun roleDoer(job: String): DungeonClass? = plan().owners[job]?.firstOrNull { it != P3Sim.ownClass }

    fun roleDoerBot(job: String): DungeonClass? = plan().owners[job]?.firstOrNull { !Players.isPlayerClass(it) }

    private var picks: HashMap<String, String>? = HashMap()
    private var stacked: HashSet<String>? = HashSet()
    private fun pickMap() = picks ?: HashMap<String, String>().also { picks = it }
    private fun stackSet() = stacked ?: HashSet<String>().also { stacked = it }
    private fun jobKey(job: String) = "$skill/${P3Sim.ownClass?.name}/$job"

    private fun picked(job: String): DungeonClass? =
        pickMap()[jobKey(job)]?.let { n -> Party.CLASSES.firstOrNull { it.name == n && it != P3Sim.ownClass } }

    fun cycleDoer(job: String) {
        val bots = Party.CLASSES.filter { it != P3Sim.ownClass }
        val next = bots[(bots.indexOf(doer(job)) + 1) % bots.size]
        if (next == roleDoer(job)) pickMap().remove(jobKey(job)) else pickMap()[jobKey(job)] = next.name
        save()
    }

    fun isStacked(job: String) = if (isStack(job)) "!${jobKey(job)}" !in stackSet() else jobKey(job) in stackSet()
    fun toggleStacked(job: String) {
        val key = if (isStack(job)) "!${jobKey(job)}" else jobKey(job)
        if (!stackSet().remove(key)) stackSet() += key
        save()
    }

    fun helperOf(job: String): DungeonClass? = picked(job) ?: roleDoer(job) ?: Party.CLASSES.firstOrNull { it != P3Sim.ownClass }

    fun allJobs(): List<String> = (1..4).flatMap { s -> jobsIn(s) }

    fun jobsIn(s: Int): List<String> = Station.all().filter { it.section == s }.map { it.id } + (if (s <= 3) listOf("gate $s") else emptyList())

    fun short(job: String): String {
        if (job.startsWith("gate")) return "G"
        if (job in Roles.LEFT.values) return "L"
        if (job in Roles.RIGHT.values) return "R"
        val name = job.substringAfter(' ')
        if (name.length > 1 && name[0] == 'T' && name.drop(1).all { it.isDigit() }) return name.drop(1)
        return "D"
    }

    fun botOrder(): List<DungeonClass> {
        val mineClass = P3Sim.ownClass
        val order = synchronized(leapLock) {
            val cur = leapOrder
            (cur + Party.CLASSES).distinct().filter { it != mineClass }.also { if (it != cur) leapOrder = it }
        }
        return if (odinSort) odinOrder(order) ?: order else order
    }

    fun orderFor(c: DungeonClass): List<DungeonClass> {
        if (c == P3Sim.ownClass) return botOrder()
        val o = Party.CLASSES.filter { it != c }
        Players.playerOf(c)?.takeIf { !Sim.isHost(it) }?.let { Players.profile(it).order }
            ?.takeIf { it.size == o.size && it.toSet() == o.toSet() }?.let { return it }
        return odinOrder(o) ?: o
    }

    var odinSort = true

    private fun odinOrder(classes: List<DungeonClass>): List<DungeonClass>? = runCatching {
        val players = classes.map { DungeonPlayer(Roles.label(it), it, 50, null) }.sortedBy { it.clazz.priority }
        LeapMenu.odinSorting(players).toList().map { it.clazz }.filter { it in classes }
    }.getOrNull()?.takeIf { it.size == classes.size && it.toSet() == classes.toSet() }

    fun cycleSlot(slot: Int, back: Boolean = false) {
        synchronized(leapLock) {
            val order = botOrder().toMutableList()
            odinSort = false
            val i = slot - 1
            val j = (i + (if (back) order.size - 1 else 1)) % order.size
            val t = order[i]; order[i] = order[j]; order[j] = t
            leapOrder = order.toList()
        }
        save()
    }

    private class Saved(
        val skill: Int? = null, val mine: List<String>? = null, val mineFor: String? = null,
        val botMin: Double? = null, val botMax: Double? = null,
        val waitForYou: Boolean? = null, val leapGap: Double? = null, val leapOrder: List<String>? = null, val odinSort: Boolean? = null,
        val spots: Map<String, List<Double>>? = null,
        val helper: Boolean? = null,
        val ghosts: List<String>? = null,
        val custom: Map<String, List<Double>>? = null,
        val picks: Map<String, String>? = null,
        val stacked: List<String>? = null,
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(Minecraft.getInstance().gameDirectory, "config/adm/p3sim-plan.json")
    private var loaded = false
    private var backedUp = false

    fun load() {
        if (loaded) return
        loaded = true
        EngineerClient.safely("p3sim plan load") {
            val f = file
            if (!f.exists()) return@safely
            val s = runCatching { gson.fromJson(f.readText(), Saved::class.java) }
                .onFailure { PlayerFiles.setAside(f) }.getOrThrow() ?: return@safely
            s.skill?.let { skill = (if (it == PROTOTYPE_1) THEORETICAL else it).coerceIn(0, SKILLS.size - 1) }
            if (s.mine != null && s.mineFor != null && s.skill != PROTOTYPE_1) mineNow = Mine(s.mineFor, LinkedHashSet(s.mine))
            s.botMin?.let { botMin = it }
            s.botMax?.let { botMax = it }
            s.waitForYou?.let { waitForYou = it }
            s.helper?.let { helper = it }
            s.ghosts?.let { ghosts.clear(); ghosts += it }
            s.custom?.forEach { (k, v) -> if (v.size >= 5) spots()[k] = Spots.Spot(k, v[0], v[1], v[2], v[3].toFloat(), v[4].toFloat()) }
            s.picks?.let { pickMap().clear(); pickMap() += it }
            s.stacked?.let { stackSet().clear(); stackSet() += it }
            s.leapGap?.let { leapGap = it }
            s.odinSort?.let { odinSort = it }
            s.leapOrder?.let { names -> synchronized(leapLock) { leapOrder = names.mapNotNull { n -> Party.CLASSES.firstOrNull { it.name == n } } } }
            s.spots?.forEach { (k, v) ->
                earlyEnters.firstOrNull { it.key == k }?.let {
                    if (v.size >= 3 && !(k == "ee2" && v == listOf(69.0, 109.0, 124.7))) it.spot = Vec3(v[0], v[1], v[2])
                    if (v.size >= 5) { it.yaw = v[3].toFloat(); it.pitch = v[4].toFloat() }
                }
            }
        }
    }

    fun sharedJson(): JsonObject = JsonObject().also {
        it.addProperty("skill", skill); it.addProperty("botMin", botMin); it.addProperty("botMax", botMax)
        it.addProperty("waitForYou", waitForYou); it.addProperty("helper", helper); it.addProperty("leapGap", leapGap)
    }

    fun applyShared(o: JsonObject, save: Boolean) {
        o.get("skill")?.let { skill = it.asInt.coerceIn(0, SKILLS.size - 1) }
        o.get("botMin")?.let { botMin = it.asDouble }
        o.get("botMax")?.let { botMax = it.asDouble }
        o.get("waitForYou")?.let { waitForYou = it.asBoolean }
        o.get("helper")?.let { helper = it.asBoolean }
        o.get("leapGap")?.let { leapGap = it.asDouble }
        if (save) save()
    }

    private var ownShared: JsonObject? = null
    private var partyShared: String? = null

    private var ownMine: List<String>? = null
    private var ownMineFor: String? = null

    fun receiveShared(o: JsonObject) {
        if (ownShared == null) { ownShared = sharedJson(); ownMine = mine().toList(); ownMineFor = mineNow.key }
        applyShared(o, save = false)
        partyShared = sharedJson().toString()
    }

    fun leaveParty() {
        ownShared?.let { applyShared(it, save = false) }
        val om = ownMine; val omf = ownMineFor
        if (om != null && omf != null && omf != mineNow.key) mineNow = Mine(omf, LinkedHashSet(om))
        ownShared = null; partyShared = null; ownMine = null; ownMineFor = null
    }

    fun partyJson(): JsonObject = JsonObject().also { o ->
        val m = mine()
        o.add("mine", JsonArray().also { a -> m.forEach { a.add(it) } })
        o.addProperty("mineFor", mineNow.key)
        o.add("order", JsonArray().also { a -> botOrder().forEach { a.add(it.name) } })
        o.add("stacks", JsonObject().also { st -> m.filter { isStacked(it) }.forEach { j -> helperOf(j)?.let { st.addProperty(j, it.name) } } })
        o.add("spots", JsonObject().also { s ->
            spots().forEach { (k, p) -> s.add(k, JsonArray().also { a -> a.add(p.x); a.add(p.y); a.add(p.z); a.add(p.yaw); a.add(p.pitch) }) }
        })
        val sh = sharedJson()
        if (partyShared != null && sh.toString() != partyShared) o.add("shared", sh)
    }

    fun save() {
        EngineerClient.safely("p3sim plan save") {
            val own = ownShared
            val skill = own?.get("skill")?.asInt ?: skill
            val botMin = own?.get("botMin")?.asDouble ?: botMin
            val botMax = own?.get("botMax")?.asDouble ?: botMax
            val waitForYou = own?.get("waitForYou")?.asBoolean ?: waitForYou
            val helper = own?.get("helper")?.asBoolean ?: helper
            val leapGap = own?.get("leapGap")?.asDouble ?: leapGap
            val mineThen = mineNow
            val keepOwn = own != null && ownMineFor != null && ownMineFor != mineThen.key
            val s = Saved(skill, if (keepOwn) ownMine.orEmpty() else mineThen.jobs.toList(), if (keepOwn) ownMineFor else mineThen.key, botMin, botMax, waitForYou, leapGap, botOrder().let { leapOrder.map { it.name } }, odinSort,
                earlyEnters.associate { it.key to listOf(it.spot.x, it.spot.y, it.spot.z, it.yaw.toDouble(), it.pitch.toDouble()) }, helper, ghosts.toList(),
                spots().mapValues { (_, p) -> listOf(p.x, p.y, p.z, p.yaw.toDouble(), p.pitch.toDouble()) }, HashMap(pickMap()), stackSet().toList())
            file.parentFile.mkdirs()
            if (!backedUp && file.exists()) { backedUp = true; file.copyTo(File(file.path + ".bak"), overwrite = true) }
            PlayerFiles.writeAtomic(file, gson.toJson(s))
        }
    }
}
