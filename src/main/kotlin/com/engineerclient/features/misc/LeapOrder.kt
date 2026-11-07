package com.engineerclient.features.misc

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.adr.PartyTracker
import com.engineerclient.features.odin.OdinLeapScale
import com.engineerclient.util.setting
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.CommandNode
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.render.roundedRectOutlined
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.PartyUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Util
import net.minecraft.world.entity.player.PlayerSkin
import org.lwjgl.sdl.SDLMouse
import java.nio.FloatBuffer
import java.nio.file.Files
import java.util.Locale
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object LeapOrder {
    private val file by lazy { FabricLoader.getInstance().configDir.resolve("adm").resolve("leaporder.txt") }
    private var saved: List<String> = emptyList()
    private val autoFile by lazy { file.resolveSibling("leaporder-sorting.txt") }
    private var revertTo: LeapMenu.Sorting? = null
    private var orderParty: Set<String>? = null
    private var aloneSince: Long? = null

    private const val ALONE_MS = 10 * 60_000L
    private const val MAX_SWAPPED = 3

    fun install() {
        runCatching {
            if (Files.exists(file)) {
                val names = Files.readAllLines(file).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.take(4)
                if (names.isNotEmpty() && DungeonUtils.customLeapOrder.isEmpty()) DungeonUtils.customLeapOrder = names
                saved = names
            }
        }.onFailure { EngineerClient.logger.warn("[ec] leap order: couldn't read $file", it) }
        runCatching {
            if (Files.exists(autoFile)) {
                val lines = Files.readAllLines(autoFile)
                revertTo = LeapMenu.Sorting.entries.firstOrNull { it.name == lines.getOrNull(0)?.trim() }
                orderParty = lines.getOrNull(1)?.takeIf { it.trim() != "?" }?.let { l -> l.split(' ').filter { it.isNotBlank() }.toSet() }
                aloneSince = lines.getOrNull(2)?.trim()?.toLongOrNull()
            }
        }.onFailure { EngineerClient.logger.warn("[ec] leap order: couldn't read $autoFile", it) }

        ClientTickEvents.END_CLIENT_TICK.register {
            val now = DungeonUtils.customLeapOrder
            if (now != saved) EngineerClient.safely("leap order save") { save(now); spots.fill(""); if (now.isNotEmpty()) useCustom() }
            EngineerClient.safely("leap order party") { checkParty() }
            EngineerClient.safely("leap order spots") { keepSpots() }
        }
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 50) { EngineerClient.safely("leap order spots") { keepSpots() } }
        on<ScreenEvent.MouseClick>(EventPriority.HIGHEST + 50) { EngineerClient.safely("leap order spots") { keepSpots() } }
        on<ScreenEvent.KeyPress>(EventPriority.HIGHEST + 50) { EngineerClient.safely("leap order spots") { keepSpots() } }
        EventBus.subscribe(this)

        val afterOdin = Identifier.fromNamespaceAndPath("engineerclient", "after_odin")
        ClientCommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, afterOdin)
        ClientCommandRegistrationCallback.EVENT.register(afterOdin) { dispatcher, _ ->
            EngineerClient.safely("leap order /od") {
                val nodes = listOfNotNull(
                    dispatcher.root.getChild("odin")?.getChild("leaporder"),
                    dispatcher.root.getChild("od")?.let { it.redirect ?: it }?.getChild("leaporder"),
                ).distinct()
                for (node in nodes) COMMAND.set(node, Command<FabricClientCommandSource> { open(); 1 })
                if (nodes.isEmpty()) EngineerClient.logger.info("[ec] leap order: Odin has no /od leaporder here, /lo only")
            }
            dispatcher.register(literal("lo").executes { open(); 1 }
                .then(argument("member1", StringArgumentType.word())
                    .then(argument("member2", StringArgumentType.word())
                        .then(argument("member3", StringArgumentType.word())
                            .then(argument("member4", StringArgumentType.word()).executes { ctx ->
                                val names = (1..4).map { StringArgumentType.getString(ctx, "member$it") }
                                set(names.map { it.lowercase(Locale.ROOT) })
                                EngineerClient.msg("§aLeap order set: §f${names.joinToString(" §7> §f")}")
                                1
                            })))))
        }
    }

    fun skin(name: String): PlayerSkin {
        mc.connection?.getPlayerInfoIgnoreCase(name)?.let { return it.skin }
        if (name.equals(mc.player?.name?.string, ignoreCase = true)) mc.player?.let { return it.skin }
        val key = name.lowercase(Locale.ROOT)
        skins[key]?.let { return it }
        if (looking.add(key)) {
            CompletableFuture.supplyAsync({ mc.services().profileResolver().fetchByName(name) }, Util.nonCriticalIoPool())
                .thenCompose { p -> p.map { mc.skinManager.get(it) }.orElse(CompletableFuture.completedFuture(Optional.empty())) }
                .whenComplete { s, e ->
                    if (e != null) EngineerClient.logger.warn("[ec] leap order: no skin for {} ({})", name, e.toString())
                    s?.ifPresent { skins[key] = it }
                }
        }
        return DefaultPlayerSkin.getDefaultSkin()
    }
    private val skins = ConcurrentHashMap<String, PlayerSkin>()
    private val looking = ConcurrentHashMap.newKeySet<String>()

    private val COMMAND by lazy { CommandNode::class.java.getDeclaredField("command").apply { isAccessible = true } }

    private fun open() = mc.schedule { mc.gui.setScreen(LeapOrderScreen()) }

    fun set(names: List<String>) {
        DungeonUtils.customLeapOrder = names.filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }
    }

    private val sorting get() = LeapMenu.setting<SelectorSetting<LeapMenu.Sorting>>("Sorting")

    val custom get() = sorting?.value == LeapMenu.Sorting.CUSTOM

    fun setCustom(on: Boolean) {
        val s = sorting ?: return
        if (on == custom) return
        if (on) { useCustom(); return }
        s.value = revertTo ?: LeapMenu.Sorting.ODIN
        ModuleManager.saveConfigurations()
    }

    private fun useCustom() {
        val s = sorting ?: return
        if (s.value != LeapMenu.Sorting.CUSTOM) {
            revertTo = s.value
            s.value = LeapMenu.Sorting.CUSTOM
            ModuleManager.saveConfigurations()
            EngineerClient.msg("§7Leap Menu sorting: §fCustom§7 (back to ${label(revertTo!!)} once you're with a different party)")
        } else if (revertTo == null) revertTo = LeapMenu.Sorting.ODIN
        orderParty = party()
        aloneSince = null
        saveAuto()
    }

    private fun checkParty() {
        val back = revertTo ?: return
        val s = sorting ?: return
        if (s.value != LeapMenu.Sorting.CUSTOM) { revertTo = null; orderParty = null; aloneSince = null; saveAuto(); return }
        val why = dropReason(System.currentTimeMillis()) ?: return
        s.value = back
        revertTo = null
        orderParty = null
        aloneSince = null
        ModuleManager.saveConfigurations()
        saveAuto()
        EngineerClient.msg("§7Leap Menu sorting back to §f${label(back)}§7: $why")
    }

    private fun dropReason(now: Long): String? {
        val me = mc.user.name.lowercase(Locale.ROOT)
        val order = DungeonUtils.customLeapOrder.map { it.lowercase(Locale.ROOT) }.filter { it != me }.toSet()

        val party = party()
        if (party != null && party.isEmpty()) {
            val since = aloneSince ?: now.also { aloneSince = it; saveAuto() }
            if (now - since > ALONE_MS) return "out of a party for ${ALONE_MS / 60_000} minutes"
        } else if (party != null) {
            if (aloneSince != null) { aloneSince = null; saveAuto() }
            val was = orderParty ?: party.also { orderParty = it; saveAuto() }
            val gone = was.count { it !in party }
            if (gone > MAX_SWAPPED) return "$gone of the party it was set for are gone"
            if (order.isNotEmpty() && order.none { it in party }) return "none of the leap order's players are in the party"
        }
        return null
    }

    private fun party(): Set<String>? {
        val me = mc.user.name.lowercase(Locale.ROOT)
        val names = synchronized(PartyTracker.state) {
            PartyTracker.state.members.keys.toList().takeIf { PartyTracker.state.known && PartyTracker.state.doubt == null }
        } ?: return null
        return names.map { it.lowercase(Locale.ROOT) }.filter { it != me }.toSet()
    }

    private val spots = Array(4) { "" }
    private val EMPTY = DungeonPlayer("Empty", DungeonClass.EMPTY, 0, null)

    private fun keepSpots() {
        if (!custom || !DungeonUtils.inDungeons) return
        val team = DungeonListener.leapTeammates.filter { it.clazz != DungeonClass.EMPTY }
        if (team.isEmpty()) return
        val byName = team.associateBy { it.name.lowercase(Locale.ROOT) }
        val out = arrayOfNulls<DungeonPlayer>(4)
        for (i in 0 until 4) spots[i].takeIf { it.isNotEmpty() }?.let { byName[it] }?.let { out[i] = it }
        val order = DungeonUtils.customLeapOrder
        for (p in team) {
            if (out.any { it === p }) continue
            val rank = order.indexOf(p.name.lowercase(Locale.ROOT))
            val i = if (rank in 0 until 4 && out[rank] == null) rank else out.indexOfFirst { it == null }
            if (i >= 0) out[i] = p
        }
        for (i in 0 until 4) spots[i] = out[i]?.name?.lowercase(Locale.ROOT) ?: spots[i].takeIf { it.isNotEmpty() && it !in byName }.orEmpty()
        val list = out.map { it ?: EMPTY }
        if (list != DungeonListener.leapTeammates) DungeonListener.leapTeammates = list
    }

    private fun label(s: LeapMenu.Sorting) = s.toString()

    private fun saveAuto() = runCatching {
        val back = revertTo
        if (back == null) Files.deleteIfExists(autoFile)
        else {
            Files.createDirectories(autoFile.parent)
            Files.write(autoFile, listOf(back.name, orderParty?.sorted()?.joinToString(" ") ?: "?", aloneSince?.toString() ?: "-"))
        }
    }.onFailure { EngineerClient.logger.warn("[ec] leap order: couldn't write $autoFile", it) }

    private fun save(names: List<String>) {
        saved = names
        Files.createDirectories(file.parent)
        Files.write(file, names)
    }
}

