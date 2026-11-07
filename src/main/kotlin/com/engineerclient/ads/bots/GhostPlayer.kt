package com.engineerclient.ads.bots

class GhostPlayer(val run: GhostRun, private val world: World, private val body: Body) {

    interface World {
        fun sectionStart(s: Int): Int?
        fun stationDone(id: String): Boolean
        fun canDo(id: String): Boolean
        fun gateDown(s: Int): Boolean
        fun canGate(s: Int): Boolean
        fun eeArrivedAt(into: Int, who: String): Int?
        fun eeReadyAt(into: Int): Int?
        fun recoredAt(who: String): Int?
        fun positionOf(who: String): P?
    }

    interface Body {
        fun move(p: P, yaw: Float, pitch: Float, held: String)
        fun complete(id: String)
        fun gate(s: Int)
        fun leap(who: String, at: P)
        fun swing()
        fun eeOn(into: Int)
        fun eeLeft(into: Int)
        fun recore()
        fun working(id: String?)
        fun giveUp(job: String)
    }

    enum class Kind { DONE, GATE, LEAP, EE_ON, EE_LEAVE, RECORE, ENTER, GO }

    sealed class Anchor(val rec: Int) {
        class Section(val s: Int, rec: Int) : Anchor(rec)
        class EeOn(val into: Int, val who: String, rec: Int) : Anchor(rec)
        class Ready(val into: Int, rec: Int) : Anchor(rec)
        class Recore(val who: String, rec: Int) : Anchor(rec)
        override fun toString() = "${javaClass.simpleName}@$rec"
    }

    class Action(val kind: Kind, val rec: Int, val settle: Int, val arg: String, val k: Int, val anchors: List<Anchor>) {
        val waitedFor = maxOf(settle, anchors.maxOfOrNull { it.rec } ?: settle)
        val work = (rec - waitedFor).coerceAtLeast(0)
        val job get() = if (kind == Kind.GATE) "gate $k" else arg
        override fun toString() = "$kind(${if (kind == Kind.DONE || kind == Kind.LEAP) arg else "$k"} @$rec settle $settle work $work $anchors)"
    }

    val actions: List<Action> = build(run)

    var shift = 0
        private set
    var next = 0
        private set
    var shown = -1
        private set
    private var waitingSince = -1
    private var offset = P(0.0, 0.0, 0.0)
    private var blendFrom = 0
    private var blendEnd = -1
    private val leapt = ArrayList<Pair<String, Int>>()
    private var workingOn: String? = null

    val finished get() = next >= actions.size && shown >= run.end

    var status = ""
        private set

    val jobs: List<String> get() = actions.filter { it.kind == Kind.DONE || it.kind == Kind.GATE }.map { it.job }

    fun leaptOnto(who: String, since: Int) = leapt.any { it.first == who && it.second >= since }

    fun expectsLeap(who: String, into: Int) = actions.drop(next).any { a ->
        a.kind == Kind.LEAP && a.arg == who && a.anchors.any { it is Anchor.EeOn && it.into == into }
    }

    fun earlyEnters(into: Int) = actions.any { it.kind == Kind.EE_ON && it.k == into }

    fun eeSpot(into: Int): GhostRun.Frame? = actions.firstOrNull { it.kind == Kind.EE_ON && it.k == into }?.let { run.frame(it.rec) }

    fun tick(n: Int) {
        var guard = 0
        while (guard++ < 32) {
            val a = actions.getOrNull(next)
            if (a == null) { show(n - shift); status = if (finished) "finished" else "playing out"; return }
            val at = n - shift
            if (at < a.settle) { show(at); status = "on its way to $a"; return }
            if (waitingSince < 0) waitingSince = n
            if (alreadyDone(a)) { fire(a, n); continue }
            val ready = readyAt(a)
            if (ready == null || n < ready) {
                if (ready == null && gaveUp(a, n)) { fire(a, n); continue }
                show(minOf(at, hold(a)))
                status = if (ready == null) "waiting: ${a.anchors.joinToString()}" else "${a.kind} in ${ready - n}"
                return
            }
            if (!fire(a, n)) { show(minOf(at, hold(a))); status = "blocked: $a"; return }
        }
    }

    private fun gaveUp(a: Action, n: Int) = waitingSince >= 0 && n - waitingSince >= MAX_WAIT + a.rec - a.settle

    private fun alreadyDone(a: Action) = when (a.kind) {
        Kind.DONE -> world.stationDone(a.arg)
        Kind.GATE -> world.gateDown(a.k)
        else -> false
    }

