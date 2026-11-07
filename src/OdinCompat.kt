package com.engineerclient

import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object OdinCompat {

    private val batCount: MethodHandle by lazy {
        val lookup = MethodHandles.publicLookup()
        val stats = DungeonListener.DungeonStats::class.java
        runCatching { lookup.findVirtual(stats, "getNumBatsKilled", MethodType.methodType(Int::class.javaPrimitiveType)) }
            .getOrElse { lookup.findVirtual(stats, "getBatKilled", MethodType.methodType(Int::class.javaPrimitiveType)) }
    }

    fun batsKilled(stats: DungeonListener.DungeonStats = DungeonListener.dungeonStats): Int = batCount.invoke(stats) as Int

    private fun dungeonUtilsInt(getter: String): MethodHandle =
        MethodHandles.publicLookup().findVirtual(DungeonUtils::class.java, getter, MethodType.methodType(Int::class.javaPrimitiveType))
            .bindTo(DungeonUtils)

    private val neededSecretsHandle by lazy { dungeonUtilsInt("getNeededSecretsAmount") }
    private val bonusScoreHandle by lazy { dungeonUtilsInt("getGetBonusScore") }
    private val scoreHandle by lazy { dungeonUtilsInt("getScore") }

    fun neededSecretsAmount(): Int = neededSecretsHandle.invoke() as Int

    fun bonusScore(): Int = bonusScoreHandle.invoke() as Int

    fun score(): Int = scoreHandle.invoke() as Int
}
