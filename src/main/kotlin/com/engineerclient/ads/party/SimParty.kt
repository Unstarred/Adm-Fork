package com.engineerclient.ads.party

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.index
import com.engineerclient.features.odin.OdinMasksUsed
import com.engineerclient.features.odin.OdinTickTimers
import com.engineerclient.features.practice.TermInfo
import com.engineerclient.features.splits.DungeonSplits
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.arena.Spots
import com.engineerclient.ads.bots.GhostCapture
import com.engineerclient.ads.bots.Party
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.core.SimServer
import com.engineerclient.ads.fight.BossBar
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.P1Maxor
import com.engineerclient.ads.fight.P2Storm
import com.engineerclient.ads.fight.P4Necron
import com.engineerclient.ads.items.HotbarLayout
import com.engineerclient.ads.items.Loadouts
import com.engineerclient.ads.items.Masks
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.plan.P3Plan
import com.engineerclient.ads.plan.Practice
import com.engineerclient.ads.plan.Roles

object SimNet {
    class Msg(val kind: String, val data: String) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Msg> = TYPE
    }

    val TYPE = CustomPacketPayload.Type<Msg>(Identifier.fromNamespaceAndPath("engineerclient", "p3sim"))
    private const val MAX = 1 shl 21
    private val CODEC: StreamCodec<RegistryFriendlyByteBuf, Msg> = StreamCodec.of(
        { b, m -> b.writeUtf(m.kind, 64); b.writeUtf(m.data, MAX) },
        { b -> Msg(b.readUtf(64), b.readUtf(MAX)) })

    private val serverSide = HashMap<String, (ServerPlayer, String) -> Unit>()
    private val clientSide = HashMap<String, (String) -> Unit>()

    fun onServer(kind: String, run: (ServerPlayer, String) -> Unit) { serverSide[kind] = run }
    fun onClient(kind: String, run: (String) -> Unit) { clientSide[kind] = run }

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC)
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC)
        ServerPlayNetworking.registerGlobalReceiver(TYPE) { m, ctx ->
            val p = ctx.player()
            val s = SimServer.server
            if (s == null || p.level().server !== s) return@registerGlobalReceiver
            val run = serverSide[m.kind] ?: return@registerGlobalReceiver
            EngineerClient.safely("p3sim net ${m.kind}") { Sim.asPlayer(p) { run(p, m.data) } }
        }
        ClientPlayNetworking.registerGlobalReceiver(TYPE) { m, _ ->
            clientSide[m.kind]?.let { run -> EngineerClient.safely("p3sim net ${m.kind}") { run(m.data) } }
        }
    }

    fun send(kind: String, data: String = "") {
        if (!P3Sim.inSim) return
        runCatching { if (ClientPlayNetworking.canSend(TYPE)) ClientPlayNetworking.send(Msg(kind, data)) }
    }

    fun send(p: ServerPlayer, kind: String, data: String = "") {
        runCatching { if (ServerPlayNetworking.canSend(p, TYPE)) ServerPlayNetworking.send(p, Msg(kind, data)) }
    }

    fun sendAll(kind: String, data: String = "") = Sim.players().forEach { send(it, kind, data) }
}

object Players {
    class Profile {
        val vals = ConcurrentHashMap<String, JsonElement>()
        @Volatile var clazz: DungeonClass? = null
        @Volatile var asked: String? = null
        val files = ConcurrentHashMap<String, String>()
        @Volatile var mine: Set<String>? = null
        @Volatile var mineFor: String? = null
        val spots = ConcurrentHashMap<String, Spots.Spot>()
        @Volatile var order: List<DungeonClass>? = null
        @Volatile var stacks: Map<String, DungeonClass> = emptyMap()
    }

    private val profiles = ConcurrentHashMap<UUID, Profile>()

    fun profile(p: ServerPlayer): Profile = profiles.getOrPut(p.uuid) { Profile() }

    fun forgetFiles(p: ServerPlayer) { HotbarLayout.forget(p.uuid); Loadouts.forget(p.uuid) }

    fun joiner(): Profile? = Sim.acting?.takeIf { !Sim.isHost(it) }?.let { profiles[it.uuid] }

