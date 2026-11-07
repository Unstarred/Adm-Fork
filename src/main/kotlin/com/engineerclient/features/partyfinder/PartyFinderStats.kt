package com.engineerclient.features.partyfinder

import com.engineerclient.core.ClassDetect
import com.engineerclient.features.misc.RandomStuff
import com.engineerclient.util.noFormatting
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale

object PartyFinderStats {

    private const val PARTY_SIZE = 5
    private val PLAYABLE = DungeonClass.entries.filter { it != DungeonClass.EMPTY }

    private val memberLine = Regex("^(\\w{1,16}): (\\w+) \\((\\d+)\\)$")

    fun decorate(screen: AbstractContainerScreen<*>, stack: ItemStack, lines: List<Component>): List<Component> {
        if (!RandomStuff.showsPartyFinderStats()) return lines
        if (!screen.title.string.startsWith("Party Finder")) return lines
        if (!clean(stack.hoverName.string).endsWith("'s Party")) return lines

        val missing = missingClasses(lines)
        if (missing.isEmpty()) return lines
        return lines.map { line ->
            val raw = clean(line.string)
            if (raw.trim() != "Members:") line
            else line.copy().append(Component.literal((if (raw.endsWith(" ")) "" else " ") + missing))
        }
    }

    private fun missingClasses(lines: List<Component>): String {
        var inMembers = false
        var members = 0
        val taken = HashSet<DungeonClass>()
        for (line in lines) {
            val text = clean(line.string).trim()
            if (text == "Members:") { inMembers = true; continue }
            if (!inMembers) continue
            val member = memberLine.find(text) ?: continue
            members++
            classNamed(member.groupValues[2])?.let { taken += it }
        }
        if (members == 0) return ""
        if (members >= PARTY_SIZE) return "§8· §7full"
        val absent = PLAYABLE.filter { it !in taken }
        if (absent.isEmpty()) return ""
        val mine = ClassDetect.myClass()
        return "§8· §cmissing: " + absent.joinToString("§8, ") { clazz ->
            val label = clazz.name.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase)
            if (clazz == mine) "§l§${clazz.colorCode}$label§r"
            else "§${clazz.colorCode}$label"
        }
    }

    private fun classNamed(text: String): DungeonClass? = PLAYABLE.firstOrNull {
        it.name.equals(text, ignoreCase = true) || (it == DungeonClass.BERSERK && text.equals("Berserker", ignoreCase = true))
    }

    private fun clean(s: String) = s.noFormatting()
}
