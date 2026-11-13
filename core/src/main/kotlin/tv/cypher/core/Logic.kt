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

    /**
     * Moves [id] one step up ([delta] < 0) or down past the nearest favourite that [isShown]. Favourites that are
     * not shown (their channel is missing from today's list) keep their place and are never dropped.
     */
    fun moveAmongShown(id: String, delta: Int, isShown: (String) -> Boolean) {
        val from = list.indexOf(id)
        if (from < 0 || delta == 0) return
        val step = if (delta > 0) 1 else -1
        var to = from + step
        while (to in list.indices && !isShown(list[to])) to += step
        if (to in list.indices) list.add(to, list.removeAt(from))
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

    /** How far through [p] we are at [now], 0..100. */
    fun percent(p: Programme, now: Long): Int {
        val length = p.stop - p.start
        if (length <= 0) return 0
        return ((now - p.start) * 100 / length).toInt().coerceIn(0, 100)
    }
}

/** Order in which a channel's streams are tried: the remembered one first, then lower quality, then higher; each once. */
class StreamFailover(private val count: Int, lastGood: Int) {
    private var order: List<Int> = orderWithFirst(lastGood)
    private var idx = 0

    val current: Int get() = order.getOrElse(idx) { -1 }

    /** [first], then the streams after it (lower quality, as streams are sorted best-first), then those before it. */
    private fun orderWithFirst(first: Int): List<Int> = buildList {
        if (first in 0 until count) {
            add(first)
            for (i in first + 1 until count) add(i)
            for (i in 0 until first) add(i)
        } else {
            for (i in 0 until count) add(i)
        }
    }

    /** Moves to the next untried stream, or returns null when all have failed. */
    fun advance(): Int? {
        if (idx + 1 >= order.size) return null
        idx++
        return order[idx]
    }

    /**
     * Buffering trouble: move to the next stream only if it is lower quality (later in the best-first list).
     * Returns it, or null (and stays put) when already on the lowest.
     */
    fun stepDown(): Int? {
        val next = order.getOrNull(idx + 1) ?: return null
        if (next <= current) return null
        idx++
        return next
    }

    /**
     * The current stream stopped after playing for [playedMs]. One that had been healthy for a while is
     * retried (it probably hiccupped); one that died quickly moves on to the next stream.
     * Returns the stream to try, or null when everything has failed.
     */
    fun failed(playedMs: Long): Int? {
        val cur = current
        if (cur >= 0 && playedMs >= HEALTHY_MS) {
            order = orderWithFirst(cur)
            idx = 0
            return cur
        }
        return advance()
    }

    companion object {
        const val HEALTHY_MS = 30_000L
    }
}

/** Delay before the next background refresh: the normal interval, or a growing short one after failures. */
class RetryBackoff(private val firstMs: Long = 60_000, private val maxMs: Long = 30 * 60_000L) {
    private var failures = 0

    fun next(success: Boolean, normalMs: Long): Long {
        if (success) { failures = 0; return normalMs }
        val d = minOf(maxMs, firstMs shl minOf(failures, 20))
        failures++
        return d
    }
}

/** Remembers, for a short time, which stream a channel had to step down to, so zapping back does not repeat the stall. */
object QualityMemory {
    const val TTL_MS = 30 * 60_000L

    fun encode(index: Int, nowMs: Long): String = "$index@$nowMs"

    /** The remembered stream index, or -1 when there is none, it is older than [ttlMs], or the value is junk. */
    fun decode(raw: String?, nowMs: Long, ttlMs: Long = TTL_MS): Int {
        val index = raw?.substringBefore('@')?.toIntOrNull() ?: return -1
        val at = raw.substringAfter('@', "").toLongOrNull() ?: return -1
        val age = nowMs - at
        return if (index >= 0 && age in 0..ttlMs) index else -1
    }
}

/** Counts playback rebuffers; true once [limit] happen within [windowMs], then starts over. */
class RebufferTracker(private val windowMs: Long = 60_000, private val limit: Int = 3) {
    private val times = ArrayDeque<Long>()

    fun record(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        times.addLast(nowMs)
        if (times.size >= limit) { times.clear(); return true }
        return false
    }
}
