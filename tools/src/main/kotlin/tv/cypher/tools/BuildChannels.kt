package tv.cypher.tools

import tv.cypher.core.*

/** Combines the FTA list, the iptv-org playlist and overrides into the published `channels.json`. */
object BuildChannels {
    class Result(val channels: List<Channel>, val numbers: Map<String, Int>, val report: String)

    private val uselessGroups = setOf("general", "undefined", "other", "others")

    fun build(
        fta: List<FtaChannel>,
        streams: List<PlaylistEntry>,
        index: List<PlaylistEntry>,
        overrides: Overrides,
        existingNumbers: Map<String, Int>,
    ): Result {
        val byId = streams.filter { it.channelId.isNotEmpty() && it.url.isNotBlank() }.groupBy { it.channelId }
        val indexById = index.filter { it.channelId.isNotEmpty() }.groupBy { it.channelId }
        val pool = byId.map { (id, e) -> Candidate(id, e.first().name) }
        val matcher = Matcher(pool, overrides)
        val poolById = pool.associateBy { it.id }

        class Pick(val id: String, val name: String, val lang: String, val cat: String)

        val picks = LinkedHashMap<String, Pick>()
        val claimedBy = HashMap<String, FtaChannel>()
        val results = ArrayList<MatchResult>()
        val duplicates = ArrayList<Pair<MatchResult, FtaChannel>>()

        for (f in fta) {
            val m = matcher.match(f)
            results.add(m)
            val c = m.candidate ?: continue
            if (m.kind == MatchKind.NONE || m.kind == MatchKind.EXCLUDED) continue
            val first = claimedBy[c.id]
            if (first != null) { duplicates.add(m to first); continue }
            claimedBy[c.id] = f
            val group = indexById[c.id]?.firstNotNullOfOrNull { it.group } ?: byId[c.id]?.firstNotNullOfOrNull { it.group }
            picks[c.id] = Pick(
                id = c.id,
                name = c.name,
                lang = overrides.lang[c.id] ?: Languages.primary(f.name, f.languages),
                cat = if (f.cat == "News") "News" else category(group),
            )
        }
        val extrasAdded = ArrayList<String>()
        val extrasMissing = ArrayList<String>()
        for (x in overrides.extra) {
            if (x.id in picks) continue
            val c = poolById[x.id]
            if (c == null) { extrasMissing.add(x.id); continue }
            val group = indexById[c.id]?.firstNotNullOfOrNull { it.group } ?: byId[c.id]?.firstNotNullOfOrNull { it.group }
            picks[c.id] = Pick(
                c.id, c.name,
                lang = x.lang ?: overrides.lang[c.id] ?: Languages.primary(c.name, emptyList()),
                cat = x.cat ?: category(group),
            )
            extrasAdded.add(c.id)
        }

        val numbers = Numbering.allocate(
            existingNumbers,
            picks.values.sortedWith(compareBy({ it.lang }, { it.name.lowercase() })).map { it.id to it.lang },
        )
        val channels = picks.values.map { p ->
            val entries = byId.getValue(p.id)
            // Best resolution first (stable: playlist order is kept within one resolution; unknown goes last).
            var list = entries.distinctBy { it.url }.sortedByDescending { it.height }.map { Stream(it.url, it.ua, it.referer) }
            overrides.preferred[p.id]?.let { sub ->
                val (pref, rest) = list.partition { sub in it.url }
                list = pref + rest
            }
            Channel(
                id = p.id,
                num = numbers.getValue(p.id),
                name = p.name,
                lang = p.lang,
                cat = p.cat,
                logo = indexById[p.id]?.firstNotNullOfOrNull { it.logo } ?: entries.firstNotNullOfOrNull { it.logo },
                streams = list,
            )
        }.sortedBy { it.num }

        return Result(channels, numbers, report(fta, results, duplicates, matcher, channels, extrasAdded, extrasMissing, byId.size))
    }

    private fun category(group: String?): String =
        group?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in uselessGroups } ?: "Entertainment"

    private fun report(
        fta: List<FtaChannel>,
        results: List<MatchResult>,
        duplicates: List<Pair<MatchResult, FtaChannel>>,
        matcher: Matcher,
        channels: List<Channel>,
        extrasAdded: List<String>,
        extrasMissing: List<String>,
        playlistChannels: Int,
    ): String = buildString {
        fun count(k: MatchKind) = results.count { it.kind == k }
        appendLine("# Match report")
        appendLine()
        appendLine("- FTA channels: ${fta.size}")
        appendLine("- Exact: ${count(MatchKind.EXACT)}, alias: ${count(MatchKind.ALIAS)}, override: ${count(MatchKind.OVERRIDE)}, fuzzy: ${count(MatchKind.FUZZY)}")
        appendLine("- Unmatched: ${count(MatchKind.NONE)}, excluded: ${count(MatchKind.EXCLUDED)}, duplicate claims: ${duplicates.size}")
        appendLine("- Playlist channels: $playlistChannels; published: ${channels.size} (${channels.count { it.streams.size > 1 }} with backup streams)")
        appendLine()
        appendLine("Fix wrong or missing matches in `data/overrides.json` (`aliases`, `map`, `exclude`, `extra`).")

        fun section(title: String, kind: MatchKind, show: (MatchResult) -> String) {
            val rows = results.filter { it.kind == kind }
            if (rows.isEmpty()) return
            appendLine(); appendLine("## $title (${rows.size})"); appendLine()
            rows.forEach { appendLine("- ${show(it)}") }
        }
        section("Fuzzy matches: please review", MatchKind.FUZZY) {
            "`${it.fta.name}` -> `${it.candidate!!.name}` (${it.candidate!!.id}), fuzzy score ${it.via}"
        }
        section("Matched through an alias: please review", MatchKind.ALIAS) {
            "`${it.fta.name}` -> `${it.candidate!!.name}` (${it.candidate!!.id}) via `${it.via}`"
        }
        section("Matched through overrides", MatchKind.OVERRIDE) {
            "`${it.fta.name}` -> `${it.candidate!!.name}` (${it.candidate!!.id})"
        }
        if (duplicates.isNotEmpty()) {
            appendLine(); appendLine("## Duplicate claims (${duplicates.size})"); appendLine()
            duplicates.forEach { (m, first) ->
                appendLine("- `${m.fta.name}` duplicate of `${first.name}`: both map to ${m.candidate!!.id}")
            }
        }
        val none = results.filter { it.kind == MatchKind.NONE }
        if (none.isNotEmpty()) {
            appendLine(); appendLine("## Unmatched (${none.size})"); appendLine()
            none.forEach {
                val hint = matcher.suggest(it.fta).joinToString { c -> "`${c.name}` (${c.id})" }
                val why = it.via?.let { v -> " [$v]" }.orEmpty()
                appendLine("- `${it.fta.name}` [${it.fta.cat}, ${it.fta.languages.joinToString("/")}]$why" + if (hint.isNotEmpty()) "; maybe: $hint" else "")
            }
        }
        if (extrasAdded.isNotEmpty() || extrasMissing.isNotEmpty()) {
            appendLine(); appendLine("## Extra channels"); appendLine()
            extrasAdded.forEach { appendLine("- added `$it`") }
            extrasMissing.forEach { appendLine("- `$it` is not in the playlist") }
        }
    }
}
