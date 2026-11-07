package com.engineerclient.practice

import java.util.Random

class SimonSaysDevice(private val io: Io) {

    interface Io {
        fun newSequence(): IntArray
        fun lamp(cell: Int, on: Boolean)
        fun button(cell: Int, up: Boolean, pressed: Boolean)
        fun lampTick(j: Int): Int = 8 * j
        fun opened(round: Int) {}
        fun completed(by: String)
    }

    enum class Start { STARTED, RESET, DROPPED, IGNORED }
    enum class Press {
        NONE,
        IDLE,
        RIGHT,
        ROUND,
        WRONG,
    }

    var now = 0L
        private set

    private class Job(val at: Long, val gen: Int, val order: Long, val run: () -> Unit)
    private val jobs = ArrayList<Job>()
    private var gen = 0
    private var order = 0L

    fun after(ticks: Int, run: () -> Unit) { jobs += Job(now + ticks.coerceAtLeast(0), gen, order++, run) }

    var round = 0
        private set
    var started = false
        private set
    var done = false
        private set
    var sequence = IntArray(MAX_ROUNDS)
        private set
    private var lastStarter = ""
    var accepting = false
        private set
    var next = 0
        private set

    private val lit = BooleanArray(16)
    private val up = BooleanArray(16)
    private val downUntil = LongArray(16)

    val shown = ArrayList<Int>()
    private var showId = 0

    private class Last(val tick: Long, val ms: Double)
    private val lastPress = HashMap<String, Last>()
    private val random = Random()

    fun due(): Int? = if (accepting && next < round) sequence[next] else null

    fun pressable(cell: Int) = up[cell] && downUntil[cell] <= now

    fun tick() {
        while (true) {
            val due = jobs.filter { it.at <= now }
            if (due.isEmpty()) break
            jobs.removeAll(due.toSet())
            for (j in due.sortedBy { it.order }) if (j.gen == gen) j.run()
        }
        now++
    }

    fun start(who: String, ms: Double, limit: Boolean = true): Start {
        if (done) return Start.IGNORED
        if (limit && !limiter(who, ms)) return Start.DROPPED
        if (started) {
            started = false
            reset()
            return Start.RESET
        }
        started = true
        lastStarter = who
        sequence = io.newSequence()
        after(ROUND_DELAY) { nextRound(lastStarter) }
        return Start.STARTED
    }

    private fun limiter(who: String, ms: Double): Boolean {
        val at = ms + random.nextGaussian() * JITTER_MS
        val last = lastPress[who]
        if (last != null) {
            val ticks = now - last.tick
            if (ticks <= 0 || ticks == 1L && at - last.ms < LIMIT_MS) return false
        }
        lastPress[who] = Last(now, at)
        return true
    }

    private fun reset() {
        round = 0; accepting = false; next = 0
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
    }

    private fun nextRound(by: String) {
        if (done) return
        round++
        if (round > MAX_ROUNDS) { complete(by); return }
        startShow()
    }

    fun showRound(n: Int) {
        if (done) return
        sequence = io.newSequence()
        round = n - 1
        nextRound(lastStarter)
    }

    private fun startShow() {
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
        accepting = false; next = 0
        val id = ++showId
        shown.clear()
        val n = round
        for (j in 0 until n) {
            val light = {
                val cell = sequence[j]
                setLamp(cell, true)
                setButton(cell, false)
                if (id == showId) shown += cell
                after(io.lampTick(j + 1) - io.lampTick(j)) { setLamp(cell, false) }
            }
            val at = io.lampTick(j)
            if (at <= 0) light() else after(at, light)
        }
        after(io.lampTick(n) + 10) { buttonsBack(accept = true) }
    }

    fun buttonsBack(accept: Boolean = false) {
        for (c in 0 until 16) if (!lit[c]) setButton(c, true)
        if (accept && round >= 1 && !accepting && !done) {
            accepting = true
            io.opened(round)
        }
    }

    private fun complete(by: String) {
        done = true
        accepting = false
        gen++
        jobs.clear()
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
        shown.clear()
        io.completed(by)
    }

    fun press(cell: Int, who: String): Press {
        if (!pressable(cell)) return Press.NONE
        io.button(cell, true, true)
        downUntil[cell] = now + 3
        after(3) { if (up[cell]) io.button(cell, true, false) }
        if (!accepting || done) return Press.IDLE
        if (cell == sequence[next]) {
            next++
            if (next < round) return Press.RIGHT
            accepting = false
            after(NEXT_ROUND_DELAY) { nextRound(who) }
            return Press.ROUND
        }
        accepting = false; next = 0; started = false; round = 0
        after(3) { for (c in 0 until 16) setButton(c, false) }
        return Press.WRONG
    }

    fun clear() {
        gen++
        jobs.clear()
        round = 0; started = false; done = false; accepting = false; next = 0
        shown.clear()
        lastPress.clear()
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
    }

    private fun setLamp(cell: Int, on: Boolean) {
        if (lit[cell] == on) return
        lit[cell] = on
        io.lamp(cell, on)
    }

    private fun clearLamps() { for (c in 0 until 16) setLamp(c, false) }

    private fun setButton(cell: Int, show: Boolean) {
        if (up[cell] == show) return
        up[cell] = show
        io.button(cell, show, false)
    }

    companion object {
        const val MAX_ROUNDS = 4
        const val ROUND_DELAY = 4
        const val NEXT_ROUND_DELAY = 6
        const val LIMIT_MS = 49.0
        const val JITTER_MS = 6.0
    }
}
