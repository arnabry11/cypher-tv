package tv.cypher.core

object Urls {
    private val raw = Regex("""^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")

    /** jsDelivr serves the same GitHub file; used when raw.githubusercontent.com is unreachable. */
    fun jsDelivrMirror(url: String): String? =
        raw.matchEntire(url)?.destructured?.let { (owner, repo, ref, path) ->
            "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/$path"
        }
}
