package com.engineerclient.clearbreakdown

class RunGate {
    private var ended = false
    private var won = false
    private var fired = false

    fun line(text: String): Boolean {
        if (fired) return false
        if (END.containsMatchIn(text)) ended = true
        else if (DEFEATED.containsMatchIn(text)) won = true
        else return false
        fired = ended && won
        return fired
    }

    fun reset() {
        ended = false; won = false; fired = false
    }

    private companion object {
        val END = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")
        val DEFEATED = Regex("^\\s*☠ Defeated ")
    }
}