    private fun hold(a: Action) = if (a.kind == Kind.DONE || a.kind == Kind.GATE || a.kind == Kind.EE_ON || a.kind == Kind.RECORE) a.rec else maxOf(a.rec - 1, a.settle)

    fun readyAt(a: Action): Int? {
        var g = a.settle + shift
        for (an in a.anchors) {
            val live = when (an) {
                is Anchor.Section -> world.sectionStart(an.s)
                is Anchor.EeOn -> world.eeArrivedAt(an.into, an.who)
                is Anchor.Ready -> world.eeReadyAt(an.into)
                is Anchor.Recore -> world.recoredAt(an.who)
            } ?: return null
            g = maxOf(g, live)
        }
        return g + a.work
    }

    private fun fire(a: Action, n: Int): Boolean {
        when (a.kind) {
            Kind.DONE -> if (!world.stationDone(a.arg)) {
                if (world.canDo(a.arg)) body.complete(a.arg)
                else if (!gaveUp(a, n)) return false
                else body.giveUp(a.arg)
            }
            Kind.GATE -> if (!world.gateDown(a.k)) {
                if (world.canGate(a.k)) body.gate(a.k)
                else if (!gaveUp(a, n)) return false
                else body.giveUp(a.job)
            }
            Kind.LEAP -> {
                val to = world.positionOf(a.arg)
                if (to != null) {
                    body.leap(a.arg, to)
                    leapt += a.arg to n
                    offset = to - run.frame(a.rec).p
                    blendFrom = a.rec
                    blendEnd = actions.getOrNull(next + 1)?.settle?.coerceAtLeast(a.rec) ?: a.rec
                }
            }
            Kind.EE_ON -> body.eeOn(a.k)
            Kind.EE_LEAVE -> body.eeLeft(a.k)
            Kind.RECORE -> body.recore()
            Kind.ENTER, Kind.GO -> {}
        }
        waitingSince = -1
        shift = n - a.rec
        next++
        show(a.rec)
        return true
    }

    private fun show(at: Int) {
        val t = at.coerceIn(0, run.end)
        if (t > shown) {
            val from = shown
            shown = t
            var swings = 0
            for (e in run.events) if (e.type == "swing" && e.n in (from + 1)..t && swings++ < 3) body.swing()
            val f = run.frame(t)
            body.move(f.p + blend(t), f.yaw, f.pitch, f.held)
        }
        working()
    }

    private fun working() {
        val a = actions.getOrNull(next)
        val id = a?.takeIf { it.kind == Kind.DONE && shown >= it.settle && isTerminal(it.arg) && !world.stationDone(it.arg) }?.arg
        if (id != workingOn) { workingOn = id; body.working(id) }
    }

    fun blend(t: Int): P {
        if (blendEnd < 0 || t < blendFrom || t > blendEnd) return ZERO
        if (blendEnd == blendFrom) return if (t == blendFrom) offset else ZERO
        return offset * ((blendEnd - t).toDouble() / (blendEnd - blendFrom))
    }

