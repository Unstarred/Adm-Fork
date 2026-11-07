package com.engineerclient

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

object ClassDetect {

    var detected: DungeonClass? = null
        private set

    fun reset() {
        detected = null
    }

    fun poll() {
        if (!DungeonUtils.inDungeons) return
        val clazz = DungeonUtils.currentDungeonPlayer.clazz
        if (clazz == DungeonClass.EMPTY || clazz == detected) return

        detected = clazz
        EngineerClient.logger.info("[ec] detected own class from tab: ${clazz.name}")
        if (EcConfig.data.lastKnownClass != clazz.name) {
            EcConfig.data.lastKnownClass = clazz.name
            EcConfig.save()
        }
    }

    fun myClass(): DungeonClass? =
        detected ?: EcConfig.data.lastKnownClass?.let { stash ->
            DungeonClass.entries.firstOrNull { it != DungeonClass.EMPTY && (it.name == stash || stashName(it) == stash) }
        }

    private fun stashName(clazz: DungeonClass): String =
        if (clazz == DungeonClass.BERSERK) "Berserker" else clazz.name.lowercase().replaceFirstChar(Char::titlecase)
}
