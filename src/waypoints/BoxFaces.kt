package com.engineerclient.waypoints

import kotlin.math.abs
import kotlin.math.sqrt

enum class Face { EAST, WEST, SOUTH, NORTH, UP }

object BoxFaces {

    fun distance(eye: DoubleArray, dir: DoubleArray, min: DoubleArray, max: DoubleArray): Double? =
        cross(eye, dir, min, max)?.let { maxOf(it.enter, 0.0) }

    fun select(eye: DoubleArray, dir: DoubleArray, min: DoubleArray, max: DoubleArray): Face? {
        val hit = cross(eye, dir, min, max) ?: return null
        val len = sqrt(dir[X] * dir[X] + dir[Y] * dir[Y] + dir[Z] * dir[Z])
        if (hit.exitAxis == Y && hit.exitHigh && len > 0 && dir[Y] / len > UP) return Face.UP

        var exit = Double.POSITIVE_INFINITY
        var face: Face? = null
        for (axis in intArrayOf(X, Z)) {
            val d = dir[axis]
            if (abs(d) < 1e-6) continue
            val far = maxOf((min[axis] - eye[axis]) / d, (max[axis] - eye[axis]) / d)
            if (far < exit) {
                exit = far
                face = if (axis == X) (if (d > 0) Face.EAST else Face.WEST) else (if (d > 0) Face.SOUTH else Face.NORTH)
            }
        }
        return face
    }

    const val TOP_PITCH = -10f
    private const val UP = 0.17364817766693033

    fun editsTop(feet: DoubleArray, pitch: Float, min: DoubleArray, max: DoubleArray): Boolean =
        pitch < TOP_PITCH && (0..2).all { feet[it] >= min[it] && feet[it] < max[it] }

    const val MIN_X = 0; const val MIN_Y = 1; const val MIN_Z = 2; const val MAX_X = 3; const val MAX_Y = 4; const val MAX_Z = 5

    fun move(c: IntArray, face: Face, by: Int): Boolean {
        val (corner, sign, other) = when (face) {
            Face.EAST -> Triple(MAX_X, 1, MIN_X)
            Face.WEST -> Triple(MIN_X, -1, MAX_X)
            Face.SOUTH -> Triple(MAX_Z, 1, MIN_Z)
            Face.NORTH -> Triple(MIN_Z, -1, MAX_Z)
            Face.UP -> Triple(MAX_Y, 1, MIN_Y)
        }
        val next = c[corner] + sign * by
        val size = if (sign > 0) next - c[other] else c[other] - next
        if (size < 1) return false
        c[corner] = next
        return true
    }

    fun move(c: DoubleArray, face: Face, by: Int): Boolean {
        val (corner, sign, other) = when (face) {
            Face.EAST -> Triple(MAX_X, 1, MIN_X)
            Face.WEST -> Triple(MIN_X, -1, MAX_X)
            Face.SOUTH -> Triple(MAX_Z, 1, MIN_Z)
            Face.NORTH -> Triple(MIN_Z, -1, MAX_Z)
            Face.UP -> Triple(MAX_Y, 1, MIN_Y)
        }
        val next = c[corner] + sign * by
        val size = if (sign > 0) next - c[other] else c[other] - next
        if (size < 1 - 1e-9) return false
        c[corner] = next
        return true
    }

    private const val X = 0
    private const val Y = 1
    private const val Z = 2

    private class Crossing(val enter: Double, val enterAxis: Int, val enterHigh: Boolean, val exitAxis: Int, val exitHigh: Boolean)

    private fun cross(eye: DoubleArray, dir: DoubleArray, min: DoubleArray, max: DoubleArray): Crossing? {
        var enter = Double.NEGATIVE_INFINITY; var exit = Double.POSITIVE_INFINITY
        var enterAxis = -1; var enterHigh = false; var exitAxis = -1; var exitHigh = false
        for (axis in 0..2) {
            val o = eye[axis]; val d = dir[axis]
            if (d == 0.0) { if (o < min[axis] || o > max[axis]) return null; continue }
            val near = if (d > 0) (min[axis] - o) / d else (max[axis] - o) / d
            val far = if (d > 0) (max[axis] - o) / d else (min[axis] - o) / d
            if (near > enter) { enter = near; enterAxis = axis; enterHigh = d < 0 }
            if (far < exit) { exit = far; exitAxis = axis; exitHigh = d > 0 }
        }
        if (exit < maxOf(enter, 0.0)) return null
        return Crossing(enter, enterAxis, enterHigh, exitAxis, exitHigh)
    }
}