    fun classOf(p: ServerPlayer): DungeonClass = if (Sim.isHost(p)) P3Sim.ownClass else profiles[p.uuid]?.clazz ?: P3Sim.ownClass

    fun playerOf(c: DungeonClass?): ServerPlayer? = if (c == null) null else Sim.players().firstOrNull { hasClass(it) && classOf(it) == c }

    @Volatile var joinerClasses: Set<DungeonClass> = emptySet()
        private set

    fun playerClasses(): Set<DungeonClass> = joinerClasses + P3Sim.ownClass

    fun isPlayerClass(c: DungeonClass?) = c != null && c in playerClasses()

    fun spotOf(p: ServerPlayer, key: String): Spots.Spot? {
        if (Sim.isHost(p)) return P3Plan.customSpot(key)
        val c = classOf(p)
        return profiles[p.uuid]?.spots?.get("${P3Plan.skill}/${c.name}/$key") ?: P3Plan.defaultSpot(c, key)
    }

    fun jobsOf(p: ServerPlayer): Set<String> {
        if (Sim.isHost(p)) return P3Plan.mine()
        if (!hasClass(p)) return emptySet()
        val c = classOf(p)
        val prof = profiles[p.uuid]
        val key = "${P3Plan.skill}/$c"
        return prof?.mine?.takeIf { prof.mineFor == key } ?: P3Plan.plan().jobsOf[c].orEmpty().toSet()
    }

    fun isPlayerJob(job: String): Boolean = Sim.players().any { job in jobsOf(it) }

    fun playerDoing(job: String): ServerPlayer? = Sim.players().firstOrNull { job in jobsOf(it) }

    fun joined(p: ServerPlayer) {
        val prof = profile(p)
        val want = prof.vals["class"]?.let { classByName(it.asString) }
        if (prof.clazz == null || prof.clazz in takenBy(p)) prof.clazz = free(p, want)
        refresh()
    }

    fun request(p: ServerPlayer, want: DungeonClass?): DungeonClass {
        val prof = profile(p)
        val taken = takenBy(p)
        val got = if (want != null && want !in taken) want else prof.clazz?.takeIf { it !in taken } ?: free(p, want)
        prof.clazz = got
        refresh()
        return got
    }

    fun left(p: ServerPlayer) { profiles[p.uuid]?.clazz = null; refresh(p) }

    fun hasClass(p: ServerPlayer) = Sim.isHost(p) || profiles[p.uuid]?.clazz != null

    private fun takenBy(p: ServerPlayer): Set<DungeonClass> = Sim.players().filter { it !== p && hasClass(it) }.map { classOf(it) }.toSet()

    private fun free(p: ServerPlayer, want: DungeonClass?): DungeonClass {
        val taken = takenBy(p)
        return want?.takeIf { it !in taken } ?: Party.CLASSES.firstOrNull { it !in taken } ?: want ?: Party.CLASSES.first()
    }

    fun classByName(n: String?): DungeonClass? = Party.CLASSES.firstOrNull { it.name == n }

    fun refresh(gone: ServerPlayer? = null) {
        joinerClasses = Sim.players().filter { !Sim.isHost(it) && it !== gone }.mapNotNull { profiles[it.uuid]?.clazz }.toSet()
    }

    fun changeClass(p: ServerPlayer, want: DungeonClass) {
        val mine = classOf(p)
        if (want == mine) return
        val holder = playerOf(want)
        if (holder != null && holder !== p) {
            if (Sim.isHost(holder)) setHostClass(mine) else { profile(holder).clazz = mine; SimNet.send(holder, "set", JsonObject().also { it.addProperty("class", mine.name) }.toString()) }
            Sim.asPlayer(holder) { Sim.note("§f${p.gameProfile.name}§7 took the §f${Roles.label(want)}§7: you're the §f${Roles.label(mine)}§7 now.") }
        }
        if (Sim.isHost(p)) setHostClass(want) else profile(p).clazz = want
        if (!Sim.isHost(p)) SimNet.send(p, "set", JsonObject().also { it.addProperty("class", want.name) }.toString())
        Sim.asPlayer(p) { Sim.note("You're the §f${Roles.label(want)}§7${holder?.takeIf { it !== p }?.let { " (§f${it.gameProfile.name}§7 is the ${Roles.label(mine)} now)" } ?: ""}.") }
        refresh()
        Party.rosterChanged()
        PartyServer.rosterDirty = true
    }