class LeapOrderScreen : Screen(Component.literal("Leap Order")) {
    private val names = Array(4) { "" }
    private var dragging = -1
    private var sawHeld = false
    private var grabX = 0.0
    private var grabY = 0.0
    private var mouseX = 0.0
    private var mouseY = 0.0
    private val slideFrom = arrayOfNulls<IntArray>(4)
    private val slideAt = LongArray(4)

    private var partyVersion = -1

    init {
        fill()
        EngineerClient.safely("leap order party") { PartyTracker.askIfUnknown() }
    }

    private fun fill() {
        partyVersion = PartyTracker.state.version
        val me = mc.player?.name?.string
        val party = party().filter { !it.equals(me, ignoreCase = true) }
        val order = DungeonUtils.customLeapOrder.take(4)
        val shown = if (party.isEmpty()) order
            else order.mapNotNull { n -> party.firstOrNull { it.equals(n, ignoreCase = true) } } +
                party.filter { p -> order.none { it.equals(p, ignoreCase = true) } }
        for (i in 0 until 4) names[i] = shown.getOrElse(i) { "" }
    }

    private fun party(): List<String> {
        val team = DungeonUtils.dungeonTeammates.map { it.name }
        if (DungeonUtils.inDungeons && team.isNotEmpty()) return team
        val tracked = synchronized(PartyTracker.state) {
            PartyTracker.state.members.values.map { it.name }.takeIf { PartyTracker.state.known }
        }
        return (tracked ?: PartyUtils.members).distinct()
    }

