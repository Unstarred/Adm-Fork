package com.engineerclient.p3sim

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class P(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: P) = P(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: P) = P(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = P(x * k, y * k, z * k)
    fun dist(o: P) = Math.sqrt((x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) + (z - o.z) * (z - o.z))
}

class GhostRun(
    val clazz: String,
    val skill: String,
    val time: Int,
    val frames: List<Frame>,
    val events: List<Ev>,
    val sectionN: IntArray,
    val date: Long = 0,
) {
    data class Frame(val p: P, val yaw: Float, val pitch: Float, val held: String = "")

    data class Ev(val n: Int, val type: String, val a: String = "", val k: Int = 0)

    fun frame(n: Int): Frame = frames[n.coerceIn(0, frames.size - 1)]
    val end get() = frames.size - 1

    val jobs: List<String> get() = events.filter { it.type == "done" }.map { it.a } + events.filter { it.type == "gate" }.map { "gate ${it.k}" }

    fun toJson(): String {
        val o = JsonObject()
        o.addProperty("v", VERSION)
        o.addProperty("class", clazz)
        o.addProperty("skill", skill)
        o.addProperty("time", time)
        o.addProperty("date", date)
        o.add("sections", JsonArray().apply { sectionN.forEach { add(it) } })
        val f = JsonArray()
        var held = ""
        val heldAt = JsonArray()
        frames.forEachIndexed { i, fr ->
            f.add(r(fr.p.x)); f.add(r(fr.p.y)); f.add(r(fr.p.z)); f.add(r1(fr.yaw.toDouble())); f.add(r1(fr.pitch.toDouble()))
            if (fr.held != held) { held = fr.held; heldAt.add(JsonArray().apply { add(i); add(held) }) }
        }
        o.add("frames", f)
        o.add("held", heldAt)
        o.add("events", JsonArray().apply {
            events.forEach { e -> add(JsonArray().apply { add(e.n); add(e.type); add(e.a); add(e.k) }) }
        })
        return o.toString()
    }

    companion object {
        const val VERSION = 1

        private fun r(d: Double) = Math.round(d * 100) / 100.0
        private fun r1(d: Double) = Math.round(d * 10) / 10.0

        fun fromJson(text: String): GhostRun? = runCatching {
            val o = JsonParser.parseString(text).asJsonObject
            if (o.get("v")?.asInt != VERSION) return null
            val f = o.getAsJsonArray("frames")
            if (f.size() == 0 || f.size() % 5 != 0) return null
            val heldAt = o.getAsJsonArray("held")?.map { it.asJsonArray.let { a -> a[0].asInt to a[1].asString } }.orEmpty()
            var h = 0
            var held = ""
            val frames = (0 until f.size() / 5).map { i ->
                while (h < heldAt.size && heldAt[h].first <= i) { held = heldAt[h].second; h++ }
                Frame(P(f[i * 5].asDouble, f[i * 5 + 1].asDouble, f[i * 5 + 2].asDouble), f[i * 5 + 3].asFloat, f[i * 5 + 4].asFloat, held)
            }
            val events = o.getAsJsonArray("events").map { it.asJsonArray.let { a -> Ev(a[0].asInt, a[1].asString, a[2].asString, a[3].asInt) } }
            val sections = o.getAsJsonArray("sections").map { it.asInt }.toIntArray()
            if (sections.size != 6) return null
            val time = o.get("time").asInt
            if (time !in 0 until frames.size) return null
            GhostRun(o.get("class").asString, o.get("skill").asString, time, frames, events, sections, o.get("date")?.asLong ?: 0)
        }.getOrNull()
    }
}