    private var hostClassSeen: DungeonClass? = null

    private fun setHostClass(c: DungeonClass) {
        hostClassSeen = c
        P3Sim.classS.index = Party.CLASSES.indexOf(c).coerceAtLeast(0)
        mc.execute { ModuleManager.saveConfigurations() }
    }

    fun tick() {
        val now = P3Sim.ownClass
        val was = hostClassSeen
        hostClassSeen = now
        if (was == null || was == now) return
        val holder = Sim.players().firstOrNull { !Sim.isHost(it) && profiles[it.uuid]?.clazz == now } ?: run { Party.rosterChanged(); PartyServer.rosterDirty = true; return }
        profile(holder).clazz = was
        SimNet.send(holder, "set", JsonObject().also { it.addProperty("class", was.name) }.toString())
        Sim.asPlayer(holder) { Sim.note("§f${Sim.hostName}§7 took the §f${Roles.label(now)}§7: you're the §f${Roles.label(was)}§7 now.") }
        refresh()
        Party.rosterChanged()
        PartyServer.rosterDirty = true
    }

    fun clear() { profiles.clear(); joinerClasses = emptySet(); hostClassSeen = null }
}

object PartySettings {
    class Opt(val key: String, val get: () -> JsonElement, val set: (JsonElement) -> Unit)

    private fun bool(key: String, s: () -> BooleanSetting?) = Opt(key, { JsonPrimitive(s()?.value == true) }, { v -> s()?.let { it.value = v.asBoolean } })
    @Suppress("UNCHECKED_CAST")
    private fun num(key: String, s: () -> NumberSetting<*>?) = Opt(key, { JsonPrimitive(s()?.value?.toDouble() ?: 0.0) }, { v ->
        s()?.let { n -> val cur = n.value; (n as NumberSetting<Number>).value = when (cur) { is Int -> Math.round(v.asDouble).toInt(); is Float -> v.asFloat; is Long -> v.asLong; else -> v.asDouble } }
    })

    val SHARED: List<Opt> by lazy { listOf(
        bool("bots") { P3Sim.botsS },
        Opt("deathTicks", { JsonPrimitive(P3Sim.deathTicksS.index) }, { P3Sim.deathTicksS.index = it.asInt }),
        Opt("terminal", { JsonPrimitive(P3Sim.terminalS.index) }, { P3Sim.terminalS.index = it.asInt }),
        num("goldorKill") { P3Sim.goldorKillS },
        bool("clickLimit") { P3Sim.clickLimitS },
        bool("noMelodies") { P3Sim.noMelodiesS },
        num("termLuck") { P3Sim.termLuckS },
        num("ssLuck") { P3Sim.ssLuckS },
        bool("firstClickMelody") { P3Sim.firstClickMelodyS },
        bool("debugBots") { P3Sim.debugBotsS },
        num("breakerRefill") { P3Sim.breakerRefillS },
        num("breakerRegen") { P3Sim.breakerRegenS },
        bool("breakerPerma") { P3Sim.breakerPermaS },
        bool("realMoves") { P3Sim.realMovesS },
        bool("lava") { P3Sim.lavaS },
        bool("p3Only") { P3Sim.p3OnlyS },
        bool("termsOnly") { P3Sim.termsOnlyS },
    ) }

    val PERSONAL: List<Opt> by lazy { listOf(
        Opt("class", { JsonPrimitive(P3Sim.ownClass.name) }, { v -> Players.classByName(v.asString)?.let { P3Sim.classS.index = Party.CLASSES.indexOf(it) } }),
        num("speed") { P3Sim.speedS },
        bool("phoenix") { P3Sim.phoenixS },
        bool("realMasks") { P3Sim.realMasksS },
        Opt("wornMask", { JsonPrimitive(P3Sim.wornMaskS.index) }, { P3Sim.wornMaskS.index = it.asInt }),
        num("ping") { P3Sim.pingS },
        bool("jitter") { P3Sim.jitterS },
        num("shortbow") { P3Sim.shortbowCooldownS },
        num("hydra") { P3Sim.hydraStartS },
        bool("mageAll") { P3Sim.mageAllS },
        bool("infinite") { P3Sim.breakerInfiniteS },
        bool("terror") { P3Sim.terrorAtTermsS },
        bool("blackCat") { P3Sim.blackCatS },
        bool("showTimes") { P3Sim.showTimesS },
    ) }

