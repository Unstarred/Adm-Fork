package com.engineerclient.adr

import com.engineerclient.EngineerClient
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

object PartyTracker {
    val state = PartyState { EngineerClient.mc.user.name }

    private const val JOIN_WAIT_MS = 4_000L
    private const val DOUBT_WAIT_MS = 2_500L
    private const val ASK_GAP_MS = 60_000L
    private const val HIDE_MS = 4_000L
    private const val FAIL_PAUSE_MS = 30 * 60_000L

    private var failures = 0
    private var answerBy = 0L
    private var pausedUntil = 0L

    private var joinedAt = 0L
    private var onHypixel = false
    private var connected = false
    private var doubtSince = 0L
    private var lastAsk = 0L
    @Volatile private var hideUntil = 0L
    private var lastTeam = emptyList<String>()

    private val NOT_IN_PARTY = Regex("""^You are not (?:currently )?in a party\.$""")
    private val SEPARATOR = Regex("""^-{10,}$""")

    fun onJoin() = synchronized(state) {
        onHypixel = ADR.onHypixel()
        if (connected) return
        connected = true
        joinedAt = System.currentTimeMillis()
        lastAsk = 0L
        lastTeam = emptyList()
        state.reset(if (onHypixel) "just joined" else "not on Hypixel")
    }

    fun onDisconnect() = synchronized(state) {
        onHypixel = false
        connected = false
        answerBy = 0L
        state.reset("not connected")
    }

    fun onChat(plain: String, colored: String): Boolean = synchronized(state) {
        val now = System.currentTimeMillis()
        val lines = plain.split('\n')
        val colors = colored.split('\n').takeIf { it.size == lines.size }
        var hide = now < hideUntil
        for ((i, l) in lines.withIndex()) {
            val listLine = state.line(l, colors?.getOrNull(i), now)
            if (answerBy != 0L && (listLine || NOT_IN_PARTY.matches(l.trim()))) { answerBy = 0L; failures = 0 }
            if (hide && !listLine && !SEPARATOR.matches(l.trim()) && l.isNotBlank() && !NOT_IN_PARTY.matches(l.trim())) hide = false
        }
        if (hide && state.doubt == null) hideUntil = now + 300
        hide
    }

    fun tick() = synchronized(state) {
        val now = System.currentTimeMillis()
        if (answerBy != 0L && now > answerBy) {
            answerBy = 0L
            failures++
            pausedUntil = now + FAIL_PAUSE_MS
            EngineerClient.logger.info("[ec] adr: /p list got no answer, " + if (failures >= 2) "not asking again until the game restarts" else "not asking for ${FAIL_PAUSE_MS / 60_000} min")
        }
        if (DungeonUtils.inDungeons) {
            val team = DungeonUtils.dungeonTeammates.map { it.name }.filter { it.matches(NAME) }
            if (team.isNotEmpty() && team != lastTeam) { lastTeam = team; state.teammates(team) }
        } else lastTeam = emptyList()

        val doubt = state.doubt
        if (doubt == null) { doubtSince = 0L; return }
        if (doubtSince == 0L) doubtSince = now
        if (!onHypixel || EngineerClient.mc.player == null) return
        if (now - joinedAt < JOIN_WAIT_MS || now - doubtSince < DOUBT_WAIT_MS || now - lastAsk < ASK_GAP_MS) return
        ask(now, doubt)
    }

    fun askIfUnknown() = synchronized(state) {
        val now = System.currentTimeMillis()
        if (state.known || !onHypixel || EngineerClient.mc.player == null || now - lastAsk < 10_000L) return
        ask(now, "asked for")
    }

    fun recheck() = synchronized(state) {
        val now = System.currentTimeMillis()
        if (EngineerClient.mc.player == null || now - lastAsk < 2_000L) return
        failures = 0; pausedUntil = 0L; answerBy = 0L
        ask(now, "recheck")
    }

    val checking: Boolean get() = answerBy != 0L && System.currentTimeMillis() < answerBy

    private fun ask(now: Long, why: String) {
        if (answerBy != 0L || failures >= 2 || now < pausedUntil) return
        lastAsk = now
        hideUntil = now + HIDE_MS
        answerBy = now + HIDE_MS
        EngineerClient.logger.info("[ec] adr: /p list ($why)")
        sendCommand("p list")
    }

    private val NAME = Regex("""^\w{1,16}$""")
}
