package tv.cypher.core

object Languages {
    private val marker = Regex("""^all indian scheduled lang\w*$""", RegexOption.IGNORE_CASE)
    private val canonical = mapOf("oriya" to "Odia", "bangla" to "Bengali", "telgu" to "Telugu", "malayali" to "Malayalam", "bojpuri" to "Bhojpuri")
    private val hints = mapOf(
        "telugu" to "Telugu", "telgu" to "Telugu", "tamil" to "Tamil", "kannada" to "Kannada",
        "malayalam" to "Malayalam", "marathi" to "Marathi", "bangla" to "Bengali", "bengali" to "Bengali",
        "gujarati" to "Gujarati", "punjabi" to "Punjabi", "odia" to "Odia", "oriya" to "Odia",
        "bhojpuri" to "Bhojpuri", "assamese" to "Assamese", "urdu" to "Urdu", "hindi" to "Hindi", "english" to "English",
    )

    /** Splits a "Hindi, English" cell; drops "NA" and the "All Indian Scheduled language" marker; de-duplicates. */
    fun clean(cell: String): List<String> =
        cell.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("NA", true) && !marker.matches(it) }
            .map { canonical[it.lowercase()] ?: it.lowercase().replaceFirstChar(Char::uppercase) }
            .distinct()

    /** The one language a channel is filed under. */
    fun primary(name: String, languages: List<String>): String {
        val langs = languages.flatMap { clean(it) }.distinct()
        val hint = Names.normalize(name).split(' ').firstNotNullOfOrNull { hints[it] }
        if (langs.isEmpty()) return hint ?: "Other"
        if (hint != null && hint in langs) return hint
        if (langs.size == 1) return langs[0]
        val regional = langs.filter { it != "English" && it != "Hindi" }
        return when {
            regional.isNotEmpty() -> regional[0]
            "Hindi" in langs -> "Hindi"
            else -> langs[0]
        }
    }
}
