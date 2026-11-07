package com.engineerclient

import com.engineerclient.adr.ADR
import com.engineerclient.adr.OnlineLink
import com.engineerclient.ads.P3Sim
import com.engineerclient.core.ClassDetect
import com.engineerclient.core.ConfigMigration
import com.engineerclient.core.DeadPlayers
import com.engineerclient.core.EcConfig
import com.engineerclient.core.EcLog
import com.engineerclient.core.LaunchStats
import com.engineerclient.core.MemReport
import com.engineerclient.features.brwaypoints.BrWaypoints2
import com.engineerclient.features.clearbreakdown.ClearBreakdown
import com.engineerclient.features.lcm.LcmMapInfo
import com.engineerclient.features.lcm.LcmMeleeWarning
import com.engineerclient.features.lcm.LcmStyle
import com.engineerclient.features.mapprediction.OdinMapPrediction
import com.engineerclient.features.misc.AgroSphere
import com.engineerclient.features.misc.HealthMana
import com.engineerclient.features.misc.I4Complete
import com.engineerclient.features.misc.LeapOrder
import com.engineerclient.features.misc.RandomStuff
import com.engineerclient.features.misc.Timers
import com.engineerclient.features.misc.Witherborn
import com.engineerclient.features.odin.OdinClickLimit
import com.engineerclient.features.odin.OdinHighlightLook
import com.engineerclient.features.odin.OdinMasksUsed
import com.engineerclient.features.odin.OdinTickTimers
import com.engineerclient.features.pov.PovPreviews
import com.engineerclient.features.practice.InfNumbersSim
import com.engineerclient.features.practice.OdinSimonSays
import com.engineerclient.features.practice.OdinTermTimes
import com.engineerclient.features.practice.Recore
import com.engineerclient.features.practice.SimonSaysPractice
import com.engineerclient.features.practice.TermInfo
import com.engineerclient.features.practice.TerminalHitboxes
import com.engineerclient.features.practice.TermsimExtras
import com.engineerclient.features.splits.DungeonSplits
import com.engineerclient.features.splits.OdinSplitsLook
import com.engineerclient.features.tpmaze.TeamTpMaze
import com.engineerclient.util.noFormatting
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
    private var windowKept = false

    private var exclusiveChecked = false

    private fun noExclusiveFullscreen() {
        exclusiveChecked = true
        if (System.getenv("XDG_SESSION_TYPE") != "wayland") return
        val o = mc.options
        if (!o.exclusiveFullscreen().get()) return
        o.exclusiveFullscreen().set(false)
        o.save()
        logger.info("[ec] exclusive fullscreen off (it breaks on Wayland once the game loses focus)")
    }

    private fun keepWindowUp() {
        if (System.getenv("XDG_SESSION_TYPE") != "wayland") { windowKept = true; return }
        val h = mc.window.handle()
        if (h == 0L) return
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0")
        if ((SDLVideo.SDL_GetWindowFlags(h) and SDLVideo.SDL_WINDOW_MINIMIZED) != 0L) SDLVideo.SDL_RestoreWindow(h)
        windowKept = true
    }

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

        ModuleManager.registerModules(ModuleConfig("adm.json"), *MODULES.toTypedArray())

        installOdinAdditions()

        if (firstRun) {
            for (m in listOf(AgroSphere, Recore, P3Sim, RandomStuff, HealthMana, Timers, ADR, SimonSaysPractice)) if (!m.enabled) m.toggle()
            ModuleManager.saveConfigurations()
        }

        on<LevelEvent.Load> { safely("levelLoad") { ClassDetect.reset() } }
        EventBus.subscribe(this)

        ClientTickEvents.END_CLIENT_TICK.register {
            if (++tickCounter % 20 == 0) safely("classPoll") { ClassDetect.poll() }
            if (!windowKept) safely("window") { keepWindowUp() }
            if (!exclusiveChecked) safely("exclusive fullscreen") { noExclusiveFullscreen() }
        }

        safely("dead players") { DeadPlayers.register() }
        safely("witherborn") { Witherborn.register() }
        safely("i4 complete") { I4Complete.register() }
        safely("session log") {
            EcLog.open(mc.gameDirectory.toPath(), listOf(
                "adm ${FabricLoader.getInstance().getModContainer("adm").map { it.metadata.version.friendlyString }.orElse("?")}" +
                    "  odin ${FabricLoader.getInstance().getModContainer("odin").map { it.metadata.version.friendlyString }.orElse("?")}",
            ))
        }
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

    private fun installOdinAdditions() {
        safely("ss solver") { OdinSimonSays.install() }
        safely("masks used") { OdinMasksUsed.install() }
        safely("tick timers") { OdinTickTimers.install() }
        safely("highlight look") { OdinHighlightLook.install() }
        safely("splits look") { OdinSplitsLook.install() }
        safely("sub splits tracking") { DungeonSplits.install() }
        safely("term info") { TermInfo.init() }
        safely("terminal hitboxes settings") { TerminalHitboxes.adoptTermInfoSettings() }
        safely("lcm style") { LcmStyle.install() }
        safely("lcm map info") { LcmMapInfo.install() }
        safely("map prediction") { OdinMapPrediction.install() }
        safely("team tp maze") { TeamTpMaze.install() }
        safely("map configs") { ModuleManager.loadConfigurations() }
        safely("hover terms") { TermsimExtras.install() }
        safely("click limit") { OdinClickLimit.install() }
        safely("odin term times") { OdinTermTimes.install() }
        safely("launch stats") { LaunchStats.install() }
        safely("leap order") { LeapOrder.install() }
        safely("p3sim") { P3Sim.init() }
    }

    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[ec] $what failed", t)
        }
    }

    private val warned = ConcurrentHashMap.newKeySet<String>()

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

    const val BETA = false

    const val PREFIX = "§8[§6ADM§8] "

    fun msg(text: String) = chat(PREFIX + text)

    fun msg(text: Component) = chat(Component.literal(PREFIX).append(text))

    val COMMANDS = listOf("adm", "ADM", "advanceddungeonsmod", "eclient", "ea", "eec", "em")

    val HELP = mutableListOf(
        "${PREFIX}§7commands:",
        "§f /adm log§7 [mark <note>] — the session log; §f/adm memreport",
    )

    val markLines = mutableListOf<() -> List<String>>()

    private fun mark(note: String) {
        EcLog.log("MARK", note.ifBlank { "(no note)" })
        markLines.forEach { lines -> lines().forEach { EcLog.log("MARK", "  " + it.noFormatting()) } }
    }

    private fun registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in listOf("adr", "ADR")) dispatcher.register(literal(name).executes { ADR.myRunsLink(); 1 }
                .then(literal("online").executes { OnlineLink.openPage(); 1 }))
            dispatcher.register(literal("edt").executes { mc.schedule { sendCommand("pc #EndDungeonsToxicity") }; 1 })
            dispatcher.register(literal("termsim").then(literal("inf").executes { mc.schedule { InfNumbersSim.open(0L) }; 1 }))
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

var <E : Enum<E>> SelectorSetting<E>.index: Int
    get() = options.indexOf(value)
    set(i) { value = options[Math.floorMod(i, options.size)] }
