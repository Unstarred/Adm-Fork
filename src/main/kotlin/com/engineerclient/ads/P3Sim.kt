package com.engineerclient.ads

import com.engineerclient.util.noFormatting
import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.bots.GhostGuard
import com.engineerclient.ads.bots.Party
import com.engineerclient.ads.core.LagBurst
import com.engineerclient.ads.core.LagPreset
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.core.SimServer
import com.engineerclient.ads.core.SimWorld
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.Terminals
import com.engineerclient.ads.items.Loadouts
import com.engineerclient.ads.items.Masks
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.party.P3Party
import com.engineerclient.ads.party.PartyClient
import com.engineerclient.ads.party.PartyServer
import com.engineerclient.ads.party.Players
import com.engineerclient.ads.party.SimActions
import com.engineerclient.ads.party.SimNet
import com.engineerclient.ads.plan.P3Plan
import com.engineerclient.ads.plan.Practice
import com.engineerclient.ads.ui.SimRestartScreen
import com.engineerclient.features.misc.RandomStuff
import com.engineerclient.index
import com.engineerclient.util.setting
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.render.HidePlayers
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.Locale

object P3Sim : Module(
    name = "ADS",
    category = Category.custom("ADM", 860, 10),
    description = "Advanced Dungeon Simulator: F7's boss in a singleplayer world of its own. /ads (or the title screen button) opens it. Only ever active in that world.",
    key = null,
) {
    const val NAME = "ADS"
    const val FULL_NAME = "Advanced Dungeon Simulator"

    val menuKey by KeybindSetting("Menu Keybind", InputConstants.UNKNOWN, "Opens the ADS menu (a big Restart) in the sim world, as /ads and the SkyBlock Menu star in your hotbar do; the full menu is in Esc. Outside it, opens the sim.").onPress { openMenuOrSim() }
    val restartKey by KeybindSetting("Restart Keybind", InputConstants.UNKNOWN, "In the sim: starts whatever you last started again (P3, S2, P2...), from scratch; in practice, the practice.").onPress {
        if (inSim) SimActions.send("restart")
    }
    val practiceKey by KeybindSetting("Practice Restart Keybind", InputConstants.UNKNOWN, "In the sim: starts your practice (the menu's Practice tab) again. A left click with the Infinileap does too.").onPress {
        if (inSim) SimActions.send("practiceRestart")
    }
    enum class ClassOption { HEALER, BERSERK, ARCHER, TANK, MAGE }
    enum class DeathTickOption { OFF, WARN, MASKS }
    enum class TerminalOption { RANDOM, ORDER, PANES, RUBIX, STARTS_WITH, SELECT, MELODY }
    enum class MaskOption { SPIRIT, BONZO }

    val classS = +SelectorSetting("Your Class", ClassOption.ARCHER, desc = "Your dungeon class (Odin's party list and leap menu). The four bots are the other classes. What you do in P3 is the menu's Plan tab.")
    val speedS = +NumberSetting("Speed", 500, 100..750, 10, desc = "Your Skyblock speed without Black Cat. Black Cat adds 100 (and 100 to the speed cap), so 450 is 550 with it out, as in recorded P3 starts (1.55 blocks a tick sprinting); Phoenix out has no Black Cat bonus.")
    val botsS = +BooleanSetting("Party Bots", true, desc = "The rest of the party's terminals, levers, devices and gates are always done at the plan's times; on, four bots show it (a late one is put on its job). Off: nobody's shown, the plan still runs.")
    val deathTicksS = +SelectorSetting("Death Ticks", DeathTickOption.MASKS, desc = "Goldor's death tick (every 60 ticks, hits anyone in a section ahead): Warn only says so; Masks uses your Spirit Mask, Bonzo's Mask and Phoenix as Hypixel does, and with none left you die (back to the section's start).")
    val terminalS = +SelectorSetting("Terminals", TerminalOption.RANDOM, desc = "Every terminal as this type, or random as on Hypixel.")
    val pingS = +NumberSetting("Simulated Ping", 0, 0..300, 10, unit = "ms", desc = "Delays the server's answer to your clicks and items by this much, like playing on Hypixel with that ping.")
    val jitterS = +BooleanSetting("Ping Jitter", true, desc = "Simulated Ping varies like a real connection: usually a few ms either way, now and then 30+ ms more, rarely a lag spike (spread recorded on Hypixel). Off: a fixed delay.")
    val goldorKillS = +NumberSetting("Goldor Kill Time", 20, 10..120, 1, unit = " ticks", desc = "How long after Goldor starts flying to the core his \"....\" line comes, i.e. he dies (recorded runs: median 43, range 17-74).")
    val shortbowCooldownS = +NumberSetting("Shortbow Shot Cooldown", 5, 1..20, 1, unit = " ticks", desc = "Ticks between shots of the Terminator, Spirit Shortbow and Mosquito Shortbow: 5 with Terror armor's attack speed, 7 without (recordings: 654 shots at 5, 21 at 7). A click inside it fires when it ends. Nasty Bite has its own 10.")
    val hydraStartS = +NumberSetting("Hydra Stacks At Start", 10, 0..10, 1, desc = "Hydra Strike stacks every start from the menu (P1, P3, a section...) begins with. Going on from one phase to the next keeps what you have.")
    val clickLimitS = +BooleanSetting("Terminal Click Limit", true, desc = "As on Hypixel: a terminal takes at most 5 clicks in any 500 ms; the rest are dropped without an answer.")
    val noMelodiesS = +BooleanSetting("No Melodies", true, desc = "Random terminals are never melodies.")
    val termLuckS = +NumberSetting("Terminal Luck", 0, -100..100, 5, unit = "%", desc = "0: terminals as Hypixel deals them. Higher: Click in Order's numbers closer together (a neat path at 100%), and fewer clicks in the others (more panes already on, fewer Rubix clicks, fewer items to pick); lower (bad luck): the other way. Toward 40% either way it picks Hypixel's easiest (or hardest) deals; past it, beyond anything Hypixel deals, down to 1 click at 100% and up to the worst at -100% (no pane on, a 10-click Rubix, 16 items to pick, 12 to select, the numbers all over). Melody: each row's purple where the green gets soonest (latest with bad luck). The terminal type: Melodies rarer (more common with bad luck), when they're on.")
    val ssLuckS = +NumberSetting("SS Luck", 0, -100..100, 5, unit = "%", desc = "0: Simon Says as Hypixel runs it. Higher: the 4 lamps closer together, steadily all the way up (4 neighbours in a line at 100%), and after a wrong press the quick restart (no stray lamp) more often, always at 100%. Lower (bad luck): the lamps spread across the board (corner to corner at -100%) and the slow restart, with a stray lamp.")
    val mageAllS = +BooleanSetting("Mage Beam On All Classes", false, desc = "Mage Beam (a left click with a sword or the Bonzo Staff) and Guided Sheep whatever your class. Ctrl+Q fires the sheep (as Archer it stays Explosive Shot: 3 quick beam clicks fire the sheep).")
    val firstClickMelodyS = +BooleanSetting("Always First Click Melody", false, desc = "Melody's first row always has its purple in the first slot, where the green starts: lock it at once. The other rows are as usual.")
    val recordS = +BooleanSetting("Record Runs", false, desc = "Writes each run, tick by tick (you, the bots, what's left, chat), to config/adm/p3sim-runs, to look at what went wrong. After 50 runs in a row it turns itself off (nothing's deleted); turn it back on for 50 more.")
    val debugBotsS = +BooleanSetting("Debug Bots", false, desc = "Chat lines for everything the P3 bots do: where they head and why, jobs, leaps, early enters (on the spot, who they wait for, why they move on).")
    val breakerRefillS = +NumberSetting("Dungeonbreaker Refill", 3, 1..10, 1, unit = "/s", desc = "Charges back each second (20 max), in irregular +2 steps. Main server: ~6 a second; alpha ~2.")
    val breakerRegenS = +NumberSetting("Dungeonbreaker Regen", 11.0, 1.0..30.0, 0.5, unit = "s", desc = "How long a broken block stays broken (recordings: ~11 s; the 21st break brings back the oldest 41 ticks later).")
    val breakerInfiniteS = +BooleanSetting("Infinite Breaker Charges", false, desc = "The Dungeonbreaker never runs out: every break is free (20 charges always).")
    val breakerPermaS = +BooleanSetting("Perma Break", false, desc = "Blocks the Dungeonbreaker breaks stay broken (through restarts too) until this is turned off; then they all come back.")
    val realMasksS = +BooleanSetting("Real Masks", true, desc = "Masks are real helmets: only the one you wear can save you, swap them in /stats (cooldowns stay with each mask). Off: whichever is ready saves you.")
    val wornMaskS = +SelectorSetting("Starting Mask", MaskOption.SPIRIT, desc = "Real Masks: the mask you wear (/stats swaps it).")
    val phoenixS = +BooleanSetting("Phoenix Pet", false, desc = "Your pet: Phoenix (saves you from a death, no Black Cat speed bonus) or Black Cat (+100 speed). The Pet Rod swaps them.")
    val realMovesS = +BooleanSetting("Real Bot Movement", true, desc = "P3 bots move as you did: each plays your best run as its class for the skill (P3 from S1), timed so its jobs are done at the plan's times (faster where the plan's tighter). A class with no run of yours: along routes recorded in ADR runs. Off: the old straight walk and Hyperion blinks.")
    val terrorAtTermsS = +BooleanSetting("Terror At Terms", false, desc = "Every P3 start puts the Terror loadout on (Terror armour, Bonzo's Mask, Black Cat) over your saved gear.")
    val blackCatS = +BooleanSetting("Black Cat At Start", true, desc = "Every start and restart puts Black Cat out. Off: the pet you had stays (as your items and gear do).")
    val lavaS = +BooleanSetting("Lava Bounce", true, desc = "Lava bounces you up as on Hypixel. Off: plain vanilla lava (no damage).")
    val termsOnlyS = +BooleanSetting("Stop After Terms", false, desc = "The run stops (as Stop does) when the core opens: no Goldor fight.")
    val p3OnlyS = +BooleanSetting("Stop After Goldor", false, desc = "The run stops (as Stop does) when Necron would speak, after Goldor's death, instead of going on to Necron.")
    val autoStartS = +BooleanSetting("Start On Join", false, desc = "Start P3 as soon as you join the sim world.")
    val lagPresetS = +SelectorSetting("Lag Preset", LagPreset.OFF, desc = "Makes the sim's server lose ticks the way Hypixel's does: it freezes for a moment, then runs a burst to catch up, and part of the lost time never comes back. Good, Typical and Bad lose 14.5, 30.3 and 56.6 ticks a minute (the measured 10th, 50th and 90th percentile of F7 runs); High slot is an instance slot above 200 (about 1.8 times Typical).")
    val lagBurstS = +NumberSetting("Lag Burst Rate", LagBurst.DEFAULT_RATE, 1.0..6.0, 0.5, desc = "How many times faster than normal the server runs the ticks it catches up after a freeze. Not measured on Hypixel (only how many ticks come back is), so this is a guess; 2 is the default.")
    val showTimesS = +BooleanSetting("Section Times", true, desc = "Each section's time in chat as it ends, and a summary at the core.")

    private val practiceHud by HUD("Practice Splits", "In practice (the menu's Practice tab): the time, and each of your tasks with the time it was done.", true, 10, 210, 1.5f) { example ->
        val lines = if (example) listOf("§6Practice S2 §f6.45", "§7Lights §a2.10", "§7T3 §a4.85", "§7EE3 §e...")
        else {
            val mode = Practice.mode
            if (!inSim || mode == null || Practice.ticks < 0 || Practice.tasks.isEmpty()) return@HUD 0 to 0
            val next = Practice.tasks.firstOrNull { it.at < 0 }
            listOf("§6Practice $mode ${if (Practice.endTicks >= 0) "§a" else "§f"}${Practice.secs(Practice.ticks)}") +
                Practice.tasks.map { t -> "§7${t.label} " + if (t.at >= 0) "§a${Practice.secs(t.at)}" else if (t === next) "§e..." else "§8-" }
        }
        lines.forEachIndexed { i, l -> text(l, 0, i * 10, Colors.WHITE, shadow = true) }
        (lines.maxOf { mc.font.width(it) }) to lines.size * 10
    }

    private val practiceTimeHud by HUD("Practice Time", "A practice's final time, large (as Term Info's Section Time), for 4 seconds.", true, 420, 300, 5f) { example ->
        val t = if (example) "§514.35" else Practice.endTicks.takeIf { inSim && Practice.active && it >= 0 && System.currentTimeMillis() - Practice.endMs < 4000 }?.let { "§5${Practice.secs(it)}" } ?: return@HUD 0 to 0
        text(t, 0, 0, Colors.WHITE, shadow = true)
        mc.font.width(t) to 10
    }

    private val practiceStartHud by HUD("Practice Start Timer", "A practice's start timer counting down, large, until terminals and the rest are up.", true, 440, 250, 4f) { example ->
        val t = if (example) "§e2.4" else Practice.ticks.takeIf { inSim && Practice.active && it < 0 }?.let { "§e${"%.1f".format(java.util.Locale.ROOT, -it / 20.0)}" } ?: return@HUD 0 to 0
        text(t, 0, 0, Colors.WHITE, shadow = true)
        mc.font.width(t) to 10
    }

    init {
        practiceHud.enabled = true; practiceTimeHud.enabled = true; practiceStartHud.enabled = true
    }

    val autoStart: Boolean get() = autoStartS.value
    val showTimes: Boolean get() = mine("showTimes", { showTimesS.value }) { it.asBoolean }

    private inline fun <T> mine(key: String, own: () -> T, read: (JsonElement) -> T): T =
        Players.joiner()?.vals?.get(key)?.let { runCatching { read(it) }.getOrNull() } ?: own()

    val ownClass: DungeonClass get() = Party.CLASSES[classS.index.coerceIn(0, 4)]
    val myClass: DungeonClass get() = Sim.acting?.takeIf { !Sim.isHost(it) }?.let { Players.classOf(it) } ?: ownClass
    val speed: Int get() = mine("speed", { speedS.value.toInt() }) { it.asDouble.toInt() }
    val bots: Boolean get() = botsS.value
    val deathTicks: Int get() = deathTicksS.index
    val ping: Int get() {
        val v = mine("ping", { pingS.value.toInt() }) { it.asDouble.toInt() }
        val j = Sim.acting?.takeIf { Players.joiner() != null } ?: return v
        return (v - j.connection.latency()).coerceAtLeast(0)
    }
    val jitter: Boolean get() = mine("jitter", { jitterS.value }) { it.asBoolean }
    val goldorKill: Int get() = goldorKillS.value.toInt()
    val p3Only: Boolean get() = p3OnlyS.value
    val termsOnly: Boolean get() = (termsOnlyS as BooleanSetting?)?.value == true

    fun toggleStopAfter(terms: Boolean) {
        val (it, other) = if (terms) (termsOnlyS as BooleanSetting?) to p3OnlyS else p3OnlyS to (termsOnlyS as BooleanSetting?)
        it ?: return
        it.value = !it.value
        if (it.value) other?.value = false
    }
    val shortbowCooldown: Int get() = mine("shortbow", { shortbowCooldownS.value.toInt() }) { it.asDouble.toInt() }
    val terrorPieces: Int get() = Sim.player?.let { p ->
        listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET)
            .count { SimItems.idOf(p.getItemBySlot(it))?.startsWith("TERROR_") == true }
    } ?: 0
    val hydraStart: Int get() = mine("hydra", { hydraStartS.value.toInt() }) { it.asDouble.toInt() }
    val lava: Boolean get() = lavaS.value
    val noMelodies: Boolean get() = noMelodiesS.value
    val ssLuck: Double get() = ((ssLuckS as NumberSetting<*>?)?.value?.toDouble() ?: 0.0) / 100.0
    val termLuck: Double get() = ((termLuckS as NumberSetting<*>?)?.value?.toDouble() ?: 0.0) / 100.0
    val mageAll: Boolean get() = mine("mageAll", { (mageAllS as BooleanSetting?)?.value == true }) { it.asBoolean }
    val firstClickMelody: Boolean get() = (firstClickMelodyS as BooleanSetting?)?.value == true
    val clickLimit: Boolean get() = clickLimitS.value
    val debugBots: Boolean get() = debugBotsS.value
    val record: Boolean get() = recordS.value

    val hidePlayers: Boolean get() = HidePlayers.enabled
    fun toggleHidePlayers() = HidePlayers.toggle()

    @JvmStatic
    fun hideBot(e: Entity): Boolean {
        if (e !is Mannequin || !hidePlayers || !inSim) return false
        val hp = HidePlayers
        if (hp.setting<BooleanSetting>("Hide all")?.value == true) return true
        val d = hp.setting<NumberSetting<*>>("Distance")?.value?.toDouble() ?: 3.0
        val me = mc.player ?: return false
        return e.distanceToSqr(me) <= d * d
    }

    fun botNamed(name: String): Entity? {
        if (!inSim) return null
        val level = mc.level ?: return null
        botByName[name]?.takeIf { !it.isRemoved && it.level() === level }?.let { return it }
        return level.entitiesForRendering().firstOrNull { it is Mannequin && !it.isRemoved && it.customName?.string?.noFormatting() == name }
            ?.also { botByName[name] = it }
    }

    private val botByName = HashMap<String, Entity>()

    val breakerRefill: Int get() = breakerRefillS.value.toInt()
    val breakerRegen: Double get() = breakerRegenS.value.toDouble()
    val breakerInfinite: Boolean get() = mine("infinite", { (breakerInfiniteS as BooleanSetting?)?.value == true }) { it.asBoolean }
    val breakerPerma: Boolean get() = (breakerPermaS as BooleanSetting?)?.value == true
    fun toggleBreakerInfinite() { (breakerInfiniteS as BooleanSetting?)?.let { it.value = !it.value } }
    fun toggleBreakerPerma() { (breakerPermaS as BooleanSetting?)?.let { it.value = !it.value } }
    val realMasks: Boolean get() = mine("realMasks", { realMasksS.value }) { it.asBoolean }
    val phoenix: Boolean get() = mine("phoenix", { phoenixS.value }) { it.asBoolean }
    val wornMask: Int get() = mine("wornMask", { wornMaskS.index }) { it.asInt }

    fun setPhoenix(on: Boolean) = setMine("phoenix", JsonPrimitive(on)) { phoenixS.value = on }
    fun setWornMask(i: Int) = setMine("wornMask", JsonPrimitive(i)) { wornMaskS.index = i }

    private fun setMine(key: String, v: JsonElement, own: () -> Unit) {
        val p = Sim.acting?.takeIf { !Sim.isHost(it) }
        if (p == null) { own(); Sim.player?.let { PartyServer.notePersonal(it) }; return }
        Players.profile(p).vals[key] = v
        SimNet.send(p, "set", JsonObject().also { it.add(key, v) }.toString())
        PartyServer.notePersonal(p)
    }
    val realMoves: Boolean get() = (realMovesS as BooleanSetting?)?.value != false
    fun toggleRealMoves() { (realMovesS as BooleanSetting?)?.let { it.value = !it.value } }
    val terrorAtTerms: Boolean get() = mine("terror", { (terrorAtTermsS as BooleanSetting?)?.value == true }) { it.asBoolean }
    fun toggleTerrorAtTerms() { (terrorAtTermsS as BooleanSetting?)?.let { it.value = !it.value } }
    val blackCatAtStart: Boolean get() = mine("blackCat", { (blackCatS as BooleanSetting?)?.value != false }) { it.asBoolean }
    fun toggleBlackCatAtStart() { (blackCatS as BooleanSetting?)?.let { it.value = !it.value } }
    val forcedTerminal: Terminals.Type? get() = terminalS.index.let { if (it == 0) null else Terminals.Type.entries[it - 1] }

    @JvmStatic
    val localSim: Boolean
        get() {
            val s = SimServer.server ?: return false
            return mc.hasSingleplayerServer() && mc.singleplayerServer === s && mc.level != null
        }

    @JvmStatic
    val inSim: Boolean
        get() = localSim || (P3Party.joined && mc.level != null)

    fun init() {
        SimNet.register()
        PartyServer.register()
        PartyClient.register()
        SimServer.register()
        P3Plan.load()
        Practice.load()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("stats")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Sim.asPlayer(p) { Masks.openStats(p) } }; 1 })
            dispatcher.register(Commands.literal("loadouts")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Sim.asPlayer(p) { Loadouts.open(p) } }; 1 })
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in listOf("ads", "p3sim")) {
                val cmd = ClientCommands.literal(name).executes { openMenuOrSim(); 1 }
                for (s in Fight.Start.entries) cmd.then(ClientCommands.literal(s.name.lowercase()).executes {
                    if (inSim) SimActions.send("start", "what" to s.name) else SimWorld.open(); 1
                })
                cmd.then(ClientCommands.literal("stop").executes { SimActions.send("stop"); 1 })
                cmd.then(ClientCommands.literal("rebuild").executes { SimWorld.rebuild(); 1 })
                dispatcher.register(cmd)
            }
            dispatcher.register(ClientCommands.literal("pos").executes { pos(); 1 })
        }
        EngineerClient.safely("ads join hook") {
            ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
                if (inSim) EngineerClient.safely("ads join area") { setArea(Island.Dungeon); DungeonListener.floor = Floor.F7 }
            }
        }
        ClientTickEvents.START_CLIENT_TICK.register {
            EngineerClient.safely("p3sim bridge") { bridge() }
            EngineerClient.safely("p3sim client items") { SimItems.clientTick() }
            EngineerClient.safely("p3sim party client") { PartyClient.tick() }
            EngineerClient.safely("p3sim ghost guard") { GhostGuard.clientTick() }
        }
        ScreenEvents.AFTER_INIT.register { _, screen, w, _ -> afterScreenInit(screen, w) }
    }

    private fun afterScreenInit(screen: Screen, w: Int) {
        if (screen is TitleScreen) EngineerClient.safely("ads title button") {
            val widgets = Screens.getWidgets(screen)
            fun key(b: Button) = (b.message.contents as? TranslatableContents)?.key
            val buttons = widgets.filterIsInstance<Button>()
            val single = buttons.firstOrNull { key(it) == "menu.singleplayer" }
            val ads = Button.builder(Component.literal(FULL_NAME)) { SimWorld.open() }.let {
                if (single != null) it.bounds(single.x, single.y - single.height - 4, single.width, single.height) else it.bounds(w - 164, 4, 160, 16)
            }.build()
            widgets.add(ads)
            val realms = buttons.firstOrNull { key(it) == "menu.online" } ?: return@safely
            widgets.remove(realms)
            widgets.add(Button.builder(Component.literal("Join Hypixel")) { RandomStuff.joinHypixel(screen) }
                .bounds(realms.x, realms.y, realms.width, realms.height).build())
        }
        if (screen is PauseScreen && inSim) EngineerClient.safely("p3sim pause button") {
            val widgets = Screens.getWidgets(screen)
            val buttons = widgets.filterIsInstance<Button>()
            fun key(b: Button) = (b.message.contents as? TranslatableContents)?.key
            val lan = buttons.firstOrNull { key(it) in LAN_KEYS }
            val quit = buttons.firstOrNull { key(it) in QUIT_KEYS } ?: buttons.maxByOrNull { it.y }
            val main = Button.builder(Component.literal("§6$NAME Menu")) { mc.gui.setScreen(SimRestartScreen()) }
            val b = when {
                lan != null -> { widgets.remove(lan); main.bounds(lan.x, lan.y, lan.width, lan.height) }
                quit != null -> main.bounds(quit.x, quit.y + quit.height + 4, quit.width, 20)
                else -> main.bounds(4, 4, 110, 20)
            }.build()
            widgets.add(b)
            if (lan != null || quit != null) {
                val dy = screen.height / 2 - (b.y + b.height / 2)
                for (w in widgets) w.y += dy
            }
        }
    }

    private val QUIT_KEYS = setOf("menu.returnToMenu", "menu.disconnect")
    private val LAN_KEYS = setOf("menu.shareToLan", "menu.multiplayerOptions.button")

    private fun pos() {
        val p = mc.player ?: return
        val f = { v: Double -> String.format(Locale.ROOT, "%.2f", v) }
        val y = Math.floor(p.y * 100 + 1e-6) / 100
        var line = "${f(p.x)}, ${f(y)}, ${f(p.z)}, yaw ${f(net.minecraft.util.Mth.wrapDegrees(p.yRot).toDouble())}, pitch ${f(p.xRot.toDouble())}"
        (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos?.let { b ->
            line += ", looking at ${b.x}, ${b.y}, ${b.z}"
        }
        mc.keyboardHandler.clipboard = line
        EngineerClient.msg("§f$line §8(copied)")
    }

    fun openMenuOrSim() {
        if (inSim) mc.execute { mc.gui.setScreen(SimRestartScreen()) } else SimWorld.open()
    }

    private var bridged = false

    private fun bridge() {
        if (!inSim) {
            if (bridged) { bridged = false; unbridge() }
            return
        }
        bridged = true
        if (!enabled) toggle()
        val me = mc.player?.name?.string ?: return
        setArea(Island.Dungeon)
        DungeonListener.floor = Floor.F7
        DungeonListener.inBoss = true
        val sent = PartyClient.team.takeIf { PartyClient.me != null && it.isNotEmpty() } ?: return
        val names = sent.joinToString { "${it.name}/${it.clazz}" }
        val mine = PartyClient.me ?: return
        if (DungeonListener.dungeonTeammates.size != 1 + sent.size || DungeonListener.dungeonTeammates.none { it.name == me } || teamClass != mine.ordinal || roster != names) {
            teamClass = mine.ordinal
            roster = names
            val team = arrayListOf(DungeonPlayer(me, mine, 50, mc.player?.skin))
            sent.forEach { m ->
                val skin = if (m.player) mc.level?.players()?.firstOrNull { it.gameProfile.name == m.name }?.let { (it as? AbstractClientPlayer)?.skin } else null
                team += DungeonPlayer(m.name, m.clazz, 50, skin)
            }

            val others = team.filter { it.name != me }
            DungeonListener.dungeonTeammates = team
            DungeonListener.dungeonTeammatesNoSelf = others
            DungeonListener.leapTeammates = others
        }
    }

    private var teamClass = -1
    private var roster = ""

    private fun unbridge() {
        teamClass = -1
        DungeonListener.floor = null
        DungeonListener.inBoss = false
        DungeonListener.dungeonTeammates = arrayListOf()
        DungeonListener.dungeonTeammatesNoSelf = emptyList()
        DungeonListener.leapTeammates = emptyList()
        setArea(Island.Unknown)
    }

    private val areaField = runCatching { LocationUtils::class.java.getDeclaredField("currentArea").apply { isAccessible = true } }.getOrNull()
    private val skyblockField = runCatching { LocationUtils::class.java.getDeclaredField("isInSkyblock").apply { isAccessible = true } }.getOrNull()

    private fun setArea(area: Island) {
        val areaField = areaField ?: return
        val skyblockField = skyblockField ?: return
        if (LocationUtils.currentArea != area) areaField.set(null, area)
        val skyblock = area == Island.Dungeon || area == Island.SinglePlayer
        if (skyblockField.getBoolean(null) != skyblock) skyblockField.setBoolean(null, skyblock)
    }
}
