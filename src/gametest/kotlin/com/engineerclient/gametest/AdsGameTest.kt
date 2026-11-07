package com.engineerclient.gametest

import com.engineerclient.EngineerClient
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.party.SimActions
import com.engineerclient.ads.plan.Practice
import com.engineerclient.ads.ui.SimRestartScreen
import com.engineerclient.features.pov.PovCapture
import com.engineerclient.features.pov.PovPreviews
import com.odtheking.odin.clickgui.ClickGUI
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

class AdsGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val c = Checks(context, "ads")
        c.phase("ADS opens") { context.enterAds() }
        if (!context.onClient { P3Sim.inSim }) { c.expect("not in ADS: the rest can't run", false); c.finish(); return }

        c.phase("P3 starts on join and the bots play it") {
            context.waitFor({ Fight.phase is GoldorPhase }, 20 * 60)
            context.waitFor({ (Fight.phase as? GoldorPhase)?.stations?.any { it.done } == true }, 20 * 60)
            val done = context.onClient { (Fight.phase as? GoldorPhase)?.stations?.count { it.done } ?: 0 }
            c.note("$done stations done by the bots")
            context.takeScreenshot("ads-p3")
        }

        c.phase("the leap menu and its teammate previews") {
            val slot = context.onClient { mc ->
                (0 until 9).firstOrNull { SimItems.idOf(mc.player!!.inventory.getItem(it)) == "INFINITE_SPIRIT_LEAP" } ?: -1
            }
            c.expect("no Infinileap on the hotbar", slot >= 0)
            if (slot >= 0) {
                context.getInput().pressKey { options -> options.keyHotbarSlots[slot] }
                context.waitTicks(2)
                context.getInput().pressKey { options -> options.keyUse }
                context.waitFor({ mc -> (mc.gui.screen() as? AbstractContainerScreen<*>)?.title?.string == "Spirit Leap" }, 20 * 10)
                context.waitTicks(20 * 3)
                c.expect("the previews don't want to draw in the leap menu", context.onClient { PovPreviews.wants() })
                val cost = context.onClient { PovCapture.costLine() }
                c.expect("no preview cost line: the previews didn't run", cost != null)
                val plain = cost?.replace(Regex("§."), "")
                c.note("preview cost line: $plain")
                val live = plain?.let { Regex("(\\d)/4").find(it)?.groupValues?.get(1)?.toInt() } ?: 0
                c.expect("no teammate preview is live ($plain)", live > 0)
                context.takeScreenshot("ads-leap-menu")
                context.onClient { PovPreviews.toggle() }
                context.waitTicks(10)
                context.takeScreenshot("ads-leap-menu-previews-off")
                context.onClient { PovPreviews.toggle() }
                context.waitTicks(10)
                context.onClient { mc -> mc.player!!.closeContainer() }
                context.waitTicks(10)
            }
        }

        c.phase("every tab of the sim menu") {
            context.setScreen { SimRestartScreen() }
            context.waitTicks(5)
            context.takeScreenshot("ads-menu")
            for (tab in listOf("Plan", "Boss", "Settings", "Teleport", "Practice")) {
                context.clickScreenButton(tab)
                context.waitTicks(5)
                context.takeScreenshot("ads-menu-${tab.lowercase()}")
            }
            context.setScreen { null }
            context.waitTicks(5)
        }

        c.phase("Odin's ClickGUI, ADM's panel with it") {
            context.setScreen { ClickGUI }
            context.waitTicks(10)
            context.takeScreenshot("clickgui")
            context.setScreen { null }
            context.waitTicks(5)
        }

        c.phase("ADM's commands") {
            for (cmd in listOf("adm help", "adm log", "adm log mark gametest", "adm memreport", "brrole", "pos", "stats", "adr")) {
                context.command(cmd)
                context.waitTicks(5)
            }
            for ((cmd, shot) in listOf("ads" to "cmd-ads-menu", "lo" to "cmd-leap-order", "termsim inf" to "cmd-termsim", "loadouts" to "cmd-loadouts")) {
                context.command(cmd)
                context.waitTicks(20)
                c.expect("/$cmd opened no screen", context.onClient { mc -> mc.gui.screen() != null })
                context.takeScreenshot(shot)
                context.onClient { mc -> mc.player?.closeContainer(); mc.gui.setScreen(null) }
                context.waitTicks(10)
            }
        }

        c.phase("a practice start, as the Practice tab's first preset button") {
            val name = context.onClient { Practice.presets().first().name }
            context.onClient { SimActions.send("practicePreset", "name" to name) }
            context.waitTicks(20 * 10)
            c.note("preset $name")
            context.takeScreenshot("ads-practice")
        }

        c.phase("every ADM module off and on again") {
            val modules = EngineerClient.MODULES.filter { it !== P3Sim }
            for (m in modules) {
                val was = context.onClient { m.enabled }
                context.onClient { m.toggle() }
                context.waitTicks(3)
                context.onClient { m.toggle() }
                context.waitTicks(3)
                c.expect("${m.name} didn't come back ${if (was) "on" else "off"}", context.onClient { m.enabled } == was)
            }
            c.note("${modules.size} modules toggled")
        }

        c.phase("ADS closes back to the title screen") { context.leaveAds() }
        c.finish()
    }
}
