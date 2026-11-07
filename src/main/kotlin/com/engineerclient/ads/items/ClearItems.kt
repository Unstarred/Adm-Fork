package com.engineerclient.ads.items

object ClearItems {

    const val PEARL_VOLUME = 0.5f

    const val PEARL_SPEED = 1.5f

    const val PEARL_SPREAD = 1.0f

    fun pearlPitch(r: Float): Float = 0.4f / (r * 0.4f + 0.8f)

    const val PEARL_PUFFS = 32

    const val SPIRIT_LEAP = "SPIRIT_LEAP"

    const val INFINILEAP = "INFINITE_SPIRIT_LEAP"

    fun leapCooldownTicks(id: String, mage: Boolean = false, doubled: Boolean = false): Int =
        if (id == SPIRIT_LEAP) 100 else if (mage) Math.round(40 * (1 - if (doubled) 0.74 else 0.49)).toInt() else 40

    fun leapConsumes(id: String): Boolean = id == SPIRIT_LEAP

    val INERT: Map<String, String> = mapOf(
        "DUNGEON_DECOY" to "Decoy",
        "INFLATABLE_JERRY" to "Inflatable Jerry",
        "DUNGEON_TRAP" to "Trap",
        "DEFUSE_KIT" to "Defuse Kit",
        "TRAINING_WEIGHTS" to "Training Weights",
        "HEALING_VIII_SPLASH_POTION" to "Healing VIII Splash Potion",
        "POTION" to "Healing VIII Splash Potion",
    )

    fun inertNote(name: String): String = "The $name ability is not in ADS yet."

    const val INERT_NOTE_GAP = 40
}
