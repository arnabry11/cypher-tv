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

    /** ISO 639-3 codes used by iptv-org's feeds, for the languages that have their own number block or tab. */
    private val iso = mapOf(
        "hin" to "Hindi", "eng" to "English", "tel" to "Telugu", "tam" to "Tamil", "kan" to "Kannada",
        "mal" to "Malayalam", "mar" to "Marathi", "ben" to "Bengali", "guj" to "Gujarati", "pan" to "Punjabi",
        "ori" to "Odia", "ory" to "Odia", "bho" to "Bhojpuri", "asm" to "Assamese", "urd" to "Urdu", "nep" to "Nepali",
    )

    fun fromIso(code: String): String? = iso[code.lowercase()]

    /**
     * The language a channel is filed under when iptv-org says what it broadcasts in ([feedLanguage], may be null).
     * The licence list ([languages]) names every language a channel may use, so on its own it misfiles national
     * Hindi channels under a regional language; it only overrides iptv-org when the channel's name agrees with it.
     */
    fun file(name: String, languages: List<String>, feedLanguage: String?): String {
        if (feedLanguage == null) return primary(name, languages)
        val hint = Names.normalize(name).split(' ').firstNotNullOfOrNull { hints[it] }
        if (hint != null && hint != feedLanguage && hint in languages.flatMap { clean(it) }) return hint
        return feedLanguage
    }

    /** The one language a channel is filed under, from the licence list and the name alone. */
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
