package tv.cypher.core

data class PlaylistEntry(
    /** tvg-id without the `@feed` suffix; empty when the playlist gives none. */
    val channelId: String,
    val feed: String?,
    val name: String,
    val logo: String?,
    val group: String?,
    val ua: String?,
    val referer: String?,
    val url: String,
    val geoBlocked: Boolean,
    /** Vertical resolution from a "(1080p)" tag in the title, 0 when unknown. */
    val height: Int = 0,
)

object M3u {
    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val resolution = Regex("""\((\d{3,4})p\)""")
    private val trailingTags = Regex("""(\s*(\([^()]*\)|\[[^\]]*]))+\s*$""")

    fun parse(text: String): List<PlaylistEntry> {
        val out = ArrayList<PlaylistEntry>()
        var header: String? = null
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> { header = line; ua = null; ref = null }
                line.startsWith("#EXTVLCOPT:") -> {
                    val kv = line.removePrefix("#EXTVLCOPT:")
                    val k = kv.substringBefore('=').trim().lowercase()
                    val v = kv.substringAfter('=', "").trim()
                    when (k) {
                        "http-user-agent" -> ua = v
                        "http-referrer", "http-referer" -> ref = v
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val h = header
                    if (h != null) out.add(entry(h, ua, ref, line))
                    header = null; ua = null; ref = null
                }
            }
        }
        return out
    }

    private fun entry(header: String, vlcUa: String?, vlcRef: String?, url: String): PlaylistEntry {
        val comma = firstCommaOutsideQuotes(header)
        val attrs = attr.findAll(if (comma >= 0) header.substring(0, comma) else header)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val title = if (comma >= 0) header.substring(comma + 1).trim() else ""
        val tvgId = attrs["tvg-id"].orEmpty()
        return PlaylistEntry(
            channelId = tvgId.substringBefore('@'),
            feed = tvgId.substringAfter('@', "").ifEmpty { null },
            name = trailingTags.replace(title, "").trim(),
            logo = attrs["tvg-logo"]?.ifEmpty { null },
            group = attrs["group-title"]?.ifEmpty { null },
            ua = (vlcUa ?: attrs["http-user-agent"])?.ifEmpty { null },
            referer = (vlcRef ?: attrs["http-referrer"])?.ifEmpty { null },
            url = url,
            geoBlocked = title.contains("[Geo-blocked]", ignoreCase = true),
            height = resolution.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }

    private fun firstCommaOutsideQuotes(s: String): Int {
        var inQuote = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return i
            }
        }
        return -1
    }
}