    fun snapshot(opts: List<Opt>): JsonObject = JsonObject().also { o -> opts.forEach { runCatching { o.add(it.key, it.get()) } } }

    fun apply(opts: List<Opt>, o: JsonObject) { opts.forEach { opt -> o.get(opt.key)?.let { v -> runCatching { opt.set(v) } } } }

    fun diff(was: JsonObject?, now: JsonObject): JsonObject = JsonObject().also { d -> now.entrySet().forEach { (k, v) -> if (was?.get(k) != v) d.add(k, v) } }
}

object PartyServer {
    @Volatile var rosterDirty = true
    private var lastShared: String? = null
    private var lastPlan: String? = null
    private val sentRoster = HashMap<UUID, String>()
    private var lastPerma = false
    private val lastPersonal = ConcurrentHashMap<UUID, String>()

    private fun personalOf(p: ServerPlayer): String = Sim.asPlayer(p) {
        "${P3Sim.speed}/${P3Sim.phoenix}/${P3Sim.realMasks}/${P3Sim.wornMaskS.index.takeIf { Sim.isHost(p) } ?: Players.profile(p).vals["wornMask"]}"
    }

    fun notePersonal(p: ServerPlayer) { if (SimServer.server?.isSameThread == true) lastPersonal[p.uuid] = personalOf(p) }

    private var lastOrder = ""

    fun register() {
        SimNet.onServer("hello") { p, data -> hello(p, data) }
        SimNet.onServer("profile") { p, data -> profile(p, data) }
        SimNet.onServer("shared") { p, data -> shared(p, data) }
        SimNet.onServer("plan") { p, data -> plan(p, data) }
        SimNet.onServer("act") { p, data -> SimActions.run(p, data) }
        SimNet.onServer("hit") { p, data -> SimItems.serverHit(p, data) }
        SimNet.onServer("left") { p, data -> SimItems.serverLeftClick(p, data) }
        SimNet.onServer("drop") { p, data -> SimItems.serverDrop(p, data == "1") }
    }

    private fun hello(p: ServerPlayer, data: String) {
        lastShared = null; lastPlan = null; sentRoster.remove(p.uuid); rosterDirty = true
        if (!Sim.isHost(p)) {
            val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: JsonObject()
            val prof = Players.profile(p)
            val again = prof.clazz != null
            o.getAsJsonObject("files")?.entrySet()?.forEach { (k, v) -> prof.files[k] = v.asString }
            Players.forgetFiles(p)
            o.getAsJsonObject("settings")?.entrySet()?.forEach { (k, v) -> prof.vals[k] = v }
            val want = prof.vals["class"]?.asString?.let { Players.classByName(it) }
            prof.asked = want?.name
            val got = Players.request(p, want)
            if (want != got) SimNet.send(p, "set", JsonObject().also { it.addProperty("class", got.name) }.toString())
            Party.rosterChanged()
            if (!again) { Fight.setup(p); BossBar.showTo(p) }
            val name = p.gameProfile.name
            if (!again) Sim.everyone { Sim.note("§e$name§7 joined the party as the §f${Roles.label(got)}§7.") }
            if (want != null && want != got && !again) Sim.note("${Players.playerOf(want)?.gameProfile?.name ?: "Someone"} plays the ${Roles.label(want)}, so you're the §f${Roles.label(got)}§7. Pick a class in the menu: taking someone's swaps you.")
            if (Fight.phase != null && !again) Sim.note("The run is under way: §fRestart§7 (menu or keybind) to play it from the start together.")
        }
        sendState(p)
    }

