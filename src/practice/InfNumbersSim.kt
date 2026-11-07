package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale
import java.util.Random

object InfNumbersSim : TermSimGUI(TerminalTypes.NUMBERS.termName, TerminalTypes.NUMBERS.windowSize) {
    private val CELLS = (1..2).flatMap { r -> (2..6).map { c -> r * 9 + c } }
    private const val SHOWN = 4
    private const val FRESH = 6

    private val rng = Random()
    @JvmStatic val queue = ArrayList<Int>()
    private val bag = ArrayDeque<Int>()
    private var openedMs = 0L
    private var lastMs = 0L
    private var firstMs = 0L
    private val gaps = ArrayList<Long>()

    @JvmStatic
    fun active(): Boolean = EngineerClient.mc.gui.screen() === this

    override fun create() {
        queue.clear(); bag.clear(); gaps.clear()
        while (queue.size < SHOWN) queue += pick()
        setSlots { if (it.index in CELLS) pane(it.index in queue) else blackPane }
        openedMs = System.currentTimeMillis(); lastMs = 0L; firstMs = 0L
    }

    private fun pick(): Int {
        if (bag.isEmpty()) {
            val fresh = CELLS.filter { it !in queue }.shuffled(rng).take(FRESH)
            bag += fresh
            bag += CELLS.filter { it !in fresh }.shuffled(rng)
        }
        return bag.removeFirst()
    }

    private fun pane(lit: Boolean) =
        ItemStack(if (lit) Items.STAINED_GLASS_PANE.red() else Items.STAINED_GLASS_PANE.lime()).apply { set(DataComponents.CUSTOM_NAME, Component.literal("")) }

    override fun slotClick(slot: Slot, button: Int) {
        if (slot.index != queue.firstOrNull()) return
        val now = System.currentTimeMillis()
        if (lastMs == 0L) firstMs = now - openedMs else gaps += now - lastMs
        lastMs = now
        queue.removeAt(0)
        slot.setSlot(pane(false))
        while (queue.size < SHOWN) {
            val added = pick()
            queue += added
            guiInventorySlots[added].setSlot(pane(true))
        }
        super.slotClick(slot, button)
    }

    override fun removed() {
        super.removed()
        if (gaps.isEmpty()) return
        val avg = gaps.average() / 1000.0
        val first = firstMs / 1000.0
        EngineerClient.msg("§7Inf numbers: §f${gaps.size + 1}§7 clicks §8· §f${fmt(avg)}s§7 between §8· §7a numbers ≈ §f${fmt(first + (CELLS.size - 1) * avg)}s §8(first click ${fmt(first)} + ${CELLS.size - 1} × ${fmt(avg)})")
        gaps.clear()
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v).let { if (v >= 1) String.format(Locale.ROOT, "%.2f", v) else it }
}
