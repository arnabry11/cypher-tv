package tv.cypher.tools

import tv.cypher.core.AppInfo
import tv.cypher.core.Config
import tv.cypher.core.Formats

/** Builds `config.json`, the one file the APK knows the URL of. */
object MakeConfig {
    fun build(repo: String, branch: String, previous: Config?, app: AppInfo?): Config {
        val base = "https://raw.githubusercontent.com/$repo/$branch"
        val prevApp = previous?.app
        val chosen = when {
            app == null -> prevApp
            prevApp != null && prevApp.versionCode > app.versionCode -> prevApp
            else -> app
        }
        return Config(schema = 1, channelsUrl = "$base/channels.json", epgUrl = "$base/epg.json.gz", app = chosen)
    }

    fun parse(json: String): Config? = try {
        Formats.compact.decodeFromString(Config.serializer(), json)
    } catch (_: Exception) {
        null
    }
}
