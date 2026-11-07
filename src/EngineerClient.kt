package com.engineerclient

import com.engineerclient.adr.ADR
import com.engineerclient.adr.OnlineLink
import com.engineerclient.clearbreakdown.ClearBreakdown
import com.engineerclient.debug.MemReport
import com.engineerclient.lcm.LcmMapInfo
import com.engineerclient.lcm.LcmStyle
import com.engineerclient.mappred.OdinMapPrediction
import com.engineerclient.misc.AgroSphere
import com.engineerclient.misc.HealthMana
import com.engineerclient.misc.I4Complete
import com.engineerclient.misc.LaunchStats
import com.engineerclient.misc.LcmMeleeWarning
import com.engineerclient.misc.LeapOrder
import com.engineerclient.misc.OdinClickLimit
import com.engineerclient.misc.OdinHighlightLook
import com.engineerclient.misc.OdinMasksUsed
import com.engineerclient.misc.OdinTickTimers
import com.engineerclient.misc.RandomStuff
import com.engineerclient.misc.Timers
import com.engineerclient.misc.Witherborn
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.pov.PovPreviews
import com.engineerclient.practice.InfNumbersSim
import com.engineerclient.practice.OdinSimonSays
import com.engineerclient.practice.OdinTermTimes
import com.engineerclient.practice.Recore
import com.engineerclient.practice.SimonSaysPractice
import com.engineerclient.practice.TermInfo
import com.engineerclient.practice.TerminalHitboxes
import com.engineerclient.practice.TermsimExtras
import com.engineerclient.rotation.EcLog
import com.engineerclient.splits.DungeonSplits
import com.engineerclient.splits.OdinSplitsLook
import com.engineerclient.tpmaze.TeamTpMaze
import com.engineerclient.waypoints.BrWaypoints2
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.clickgui.ClickGUI
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.sendCommand
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.sdl.SDLHints
import org.lwjgl.sdl.SDLVideo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object EngineerClient : ClientModInitializer {

    val logger: Logger = LoggerFactory.getLogger("engineerclient")
    val mc: Minecraft get() = Minecraft.getInstance()

    private var tickCounter = 0
    /** [keepWindowUp] has run (once is enough: the attribute sticks). */
    private var windowKept = false

    /** [noExclusiveFullscreen] has run. */
    private var exclusiveChecked = false

    /**
     * Exclusive fullscreen (a video mode change) on Wayland goes through Xwayland, and once the game
     * loses focus KDE never shows its window again, focused or not. Borderless fullscreen looks the
     * same and doesn't break, so on Wayland exclusive is turned off. Since 26.3 the option applies
     * live, so a window in exclusive fullscreen goes straight to borderless.
     */
    private fun noExclusiveFullscreen() {
        exclusiveChecked = true
        if (System.getenv("XDG_SESSION_TYPE") != "wayland") return
        val o = mc.options
        if (!o.exclusiveFullscreen().get()) return
        o.exclusiveFullscreen().set(false)
        o.save()
        logger.info("[ec] exclusive fullscreen off (it breaks on Wayland once the game loses focus)")
    }

    /**
     * Fullscreen game windows minimize themselves when they lose focus (Alt-Tab, a click on another
     * screen), and on KDE Wayland a window minimized that way comes back blank: the game never hears
     * it was restored, so it keeps skipping its frames. Auto-minimize off, and a window that is
     * already stuck minimized is restored. Only on Wayland: elsewhere a fullscreen window that doesn't
     * minimize stays in front of whatever you Alt-Tab to (Windows), so tabbing out looks broken.
     */
    private fun keepWindowUp() {
        if (System.getenv("XDG_SESSION_TYPE") != "wayland") { windowKept = true; return }
        val h = mc.window.handle()
        if (h == 0L) return
        // 26.3 windows are SDL3: auto-minimize is a hint, read at every focus loss.
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0")
        if ((SDLVideo.SDL_GetWindowFlags(h) and SDLVideo.SDL_WINDOW_MINIMIZED) != 0L) SDLVideo.SDL_RestoreWindow(h)
        windowKept = true
    }

    /**
     * Every module, in the order the ClickGUI panel lists them (PanelOrderMixin: Odin itself sorts
     * a panel by name width).
     */
    val MODULES: List<com.odtheking.odin.features.Module> by lazy {
        listOf(
            PovPreviews, P3Sim, ADR, SimonSaysPractice, BrWaypoints2, AgroSphere, DungeonSplits, Recore,
            ClearBreakdown,
            RandomStuff, HealthMana, Timers, LcmMeleeWarning,
            TerminalHitboxes,
        )
    }

    override fun onInitializeClient() {
        try { ConfigMigration.rename(mc.gameDirectory.toPath()) } catch (t: Throwable) { logger.warn("[ec] moving Engineer Client's settings to ADM failed", t) }
        try { ConfigMigration.run(mc.gameDirectory.toPath().resolve("config").resolve("odin")) } catch (t: Throwable) { logger.warn("[ec] module settings migration failed", t) }
        val firstRun = EcConfig.load()
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> EcConfig.migrateRoleBindings() }

        // Register our own module into Odin's module system: own ClickGUI panel
        // ("Engineer Client"), own config file (config/odin/addons/adm.json), own event
        // subscription lifecycle. This is Odin's documented addon path.
        ModuleManager.registerModules(ModuleConfig("adm.json"), *MODULES.toTypedArray())

        installOdinAdditions()

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn these on once.
        if (firstRun) {
            for (m in listOf(AgroSphere, Recore, P3Sim, RandomStuff, HealthMana, Timers, ADR, SimonSaysPractice)) if (!m.enabled) m.toggle()
            ModuleManager.saveConfigurations()
        }

        // Odin's event bus: world load resets class detection.
        on<LevelEvent.Load> { safely("levelLoad") { ClassDetect.reset() } }
        EventBus.subscribe(this)

        // Own-class poll: once a second is plenty; Odin keeps the teammate list fresh from packets.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++tickCounter % 20 == 0) safely("classPoll") { ClassDetect.poll() }
            if (!windowKept) safely("window") { keepWindowUp() }
            if (!exclusiveChecked) safely("exclusive fullscreen") { noExclusiveFullscreen() }
        }

        // Each on its own: a throw in one would otherwise end client init here, /adm included.
        safely("dead players") { DeadPlayers.register() }
        safely("witherborn") { Witherborn.register() }
        safely("i4 complete") { I4Complete.register() }
        safely("session log") {
            EcLog.open(mc.gameDirectory.toPath(), listOf(
                "adm ${FabricLoader.getInstance().getModContainer("adm").map { it.metadata.version.friendlyString }.orElse("?")}" +
                    "  odin ${FabricLoader.getInstance().getModContainer("odin").map { it.metadata.version.friendlyString }.orElse("?")}",
            ))
        }
        // The player is not known until they log in; record who this client is once they are.
        ClientTickEvents.END_CLIENT_TICK.register(object : ClientTickEvents.EndTick {
            var done = false
            override fun onEndTick(client: Minecraft) {
                if (done) return
                client.player?.let { EcLog.log("SESSION", "I am ${it.name.string}"); done = true }
            }
        })

        registerCommand()

        logger.info("[ec] initialized — class stash=${EcConfig.data.lastKnownClass}")
    }

    /**
     * Additions to Odin's own modules (e.g. the Engineer Splits look on Odin's Splits) go in
     * before anything saves the configs, which would drop saved values for settings that don't
     * exist yet.
     */
    private fun installOdinAdditions() {
        safely("ss solver") { OdinSimonSays.install() }
        safely("masks used") { OdinMasksUsed.install() }
        safely("tick timers") { OdinTickTimers.install() }
        safely("highlight look") { OdinHighlightLook.install() }
        safely("splits look") { OdinSplitsLook.install() }
        safely("sub splits tracking") { DungeonSplits.install() }
        safely("term info") { TermInfo.init() }
        safely("terminal hitboxes settings") { TerminalHitboxes.adoptTermInfoSettings() }
        // Room Prediction and LCM Style (with LCM Colors): settings on Odin's own Dungeon Map, and Map Info's LCM Style
        // (from Ascent Client, same names, so saved values carry over); Odin's configs are read again so they apply
        // (reading never writes the file).
        safely("lcm style") { LcmStyle.install() }
        safely("lcm map info") { LcmMapInfo.install() }
        safely("map prediction") { OdinMapPrediction.install() }
        // Team TP Maze: settings on Odin's Puzzle Solvers (its TP Maze), the party's shared maze map over Odin's websocket.
        safely("team tp maze") { TeamTpMaze.install() }
        safely("map configs") { ModuleManager.loadConfigurations() }
        safely("hover terms") { TermsimExtras.install() }
        safely("click limit") { OdinClickLimit.install() }
        safely("odin term times") { OdinTermTimes.install() }
        safely("launch stats") { LaunchStats.install() }
        safely("leap order") { LeapOrder.install() }
        safely("p3sim") { P3Sim.init() }
    }

    /** Every handler that runs inside Odin's bus or a coroutine must not be able to take Odin down with it. */
    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[ec] $what failed", t)
        }
    }

    /** What [warnOnce] has logged. */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /**
     * Logs [t] the first time [what] fails, for a hook that has to swallow its own failure and runs too often to log
     * each one (per chat line, per item drawn, per click). A feature that broke still shows in the log, once.
     */
    @JvmStatic
    fun warnOnce(what: String, t: Throwable) {
        if (warned.add(what)) logger.error("[ec] $what failed (logged once)", t)
    }

    fun chat(msg: String) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    fun chat(msg: Component) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(msg) }
    }

    /** Engineer Client's beta builds: true. False in ADM, the public build, which hides the unfinished ADS buttons (full run, GUI). */
    const val BETA = false

    /** What every line the mod says in chat starts with. */
    const val PREFIX = "§8[§6ADM§8] "

    /** A line from the mod, with its [PREFIX]. Anything the mod says goes through here. */
    fun msg(text: String) = chat(PREFIX + text)

    fun msg(text: Component) = chat(Component.literal(PREFIX).append(text))

    /** The command's names: /adm and its aliases. Other parts of the mod add their subcommands under these. */
    val COMMANDS = listOf("adm", "ADM", "advanceddungeonsmod", "eclient", "ea", "eec", "em")

    /** /adm help: what the rest of it does. Other parts of the mod add their own lines. */
    val HELP = mutableListOf(
        "${PREFIX}§7commands:",
        "§f /adm log§7 [mark <note>] — the session log; §f/adm memreport",
    )

    /** More lines for /adm log mark to stamp under the note (other parts of the mod add theirs). */
    val markLines = mutableListOf<() -> List<String>>()

    private fun mark(note: String) {
        EcLog.log("MARK", note.ifBlank { "(no note)" })
        markLines.forEach { lines -> lines().forEach { EcLog.log("MARK", "  " + it.replace(Regex("§."), "")) } }
    }

    private fun registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /adr: the link to all your uploaded runs, private ones included.
            // /adr online: ADR Online in your browser, signed in.
            for (name in listOf("adr", "ADR")) dispatcher.register(literal(name).executes { ADR.myRunsLink(); 1 }
                .then(literal("online").executes { OnlineLink.openPage(); 1 }))
            // /edt: #EndDungeonsToxicity in party chat.
            dispatcher.register(literal("edt").executes { mc.schedule { sendCommand("pc #EndDungeonsToxicity") }; 1 })
            // /termsim inf: next to Odin's /termsim (Brigadier merges the trees; the literal wins over its arguments).
            dispatcher.register(literal("termsim").then(literal("inf").executes { mc.schedule { InfNumbersSim.open(0L) }; 1 }))
            // /adm on its own: Odin's settings menu, as its /od opens it; /adm help lists the rest.
            // Same tree under every alias (Brigadier literals are case-sensitive). Built fresh per
            // name — a bare redirect would not run the root executes on the alias itself.
            for (name in COMMANDS) dispatcher.register(
                literal(name)
                    .executes { mc.schedule { mc.setScreenAndShow(ClickGUI) }; 1 }
                    .then(literal("help").executes { ctx ->
                        HELP.forEach { ctx.source.sendFeedback(Component.literal(it)) }
                        1
                    })
                    .then(literal("log")
                        .executes { ctx ->
                            ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7log: §f${EcLog.path ?: "not open"}"))
                            ctx.source.sendFeedback(Component.literal("§7 send that file to debug a run; §f/adm log mark <note>§7 stamps a note into it"))
                            1
                        }
                        .then(literal("mark")
                            .executes { ctx -> mark(""); ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7marked.")); 1 }
                            .then(argument("note", StringArgumentType.greedyString()).executes { ctx ->
                                val note = StringArgumentType.getString(ctx, "note")
                                mark(note)
                                ctx.source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7marked: §f$note"))
                                1
                            })))
                    .then(literal("memreport").executes {
                        MemReport.write { line -> msg(line) }
                        1
                    })
            )
        }
    }
}

/** A selector's place in its option list (Odin's selectors hold the enum constant); setting it wraps around. */
var <E : Enum<E>> SelectorSetting<E>.index: Int
    get() = options.indexOf(value)
    set(i) { value = options[Math.floorMod(i, options.size)] }
