package com.engineerclient.features.practice

class SimonSaysSolver<C> {

    private class Light<C>(val cell: C, val on: Long) { var off = -1L }
    private enum class Kind { ON, AGAIN, OFF, FLASH }
    private class Packet<C>(val tick: Long, val cell: C, val kind: Kind)

    private val shown = ArrayList<Light<C>>()
    private val packets = ArrayList<Packet<C>>()
    private var showing = false
    private var skip = -1L

    var answer: List<C> = emptyList()
        private set

    var next = 0
        private set

    fun lamp(cell: C, lit: Boolean, was: Boolean, tick: Long) {
        val kind = if (lit) (if (was) Kind.AGAIN else Kind.ON) else (if (was) Kind.OFF else Kind.FLASH)
        if (kind == Kind.ON && skip < 0 && !showing) packets.clear()
        if (kind != Kind.FLASH || showing || skip >= 0) packets += Packet(tick, cell, kind)
        when (kind) {
            Kind.ON -> if (skip >= 0) { shown += Light(cell, tick); answer = lastChain(tick) } else lightOn(cell, tick)
            Kind.OFF -> { lightOff(cell, tick); if (skip >= 0) answer = lastChain(tick) }
            else -> { if (skip >= 0) answer = lastChain(tick) }
        }
    }

    fun serverTick(tick: Long) {
        if (skip >= 0) answer = lastChain(tick)
    }

    private fun lightOn(cell: C, tick: Long) {
        if (!showing) { showing = true; shown.clear(); answer = emptyList(); next = 0 }
        shown += Light(cell, tick)
        answer = soFar()
    }

    private fun lightOff(cell: C, tick: Long) {
        shown.lastOrNull { it.cell == cell && it.off < 0 }?.off = tick
        if (showing) answer = soFar()
    }

    private fun soFar(): List<C> {
        val a = shown.toMutableList()
        while (a.size > 1 && a[0].off >= 0 && a[0].off - a[0].on <= 2) a.removeAt(0)
        return a.map { it.cell }
    }

    private fun lastStart(before: Long): Long {
        var last = -1L
        for (p in packets) {
            if (p.tick >= before) break
            val clear = p.kind == Kind.FLASH || ((p.kind == Kind.AGAIN || p.kind == Kind.OFF) &&
                packets.none { it.tick == p.tick - 8 && it.cell == p.cell && it.kind != Kind.OFF })
            if (clear && packets.any { it.tick == p.tick && (it.kind == Kind.ON || it.kind == Kind.AGAIN) }) last = p.tick
        }
        return last
    }

    private fun lastChain(now: Long): List<C> {
        val out = ArrayList<C>()
        for (j in 0 until MAX_ROUNDS) {
            val t = skip + 8L * j
            if (t > now) break
            val exact = packets.filter { it.tick == t && (it.kind == Kind.ON || it.kind == Kind.AGAIN) }.map { it.cell }
            if (t == now && exact.isEmpty()) break
            val cell = exact.lastOrNull() ?: if (j == 0) null else litAt(t).lastOrNull()
            out += cell ?: break
        }
        return out
    }

    private fun litAt(t: Long): List<C> {
        val last = LinkedHashMap<C, Long>()
        for (p in packets) {
            if (p.tick > t) break
            if (p.kind != Kind.OFF) last[p.cell] = p.tick
            if (p.kind == Kind.OFF || p.kind == Kind.FLASH) last.remove(p.cell)
        }
        return last.filterValues { it > t - 8 }.keys.toList()
    }

    fun buttonsUp(tick: Long) {
        if (!showing) return
        showing = false
        next = 0
        val start = lastStart(tick)
        if (start >= 0) { skip = start; answer = lastChain(tick); return }
        val a = shown.toMutableList()
        while (a.size > 1 && a[0].off >= 0 && a[0].off - a[0].on <= 2) a.removeAt(0)
        if (shown.any { it.off < 0 } && a.size >= 3) a.removeAt(0)
        answer = a.map { it.cell }
    }

    fun buttonsGone(tick: Long) {
        if (skip >= 0) {
            skip = -1
            val fresh = shown.filter { it.on == tick }
            packets.removeAll { it.tick != tick }
            shown.clear(); shown.addAll(fresh)
            showing = fresh.isNotEmpty()
            answer = if (showing) soFar() else emptyList(); next = 0
            return
        }
        if (showing) return
        answer = emptyList(); next = 0
    }

    fun pressed(cell: C, tick: Long) {
        if (showing) buttonsUp(tick)
        if (answer.getOrNull(next) == cell) { next++; return }
        val i = answer.indexOf(cell)
        if (i >= next) next = i + 1
    }

    fun reset() { shown.clear(); packets.clear(); showing = false; skip = -1; answer = emptyList(); next = 0 }

    companion object {
        const val MAX_ROUNDS = 4
    }
}
