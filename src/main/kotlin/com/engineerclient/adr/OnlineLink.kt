package com.engineerclient.adr

import com.engineerclient.EngineerClient
import com.engineerclient.util.Http
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

object OnlineLink {
    private const val SITE = "undonecoffee.com"
    private val http: HttpClient = Http.client

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connecting = false
    @Volatile private var stopped = false
    @Volatile private var retryAt = 0L
    private var backoff = 5_000L
    private var sentParty = -1
    private var partyChangedAt = 0L
    private var sentWhere = ""
    private var lastPing = 0L
    private var sending: CompletableFuture<WebSocket> = CompletableFuture.completedFuture(null)

    fun tick() {
        if (stopped) return
        val now = System.currentTimeMillis()
        val ws = socket
        if (ws == null) {
            if (!connecting && now >= retryAt && EngineerClient.mc.player != null) connect()
            return
        }
        val v = PartyTracker.state.version
        if (v != sentParty) {
            if (partyChangedAt == 0L) partyChangedAt = now
            if (now - partyChangedAt > 400) { sentParty = v; partyChangedAt = 0L; send(party()) }
        }
        val floor = DungeonUtils.floor?.name
        val where = if (floor != null) "dungeon|$floor" else "game"
        if (where != sentWhere) {
            sentWhere = where
            send(JsonObject().apply { addProperty("t", "where"); addProperty("where", where.substringBefore('|')); floor?.let { addProperty("floor", it) }; addProperty("since", now) })
        }
        if (now - lastPing > 30_000) {
            lastPing = now
            sendText("ping")
        }
    }

    private fun party(): JsonObject = synchronized(PartyTracker.state) {
        val s = PartyTracker.state
        JsonObject().apply {
            addProperty("t", "party")
            addProperty("known", s.known)
            s.doubt?.let { addProperty("doubt", it) }
            add("members", JsonArray().apply {
                for (m in s.members.values) add(JsonObject().apply {
                    addProperty("n", m.name)
                    addProperty("r", when (m.role) { PartyState.Role.LEADER -> "L"; PartyState.Role.MOD -> "M"; else -> "" })
                    if (!m.online) addProperty("off", true)
                })
            })
        }
    }

    private fun hello() = JsonObject().apply {
        val u = EngineerClient.mc.user
        addProperty("t", "hello"); addProperty("name", u.name); addProperty("uuid", u.profileId.toString().replace("-", ""))
    }

    private fun connect() {
        connecting = true
        http.newWebSocketBuilder().header("Authorization", "Bearer ${ADR.token()}").connectTimeout(Duration.ofSeconds(15))
            .buildAsync(URI.create("wss://$SITE/adr/api/online/game"), Listener())
            .whenComplete { ws, err ->
                connecting = false
                if (err != null) { retryLater(); return@whenComplete }
                backoff = 5_000L
                sentParty = -1; sentWhere = ""; lastPing = 0L
                sending = ws.sendText(hello().toString(), true)
                socket = ws
            }
    }

    private fun retryLater() {
        socket = null
        retryAt = System.currentTimeMillis() + backoff
        backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
    }

    private fun send(o: JsonObject) = sendText(o.toString())

    private fun sendText(text: String) {
        val ws = socket ?: return
        sending = sending.handle { _, _ -> Unit }.thenCompose { ws.sendText(text, true) }
    }

    @Volatile var onRelay: ((String) -> Unit)? = null

    fun sendRelay(frames: List<String>): Boolean {
        if (socket == null) return false
        for (f in frames) sendText(f)
        return true
    }

    fun close() {
        val ws = socket
        socket = null
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
    }

    private class Listener : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val whole = text.toString()
                text.setLength(0)
                if (whole.startsWith("{\"t\":\"cb\"")) EngineerClient.safely("adr online relay") { onRelay?.invoke(whole) }
            }
            ws.request(1)
            return null
        }
        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (socket === ws) { if (statusCode == 4002) { stopped = true; socket = null } else retryLater() }
            return null
        }
        override fun onError(ws: WebSocket, error: Throwable) { if (socket === ws) retryLater() }
    }

    fun openPage() {
        Thread.ofPlatform().name("engineerclient-adr-online").daemon(true).start {
            try {
                val u = EngineerClient.mc.user
                val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                val body = JsonObject().apply { addProperty("key", key); addProperty("name", u.name); addProperty("uuid", u.profileId.toString().replace("-", "")) }
                val res = http.send(HttpRequest.newBuilder(URI.create("https://$SITE/adr/api/online/key"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer ${ADR.token()}")
                    .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() != 200) {
                    EngineerClient.msg("§cADR: the site didn't sign you in (${res.body().take(80)}). Try again in a minute.")
                    return@start
                }
                browse("https://$SITE/adr/online#k=$key")
                EngineerClient.msg("§aADR Online opened in your browser§7 - signed in there (any older sign-in is signed out).")
            } catch (t: Throwable) {
                EngineerClient.logger.warn("[ec] adr: online sign-in failed", t)
                EngineerClient.msg("§cADR: couldn't reach the site.")
            }
        }
    }

    private fun browse(url: String) {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> arrayOf("open", url)
            else -> arrayOf("xdg-open", url)
        }
        Runtime.getRuntime().exec(cmd)
    }
}
