package com.engineerclient.ads.bots

import com.engineerclient.EngineerClient
import net.minecraft.client.Minecraft
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.plan.P3Plan
import com.engineerclient.ads.plan.Roles

class GhostRecording(val clazz: String, val skill: String) {
    val frames = ArrayList<GhostRun.Frame>()
    val events = ArrayList<GhostRun.Ev>()
    val sectionN = IntArray(6) { -1 }.also { it[1] = 0 }

    fun frame(n: Int, f: GhostRun.Frame) {
        if (n < 0) return
        while (frames.size < n) frames += frames.lastOrNull() ?: f
        if (frames.size == n) frames += f else frames[n] = f
    }

    fun event(n: Int, type: String, a: String = "", k: Int = 0) { if (n >= 0) events += GhostRun.Ev(n, type, a, k) }

    fun section(s: Int, n: Int) { if (s in 1..5 && sectionN[s] < 0) sectionN[s] = n }

    fun finish(date: Long = System.currentTimeMillis()): GhostRun? {
        val time = sectionN[5]
        if (time < 0 || frames.isEmpty() || time >= frames.size) return null
        val end = frames.size - 1
        val evs = events.filter { it.n in 0..end }.map { e ->
            if (e.type != "leap") e else {
                val m = (e.n..minOf(e.n + 2, end)).filter { it > 0 }.maxByOrNull { frames[it].p.dist(frames[it - 1].p) }
                if (m != null && frames[m].p.dist(frames[m - 1].p) > LEAP_JUMP) e.copy(n = m) else e
            }
        }.withIndex().sortedWith(compareBy({ it.value.n }, { it.index })).map { it.value }
        return GhostRun(clazz, skill, time, frames.toList(), evs, sectionN.copyOf(), date)
    }

    companion object {
        const val LEAP_JUMP = 4.0
    }
}

object GhostStore {
    private val cache = HashMap<String, GhostRun?>()
    var root: File? = null
    private val dir get() = root ?: File(Minecraft.getInstance().gameDirectory, "config/adm/p3sim-best")

    private fun file(skill: String, clazz: String) = File(File(dir, skill.replace(Regex("[^A-Za-z0-9 _-]"), "_")), "$clazz.json.gz")

    @Synchronized fun best(skill: String, clazz: String): GhostRun? = cache.getOrPut("$skill/$clazz") {
        runCatching { file(skill, clazz).takeIf { it.exists() }?.let { f -> f.inputStream().use { raw -> GZIPInputStream(raw).use { GhostRun.fromJson(it.readBytes().decodeToString()) } } } }.getOrNull()
    }

    fun better(new: GhostRun, old: GhostRun?) = old == null || new.time <= old.time

    @Synchronized fun offer(run: GhostRun): Pair<Boolean, GhostRun?> {
        val old = best(run.skill, run.clazz)
        if (!better(run, old)) return false to old
        val f = file(run.skill, run.clazz)
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(run.toJson().encodeToByteArray()) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        cache["${run.skill}/${run.clazz}"] = run
        return true to old
    }

    @Synchronized fun forget(skill: String, clazz: String) { file(skill, clazz).delete(); cache.remove("$skill/$clazz") }

    @Synchronized fun clearCache() = cache.clear()
}

object GhostCapture {
    private var rec: GhostRecording? = null
    private var phase: GoldorPhase? = null
    private var wasSwinging = false
    private var inCoreFor = 0

    fun start(p: GoldorPhase) {
        rec = null
        phase = null
        if (p.from != 1 || p.practice) return
        val c = P3Sim.myClass ?: return
        rec = GhostRecording(c.name, P3Plan.skillName())
        phase = p
        inCoreFor = 0
    }

    fun drop() { rec = null; phase = null }

    fun invalidate(why: String) {
        if (rec == null) return
        drop()
        Sim.note("§7This run won't count as a best ($why).")
    }

    fun stopped(p: GoldorPhase) {
        if (p !== phase) return
        if (p.section >= 5) keep() else drop()
    }

    fun event(type: String, a: String = "", k: Int = 0) {
        val p = phase ?: return
        rec?.event(p.n, type, a, k)
    }

    fun tick(p: GoldorPhase) {
        val r = rec ?: return
        if (p !== phase) return
        val player = Sim.player ?: return
        if (player.isCreative || player.isSpectator || player.abilities.flying) { invalidate("creative, spectator or flying"); return }
        val n = p.n
        r.section(p.section, n)
        val pos = player.position()
        r.frame(n, GhostRun.Frame(P(pos.x, pos.y, pos.z), player.yRot, player.xRot, heldKey(SimItems.idOf(player.mainHandItem))))
        if (player.isSwinging && !wasSwinging) r.event(n, "swing")
        wasSwinging = player.isSwinging
        if (p.section >= 5) {
            if (GoldorPhase.CORE_BOX.contains(pos)) inCoreFor++
            if (inCoreFor >= KEEP_IN_CORE || n - r.sectionN[5] >= KEEP_MAX) keep()
        }
    }

    private fun keep() {
        val r = rec ?: return
        rec = null
        phase = null
        val run = r.finish() ?: return
        EngineerClient.safely("p3sim ghost save") {
            val (saved, old) = GhostStore.offer(run)
            val label = Roles.label(Party.CLASSES.first { it.name == run.clazz })
            if (saved) Sim.note("§dNew best as $label§7 (${run.skill}): §f${secs(run.time)}§7${old?.let { " (was ${secs(it.time)})" } ?: ""}. The $label bot can play it (menu > Plan).")
        }
    }

    private fun secs(n: Int) = "%.2fs".format(Locale.ROOT, n / 20.0)

    fun heldKey(id: String?): String = id?.takeIf { it != "SKYBLOCK_MENU" } ?: ""

    private const val KEEP_IN_CORE = 40
    private const val KEEP_MAX = 400
}
