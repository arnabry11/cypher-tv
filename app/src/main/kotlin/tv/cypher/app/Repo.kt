package tv.cypher.app

import android.content.Context
import tv.cypher.core.AppInfo
import tv.cypher.core.Channel
import tv.cypher.core.Config
import tv.cypher.core.Formats
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileNotFoundException

/** Local copies of the published files, and the background refresh that keeps them current. */
class Repo(private val ctx: Context, private val prefs: Prefs, val guide: GuideDb) {
    private val dir: File = ctx.filesDir
    private val channelsFile = File(dir, "channels.json")
    private val epgFile = File(ctx.cacheDir, "epg.json.gz")

    class Refresh(val channels: List<Channel>?, val update: AppInfo?, val guideUpdated: Boolean)

    /** Channels from the last download, or the copy bundled in the APK on first run, or empty. */
    fun loadCachedChannels(): List<Channel> {
        val text = try {
            if (channelsFile.exists()) channelsFile.readText()
            else ctx.assets.open("channels.json").bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            return emptyList()
        } catch (_: java.io.IOException) {
            return emptyList()
        }
        return parseChannels(text) ?: emptyList()
    }

    private fun parseChannels(text: String): List<Channel>? = try {
        Formats.compact.decodeFromString(ListSerializer(Channel.serializer()), text).takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Blocking. Fetches config, channels and guide; each stage fails independently. */
    fun refresh(): Refresh {
        var channels: List<Channel>? = null
        var update: AppInfo? = null
        var guideUpdated = false

        val cfgFile = File(dir, "config.json")
        val config: Config? = try {
            val url = BuildConfig.CONFIG_URL
            val d = Net.downloadWithFallback(url, cfgFile)
            if (!d.notModified) prefs.setEtag(url, d.etag)
            Formats.compact.decodeFromString(Config.serializer(), cfgFile.readText()).takeIf { it.schema <= SUPPORTED_SCHEMA }
        } catch (_: Exception) {
            null
        } ?: try {
            if (cfgFile.exists()) Formats.compact.decodeFromString(Config.serializer(), cfgFile.readText()) else null
        } catch (_: Exception) {
            null
        }
        if (config == null) return Refresh(null, null, false)

        config.app?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let { update = it }

        try {
            val staged = File(dir, "channels.new")
            Net.downloadWithFallback(config.channelsUrl, staged)
            val parsed = parseChannels(staged.readText())
            if (parsed != null) {
                channelsFile.delete()
                staged.renameTo(channelsFile)
                channels = parsed
            } else {
                staged.delete()
            }
        } catch (_: Exception) {
        }

        try {
            val d = Net.downloadWithFallback(config.epgUrl, epgFile, prefs.etag(config.epgUrl))
            if (!d.notModified || guide.isEmpty()) {
                epgFile.inputStream().use { guide.importGzip(it) }
                prefs.setEtag(config.epgUrl, d.etag)
                guideUpdated = true
            }
        } catch (_: Exception) {
        }
        return Refresh(channels, update, guideUpdated)
    }

    companion object {
        const val SUPPORTED_SCHEMA = 1
    }
}
