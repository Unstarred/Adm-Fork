package com.engineerclient.ads.bots

import com.engineerclient.ads.bots.BotBody.place
import com.engineerclient.ads.bots.BotBody.swing
import com.engineerclient.ads.bots.Party.Bot
import com.engineerclient.ads.bots.Party.STANDS
import com.engineerclient.ads.bots.Party.i4Shot
import com.engineerclient.ads.bots.Party.jobs
import com.engineerclient.ads.bots.Party.ssClick
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import com.engineerclient.ads.core.Sim
import com.engineerclient.ads.fight.GoldorPhase
import com.engineerclient.ads.items.Bows

/** The bots at the S4 and SS devices: the i4 bot shooting the board as a player does, the SS bot clicking for show. */
object DeviceBots {
    /** The S4 device's plate stand, and where its 3x3 board is (x64-68, y126-130, z50). */
    private val I4_STAND get() = STANDS.getValue("S4 Target")

    /**
     * The i4 bot does the device as a player does: on the plate (the board runs for it), Terminator out, shooting at
     * the lit target a few ticks after it shows; nothing lit, a stray shot at the board now and then. Its job's time
     * still finishes it if the board hasn't by then.
     */
    internal fun i4Bot(phase: GoldorPhase) {
        val n = phase.n
        val target = phase.devices.target
        val st = phase.stations.firstOrNull { it.id == "S4 Target" }
        val b = jobs.firstOrNull { it.job == "S4 Target" }?.bot?.takeIf { it.entity != null && it.ghost == null && it.to == null && it.pos.distanceTo(I4_STAND) <= 1.0 }
        if (b == null || st == null || st.done || n < 0) { target.botOn = null; return }
        target.botOn = b.name
        b.routeHeld = "TERMINATOR"; b.routeHeldUntil = n + 5
        val cell = target.shown
        val aim = cell?.let { Vec3(it.x + 0.5, it.y + 0.5, it.z.toDouble()) }
            ?: Vec3(66.5 + Random.nextDouble(-2.5, 2.5), 128.5 + Random.nextDouble(-2.5, 2.5), 50.0)
        val eye = b.pos.add(0.0, 1.62, 0.0)
        // The arrow drops ~0.7 over the 15 blocks to the board: aimed that much higher.
        lookAt(b, aim.add(0.0, 0.7, 0.0))
        if (n < i4Shot) return
        // Shown: 3-6 ticks to react; nothing shown, an odd shot every ~half second.
        if (cell == null && Random.nextInt(10) != 0) return
        i4Shot = n + if (cell != null) 3 + Random.nextInt(4) else 8
        val v = aim.add(0.0, 0.7, 0.0).subtract(eye).normalize().scale(3.0)
        swing(b)
        Sim.sound(SoundEvents.ARROW_SHOOT, 1f, 1f / (Random.nextFloat() * 0.4f + 1.2f) + 0.5f, eye, SoundSource.PLAYERS)
        Bows.launch(eye, eye.add(v), v)
    }

    /** The SS start button and its 4x4 of buttons (x 110, facing west). */
    private val SS_START = Vec3(110.9, 121.5, 91.5)

    /**
     * The SS bot looks like it's doing it (it's only for show: the device is left alone): at the device, the start
     * button first, then a button of the grid every few ticks, looked at and clicked, until its job's done.
     */
    internal fun ssBot(phase: GoldorPhase) {
        val n = phase.n
        val st = phase.stations.firstOrNull { it.id == "S1 SS" }
        val b = jobs.firstOrNull { it.job == "S1 SS" }?.bot?.takeIf { it.ghost == null && it.to == null && it.pos.distanceTo(STANDS.getValue("S1 SS")) <= 3.0 }
        if (b == null || st == null || st.done || n < 0 || n < ssClick) return
        val at = if (n < 10) SS_START else Vec3(110.9, 120.5 + Random.nextInt(4), 92.5 + Random.nextInt(4))
        lookAt(b, at)
        swing(b)
        // The start a couple of times, then a press every 4-9 ticks with the odd pause (watching the lights).
        ssClick = n + if (n < 10) 4 else if (Random.nextInt(5) == 0) 15 + Random.nextInt(15) else 4 + Random.nextInt(6)
    }

    /** [b] faces [at] from its eyes. */
    private fun lookAt(b: Bot, at: Vec3) {
        val d = at.subtract(b.pos.add(0.0, 1.62, 0.0))
        b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
        b.pitch = (-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))).toFloat()
        place(b)
    }
}
