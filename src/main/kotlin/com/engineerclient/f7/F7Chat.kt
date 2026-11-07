package com.engineerclient.f7

/**
 * The F7/M7 boss's chat lines ADM follows the fight by, as Hypixel sends them once their § codes are stripped
 * ([com.engineerclient.util.noFormatting]). One copy for every feature that keys on them (Timers, Sub Splits, Recore,
 * Term Info, Simon Says practice, Odin's tick timers, i4 completion); each used to keep its own.
 */
object F7Chat {
    /** Maxor's first line: the boss fight starts (P1). */
    const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"

    /** Storm's first line: P2 starts. */
    const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."

    /** Storm calling his lightning. */
    val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")

    /** Storm hit by a crushing pillar. */
    val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")

    /** Storm's death line: P2 is over. */
    const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."

    /** Goldor's first line: P3 starts, and the terminals with it. */
    const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    /** The core opens: the terminals are over. */
    const val CORE_OPEN = "The Core entrance is opening!"

    /** Necron's first line: F7 and M7 alike since the boss update (the other, "Finally, I heard so much about you.", is rare). */
    val NECRON_START = setOf(
        "[BOSS] Necron: You went further than any human before, congratulations.",
        "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
    )

    /** How every one of Necron's lines starts. */
    const val NECRON = "[BOSS] Necron: "
}
