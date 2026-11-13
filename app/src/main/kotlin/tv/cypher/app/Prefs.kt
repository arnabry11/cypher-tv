package tv.cypher.app

import android.content.Context

/** Everything small the app remembers: favourites order, last channel, recent stream step-downs. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cypher", Context.MODE_PRIVATE)

    var favourites: List<String>
        get() = sp.getString("fav", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("fav", v.joinToString("\n")).apply()

    var lastChannel: String?
        get() = sp.getString("last", null)
        set(v) = sp.edit().putString("last", v).apply()

    /** Encoded by core's QualityMemory: the stream a channel recently had to step down to. */
    fun qualityMemory(channelId: String): String? = sp.getString("down:$channelId", null)

    fun setQualityMemory(channelId: String, raw: String) = sp.edit().putString("down:$channelId", raw).apply()

    fun etag(url: String): String? = sp.getString("etag:$url", null)

    fun setEtag(url: String, etag: String?) = sp.edit().putString("etag:$url", etag).apply()
}
