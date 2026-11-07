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

object HypixelPack {
    private const val NAMESPACE = "hypixel_skyblock"

    private val PACK_ID: UUID = UUID.nameUUIDFromBytes("adm:ads/hypixel_skyblock".toByteArray())

    private const val LOAD_TIMEOUT_S = 60L

    private val SYMBOLS = mapOf(
        '' to "๑",
        '' to "✎",
        '' to "☠",
        '' to "❈",
        '' to "❁",
        '' to "❤",
        '' to "♣",
        '' to "✯",
        '' to "✦",
        '' to "♨",
        '' to "☣",
        '' to "❁",
        '' to "⚚",
        '' to "",
        '' to "",
    )

    fun loaded(): Boolean = Minecraft.getInstance().resourceManager.namespaces.contains(NAMESPACE)

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

    fun newestCached(downloads: Path): Path? {
        if (!Files.isDirectory(downloads)) return null
        val files = Files.walk(downloads, 2).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        return files.sortedByDescending { Files.getLastModifiedTime(it) }.firstOrNull(::carriesNamespace)
    }

    private fun carriesNamespace(file: Path): Boolean = runCatching {
        ZipFile(file.toFile()).use { zip -> zip.stream().anyMatch { it.name.startsWith("assets/$NAMESPACE/") } }
    }.getOrDefault(false)

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

    private fun symbols(c: Component): MutableComponent {
        val contents = c.contents
        val out = if (contents is PlainTextContents) Component.literal(symbols(contents.text())) else c.plainCopy()
        out.setStyle(c.style)
        for (sibling in c.siblings) out.append(symbols(sibling))
        return out
    }
}
