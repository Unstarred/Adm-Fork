package com.engineerclient.p3sim

import com.engineerclient.dungeonpack.pack.Packs
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

object LagRuntime {
    private val log = LoggerFactory.getLogger("engineerclient")

    @Volatile var settingPreset: LagPreset = LagPreset.OFF
    @Volatile var burstRate: Double = LagBurst.DEFAULT_RATE

    val effective: LagPreset get() = settingPreset

    val stats = LagStats()

    private var model: LagGenerator? = null
    private var modelFor: LagPreset? = null
    private var modelPack: Any? = null
    private var burstLeft = 0
    private var lastTickNs = 0L
    private var nextTickField: Field? = null
    private var fieldLooked = false

    val canForget: Boolean get() { lookField(); return nextTickField != null }

    private fun lookField() {
        if (fieldLooked) return
        fieldLooked = true
        nextTickField = try { MinecraftServer::class.java.getDeclaredField("nextTickTimeNanos").apply { isAccessible = true } }
        catch (t: Throwable) { log.warn("[ads] the lag layer cannot reach the server's tick clock (${t.javaClass.simpleName}); lost ticks will be caught up in full"); null }
    }

    fun reset() { model = null; modelFor = null; burstLeft = 0; lastTickNs = 0; stats.clear() }

    private fun modelFor(p: LagPreset): LagGenerator? {
        if (p == LagPreset.OFF) return null
        val pack = Packs.current?.runstate
        if (model != null && modelFor == p && modelPack === pack) return model
        val loss = LagPresets.lossPerMinute(p, pack?.lag?.tiers)
        model = LagGenerator(LagParams.fromPack(pack?.lag?.raw), loss)
        modelFor = p; modelPack = pack
        log.info("[ads] lag layer: ${p.label}, ${"%.1f".format(loss)} ticks lost a minute, ${"%.2f".format(model!!.freezesPerMinute)} freezes a minute")
        return model
    }

    fun afterTick(server: MinecraftServer) {
        val now = System.nanoTime()
        stats.record(now)
        val preset = effective
        val m = modelFor(preset)
        if (m == null) { burstLeft = 0; lastTickNs = now; return }
        if (burstLeft > 0) {
            burstLeft--
            val wait = LagBurst.sleepMs((now - lastTickNs) / 1e6, burstRate)
            if (wait >= 1.0) sleep(wait)
            lastTickNs = System.nanoTime()
            return
        }
        val ev = m.nextTick()
        if (ev == null) { lastTickNs = now; return }
        sleep(ev.gapMs - LagGenerator.TICK_MS)
        val behind = behindTicks(server)
        if (behind == null) { burstLeft = LagBurst.ticksFor(ev.recoveredTicks, burstRate); lastTickNs = System.nanoTime(); return }
        val keep = ev.recoveredTicks.coerceAtMost(behind)
        val forget = behind - keep
        if (forget > 0) nextTickField?.let { f -> f.setLong(server, minOf(f.getLong(server) + forget * (LagGenerator.TICK_MS * 1e6).toLong(), Util.getNanos())) }
        burstLeft = LagBurst.ticksFor(keep, burstRate)
        lastTickNs = System.nanoTime()
    }

    private fun behindTicks(server: MinecraftServer): Int? {
        lookField()
        val f = nextTickField ?: return null
        val behindNs = Util.getNanos() - f.getLong(server)
        return if (behindNs <= 0) 0 else (behindNs / (LagGenerator.TICK_MS * 1e6)).toInt()
    }

    private fun sleep(ms: Double) {
        if (ms < 1.0) return
        try { Thread.sleep(ms.toLong(), ((ms - ms.toLong()) * 1e6).toInt()) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
