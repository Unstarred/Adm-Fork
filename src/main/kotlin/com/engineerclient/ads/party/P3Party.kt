package com.engineerclient.ads.party

import com.engineerclient.EngineerClient
import com.engineerclient.ads.core.SimServer
import com.engineerclient.util.Http
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.util.HttpUtil
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object P3Party {
    private const val RELAY = "wss://undonecoffee.com/p3sim/api"
    private const val CHUNK = 32 * 1024

    @JvmStatic @Volatile var loopbackOnly = false
        private set

    private val mc get() = Minecraft.getInstance()

    private val version: String by lazy {
        FabricLoader.getInstance().getModContainer("adm").map { it.metadata.version.friendlyString }.orElse("?").substringBefore('+').substringBefore('-')
    }

    private val http: HttpClient get() = Http.client

    private class Host(val port: Int) {
        @Volatile var ws: WebSocket? = null
        @Volatile var code: String? = null
        val sockets = ConcurrentHashMap<Int, Socket>()
        val sendLock = Any()
    }

    @Volatile private var host: Host? = null

    val hosting get() = host != null

    val joined: Boolean get() = !mc.hasSingleplayerServer() && (mc.currentServer?.name?.startsWith(PARTY_NAME) == true || AdsServer.inHostsSim)
    private const val PARTY_NAME = "ADS party"
    val code: String? get() = host?.code

    fun players(): List<String> = runCatching { SimServer.server?.playerList?.players?.map { it.gameProfile.name }.orEmpty() }.getOrDefault(emptyList())

    fun host() {
        if (host != null) return
        val server = mc.singleplayerServer ?: return
        if (!SimServer.isSim(server)) return
        loopbackOnly = true
        val port = if (server.isPublished) server.port else HttpUtil.getAvailablePort()
        if (!server.isPublished && !server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port)) {
            loopbackOnly = false
            EngineerClient.msg("§cCouldn't open your sim to a party.")
            return
        }
        val h = Host(port)
        host = h
        connect("$RELAY/host?v=${enc(version)}",
            onText = { t -> onHostText(h, t) },
            onBinary = { b -> if (b.isNotEmpty()) h.sockets[b[0].toInt() and 0xff]?.let { s -> runCatching { s.getOutputStream().write(b, 1, b.size - 1) }.onFailure { runCatching { s.close() } } } },
            onClose = { why -> if (host === h) { EngineerClient.msg("§cThe party closed${why?.let { ": $it" } ?: ""}."); mc.execute { stopHosting() } } },
        ).whenComplete { ws, err ->
            if (err != null) { EngineerClient.msg("§cCouldn't reach the party relay: ${err.cause?.message ?: err.message}"); mc.execute { stopHosting() } }
            else h.ws = ws
        }
    }

    private fun onHostText(h: Host, text: String) {
        val m = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (m.get("t")?.asString) {
            "code" -> { h.code = m.get("code").asString; EngineerClient.msg("§7Party code: §b§l${h.code}§7") }
            "open" -> {
                val id = m.get("id").asInt
                val s = runCatching { Socket(InetAddress.getLoopbackAddress(), h.port).also { it.tcpNoDelay = true } }.getOrElse {
                    sendText(h, """{"t":"close","id":$id}"""); return
                }
                h.sockets[id] = s
                pump("p3party-host-$id", s, { buf, n -> sendBinary(h, ByteArray(n + 1).also { it[0] = id.toByte(); System.arraycopy(buf, 0, it, 1, n) }) }) {
                    if (h.sockets.remove(id, s)) sendText(h, """{"t":"close","id":$id}""")
                }
            }
            "close" -> h.sockets.remove(m.get("id").asInt)?.let { runCatching { it.close() } }
        }
    }

    private fun sendText(h: Host, t: String) = synchronized(h.sendLock) { runCatching { h.ws?.sendText(t, true)?.get(10, TimeUnit.SECONDS) } }
    private fun sendBinary(h: Host, b: ByteArray) = synchronized(h.sendLock) { h.ws?.sendBinary(ByteBuffer.wrap(b), true)?.get(30, TimeUnit.SECONDS) }

    fun stopHosting(unpublish: Boolean = true) {
        val h = host ?: return
        host = null
        h.sockets.values.forEach { runCatching { it.close() } }
        h.sockets.clear()
        runCatching { h.ws?.sendClose(WebSocket.NORMAL_CLOSURE, "closed") }
        runCatching { h.ws?.abort() }
        if (unpublish) mc.singleplayerServer?.let { s -> if (SimServer.isSim(s) && s.isPublished) runCatching { s.unpublishServer() } }
        loopbackOnly = false
    }

    fun join(code: String, asVersion: String = version) {
        val c = code.trim().uppercase()
        if (!Regex("[A-HJ-NP-Z2-9]{4}").matches(c)) { EngineerClient.msg("§cA party code is 4 letters and numbers."); return }
        if (host != null) { EngineerClient.msg("§cClose your own party first."); return }
        val local = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sock = CompletableFuture<Socket>()
        val lock = Any()
        var wsRef: WebSocket? = null
        connect("$RELAY/join/$c?v=${enc(asVersion)}",
            onText = { t ->
                val m = runCatching { JsonParser.parseString(t).asJsonObject }.getOrNull()
                when (m?.get("t")?.asString) {
                    "error" -> {
                        runCatching { local.close() }
                        val msg = m.get("msg").asString
                        val theirs = Regex("host has (?:EngineerClient|ADM) (\\S+?),").find(msg)?.groupValues?.get(1)
                        if (theirs != null && theirs != asVersion && theirs.substringBefore('+').substringBefore('-') == version) mc.execute { join(c, theirs) }
                        else EngineerClient.msg("§c$msg")
                    }
                    "ok" -> {
                        Thread({
                            val s = runCatching { local.soTimeout = 30_000; local.accept().also { it.tcpNoDelay = true } }.getOrNull()
                            runCatching { local.close() }
                            if (s == null) { runCatching { wsRef?.abort() }; return@Thread }
                            sock.complete(s)
                            pump("p3party-join", s, { buf, n -> synchronized(lock) { wsRef?.sendBinary(ByteBuffer.wrap(buf, 0, n), true)?.get(30, TimeUnit.SECONDS) } }) {
                                runCatching { wsRef?.sendClose(WebSocket.NORMAL_CLOSURE, "left") }
                            }
                        }, "p3party-accept").apply { isDaemon = true }.start()
                        mc.execute {
                            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Joining party $c"))
                            ConnectScreen.startConnecting(TitleScreen(), mc, ServerAddress("127.0.0.1", local.localPort),
                                ServerData("$PARTY_NAME $c", "127.0.0.1:${local.localPort}", ServerData.Type.OTHER), false, null)
                        }
                    }
                }
            },
            onBinary = { b -> runCatching { sock.get(30, TimeUnit.SECONDS).getOutputStream().write(b) } },
            onClose = { _ -> runCatching { local.close() }; if (sock.isDone) runCatching { sock.get().close() } },
        ).whenComplete { ws, err ->
            if (err != null) { runCatching { local.close() }; EngineerClient.msg("§cCouldn't reach the party relay: ${err.cause?.message ?: err.message}") }
            else wsRef = ws
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun pump(name: String, s: Socket, send: (ByteArray, Int) -> Unit, done: () -> Unit) {
        Thread({
            val buf = ByteArray(CHUNK)
            try {
                val inp = s.getInputStream()
                while (true) { val n = inp.read(buf); if (n < 0) break; if (n > 0) send(buf, n) }
            } catch (_: Exception) {
            } finally { runCatching { s.close() }; done() }
        }, name).apply { isDaemon = true }.start()
    }

    private fun connect(url: String, onText: (String) -> Unit, onBinary: (ByteArray) -> Unit, onClose: (String?) -> Unit): CompletableFuture<WebSocket> {
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            private val bin = ByteArrayOutputStream()
            private var closed = false
            private fun close(why: String?) { if (!closed) { closed = true; EngineerClient.safely("p3party close") { onClose(why) } } }
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) { val t = text.toString(); text.setLength(0); EngineerClient.safely("p3party text") { onText(t) } }
                ws.request(1); return null
            }
            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val a = ByteArray(data.remaining()); data.get(a); bin.write(a)
                if (last) { val b = bin.toByteArray(); bin.reset(); EngineerClient.safely("p3party bytes") { onBinary(b) } }
                ws.request(1); return null
            }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { close(reason.takeIf { it.isNotBlank() && it != "bye" }); return null }
            override fun onError(ws: WebSocket, error: Throwable) { close(error.message) }
        }
        return http.newWebSocketBuilder().buildAsync(URI.create(url), listener)
    }
}