    private fun profile(p: ServerPlayer, data: String) {
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        if (Sim.isHost(p)) return
        val prof = Players.profile(p)
        o.getAsJsonObject("files")?.let { f -> f.entrySet().forEach { (k, v) -> prof.files[k] = v.asString }; Players.forgetFiles(p) }
        o.getAsJsonObject("settings")?.entrySet()?.forEach { (k, v) -> prof.vals[k] = v }
        val want = o.getAsJsonObject("settings")?.get("class")?.asString?.let { Players.classByName(it) }
        if (prof.clazz == null) return
        if (want != null && want != prof.clazz && want.name != prof.asked) Players.changeClass(p, want)
        prof.asked = want?.name
    }

    private fun shared(p: ServerPlayer, data: String) {
        if (Sim.isHost(p)) return
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        mc.execute {
            PartySettings.apply(PartySettings.SHARED, o)
            ModuleManager.saveConfigurations()
        }
        val what = o.keySet().joinToString(", ")
        if (what.isNotEmpty()) Sim.everyone { Sim.note("§f${p.gameProfile.name}§7 changed: $what") }
    }

    private fun plan(p: ServerPlayer, data: String) {
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        if (Sim.isHost(p)) return
        val prof = Players.profile(p)
        o.getAsJsonArray("mine")?.let { a -> prof.mine = a.map { it.asString }.toSet(); prof.mineFor = o.get("mineFor")?.asString }
        o.getAsJsonArray("order")?.let { a -> prof.order = a.mapNotNull { Players.classByName(it.asString) } }
        o.getAsJsonObject("stacks")?.let { st -> prof.stacks = st.entrySet().mapNotNull { (k, v) -> Players.classByName(v.asString)?.let { k to it } }.toMap() }
        o.getAsJsonObject("spots")?.let { sp ->
            prof.spots.clear()
            sp.entrySet().forEach { (k, v) ->
                val a = v.asJsonArray
                if (a.size() >= 5) prof.spots[k] = Spots.Spot(k, a[0].asDouble, a[1].asDouble, a[2].asDouble, a[3].asFloat, a[4].asFloat)
            }
        }
        o.getAsJsonObject("shared")?.let { sh -> mc.execute { P3Plan.applyShared(sh, save = true) } }
    }

    private fun sendState(p: ServerPlayer) {
        SimNet.send(p, "shared", PartySettings.snapshot(PartySettings.SHARED).toString())
        SimNet.send(p, "planShared", P3Plan.sharedJson().toString())
        roster(p, force = true)
        SimNet.send(p, "phase", phaseName())
    }

    private fun phaseName(): String = when (val ph = Fight.phase) {
        null -> ""
        is P1Maxor -> "P1"; is P2Storm -> "P2"; is P4Necron -> "P4"
        else -> "P3"
    }
    private var lastPhase = ""

    fun tick() {
        val joiners = Sim.players().filter { !Sim.isHost(it) }
        Players.tick()
        Players.refresh()
        val shared = PartySettings.snapshot(PartySettings.SHARED).toString()
        if (shared != lastShared) { lastShared = shared; joiners.forEach { SimNet.send(it, "shared", shared) } }
        val plan = P3Plan.sharedJson().toString()
        if (plan != lastPlan) { lastPlan = plan; joiners.forEach { SimNet.send(it, "planShared", plan) } }
        val ph = phaseName()
        if (ph != lastPhase) { lastPhase = ph; SimNet.sendAll("phase", ph) }
        val perma = P3Sim.breakerPerma
        if (lastPerma && !perma) Sim.forEachPlayer { SimItems.unPerma() }
        lastPerma = perma
        for (p in Sim.players()) Sim.asPlayer(p) {
            val now = personalOf(p)
            val was = lastPersonal.put(p.uuid, now)
            if (was != null && was != now) {
                val (s0, ph0, rm0, wm0) = was.split('/')
                val (s1, ph1, rm1, wm1) = now.split('/')
                if (s0 != s1 || ph0 != ph1) Fight.applySpeed(p)
                if ((rm0 != rm1 || wm0 != wm1) && !Masks.ghost) Masks.equip(p)
            }
        }
        val order = Sim.players().joinToString { p -> Party.mates(p).joinToString { it.name } }
        if (order != lastOrder) { lastOrder = order; rosterDirty = true }
        if (rosterDirty || Fight.serverTick % 20 == 0) { rosterDirty = false; Sim.players().forEach { roster(it) } }
    }