    override fun isPauseScreen() = false

    private val scale get() = OdinLeapScale.get()

    private fun box(i: Int): IntArray {
        val s = scale
        val w = (LeapMenu.BOX_WIDTH * s).toInt()
        val h = (LeapMenu.BOX_HEIGHT * s).toInt()
        val x0 = if (i % 2 == 0) width / 2 - 24 - w else width / 2 + 24
        val y0 = if (i / 2 == 0) height / 2 - 24 - h else height / 2 + 24
        return intArrayOf(x0, y0, x0 + w, y0 + h)
    }

    private fun drawnAt(i: Int): IntArray {
        val b = box(i)
        val from = slideFrom[i] ?: return b
        val t = (System.currentTimeMillis() - slideAt[i]) / SLIDE_MS.toFloat()
        if (t >= 1f) { slideFrom[i] = null; return b }
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        val x = from[0] + ((b[0] - from[0]) * e).toInt()
        val y = from[1] + ((b[1] - from[1]) * e).toInt()
        return intArrayOf(x, y, x + b[2] - b[0], y + b[3] - b[1])
    }

    private fun slide(i: Int, x: Int, y: Int) { slideFrom[i] = intArrayOf(x, y); slideAt[i] = System.currentTimeMillis() }

    private fun heldAt(x: Double, y: Double): IntArray {
        val b = box(dragging)
        return intArrayOf((x - grabX).toInt().coerceIn(0, width - (b[2] - b[0])), (y - grabY).toInt().coerceIn(0, height - (b[3] - b[1])))
    }