    companion object {
        const val SETTLE = 3.0
        const val BACK = 40
        const val MAX_WAIT = 600
        private val ZERO = P(0.0, 0.0, 0.0)

        fun eeOwner(planned: String?, players: Set<String>, ghostsThere: List<String>, ghosts: Set<String>): String? = when {
            planned != null && planned in players -> planned
            ghostsThere.isNotEmpty() -> ghostsThere.first()
            planned == null || planned in ghosts -> null
            else -> planned
        }

        fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.substring(1, 2).toInt()

        fun isDevice(id: String) = id.endsWith(" SS") || id.endsWith(" Lights") || id.endsWith(" Arrows") || id.endsWith(" Target")

        fun isTerminal(id: String) = Regex("S\\d T\\d").matches(id)

        fun settle(run: GhostRun, from: Int, at: Int): Int {
            val p = run.frame(at).p
            var m = at
            while (m - 1 >= from && m - 1 >= 0 && run.frame(m - 1).p.dist(p) <= SETTLE) m--
            return m
        }

        fun build(run: GhostRun): List<Action> {
            val me = run.clazz
            val ev = run.events.withIndex().sortedWith(compareBy({ it.value.n }, { it.index })).map { it.value }
            fun section(s: Int) = run.sectionN.getOrNull(s)?.takeIf { it >= 0 && s >= 2 }?.let { Anchor.Section(s, it) }
            class Raw(val n: Int, val order: Int, val make: (prev: Int) -> Action)
            val raw = ArrayList<Raw>()
            ev.forEachIndexed { i, e ->
                when (e.type) {
                    "done" -> raw += Raw(e.n, i) { prev ->
                        val anchors = if (isDevice(e.a)) emptyList() else listOfNotNull(section(sectionOf(e.a)))
                        Action(Kind.DONE, e.n, settle(run, prev, e.n), e.a, 0, anchors)
                    }
                    "gate" -> raw += Raw(e.n, i) { prev -> Action(Kind.GATE, e.n, settle(run, prev, e.n), "", e.k, listOfNotNull(section(e.k))) }
                    "leap" -> raw += Raw(e.n, i) { prev ->
                        val onEe = ev.lastOrNull { it.type == "ee" && it.a == e.a && it.n <= e.n }
                        val recore = ev.lastOrNull { it.type == "recore" && it.a == e.a && it.n <= e.n }
                        val settle = settle(run, prev, (e.n - 1).coerceAtLeast(prev))
                        val anchors = listOfNotNull(onEe?.let { Anchor.EeOn(it.k, e.a, it.n) }, recore?.let { Anchor.Recore(e.a, it.n) }, startedDuring(run, settle, e.n))
                        Action(Kind.LEAP, e.n, settle, e.a, 0, anchors)
                    }
                    "recore" -> if (e.a == me) raw += Raw(e.n, i) { _ -> Action(Kind.RECORE, e.n, e.n, "", 0, emptyList()) }
                    "core" -> if (ev.indexOfFirst { it.type == "core" } == i) raw += Raw(e.n, i) { prev ->
                        Action(Kind.ENTER, e.n, settle(run, prev, (e.n - 1).coerceAtLeast(prev)), "", 5, listOfNotNull(section(5)))
                    }
                    "ee" -> if (e.a == me && ev.indexOfFirst { it.type == "ee" && it.a == me && it.k == e.k } == i) {
                        val into = e.k
                        raw += Raw(e.n, i) { _ -> Action(Kind.EE_ON, e.n, e.n, "", into, emptyList()) }
                        val j = ev.indices.firstOrNull { x ->
                            val l = ev[x]
                            l.type == "left" && l.a == me && l.k == into && l.n >= e.n && ev.none { it.type == "back" && it.k == into && it.n in l.n..l.n + BACK }
                        } ?: -1
                        if (j >= 0) raw += Raw(ev[j].n, j) { _ ->
                            val ready = ev.lastOrNull { it.type == "landed" && it.n in e.n..ev[j].n }
                            val anchors = listOfNotNull(ready?.let { Anchor.Ready(into, it.n) }, startedDuring(run, e.n, ev[j].n))
                            Action(Kind.EE_LEAVE, ev[j].n, e.n, "", into, anchors)
                        }
                    }
                }
            }
            val out = ArrayList<Action>()
            var prev = 0
            for (r in raw.sortedWith(compareBy<Raw> { it.n }.thenBy { it.order })) {
                val a = r.make(prev)
                out += if (a.settle < prev) Action(a.kind, a.rec, prev.coerceAtMost(a.rec), a.arg, a.k, a.anchors) else a
                prev = a.rec
            }
            return (out + departures(run, out)).withIndex()
                .sortedWith(compareBy({ it.value.rec }, { if (it.value.kind == Kind.GO) 0 else 1 }, { it.index })).map { it.value }
        }

        const val STAND = 1.5
        const val MIN_STAND = 5

        private fun startedDuring(run: GhostRun, from: Int, to: Int): Anchor.Section? =
            (2..5).filter { s -> run.sectionN[s] in (from + 1)..to }.maxByOrNull { run.sectionN[it] }?.let { Anchor.Section(it, run.sectionN[it]) }

        fun departures(run: GhostRun, actions: List<Action>): List<Action> {
            val out = ArrayList<Action>()
            var from = 0
            for (i in 0..actions.size) {
                val until = if (i < actions.size) actions[i].settle else run.end
                var m = from
                while (m < until) {
                    val p = run.frame(m).p
                    var e = m
                    while (e + 1 <= until && run.frame(e + 1).p.dist(p) <= STAND) e++
                    if (e - m >= MIN_STAND && e + 1 <= until) startedDuring(run, m, e + 1)?.let { out += Action(Kind.GO, e + 1, m, "", it.s, listOf(it)) }
                    m = e + 1
                }
                if (i < actions.size) from = maxOf(from, actions[i].rec)
            }
            return out
        }
    }
}
