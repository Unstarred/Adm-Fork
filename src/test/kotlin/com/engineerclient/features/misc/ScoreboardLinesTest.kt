package com.engineerclient.features.misc

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Clean Scoreboard's decision over a sidebar line's plain text (codes and Hypixel's salt already stripped). The lines
 * are the shapes the patterns are written for, not recorded sidebars.
 */
class ScoreboardLinesTest {

    private val defaults = Triple(ScoreboardLines.hideCatacombsLocation, ScoreboardLines.hideTimeElapsed, ScoreboardLines.hideCleared)

    @AfterTest
    fun restore() {
        ScoreboardLines.hideCatacombsLocation = defaults.first
        ScoreboardLines.hideTimeElapsed = defaults.second
        ScoreboardLines.hideCleared = defaults.third
    }

    @Test
    fun `the noisy lines are hidden`() {
        for (line in listOf("11/06/26 m15F", "Early Summer 19th", "9:40pm ☀", "Objective", "www.hypixel.net", "Solo", "Keys: ■ ✖", ""))
            assertTrue(ScoreboardLines.hides(line), line)
    }

    @Test
    fun `purse, bits and teammates stay`() {
        for (line in listOf("Purse: 1,234,567", "Piggy: 12 (+3)", "Bits: 4,200", "[M] Steve 12,345❤", "[A] Alex [Lv42]"))
            assertFalse(ScoreboardLines.hides(line), line)
    }

    @Test
    fun `a line no pattern knows is left alone`() {
        assertFalse(ScoreboardLines.hides("Something new from Hypixel"))
        assertNull(ScoreboardLines.kindOf("Something new from Hypixel", null))
    }

    @Test
    fun `the objective's task is only known by the line above it`() {
        assertFalse(ScoreboardLines.hides("Talk to Enid"))
        assertEquals(ScoreboardLines.Kind.OBJECTIVE_TASK, ScoreboardLines.kindOf("Talk to Enid", ScoreboardLines.Kind.OBJECTIVE))
        assertTrue(ScoreboardLines.hides("Talk to Enid", ScoreboardLines.Kind.OBJECTIVE))
    }

    @Test
    fun `the three dungeon lines follow their own settings`() {
        assertEquals(ScoreboardLines.Kind.LOCATION_CATACOMBS, ScoreboardLines.kindOf("⏣ The Catacombs (F7)", null))
        assertEquals(ScoreboardLines.Kind.TIME_ELAPSED, ScoreboardLines.kindOf("Time Elapsed: 05m 12s", null))
        assertEquals(ScoreboardLines.Kind.CLEARED, ScoreboardLines.kindOf("Cleared: 45% (123)", null))
        ScoreboardLines.hideCatacombsLocation = false; ScoreboardLines.hideTimeElapsed = false; ScoreboardLines.hideCleared = false
        assertFalse(ScoreboardLines.hides("⏣ The Catacombs (F7)"))
        assertFalse(ScoreboardLines.hides("Time Elapsed: 05m 12s"))
        assertFalse(ScoreboardLines.hides("Cleared: 45% (123)"))
        ScoreboardLines.hideCatacombsLocation = true; ScoreboardLines.hideTimeElapsed = true; ScoreboardLines.hideCleared = true
        assertTrue(ScoreboardLines.hides("⏣ The Catacombs (F7)"))
        assertTrue(ScoreboardLines.hides("Time Elapsed: 05m 12s"))
        assertTrue(ScoreboardLines.hides("Cleared: 45% (123)"))
    }
}
