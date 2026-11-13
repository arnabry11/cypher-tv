package tv.cypher.core

object ConfigLoader {
    /** Parses `config.json`; null for anything unusable: not JSON (e.g. a captive-portal page), blank URLs, or a newer schema than this app knows. */
    fun parse(text: String, supportedSchema: Int): Config? {
        val c = try {
            Formats.compact.decodeFromString(Config.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        if (c.schema > supportedSchema || c.channelsUrl.isBlank() || c.epgUrl.isBlank()) return null
        return c
    }
}
