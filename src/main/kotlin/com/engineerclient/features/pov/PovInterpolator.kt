package com.engineerclient.features.pov

import java.util.UUID

class PovInterpolator(
    private val capacity: Int = 40,
    private val evictAfterNanos: Long = 5_000_000_000L,
) {

    data class Sample(
        val nanos: Long,
        val x: Double,
        val y: Double,
        val z: Double,
        val headYaw: Float,
        val pitch: Float,
    )

    data class Pose(
        val x: Double,
        val y: Double,
        val z: Double,
        val headYaw: Float,
        val pitch: Float,
    )

    private val rings = HashMap<UUID, ArrayDeque<Sample>>()

    fun sample(id: UUID, nanos: Long, x: Double, y: Double, z: Double, headYaw: Float, pitch: Float) {
        val ring = rings.getOrPut(id) { ArrayDeque(capacity) }
        val s = Sample(nanos, x, y, z, headYaw, pitch)
        val newest = ring.lastOrNull()
        if (newest != null && nanos <= newest.nanos) {
            ring[ring.size - 1] = s
            return
        }
        ring.addLast(s)
        while (ring.size > capacity) ring.removeFirst()
    }

    fun sample(id: UUID, nanos: Long, pose: Pose) =
        sample(id, nanos, pose.x, pose.y, pose.z, pose.headYaw, pose.pitch)

    fun poseAt(id: UUID, nanos: Long): Pose? {
        val ring = rings[id] ?: return null
        if (ring.isEmpty()) return null
        val oldest = ring.first()
        if (nanos <= oldest.nanos) return pose(oldest)
        val newest = ring.last()
        if (nanos >= newest.nanos) return pose(newest)

        var i = ring.size - 1
        while (i > 0 && ring[i - 1].nanos > nanos) i--
        val a = ring[i - 1]
        val b = ring[i]
        val span = (b.nanos - a.nanos).toDouble()
        val t = if (span <= 0.0) 1.0 else ((nanos - a.nanos).toDouble() / span).coerceIn(0.0, 1.0)
        return Pose(
            x = lerp(t, a.x, b.x),
            y = lerp(t, a.y, b.y),
            z = lerp(t, a.z, b.z),
            headYaw = rotLerp(t.toFloat(), a.headYaw, b.headYaw),
            pitch = lerp(t, a.pitch.toDouble(), b.pitch.toDouble()).toFloat(),
        )
    }

    fun evict(nowNanos: Long) {
        rings.entries.removeAll { (_, ring) ->
            val newest = ring.lastOrNull() ?: return@removeAll true
            nowNanos - newest.nanos > evictAfterNanos
        }
    }

    fun newest(id: UUID): Sample? = rings[id]?.lastOrNull()

    fun clear() = rings.clear()

    private fun pose(s: Sample) = Pose(s.x, s.y, s.z, s.headYaw, s.pitch)

    companion object {
        fun lerp(t: Double, a: Double, b: Double) = a + (b - a) * t

        fun wrapDegrees(deg: Float): Float {
            var d = deg % 360.0f
            if (d >= 180.0f) d -= 360.0f
            if (d < -180.0f) d += 360.0f
            return d
        }

        fun rotLerp(t: Float, a: Float, b: Float): Float = wrapDegrees(a + wrapDegrees(b - a) * t)
    }
}
