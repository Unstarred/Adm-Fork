package com.engineerclient.f7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The F7/M7 boss room's fixed geometry: the four P3 sections and Goldor's core room. */
class P3SectionsTest {

    @Test
    fun `each section's middle is in that section`() {
        assertEquals(1, P3Sections.sectionAt(101.0, 120.0, 85.0))
        assertEquals(2, P3Sections.sectionAt(55.0, 120.0, 133.0))
        assertEquals(3, P3Sections.sectionAt(6.0, 120.0, 86.0))
        assertEquals(4, P3Sections.sectionAt(53.0, 120.0, 38.0))
        assertTrue(P3Sections.isInSection(2, 55.0, 120.0, 133.0))
        assertFalse(P3Sections.isInSection(1, 55.0, 120.0, 133.0))
    }

    @Test
    fun `outside the boss room is no section`() {
        assertEquals(0, P3Sections.sectionAt(0.0, 70.0, 0.0))
    }

    @Test
    fun `the core box runs from the core floor up`() {
        assertTrue(P3Sections.inCoreBox(55.0, 115.0, 80.0))
        assertFalse(P3Sections.inCoreBox(55.0, 109.9, 80.0))
        assertFalse(P3Sections.inCoreBox(71.0, 115.0, 80.0))
        assertTrue(P3Sections.inCoreBox(39.0, 110.0, 54.0))
    }
}
