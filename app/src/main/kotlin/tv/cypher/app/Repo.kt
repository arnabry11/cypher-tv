package tv.cypher.app

import android.content.Context
import tv.cypher.core.AppInfo
import tv.cypher.core.Channel
import tv.cypher.core.Config
import tv.cypher.core.ConfigLoader
import tv.cypher.core.Formats
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileNotFoundException

/** Local copies of the published files, and the background refresh that keeps them current. */
class Repo(private val ctx: Context, private val prefs: Prefs, val guide: GuideDb) {
    private val dir: File = ctx.filesDir
    private val channelsFile = File(dir, "channels.json")
    private val epgFile = File(ctx.cacheDir, "epg.json.gz")

    /** [ok] is false when any stage failed, so the caller can retry sooner than the normal interval. */
    class Refresh(val channels: List<Channel>?, val update: AppInfo?, val guideUpdated: Boolean, val ok: Boolean)

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
        var ok = true
        var channels: List<Channel>? = null
        var update: AppInfo? = null
        var guideUpdated = false

        // Validate before replacing: a captive-portal page or a newer schema must not overwrite the last good config.
        val cfgFile = File(dir, "config.json")
        val stagedCfg = File(dir, "config.new")
        var config: Config? = try {
            Net.downloadWithFallback(BuildConfig.CONFIG_URL, stagedCfg)
            ConfigLoader.parse(stagedCfg.readText(), SUPPORTED_SCHEMA)?.also { stagedCfg.renameTo(cfgFile) }
        } catch (_: Exception) {
            null
        }
        stagedCfg.delete()
        if (config == null) {
            ok = false
            config = cfgFile.takeIf { it.exists() }?.let { ConfigLoader.parse(it.readText(), SUPPORTED_SCHEMA) }
        }
        if (config == null) return Refresh(null, null, false, false)

        config.app?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let { update = it }

        try {
            val staged = File(dir, "channels.new")
            Net.downloadWithFallback(config.channelsUrl, staged)
            val parsed = parseChannels(staged.readText())
            if (parsed != null && staged.renameTo(channelsFile)) { // rename replaces atomically
                channels = parsed
            } else {
                ok = false
                staged.delete()
            }
        } catch (_: Exception) {
            ok = false
        }

        try {
            val d = Net.downloadWithFallback(config.epgUrl, epgFile, prefs.etag(config.epgUrl))
            if (!d.notModified || guide.isEmpty()) {
                epgFile.inputStream().use { guide.importGzip(it) }
                prefs.setEtag(config.epgUrl, d.etag)
                guideUpdated = true
            }
        } catch (_: Exception) {
            ok = false
        }
        return Refresh(channels, update, guideUpdated, ok)
    }

    companion object {
        const val SUPPORTED_SCHEMA = 1
    }
}
