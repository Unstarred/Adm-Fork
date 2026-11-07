package com.engineerclient.splits

import com.odtheking.odin.utils.skyblock.SplitsGroup
import com.odtheking.odin.utils.skyblock.SplitsManager

object SimOdinSplits {
    private val groupField = runCatching { SplitsManager::class.java.getDeclaredField("currentSplits").apply { isAccessible = true } }.getOrNull()
    private val ticksField = runCatching { SplitsManager::class.java.getDeclaredField("tickCounter").apply { isAccessible = true } }.getOrNull()

    private fun group(): SplitsGroup? = groupField?.get(null) as? SplitsGroup
    private fun odinTicks(): Long = ticksField?.getLong(null) ?: 0L

    const val BLOOD_OPEN = "§2Blood Open"
    const val BLOOD_CLEAR = "§bBlood Clear"
    const val PORTAL = "§dPortal Entry"
    const val MAXOR = "§5Maxor"
    const val STORM = "§3Storm"
    const val TERMINALS = "§6Terminals"
    const val GOLDOR = "§7Goldor"
    const val NECRON = "§cNecron"

    private val OURS = mapOf(
        BLOOD_OPEN to SplitTracker.OPEN, BLOOD_CLEAR to SplitTracker.BLOOD, PORTAL to SplitTracker.PORTAL,
        MAXOR to SplitTracker.MAXOR, STORM to SplitTracker.STORM, TERMINALS to SplitTracker.TERMS,
        GOLDOR to SplitTracker.GOLDOR, NECRON to SplitTracker.NECRON,
    )

    fun odinName(ours: String): String? = OURS.entries.firstOrNull { it.value == ours }?.key

    fun target(odinName: String): Long =
        OdinSplitsLook.f7Target(odinName)?.let { (it * 1000).toLong() }
            ?: OURS[odinName]?.let { SplitPace.ref(it)?.ms } ?: 0L

    private val startRunMethod = runCatching { SplitsManager::class.java.getDeclaredMethod("startRun").apply { isAccessible = true } }.getOrNull()

    private var pending: String? = null
    private var headMs = 0L
    private var needStart = false

    private fun startOdinRun(): Boolean {
        runCatching { startRunMethod?.invoke(SplitsManager) }
        return group()?.splits?.isNotEmpty() == true
    }

    fun start(odinName: String, headMs: Long = 0) {
        pending = odinName
        this.headMs = headMs
        needStart = true
        tick()
    }

    fun stop() {
        pending = null
        needStart = false
        runCatching { groupField?.set(null, SplitsGroup(emptyList(), null)) }
    }

    fun tick() {
        val name = pending ?: return
        if (needStart) { if (!startOdinRun()) return; needStart = false }
        val g = group() ?: return
        val splits = g.splits
        val k = splits.indexOfFirst { it.name == name }
        if (k < 0) { pending = null; return }
        if (splits[k].time == 0L) {
            splits[k].time = System.currentTimeMillis() - headMs
            splits[k].ticks = odinTicks() - headMs / 50
        }
        for (j in k - 1 downTo 0) {
            val ms = target(splits[j].name)
            splits[j].time = splits[j + 1].time - ms
            splits[j].ticks = splits[j + 1].ticks - ms / 50
        }
        pending = null
    }
}