    private fun roster(p: ServerPlayer, force: Boolean = false) {
        val me = Players.classOf(p)
        val a = JsonArray()
        for (m in Party.mates(p)) a.add(JsonObject().also { it.addProperty("n", m.name); it.addProperty("c", m.clazz.name); it.addProperty("p", m.player != null) })
        val o = JsonObject().also { it.addProperty("me", me.name); it.addProperty("in", Players.hasClass(p)); it.add("team", a); it.addProperty("party", Sim.players().size > 1) }
        val s = o.toString()
        if (!force && sentRoster[p.uuid] == s) return
        sentRoster[p.uuid] = s
        SimNet.send(p, "roster", s)
    }

    fun left(p: ServerPlayer) { sentRoster.remove(p.uuid); lastPersonal.remove(p.uuid); rosterDirty = true }

    fun reset() { lastShared = null; lastPlan = null; sentRoster.clear(); lastPersonal.clear(); rosterDirty = true; lastPhase = "" }
}

object PartyClient {
    class Mate(val name: String, val clazz: DungeonClass, val player: Boolean)

    @Volatile var me: DungeonClass? = null
        private set
    @Volatile var team: List<Mate> = emptyList()
        private set
    @Volatile var party = false
        private set
    @Volatile var phase = ""
        private set

    private var helloSent = false

    private var lastProfile: String? = null
    private var lastShared: JsonObject? = null
    private var lastPlan: String? = null
    private var ownShared: JsonObject? = null
    private var gotShared = false
    private var helloTicks = 0

    val joiner: Boolean get() = P3Sim.inSim && !P3Sim.localSim

    fun register() {
        SimNet.onClient("shared") { data ->
            if (!joiner) return@onClient
            val o = JsonParser.parseString(data).asJsonObject
            if (!gotShared) { gotShared = true; ownShared = PartySettings.snapshot(PartySettings.SHARED).also { keepOwn(it) } }
            PartySettings.apply(PartySettings.SHARED, o)
            lastShared = PartySettings.snapshot(PartySettings.SHARED)
        }
        SimNet.onClient("planShared") { data -> if (joiner) P3Plan.receiveShared(JsonParser.parseString(data).asJsonObject) }
        SimNet.onClient("set") { data ->
            PartySettings.apply(PartySettings.PERSONAL, JsonParser.parseString(data).asJsonObject)
            lastProfile = null
            ModuleManager.saveConfigurations()
        }
        SimNet.onClient("roster") { data ->
            val o = JsonParser.parseString(data).asJsonObject
            me = if (o.get("in")?.asBoolean != false) Players.classByName(o.get("me")?.asString) else null
            team = o.getAsJsonArray("team").mapNotNull { e -> val m = e.asJsonObject; Players.classByName(m.get("c").asString)?.let { Mate(m.get("n").asString, it, m.get("p").asBoolean) } }
            party = o.get("party")?.asBoolean == true
        }
        SimNet.onClient("phase") { phase = it }
        SimNet.onClient("fx") { fx(it) }
        SimNet.onClient("file") { data ->
            val o = JsonParser.parseString(data).asJsonObject
            PlayerFiles.writeLocal(o.get("name").asString, o.get("text").asString)
            HotbarLayout.forgetLocal(); Loadouts.forgetLocal()
        }
        SimNet.onClient("say") { EngineerClient.msg(it) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> mc.execute { left() } }
    }

    private var restoredOwn = false

    private fun restoreOwn() {
        restoredOwn = true
        EngineerClient.safely("p3sim own settings restore") {
            if (!ownFile.exists()) return@safely
            runCatching { PartySettings.apply(PartySettings.SHARED, JsonParser.parseString(ownFile.readText()).asJsonObject) }
            ModuleManager.saveConfigurations()
            ownFile.delete()
        }
    }

