package com.engineerclient.f7

object F7Chat {
    const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"

    const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."

    val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")

    val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")

    const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."

    const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    const val CORE_OPEN = "The Core entrance is opening!"

    val NECRON_START = setOf(
        "[BOSS] Necron: You went further than any human before, congratulations.",
        "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
    )

    const val NECRON = "[BOSS] Necron: "
}
