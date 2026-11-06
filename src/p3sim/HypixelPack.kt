package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Hypixel's SkyBlock resource pack in ADS.
 *
 * ADS's items are Hypixel's own ([look]): an item model and a tooltip style under `hypixel_skyblock:`, and
 * lore that draws its stat icons with private-use glyphs. All three exist only in Hypixel's pack, which Hypixel sends
 * as a server pack, and 26.3 drops every server pack the moment you leave a server (`disconnectFromWorld` always ends
 * in `clearDownloadedResourcePacks`). So the ADS world, which is singleplayer, never had it unless the player had
 * installed the pack by hand, and every Hypixel item drew as the missing texture.
 *
 * The game keeps every server pack it has downloaded, under `downloads/<pack id>/<sha1>`. [loadThen] loads the newest
 * one carrying Hypixel's namespace the way 26.3 loads a world's own `resources.zip` (`WorldOpenFlows
 * .loadBundledResourcePack`: configureForLocalWorld, waitForPackFeedback, pushLocalPack), so leaving the world drops it
 * again. It goes on top, as it does on Hypixel. With no cached copy, ADS's items fall back to plain vanilla ones:
 * [loaded] is false, and [text] gives the glyphs as the symbols Hypixel's lore used before its pack.
 */
object HypixelPack {
    private const val NAMESPACE = "hypixel_skyblock"

    /** The id the cached pack is pushed under: ADS's own, not the one Hypixel sends. */
    private val PACK_ID: UUID = UUID.nameUUIDFromBytes("adm:ads/hypixel_skyblock".toByteArray())

    /** Loading an 18 MB pack takes seconds; this only stops a reload that never reports from holding the world back. */
    private const val LOAD_TIMEOUT_S = 60L

    /**
     * Hypixel's private-use glyphs in ADS's text, each identified by the lore around it (`SimItems.Lore`,
     * `loadouts.json`), as the symbol Hypixel used for it before its pack. An empty string drops the glyph and the
     * space after it: the mob-type icons have no symbol, and the text reads the same without them.
     */
    private val SYMBOLS = mapOf(
        '' to "๑", // Ability Damage
        '' to "✎", // Intelligence
        '' to "☠", // Crit Damage
        '' to "❈", // Defense
        '' to "❁", // Strength
        '' to "❤", // Health
        '' to "♣", // Pet Luck
        '' to "✯", // Magic Find
        '' to "✦", // Speed
        '' to "♨", // Vitality
        '' to "☣", // Crit Chance
        '' to "❁", // Damage
        '' to "⚚", // fragged: the item was upgraded with boss fragments
        '' to "", // mob type, on a boss's name tag
        '' to "", // mob type: Wither
    )

    /**
     * Whether the game's resources include Hypixel's pack right now: installed by hand, or loaded by [loadThen].
     * Called from the sim server's thread too. The resource manager is only swapped by a reload, and every reload
     * that matters here finishes before the world is built.
     */
    fun loaded(): Boolean = Minecraft.getInstance().resourceManager.namespaces.contains(NAMESPACE)

    /**
     * Loads the newest cached copy of Hypixel's pack, then runs [then] on the client thread. With no cached copy,
     * [then] runs straight away and ADS uses the vanilla look.
     */
    fun loadThen(then: () -> Unit) {
        val mc = Minecraft.getInstance()
        val pack = newestCached(mc.gameDirectory.toPath().resolve("downloads"))
        if (pack == null) {
            EngineerClient.logger.info("[p3sim] no cached hypixel pack, ads uses the vanilla look")
            then()
            return
        }
        val source = mc.downloadedPackSource
        source.configureForLocalWorld()
        source.waitForPackFeedback(PACK_ID).orTimeout(LOAD_TIMEOUT_S, TimeUnit.SECONDS).whenComplete { _, err ->
            if (err != null) EngineerClient.logger.warn("[p3sim] hypixel pack not loaded file=${pack.fileName}", err)
            else EngineerClient.logger.info("[p3sim] hypixel pack loaded file=${pack.fileName}")
            mc.execute { then() }
        }
        source.pushLocalPack(PACK_ID, pack)
    }

    /** The newest file under [downloads] that carries Hypixel's namespace, or null. */
    fun newestCached(downloads: Path): Path? {
        if (!Files.isDirectory(downloads)) return null
        val files = Files.walk(downloads, 2).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        return files.sortedByDescending { Files.getLastModifiedTime(it) }.firstOrNull(::carriesNamespace)
    }

    private fun carriesNamespace(file: Path): Boolean = runCatching {
        ZipFile(file.toFile()).use { zip -> zip.stream().anyMatch { it.name.startsWith("assets/$NAMESPACE/") } }
    }.getOrDefault(false)

    /**
     * Hypixel's look for [s], set after its name and lore: the item model hypixel_skyblock:item/[model] and the tooltip
     * [style], both drawn only by Hypixel's pack. Without the pack they would draw as the missing texture, so [s]
     * keeps its base item (or the vanilla model [vanilla]) and the vanilla tooltip, and its name and lore get
     * readable symbols for Hypixel's glyphs.
     */
    fun look(s: ItemStack, model: String?, style: String?, vanilla: String? = null) {
        if (loaded()) {
            if (model != null) s.set(DataComponents.ITEM_MODEL, Identifier.parse("$NAMESPACE:item/$model"))
            if (style != null) s.set(DataComponents.TOOLTIP_STYLE, Identifier.parse("$NAMESPACE:$style"))
            return
        }
        if (vanilla != null) s.set(DataComponents.ITEM_MODEL, Identifier.withDefaultNamespace(vanilla))
        s.get(DataComponents.CUSTOM_NAME)?.let { s.set(DataComponents.CUSTOM_NAME, symbols(it)) }
        s.get(DataComponents.LORE)?.let { lore -> s.set(DataComponents.LORE, ItemLore(lore.lines().map { symbols(it) })) }
    }

    /** [text] as the player can read it: unchanged with Hypixel's pack, its glyphs as [SYMBOLS] without. */
    fun text(text: String): String = if (loaded()) text else symbols(text)

    private fun symbols(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val symbol = SYMBOLS[text[i]]
            if (symbol == null) out.append(text[i])
            else {
                out.append(symbol)
                if (symbol.isEmpty() && i + 1 < text.length && text[i + 1] == ' ') i++
            }
            i++
        }
        return out.toString()
    }

    /** [c] with every literal part's glyphs as [SYMBOLS]. */
    private fun symbols(c: Component): MutableComponent {
        val contents = c.contents
        val out = if (contents is PlainTextContents) Component.literal(symbols(contents.text())) else c.plainCopy()
        out.setStyle(c.style)
        for (sibling in c.siblings) out.append(symbols(sibling))
        return out
    }
}