    private val ownFile get() = File(mc.gameDirectory, "config/adm/p3sim-own-settings.json")
    private fun keepOwn(o: JsonObject) = EngineerClient.safely("p3sim own settings keep") { ownFile.parentFile.mkdirs(); ownFile.writeText(o.toString()) }

    private fun fx(what: String) {
        val o = runCatching { JsonParser.parseString(what).asJsonObject }.getOrNull() ?: return
        val n = o.get("n")?.asInt ?: 0
        when (o.get("fx")?.asString) {
            "splitsStart" -> {
                val split = o.get("split")?.asString ?: return
                EngineerClient.safely("p3sim splits") { DungeonSplits.simStart(split, n) }
                EngineerClient.safely("p3sim arrows device") { Fight.resetArrowsDevice() }
            }
            "splitsStop" -> {
                EngineerClient.safely("p3sim splits stop") { DungeonSplits.simStop() }
                EngineerClient.safely("p3sim arrows device") { Fight.resetArrowsDevice() }
                EngineerClient.safely("p3sim tick timers stop") { OdinTickTimers.stopAll() }
            }
            "masksReset" -> EngineerClient.safely("p3sim masks") { OdinMasksUsed.resetTimers() }
            "arrowsReset" -> EngineerClient.safely("p3sim arrows device") { Fight.resetArrowsDevice() }
            "termStart" -> EngineerClient.safely("p3sim term info") { TermInfo.simStart(n) }
            "termClear" -> EngineerClient.safely("p3sim term info") { TermInfo.simClearTime() }
            "sectionTime" -> EngineerClient.safely("p3sim term info") { TermInfo.simSectionTime(n) }
        }
    }

    fun send(fx: String, n: Int = 0, split: String? = null, to: ServerPlayer? = null) {
        val o = JsonObject().also { it.addProperty("fx", fx); it.addProperty("n", n); split?.let { s -> it.addProperty("split", s) } }.toString()
        if (to != null) SimNet.send(to, "fx", o) else SimNet.sendAll("fx", o)
    }

    fun tick() {
        if (!restoredOwn && !P3Sim.inSim) restoreOwn()
        if (!P3Sim.inSim || mc.player == null) { if (helloSent) left(); return }
        if (!helloSent) {
            helloSent = true
            SimNet.send("hello", profileJson(withFiles = true))
            lastProfile = profileJson(withFiles = false)
            if (joiner) sendPlan(force = true)
            helloTicks = 0
            return
        }
        if (me == null && ++helloTicks % 100 == 0) { SimNet.send("hello", profileJson(withFiles = true)); if (joiner) sendPlan(force = true) }
        val prof = profileJson(withFiles = false)
        if (prof != lastProfile) { lastProfile = prof; SimNet.send("profile", prof) }
        if (joiner && gotShared) {
            val now = PartySettings.snapshot(PartySettings.SHARED)
            val d = PartySettings.diff(lastShared, now)
            if (d.size() > 0) { lastShared = now; SimNet.send("shared", d.toString()) }
            sendPlan()
        }
    }

    private fun profileJson(withFiles: Boolean): String {
        val o = JsonObject()
        o.add("settings", PartySettings.snapshot(PartySettings.PERSONAL))
        if (withFiles) o.add("files", JsonObject().also { f -> PlayerFiles.NAMES.forEach { n -> PlayerFiles.readLocal(n)?.let { f.addProperty(n, it) } } })
        return o.toString()
    }

    private fun sendPlan(force: Boolean = false) {
        val j = P3Plan.partyJson().toString()
        if (!force && j == lastPlan) return
        lastPlan = j
        SimNet.send("plan", j)
    }

    private fun left() {
        if (gotShared) ownShared?.let {
            PartySettings.apply(PartySettings.SHARED, it)
            EngineerClient.safely("p3sim own settings back") { ModuleManager.saveConfigurations() }
            ownFile.delete()
        }
        if (helloSent && !P3Sim.localSim) P3Plan.leaveParty()
        helloSent = false; gotShared = false; ownShared = null; lastShared = null; lastProfile = null; lastPlan = null
        team = emptyList(); me = null; party = false; phase = ""
    }
}

