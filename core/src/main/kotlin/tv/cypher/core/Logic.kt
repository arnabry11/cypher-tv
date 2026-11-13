package tv.cypher.core

/** Ordered favourites. */
class Favourites(initial: List<String> = emptyList()) {
    private val list = initial.distinct().toMutableList()
    val ids: List<String> get() = list.toList()

    operator fun contains(id: String) = id in list

    fun toggle(id: String) {
        if (!list.remove(id)) list.add(id)
    }

    fun remove(id: String) { list.remove(id) }

    /** Moves the item at [index] by [delta] (clamped); returns its new index. */
    fun move(index: Int, delta: Int): Int {
        if (index !in list.indices) return index
        val to = (index + delta).coerceIn(0, list.lastIndex)
        if (to != index) list.add(to, list.removeAt(index))
        return to
    }
}

object Zap {
    fun next(list: List<String>, current: String?): String? {
        if (list.isEmpty()) return null
        val i = list.indexOf(current)
        return if (i < 0) list.first() else list[(i + 1) % list.size]
    }

    fun prev(list: List<String>, current: String?): String? {
        if (list.isEmpty()) return null
        val i = list.indexOf(current)
        return if (i < 0) list.last() else list[(i - 1 + list.size) % list.size]
    }
}

/** Digits typed on the remote; the caller commits after a pause. */
class NumberEntry(private val maxDigits: Int = 4) {
    private val sb = StringBuilder()
    val text: String get() = sb.toString()

    fun press(digit: Int) {
        if (digit in 0..9 && sb.length < maxDigits) sb.append(digit)
    }

    fun clear() = sb.setLength(0)

    fun commit(): Int? {
        val n = sb.toString().toIntOrNull()
        sb.setLength(0)
        return n
    }

    companion object {
        fun resolve(number: Int, byNumber: Map<Int, String>): String? = byNumber[number]
    }
}

object NowNext {
    /**
     * [rows] are programmes of one channel sorted by start, starting at or before [now].
     * Returns the one on air (null in a gap) and the one after it.
     */
    fun pick(rows: List<Programme>, now: Long): Pair<Programme?, Programme?> {
        val current = rows.firstOrNull { it.start <= now && now < it.stop }
        val after = current?.stop ?: now
        val next = rows.firstOrNull { it.start >= after && it !== current && (current != null || it.start > now) }
        return current to next
    }
}

/** Order in which a channel's backup streams are tried: last-known-good first, each once. */
class StreamFailover(count: Int, lastGood: Int) {
    private val order: List<Int> = buildList {
        if (lastGood in 0 until count) add(lastGood)
        for (i in 0 until count) if (i != lastGood) add(i)
    }
    private var idx = 0

    val current: Int get() = order.getOrElse(idx) { -1 }

    /** Moves to the next untried stream, or returns null when all have failed. */
    fun advance(): Int? {
        if (idx + 1 >= order.size) return null
        idx++
        return order[idx]
    }
}
