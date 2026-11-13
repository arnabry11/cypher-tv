package tv.cypher.core

import kotlin.math.max
import kotlin.math.min

data class Candidate(val id: String, val name: String) {
    val norm: String = Names.normalize(name)
}

enum class MatchKind { EXACT, ALIAS, OVERRIDE, FUZZY, NONE, EXCLUDED }

data class MatchResult(
    val fta: FtaChannel,
    val kind: MatchKind,
    val candidate: Candidate? = null,
    /** What matched: the alias, override name or fuzzy score, for the review report. */
    val via: String? = null,
)

/** Matches MIB FTA channel names against the playlist's channels. */
class Matcher(candidates: List<Candidate>, private val overrides: Overrides) {
    private val byId = candidates.associateBy { it.id }
    private val byNorm: Map<String, List<Candidate>> = candidates.groupBy { it.norm }
    private val all = candidates
    private val excluded = overrides.exclude.map { it.lowercase() }.toSet()

    fun match(fta: FtaChannel): MatchResult {
        val primary = Names.primary(fta.name)
        if (fta.name.lowercase() in excluded || primary.lowercase() in excluded) {
            return MatchResult(fta, MatchKind.EXCLUDED)
        }
        fun done(kind: MatchKind, c: Candidate, via: String? = null): MatchResult =
            if (c.id.lowercase() in excluded) MatchResult(fta, MatchKind.EXCLUDED, c)
            else MatchResult(fta, kind, c, via)

        overrides.map[fta.name]?.let { id ->
            val c = byId[id] ?: return MatchResult(fta, MatchKind.NONE, via = "override id $id not in playlist")
            return done(MatchKind.OVERRIDE, c, id)
        }
        lookup(Names.normalize(primary))?.let { return done(MatchKind.EXACT, it) }
        for (a in overrides.aliases[fta.name].orEmpty()) {
            lookup(Names.normalize(a))?.let { return done(MatchKind.OVERRIDE, it, a) }
        }
        for (a in fta.aliases) {
            lookup(Names.normalize(a))?.let { return done(MatchKind.ALIAS, it, a) }
        }
        fuzzy(Names.normalize(primary))?.let { (c, score) ->
            return done(MatchKind.FUZZY, c, "%.2f".format(score))
        }
        return MatchResult(fta, MatchKind.NONE)
    }

    private fun lookup(norm: String): Candidate? {
        if (norm.isEmpty()) return null
        val list = byNorm[norm] ?: return null
        return list.firstOrNull { it.id.endsWith(".in") } ?: list.first()
    }

    private fun fuzzy(norm: String): Pair<Candidate, Double>? {
        val a = norm.replace(" ", "")
        if (a.length < MIN_FUZZY_LEN) return null
        val digitsA = digits(a)
        var best: Candidate? = null
        var bestScore = 0.0
        var second = 0.0
        for (c in all) {
            val b = c.norm.replace(" ", "")
            if (b.length < MIN_FUZZY_LEN || kotlin.math.abs(a.length - b.length) > 2) continue
            if (digits(b) != digitsA) continue
            val score = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
            if (score > bestScore) { second = bestScore; bestScore = score; best = c }
            else if (score > second) second = score
        }
        if (best == null || bestScore < FUZZY_THRESHOLD || bestScore - second < FUZZY_MARGIN) return null
        return best to bestScore
    }

    private fun digits(s: String) = s.filter { it.isDigit() }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    companion object {
        const val MIN_FUZZY_LEN = 6
        const val FUZZY_THRESHOLD = 0.88
        const val FUZZY_MARGIN = 0.03
    }
}