object PlayerFiles {
    const val HOTBAR = "p3sim-hotbar.json"
    const val LOADOUTS = "p3sim-loadouts.json"
    val NAMES = listOf(HOTBAR, LOADOUTS)

    private fun file(name: String) = File(Minecraft.getInstance().gameDirectory, "config/adm/$name")

    fun readLocal(name: String): String? = runCatching { file(name).takeIf { it.exists() }?.readText() }.getOrNull()

    fun writeLocal(name: String, text: String) {
        if (name !in NAMES) return
        EngineerClient.safely("p3sim file $name") { writeAtomic(file(name), text) }
    }

    fun setAsideLocal(name: String) { if (owner() == null) setAside(file(name)) }

    private val setAside = HashSet<String>()

    fun setAside(f: File) {
        if (!f.exists() || !setAside.add(f.path)) return
        runCatching { f.copyTo(File(f.path + ".corrupt"), overwrite = true) }
            .onSuccess { EngineerClient.logger.warn("[ec] p3sim: ${f.name} couldn't be read, kept as ${it.name}") }
    }

    fun writeAtomic(f: File, text: String) {
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun read(name: String): String? {
        val p = Sim.acting?.takeIf { !Sim.isHost(it) } ?: return readLocal(name)
        return Players.profile(p).files[name]
    }

    fun write(name: String, text: String) {
        val p = Sim.acting?.takeIf { !Sim.isHost(it) } ?: return writeLocal(name, text)
        Players.profile(p).files[name] = text
        SimNet.send(p, "file", JsonObject().also { it.addProperty("name", name); it.addProperty("text", text) }.toString())
    }

    fun owner(): UUID? = Sim.acting?.takeIf { !Sim.isHost(it) }?.uuid
}

object SimActions {
    fun send(action: String, vararg args: Pair<String, Any?>) {
        val o = JsonObject()
        o.addProperty("a", action)
        for ((k, v) in args) when (v) {
            null -> {}
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            else -> o.addProperty(k, v.toString())
        }
        SimNet.send("act", o.toString())
    }

    fun run(p: ServerPlayer, data: String) {
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
        fun d(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asDouble
        fun b(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asBoolean == true
        val who = p.gameProfile.name
        when (s("a")) {
            "start" -> {
                val what = runCatching { Fight.Start.valueOf(s("what") ?: "P3") }.getOrNull() ?: return
                if (!Sim.isHost(p)) Sim.everyone { Sim.note("§f$who§7 started §f${what.label}§7.") }
                Fight.start(what)
            }
            "restart" -> {
                if (Practice.active) { if (Sim.isHost(p)) Practice.restart() else Sim.note("Practice is the host's: their start, jobs and checkpoints.") }
                else {
                    if (!Sim.isHost(p)) Sim.everyone { Sim.note("§f$who§7 restarted.") }
                    Fight.start(Fight.lastStart)
                }
            }
            "practiceRestart" -> if (Sim.isHost(p)) Practice.restart() else Sim.note("Practice is the host's: their start, jobs and checkpoints.")
            "stop" -> {
                if (!Sim.isHost(p)) Sim.everyone { Sim.note("§f$who§7 stopped the run.") }
                Fight.end()
            }
            "tp" -> {
                if (Sim.isHost(p)) GhostCapture.invalidate("a menu teleport")
                Sim.tp(p, d("x") ?: return, d("y") ?: return, d("z") ?: return, d("yaw")?.toFloat(), d("pitch")?.toFloat())
            }
            "hotbarSave" -> Sim.chat("§8[§6ADS§8] " + HotbarLayout.save(p, b("p3")))
            "hotbarReset" -> { Sim.chat("§8[§6ADS§8] " + HotbarLayout.reset(b("p3"))); SimItems.giveHotbar(p, b("p3"), start = true) }
            "resetItems" -> { HotbarLayout.dropLive(b("p3")); SimItems.giveHotbar(p, b("p3"), start = true) }
            "practiceCustom", "practicePreset" -> if (!Sim.isHost(p)) Sim.note("Practice is the host's: their start, jobs and checkpoints.")
                else if (s("a") == "practiceCustom") Practice.startCustom() else Practice.startPreset(s("name") ?: return)
        }
    }
}
