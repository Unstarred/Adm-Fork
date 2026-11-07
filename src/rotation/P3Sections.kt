package com.engineerclient.rotation

object P3Sections {

    private val BOXES = arrayOf(
        doubleArrayOf(113.0, 160.0, 48.0, 89.0, 100.0, 122.0),
        doubleArrayOf(91.0, 160.0, 145.0, 19.0, 100.0, 121.0),
        doubleArrayOf(-6.0, 160.0, 123.0, 19.0, 100.0, 50.0),
        doubleArrayOf(17.0, 160.0, 27.0, 90.0, 100.0, 50.0),
    )

    fun sectionAt(x: Double, y: Double, z: Double): Int {
        BOXES.forEachIndexed { i, b ->
            if (within(x, b[0], b[3]) && within(y, b[1], b[4]) && within(z, b[2], b[5])) return i + 1
        }
        return 0
    }

    fun isInSection(section: Int, x: Double, y: Double, z: Double): Boolean =
        section in 1..BOXES.size && sectionAt(x, y, z) == section

    private fun within(v: Double, a: Double, b: Double) = v >= minOf(a, b) && v <= maxOf(a, b)
}
