package com.engineerclient.core

import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import com.engineerclient.EngineerClient

object DeadPlayers {

    private val DIED = Regex("""^ ☠ (\w{1,16}) (?:.*became a ghost\.|was killed by .*)$""")
    private val REVIVED = Regex("""^ ❣ (\w{1,16}) was revived\b.*""")
    private val SELF_REVIVED = Regex("""^(?:Your .* revived you\b.*| ❣ You were revived\b.*)""")

    private val dead = HashSet<String>()

    fun register() {
        onReceive<ClientboundSystemChatPacket> {
            if (overlay) return@onReceive
            val line = content.string
            EngineerClient.mc.execute { onChat(line) }
        }
        on<LevelEvent.Load> { dead.clear() }
        EventBus.subscribe(this)
    }

    private fun onChat(line: String) {
        if (!line.startsWith(" ☠ ") && !line.startsWith(" ❣ ") && !line.startsWith("Your ")) return
        val me = EngineerClient.mc.player?.gameProfile?.name()?.lowercase()
        DIED.find(line)?.let { m -> (if (m.groupValues[1] == "You") me else m.groupValues[1].lowercase())?.let { dead += it }; return }
        REVIVED.find(line)?.let { m -> (if (m.groupValues[1] == "You") me else m.groupValues[1].lowercase())?.let { dead -= it }; return }
        if (SELF_REVIVED.matches(line)) me?.let { dead -= it }
    }

    @JvmStatic
    fun isDead(name: String): Boolean =
        name.lowercase() in dead || DungeonUtils.dungeonTeammates.any { it.isDead && it.name.equals(name, ignoreCase = true) }

    fun isDead(p: DungeonPlayer): Boolean = p.isDead || p.name.lowercase() in dead
}
