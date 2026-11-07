package com.engineerclient.ads.bots

import com.engineerclient.ads.bots.BotBody.setHeld
import com.engineerclient.ads.bots.BotBody.swing
import com.engineerclient.ads.bots.BotMoves.leapTo
import com.engineerclient.ads.bots.Party.Bot
import com.engineerclient.ads.bots.Party.Job
import com.engineerclient.ads.bots.Party.Target
import com.engineerclient.ads.bots.Party.arrivedOnEe
import com.engineerclient.ads.bots.Party.asTarget
import com.engineerclient.ads.bots.Party.botOf
import com.engineerclient.ads.bots.Party.bots
import com.engineerclient.ads.bots.Party.dbg
import com.engineerclient.ads.bots.Party.eeArrivedN
import com.engineerclient.ads.bots.Party.eeOf
import com.engineerclient.ads.bots.Party.holding
import com.engineerclient.ads.bots.Party.jobs
import com.engineerclient.ads.bots.Party.p
import com.engineerclient.ads.bots.Party.posOf
import com.engineerclient.ads.bots.Party.readyAt
import com.engineerclient.ads.bots.Party.recoredN
import com.engineerclient.ads.bots.Party.released
import com.engineerclient.ads.bots.Party.sectionN
import com.engineerclient.ads.bots.Party.vec
import com.engineerclient.ads.bots.Party.walkOn
import com.engineerclient.ads.bots.Party.youArrivedN
import com.engineerclient.ads.bots.Party.youInCoreN
import com.engineerclient.ads.bots.Party.youLeftN
import com.engineerclient.ads.arena.Station
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.party.Players

object GhostBots {
    internal fun trackYou(phase: GoldorPhase) {
        val n = phase.n
        for (into in 2..5) {
            val ee = eeOf(into)?.takeIf { it.byYou } ?: continue
            val p = ee.player?.position() ?: continue
            val d = p.distanceTo(ee.spot)
            if (youArrivedN[into] < 0) {
                if (phase.section < into && d <= 3.0) { youArrivedN[into] = n; GhostCapture.event("ee", ee.owner!!.name, into) }
            } else if (youLeftN[into] < 0 && d > 3.0) { youLeftN[into] = n; GhostCapture.event("left", ee.owner!!.name, into) }
            else if (youLeftN[into] >= 0 && d <= 3.0 && n - youLeftN[into] <= GhostPlayer.BACK) { youLeftN[into] = -1; GhostCapture.event("back", ee.owner!!.name, into) }
        }
        val inCore = Sim.players().firstOrNull { GoldorPhase.CORE_BOX.contains(it.position()) }
        if (phase.section >= 5 && youInCoreN < 0 && inCore != null) {
            youInCoreN = n
            GhostCapture.event("core")
            Players.classOf(inCore).takeIf { eeOf(5)?.owner == it }?.let { GhostCapture.event("recore", it.name) }
        }
    }

    internal class GhostWorld(val b: Bot, val phase: GoldorPhase) : GhostPlayer.World {
        override fun sectionStart(s: Int) = if (s <= phase.section) (if (s <= 1) 0 else sectionN[s]) else null
        override fun stationDone(id: String) = phase.stations.firstOrNull { it.id == id }?.done ?: true
        override fun canDo(id: String): Boolean {
            val st = phase.stations.firstOrNull { it.id == id } ?: return false
            if (st.done) return false
            if (st.section != phase.section && !(st.kind == Station.Kind.DEVICE && st.section > phase.section)) return false
            return !(st.section == phase.section && holding(phase) && phase.stations.count { it.section == phase.section && !it.done } == 1)
        }
        override fun gateDown(s: Int) = phase.gateIsDown(s)
        override fun canGate(s: Int) = phase.section >= s
        override fun eeArrivedAt(into: Int, who: String): Int? {
            val owner = eeOf(into)?.owner ?: return 0
            if (owner.name != who) return 0
            return (if (Players.isPlayerClass(owner)) youArrivedN[into] else eeArrivedN[into]).takeIf { it >= 0 }
        }
        override fun eeReadyAt(into: Int) = if (eeOf(into)?.owner == b.clazz) readyAt[into].takeIf { it >= 0 } else 0
        override fun recoredAt(who: String): Int? {
            val owner = eeOf(5)?.owner ?: return 0
            if (owner.name != who) return 0
            return (if (Players.isPlayerClass(owner)) youInCoreN else recoredN).takeIf { it >= 0 }
        }
        override fun positionOf(who: String): P? =
            posOf(Players.classByName(who))?.p() ?: bots.firstOrNull { it.clazz.name == who }?.pos?.p()
    }

    internal class GhostBody(val b: Bot, val phase: GoldorPhase) : GhostPlayer.Body {
        override fun move(p: P, yaw: Float, pitch: Float, held: String) {
            b.pos = p.vec(); b.yaw = yaw; b.pitch = pitch; b.to = null
            setHeld(b, held)
        }
        override fun complete(id: String) {
            val st = phase.stations.firstOrNull { it.id == id } ?: return
            if (st.kind == Station.Kind.LEVER) phase.pullLever(st, b.name) else st.complete(b.name)
            if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
            dbg("§d${b.name}§7 (ghost) did $id")
        }
        override fun gate(s: Int) { if (phase.blowGate(s, b.name)) dbg("§d${b.name}§7 (ghost) blew gate $s") }
        override fun leap(who: String, at: P) {
            val c = Players.classByName(who)
            val onto = Players.playerOf(c)?.asTarget() ?: botOf(c)?.asTarget()
            leapTo(b, Target(at.vec(), onto?.name, onto?.yaw), phase.n)
            dbg("§d${b.name}§7 (ghost) leaps onto $who")
        }
        override fun swing() = swing(b)
        override fun eeOn(into: Int) {
            if (eeOf(into)?.owner != b.clazz) return
            b.inSection = maxOf(b.inSection, into); b.hold = true
            arrivedOnEe(b, into, phase.n)
        }
        override fun eeLeft(into: Int) {
            if (eeOf(into)?.owner != b.clazz) return
            released[into] = true; b.hold = false
            dbg("§d${b.name}§7 (ghost) moves on from EE$into")
        }
        override fun recore() {
            if (eeOf(5)?.owner != b.clazz || recoredN >= 0) return
            recoredN = phase.n
            GhostCapture.event("recore", b.clazz.name)
        }
        override fun working(id: String?) { b.working = id?.let { i -> phase.stations.firstOrNull { it.id == i } } }
        override fun giveUp(job: String) {
            val bot = bots.filter { it.ghost == null }.minByOrNull { o -> jobs.count { it.bot === o } } ?: return
            jobs += Job(job, bot, phase.section, 0.0).also { it.at = phase.n }
            dbg("§d${b.name}§7 (ghost) couldn't do $job: §e${bot.name}§7 does")
            walkOn(bot, phase.n)
        }
    }
}
