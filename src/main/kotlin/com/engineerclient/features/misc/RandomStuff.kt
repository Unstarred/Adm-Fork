package com.engineerclient.features.misc

import com.engineerclient.features.chat.ChatHider
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.ClickGUI
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.FriendsButton
import net.minecraft.client.gui.components.PlainTextButton
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.Connection
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemDisplayContext
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

object RandomStuff : Module(
    name = "Random Stuff",
    category = Category.custom("ADM", 860, 10),
    description = "A collection of small unrelated QoL toggles.",
    key = null,
) {
    private val hideTermNumbers by BooleanSetting("Hide Numbers' Numbers", false, desc = "In the numbers terminal (\"Click in order!\"), hides the numbers 1-10 so only the solver's order colours are left to go on. Odin's old Show Numbers, inverted. Applies to /termsim as well.")
    private val termCursorReset by BooleanSetting("Force Cursor Reset", false, desc = "Opening a terminal always puts the cursor in the middle of the screen. On Linux (Xwayland) the game sometimes leaves it where it was; this moves it again. Terminals (and /termsim) only, no other menu.")
    private val partyFinderStats by BooleanSetting("Party Finder Classes", true, desc = "In the Party Finder, which classes each party is missing (yours in bold).")
    private val signEnterConfirms by BooleanSetting("Enter Confirms Sign", true, desc = "On a sign edit screen, Enter finishes it instead of starting a new line — so a Bazaar or Auction House search is type-and-Enter.")

    private val hideItemNames by BooleanSetting("Hide Item Names", false, desc = "Hides the item name that pops up above the hotbar when you switch to a different item.")
    private val hideActionBar by BooleanSetting("Hide Action Bar", false, desc = "Hides the entire action bar (the overlay text above the hotbar) — health/mana/defense text, level up messages, all of it.")

    private val blessOnLeave by BooleanSetting("Bless On Party Leave", true, desc = "Sends \"bless\" in party chat whenever someone leaves the party.")
    private val blackSky by BooleanSetting("Black Sky", true, desc = "Makes the sky (and distant fog) black instead of blue. Pairs with Sodium Extra's Sky toggle.")

    private val blurInGui by BooleanSetting("Blur In GUI", false, desc = "Blurs the world behind any open GUI — a chest, the Bazaar, your inventory. The HUD and the GUI itself stay sharp.")
    private val blurStrength by NumberSetting("Blur Strength", 4, 1..10, 1, desc = "How far the blur reaches, in pixels. 10 is as far as the game's own blur shader goes.").withDependency { blurInGui }

    private val noGlint by BooleanSetting("No Enchant Glint", true, desc = "Removes the enchantment glint from items, so colours and textures stay readable.")

    private val hideOwnArrows by BooleanSetting("Hide Own Arrows Nearby", true, desc = "Arrows you shoot are not drawn while they fly within 5 blocks of you.")

    private val autoJoinSkyblock by BooleanSetting("Auto Join Skyblock", false, desc = "Each time you connect to Hypixel (Prism's join on launch, the server list...), /skyblock goes out right after you land, and again until you're on Skyblock.")
    private val skyblockDelay by NumberSetting("Skyblock Join Delay", 0.2, 0.0..3.0, 0.05, desc = "How long after landing on Hypixel the first /skyblock goes out.", unit = "s").withDependency { autoJoinSkyblock }

    private val cleanMenus by BooleanSetting("Clean Menus", true, desc = "Title screen: no Friends, Language or Accessibility buttons. Esc menu: no Advancements, Statistics, Server Links or icon row (bugs, feedback, friends, player reporting), and Options shares its row with a Mods button.")
    private val skipLoadingScreen by BooleanSetting("Clean Loading Screen", true, desc = "No \"Loading terrain\" screen when joining or changing world: the world shows as it loads in.")
    private val hideSbLines by BooleanSetting("Clean Scoreboard", false, desc = "Hides the noisy sidebar lines: date and server, clock, season, other locations, objective, Keys, pre-start countdown, Solo, www.hypixel.net, blank spacers and the title, plus the three below. Purse, Bits and teammates always stay. Off, the sidebar is left alone.")
    private val hideSbCatacombs by BooleanSetting("Scoreboard: Hide Catacombs Location", true, desc = "Hides the location line in dungeons: The Catacombs (F1-F7, M1-M7, E).").withDependency { hideSbLines }
    private val hideSbElapsed by BooleanSetting("Scoreboard: Hide Time Elapsed", false, desc = "Hides the dungeon's Time Elapsed line.").withDependency { hideSbLines }
    private val hideSbCleared by BooleanSetting("Scoreboard: Hide Cleared %", false, desc = "Hides the dungeon's Cleared: #% (#) line.").withDependency { hideSbLines }
    private val cleanChat by BooleanSetting("Clean Chat", true, desc = "Hides the chat lines its rules mark Block (built in, or config/adm/chat-hider.json). Other mods still see every line.")
    private val logHidden by BooleanSetting("Log Hidden Lines", false, desc = "Writes every hidden line and the rule that hid it to the EC session log, for checking a rule.").withDependency { cleanChat }
    private val reloadChatRules by ActionSetting("Reload Rules", desc = "Re-reads config/adm/chat-hider.json (or the built-in rules if that file is absent).") { ChatHider.reload() }.withDependency { cleanChat }

    private val partyLeaveRegex = Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} has left the party\\.$")
    private const val BLESS_COOLDOWN_MS = 10_000L
    private var lastBless = 0L

    private var pendingSkyblockJoin = false
    private var skyblockConnection: Connection? = null
    private var ticksUntilSkyblock = -1
    private var attempts = 0

    fun joinHypixel(screen: Screen) = connect(screen)

    private fun connect(screen: Screen) {
        ConnectScreen.startConnecting(
            screen,
            mc,
            ServerAddress.parseString(HYPIXEL_ADDRESS),
            ServerData("Hypixel", HYPIXEL_ADDRESS, ServerData.Type.OTHER),
            false,
            null,
        )
    }

    private const val HYPIXEL_ADDRESS = "hypixel.net"
    private const val RETRY_TICKS = 60
    private const val TRANSFER_TICKS = 60
    private const val MAX_ATTEMPTS = 5

    fun skipsLoadingScreen(): Boolean = enabled && skipLoadingScreen

    fun cleansMenus(): Boolean = enabled && cleanMenus

    fun cleansChat(): Boolean = enabled && cleanChat

    fun logsHiddenChat(): Boolean = logHidden

    fun smoothsGuiText(m00: Float, m01: Float): Boolean {
        if (mc.gui.screen() !is ClickGUI) return false
        val px = sqrt(m00 * m00 + m01 * m01) * mc.window.guiScale
        return abs(px - round(px)) > 0.01f
    }

    fun modsScreen(parent: Screen): Screen? = runCatching {
        Class.forName("com.terraformersmc.modmenu.gui.ModsScreen").getConstructor(Screen::class.java).newInstance(parent) as Screen
    }.getOrNull()

    private val titleIconKeys = setOf("options.language", "options.accessibility", "accessibility.onboarding.accessibility.button")

    private fun cleanTitleScreen(screen: TitleScreen) {
        val widgets = Screens.getWidgets(screen)
        val icons = widgets.filter { it is FriendsButton || (it is Button && (it.message.contents as? TranslatableContents)?.key in titleIconKeys) }
        val row = icons.firstOrNull()?.y ?: return
        widgets.removeAll(icons)
        widgets.filter { it.y > row && it !is PlainTextButton }.forEach { it.y -= 24 }
    }

    fun showsPartyFinderStats(): Boolean = enabled && partyFinderStats

    fun blackSkyActive(): Boolean = enabled && blackSky

    fun signEnterFinishes(key: Int): Boolean =
        enabled && signEnterConfirms && LocationUtils.isInSkyblock &&
            (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)

    private var glintOff = false

    fun hidesGlint(context: ItemDisplayContext): Boolean {
        if (!glintOff) return false
        return !(context == ItemDisplayContext.GUI && inTerminal())
    }

    @JvmStatic
    fun hidesTerminalNumbers(): Boolean = enabled && hideTermNumbers

    fun hidesOwnArrow(arrow: AbstractArrow, inGround: Boolean): Boolean {
        if (!enabled || !hideOwnArrows || inGround) return false
        val me = mc.player ?: return false
        return arrow.owner === me && arrow.distanceToSqr(me) < 25.0
    }

    @JvmStatic
    fun hidesTerminalNumber(slot: Slot): Boolean {
        if (!hidesTerminalNumbers()) return false
        val term = TerminalUtils.currentTerm ?: return false
        return term.type == TerminalTypes.NUMBERS && slot.index < term.type.windowSize
    }

    fun hidesArmorGlint(): Boolean = enabled && noGlint

    fun blursGui(): Boolean = enabled && blurInGui && mc.gui.screen() != null && mc.level != null

    var buildingGui = false

    fun blursAfterReset(): Boolean = buildingGui && blursGui()

    fun blurRadius(): Int = blurStrength.toInt()

    fun hidesItemNames(): Boolean = enabled && hideItemNames

    fun forcesTermCursorReset(): Boolean = enabled && termCursorReset

    fun termCursorResetNow(): Boolean = forcesTermCursorReset() && inTerminal()

    private fun inTerminal(): Boolean =
        TerminalUtils.currentTerm != null || mc.gui.screen() is TermSimGUI

    init {
        on<TickEvent.End> {
            glintOff = enabled && noGlint
            ScoreboardLines.hideLines = enabled && hideSbLines
            ScoreboardLines.hideCatacombsLocation = hideSbCatacombs
            ScoreboardLines.hideTimeElapsed = hideSbElapsed
            ScoreboardLines.hideCleared = hideSbCleared
        }

        on<MessageEvent.Overlay> {
            if (hideActionBar) cancel()
        }

        on<MessageEvent.Chat> {
            if (blessOnLeave && partyLeaveRegex.matches(message) && System.currentTimeMillis() - lastBless >= BLESS_COOLDOWN_MS) {
                lastBless = System.currentTimeMillis(); sendCommand("pc bless")
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is TitleScreen && cleansMenus()) cleanTitleScreen(screen)
        }

        ClientPlayConnectionEvents.JOIN.register { handler, _, client ->
            val ip = client.currentServer?.ip?.lowercase().orEmpty()
            pendingSkyblockJoin = false
            ticksUntilSkyblock = -1
            if (!enabled || !autoJoinSkyblock || !ip.contains("hypixel")) return@register
            val connection = handler.connection
            if (connection === skyblockConnection) {
                if (pendingSkyblockJoin && attempts > 0) ticksUntilSkyblock = TRANSFER_TICKS
                return@register
            }
            skyblockConnection = connection
            pendingSkyblockJoin = true
            attempts = 0
            ticksUntilSkyblock = (skyblockDelay * 20).toInt()
        }

        on<LevelEvent.Unload> { ScoreboardLines.hideLines = false }

        onReceive<ClientboundRespawnPacket> {
            if (!enabled || !autoJoinSkyblock || !pendingSkyblockJoin || attempts == 0) return@onReceive
            ticksUntilSkyblock = TRANSFER_TICKS
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!pendingSkyblockJoin || ticksUntilSkyblock < 0) return@register
            if (!enabled || !autoJoinSkyblock || LocationUtils.isInSkyblock || attempts >= MAX_ATTEMPTS) { pendingSkyblockJoin = false; ticksUntilSkyblock = -1; return@register }
            if (client.player == null || ticksUntilSkyblock-- > 0) return@register
            attempts++
            sendCommand("skyblock")
            ticksUntilSkyblock = RETRY_TICKS
        }
    }

    override fun onDisable() {
        super.onDisable()
        ScoreboardLines.hideLines = false
        glintOff = false
    }
}