    private fun spotAt(x: Double, y: Double): Int = (if (x < width / 2.0) 0 else 1) + (if (y < height / 2.0) 0 else 2)

    private fun onBox(i: Int, x: Double, y: Double): Boolean { val b = box(i); return x >= b[0] && x < b[2] && y >= b[1] && y < b[3] }

    private fun clazz(name: String): DungeonClass? = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, ignoreCase = true) }?.clazz

    override fun extractRenderState(gfx: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        super.extractRenderState(gfx, mx, my, delta)
        mouseX = mx.toDouble(); mouseY = my.toDouble()
        if (dragging < 0 && PartyTracker.state.version != partyVersion) fill()
        if (dragging >= 0) when (leftHeld()) {
            true -> sawHeld = true
            false -> if (sawHeld) drop(mouseX, mouseY)
            null -> {}
        }

        val cb = checkbox()
        val on = LeapOrder.custom
        val hot = dragging < 0 && mouseX >= cb[0] && mouseX < cb[2] && mouseY >= cb[1] && mouseY < cb[3]
        gfx.fill(cb[0], cb[1], cb[0] + CHECK, cb[1] + CHECK, (if (hot) CHECK_HOT else OUTLINE).rgba)
        gfx.fill(cb[0] + 1, cb[1] + 1, cb[0] + CHECK - 1, cb[1] + CHECK - 1, BOX.rgba)
        if (on) gfx.fill(cb[0] + 3, cb[1] + 3, cb[0] + CHECK - 3, cb[1] + CHECK - 3, CHECKED.rgba)
        gfx.text(LABEL, cb[0] + CHECK + 4, cb[1] + 1, WHITE, shadow = true)

        val rb = recheckButton()
        val rHot = dragging < 0 && mouseX >= rb[0] && mouseX < rb[2] && mouseY >= rb[1] && mouseY < rb[3]
        gfx.fill(rb[0], rb[1], rb[2], rb[3], (if (rHot) CHECK_HOT else OUTLINE).rgba)
        gfx.fill(rb[0] + 1, rb[1] + 1, rb[2] - 1, rb[3] - 1, (if (rHot) HOVER else BOX).rgba)
        val label = if (PartyTracker.checking) RECHECKING else RECHECK
        gfx.text(label, (rb[0] + rb[2] - mc.font.width(label)) / 2, rb[1] + 4, WHITE, shadow = true)

        if (dragging >= 0) {
            val q = spotAt(mouseX, mouseY)
            val qx = if (q % 2 == 0) 0 else width / 2
            val qy = if (q / 2 == 0) 0 else height / 2
            gfx.fill(qx, qy, qx + (width + 1) / 2, qy + (height + 1) / 2, QUARTER.rgba)
        }
        for (i in 0 until 4) {
            val b = box(i)
            if (i == dragging) {
                gfx.roundedRectOutlined(b[0], b[1], b[2], b[3], EMPTY.rgba, OUTLINE.rgba, 1.5f, 9f * scale)
                continue
            }
            val hovered = dragging >= 0 && spotAt(mouseX, mouseY) == i
            val at = drawnAt(i)
            drawBox(gfx, at[0], at[1], names[i], if (hovered) HOVER else BOX)
        }
        if (dragging >= 0) {
            val at = heldAt(mouseX, mouseY)
            drawBox(gfx, at[0], at[1], names[dragging], DRAGGED)
        }
    }

    private fun drawBox(gfx: GuiGraphicsExtractor, x: Int, y: Int, name: String, fill: Color) {
        val w = LeapMenu.BOX_WIDTH
        val h = LeapMenu.BOX_HEIGHT
        val s = scale
        gfx.pose().pushMatrix()
        gfx.pose().translate(x.toFloat(), y.toFloat())
        gfx.pose().scale(s, s)
        val c = clazz(name)
        gfx.roundedRectOutlined(0, 0, w, h, fill.rgba, (c?.color ?: OUTLINE).rgba, 2f, 9f)
        if (name.isEmpty()) {
            gfx.text("§8Empty", 15, h / 2 - 4, WHITE, shadow = false)
        } else {
            val face = (h * 0.76).toInt()
            PlayerFaceExtractor.extractRenderState(gfx, LeapOrder.skin(name), 9, 9, face)
            gfx.text(name, 15 + face, (h / 2.5).toInt(), c?.color ?: WHITE, shadow = true)
            gfx.text(c?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "§7In party", 15 + face, (h / 1.7).toInt(), WHITE, shadow = true)
        }
        gfx.pose().popMatrix()
    }

    private fun checkbox(): IntArray {
        val w = CHECK + 4 + mc.font.width(LABEL)
        val x0 = (width - w) / 2
        val y0 = box(0)[1] - CHECK - 10
        return intArrayOf(x0, y0, x0 + w, y0 + CHECK)
    }

    private fun recheckButton(): IntArray {
        val w = maxOf(mc.font.width(RECHECK), mc.font.width(RECHECKING)) + 16
        val x0 = (width - w) / 2
        val y0 = box(2)[3] + 10
        return intArrayOf(x0, y0, x0 + w, y0 + 16)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val cb = checkbox()
        if (isLeft(event) && event.x() >= cb[0] && event.x() < cb[2] && event.y() >= cb[1] && event.y() < cb[3]) {
            LeapOrder.setCustom(!LeapOrder.custom)
            mc.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f)
            return true
        }
        val rb = recheckButton()
        if (isLeft(event) && event.x() >= rb[0] && event.x() < rb[2] && event.y() >= rb[1] && event.y() < rb[3]) {
            EngineerClient.safely("leap order recheck") { PartyTracker.recheck() }
            mc.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f)
            return true
        }
        val i = spotAt(event.x(), event.y())
        if (isLeft(event) && i >= 0 && names[i].isNotEmpty()) {
            dragging = i
            sawHeld = false
            val b = box(i)
            if (onBox(i, event.x(), event.y())) { grabX = event.x() - b[0]; grabY = event.y() - b[1] }
            else { grabX = (b[2] - b[0]) / 2.0; grabY = (b[3] - b[1]) / 2.0 }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dragging < 0) return super.mouseReleased(event)
        drop(event.x(), event.y())
        return true
    }

    private fun drop(x: Double, y: Double) {
        val to = spotAt(x, y)
        val held = heldAt(x, y)
        val other = drawnAt(to)
        slide(to, held[0], held[1])
        if (to != dragging) slide(dragging, other[0], other[1])
        if (to != dragging) {
            val t = names[to]; names[to] = names[dragging]; names[dragging] = t
            val order = names.filter { it.isNotEmpty() }
            for (i in 0 until 4) names[i] = order.getOrElse(i) { "" }
            LeapOrder.set(order)
            LeapOrder.setCustom(true)
            mc.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f)
        }
        dragging = -1
    }

    private fun isLeft(event: MouseButtonEvent): Boolean = event.button() == InputConstants.MOUSE_BUTTON_LEFT

    private fun leftHeld(): Boolean? = runCatching {
        (SDLMouse.SDL_GetMouseState(null as FloatBuffer?, null as FloatBuffer?) and 1) != 0
    }.getOrNull()

    private companion object {
        const val SLIDE_MS = 90L
        val WHITE = Color(255, 255, 255, 1f)
        val BOX = Color(30, 30, 30, 0.85f)
        val HOVER = Color(60, 60, 60, 0.9f)
        val DRAGGED = Color(45, 45, 45, 0.7f)
        val EMPTY = Color(0, 0, 0, 0.25f)
        val OUTLINE = Color(120, 120, 120, 1f)
        val QUARTER = Color(255, 255, 255, 0.06f)
        const val CHECK = 10
        const val LABEL = "Custom Sorting"
        const val RECHECK = "Recheck Party"
        const val RECHECKING = "§7Checking..."
        val CHECKED = Color(85, 255, 85, 1f)
        val CHECK_HOT = Color(200, 200, 200, 1f)
    }
}
