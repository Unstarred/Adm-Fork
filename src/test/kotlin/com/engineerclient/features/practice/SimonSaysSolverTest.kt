package com.engineerclient.features.practice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simon Says' answer from lamp packets, the rounds traced by hand against the rules in [SimonSaysSolver]'s KDoc.
 * Cells are letters; ticks are server ticks, a lamp lit for 8 of them as the device does.
 */
class SimonSaysSolverTest {

    private fun SimonSaysSolver<String>.on(cell: String, t: Long) = lamp(cell, lit = true, was = false, tick = t)
    private fun SimonSaysSolver<String>.off(cell: String, t: Long) = lamp(cell, lit = false, was = true, tick = t)

    @Test
    fun `an ordinary round is the lights in the order they came on`() {
        val s = SimonSaysSolver<String>()
        s.on("A", 100); s.off("A", 108)
        s.on("B", 108); s.off("B", 116)
        s.buttonsUp(120)
        assertEquals(listOf("A", "B"), s.answer)
    }

    @Test
    fun `the answer shows the lights so far while the round is still lighting`() {
        val s = SimonSaysSolver<String>()
        s.on("A", 100)
        assertEquals(listOf("A"), s.answer)
        s.off("A", 108); s.on("C", 108)
        assertEquals(listOf("A", "C"), s.answer)
    }

    @Test
    fun `a light on for two ticks or less at the front is a stray`() {
        val s = SimonSaysSolver<String>()
        s.on("X", 100); s.off("X", 101)
        s.on("A", 102); s.off("A", 110)
        s.buttonsUp(112)
        assertEquals(listOf("A"), s.answer)
    }

    @Test
    fun `buttons back while the last light is on drop the first of three or more`() {
        val s = SimonSaysSolver<String>()
        s.on("A", 100); s.off("A", 108)
        s.on("B", 108); s.off("B", 116)
        s.on("C", 116)
        s.buttonsUp(120)
        assertEquals(listOf("B", "C"), s.answer)
    }

    @Test
    fun `presses move along the answer and catch up over a missed one`() {
        val s = SimonSaysSolver<String>()
        s.on("A", 100); s.off("A", 108)
        s.on("B", 108); s.off("B", 116)
        s.on("C", 116); s.off("C", 124)
        s.buttonsUp(128)
        assertEquals(listOf("A", "B", "C"), s.answer)
        s.pressed("A", 130)
        assertEquals(1, s.next)
        s.pressed("C", 132)
        assertEquals(3, s.next)
    }

    @Test
    fun `the buttons going away between rounds clears the answer`() {
        val s = SimonSaysSolver<String>()
        s.on("A", 100); s.off("A", 108)
        s.buttonsUp(110)
        s.pressed("A", 112)
        s.buttonsGone(120)
        assertTrue(s.answer.isEmpty())
        assertEquals(0, s.next)
    }

    @Test
    fun `reset forgets the round`() {
        val s = SimonSaysSolver<String>()
        s.on("A", 100)
        s.reset()
        assertTrue(s.answer.isEmpty())
        assertEquals(0, s.next)
    }
}
