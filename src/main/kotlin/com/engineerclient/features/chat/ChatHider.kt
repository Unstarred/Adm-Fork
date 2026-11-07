package com.engineerclient.features.chat

import com.engineerclient.EngineerClient
import com.engineerclient.features.misc.RandomStuff
import com.engineerclient.core.EcLog
import net.minecraft.network.chat.Component
import java.nio.file.Files

object ChatHider {
    fun reload() = load(announce = true)

    @Volatile
    private var rules: ChatRules = ChatRules(emptyList(), emptyList())
    private var loaded = false

    private val overrideFile = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("adm").resolve("chat-hider.json")

    private fun load(announce: Boolean) {
        loaded = true
        val (set, from) = try {
            if (Files.exists(overrideFile)) ChatRules.parse(Files.readString(overrideFile)) to overrideFile.fileName.toString()
            else ChatRules.bundled() to "built-in"
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] chat hider rules failed to load", t)
            ChatRules(emptyList(), emptyList()) to "none (load failed)"
        }
        rules = set
        EngineerClient.logger.info("[ec] chat hider: ${set.size} rules from $from" + if (set.invalid.isNotEmpty()) ", ${set.invalid.size} invalid" else "")
        if (announce) EngineerClient.msg("§7clean chat: §f${set.size}§7 rules from §f$from" + if (set.invalid.isNotEmpty()) " §c(${set.invalid.size} invalid regex)" else "")
    }

    fun shouldHide(message: Component): Boolean {
        if (!RandomStuff.cleansChat()) return false
        if (!loaded) load(announce = false)
        val text = ChatRules.strip(message.string)
        val rule = rules.hides(text) ?: return false
        if (RandomStuff.logsHiddenChat()) EcLog.log("CHAT-HIDE", "$rule ⇐ ${text.replace("\n", "\\n")}")
        return true
    }
}
