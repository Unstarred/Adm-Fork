package com.engineerclient.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {

    @Test
    fun `colour and format codes go`() {
        assertEquals("[BOSS] Goldor: Who dares trespass into my domain?", "§4[BOSS] Goldor§r§c: Who dares trespass into my domain?".noFormatting())
    }

    @Test
    fun `hypixel's salt in the middle of a word goes too`() {
        assertEquals("Early Summer 19th", "Early Summer 19§wth".noFormatting())
        assertEquals("The Catacombs (F7)", "The Catac§uombs (F7)".noFormatting())
        assertEquals("", "§j".noFormatting())
    }

    @Test
    fun `plain text is left as it is`() {
        assertEquals("Purse: 1,234", "Purse: 1,234".noFormatting())
    }
}
