package com.engineerclient.features.clearbreakdown

import com.google.gson.Gson
import com.odtheking.odin.features.impl.render.ClickGUIModule
import com.odtheking.odin.utils.network.webSocket
import com.odtheking.odin.utils.skyblock.LocationUtils

object OdinSync {
    @Volatile var onPayload: (player: String, tiles: List<Long>, found: Int) -> Unit = { _, _, _ -> }

    private val gson = Gson()

    private class Msg(val playerName: String?, val roomName: String?, val tiles: List<Tile>?, val foundSecrets: Int?)
    private class Tile(val data: Long?)

    private val socket = webSocket {
        onMessage { text ->
            val m = try { gson.fromJson(text, Msg::class.java) } catch (_: Exception) { null } ?: return@onMessage
            val player = m.playerName ?: return@onMessage
            val tiles = m.tiles?.map { it.data ?: return@onMessage } ?: return@onMessage
            val found = m.foundSecrets ?: return@onMessage
            if (m.roomName == null) return@onMessage
            onPayload(player, tiles, found)
        }
    }

    fun connect() {
        LocationUtils.lobbyId?.let { socket.connect("${ClickGUIModule.webSocketUrl}$it") }
    }

    fun shutdown() = socket.shutdown()
}
